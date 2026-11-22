package com.redtermapp.util

import android.content.Context
import com.redtermapp.distro.ProotLaunch
import com.redtermapp.distro.TarExtractor
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The on-demand "extended toolkit": extra real tools the shell can gain without
 * an app update. They live on disk under `filesDir/extended-toolkit/<arch>/
 * {bin,lib}` and the device-shell launcher exposes that directory on PATH (and
 * `lib` on LD_LIBRARY_PATH). Because the launcher only ever looks at what is on
 * disk, the tool set is whatever the app and the user have placed there:
 *
 *  - anything [install] downloads from [SOURCES] (our hosted artifacts, or a
 *    URL a build/host adds),
 *  - anything a user drops in with [importToolDir] (their own prebuilt tool),
 *
 * and all of it lands on PATH with no further wiring.
 *
 * Gated off by default the way the distro logos are: [SOURCES] is empty, so
 * nothing is fetched and the shell just runs the lean built-in toolkit. Nothing
 * in this object costs anything until a source or an import fills the directory.
 */
object ExtendedToolkit {

    /**
     * Candidate tarball URLs (layout `device-extended-toolkit/<arch>/{bin,lib}`).
     * Each is fetched once and unpacked into this device's `<arch>` directory.
     * Empty by default: no hosted artifact yet, so the shell stays lean.
     */
    val SOURCES: List<String> = emptyList()

    /** A fetched tool set bigger than this is not one. */
    private const val MAX_BYTES = 512L * 1024 * 1024

    private fun dir(context: Context): File =
        File(context.filesDir, "extended-toolkit").apply { if (!exists()) mkdirs() }

    /** This device's ABI dir name (aarch64/armv7a/i686/x86_64), or null. */
    private fun arch(context: Context): String? = DeviceToolkit.assetArch(ProotLaunch.deviceAbi())

    /**
     * The on-disk `<arch>/bin`, when it holds at least one tool, else null.
     * This is the single source of truth the launcher exposes: tools from any
     * [SOURCES] entry or a [importToolDir] all appear here.
     */
    fun binDir(context: Context): File? {
        val a = arch(context) ?: return null
        val d = File(dir(context), "$a/bin")
        return if (d.isDirectory && d.listFiles()?.isNotEmpty() == true) d else null
    }

    /** The on-disk `<arch>/lib`, when it has content, else null. */
    fun libDir(context: Context): File? {
        val a = arch(context) ?: return null
        val d = File(dir(context), "$a/lib")
        return if (d.isDirectory && d.listFiles()?.isNotEmpty() == true) d else null
    }

    /**
     * Fetch + unpack every [SOURCES] entry that has not been fetched yet.
     * Idempotent per source (a marker under `.done/`), best-effort, and a
     * no-op returning 0 while [SOURCES] is empty. Returns the count fetched
     * this call. A tool a user later [importToolDir]s into the same
     * `<arch>` directory is picked up by [binDir] without any re-download.
     */
    fun install(context: Context): Int {
        val a = arch(context) ?: return 0
        val live = dir(context)
        var fetched = 0
        for (url in SOURCES) {
            if (url.isEmpty() || isDone(context, url)) continue
            val archive = File(context.cacheDir, "ext-kt-${marker(url)}.tar")
            if (!download(context, url, archive)) {
                AppLog.w(context, "ext-toolkit", "download of $url failed, keeping what is present")
                continue
            }
            // Extract into a staging dir, never the live toolkit dir: a failed
            // extraction must not leave half-written tools where binDir and
            // the launcher's PATH can see them shadowing the lean toolkit.
            val stage = File(context.cacheDir, "ext-kt-stage-${marker(url)}")
            stage.deleteRecursively()
            if (!stage.mkdirs()) {
                archive.delete()
                continue
            }
            val r = TarExtractor.extract(archive, stage, stripComponents = 1)
            archive.delete()
            if (!r.ok) {
                AppLog.w(context, "ext-toolkit", "extract of $url: ${r.failed.size} failed: ${r.failed.take(3)}")
                stage.deleteRecursively()
                continue
            }
            // Complete: merge the staged <arch> into the live <arch> (existing
            // tools from other sources or an import are kept, this source's
            // files added or overwritten), then drop the staging copy.
            copyInto(File(stage, a), File(live, a))
            stage.deleteRecursively()
            markDone(context, url)
            fetched++
        }
        if (fetched > 0) AppLog.i(context, "ext-toolkit", "fetched $fetched source(s) for $a")
        return fetched
    }

    /**
     * Import a user-supplied prebuilt tool set into this device's `<arch>`
     * directory, so it joins the shell's PATH with no other wiring. Copies
     * every executable under [bin] into `<arch>/bin` (and, if given, the
     * shared libraries under [lib] into `<arch>/lib`, which the launcher puts
     * on LD_LIBRARY_PATH). The caller is responsible for the ABI matching this
     * device; this only moves files. Returns the number of files copied.
     */
    fun importToolDir(context: Context, bin: File, lib: File? = null): Int {
        val a = arch(context) ?: return 0
        var n = 0
        n += copyInto(bin, File(dir(context), "$a/bin"))
        if (lib != null) n += copyInto(lib, File(dir(context), "$a/lib"))
        if (n > 0) AppLog.i(context, "ext-toolkit", "imported $n file(s) into $a from ${bin.absolutePath}")
        return n
    }

    private fun copyInto(src: File, into: File): Int {
        if (!src.isDirectory) return 0
        var n = 0
        // Recurse through the *source* tree, carrying the matching destination
        // directory down: reading the destination instead of the source would
        // silently skip any subtree whose destination dir does not exist yet.
        fun walk(s: File, d: File) {
            d.mkdirs()
            s.listFiles()?.forEach { f ->
                val t = File(d, f.name)
                when {
                    f.isDirectory -> walk(f, t)
                    f.isFile -> {
                        f.copyTo(t, overwrite = true)
                        // Asset/file copies land without the exec bit; re-assert it so a
                        // dropped-in tool is actually runnable.
                        t.setExecutable(true, false)
                        n++
                    }
                }
            }
        }
        walk(src, into)
        return n
    }

    private fun isDone(context: Context, url: String): Boolean =
        File(dir(context), ".done/${marker(url)}").isFile

    private fun markDone(context: Context, url: String) {
        File(dir(context), ".done/${marker(url)}").apply {
            parentFile?.mkdirs()
            writeText(url)
        }
    }

    /** A short, stable, filesystem-safe id for a source URL. */
    private fun marker(url: String): String = "src-${Integer.toHexString(url.hashCode())}"

    private fun download(context: Context, url: String, to: File): Boolean {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            val c = conn!!
            c.connectTimeout = 15_000
            c.readTimeout = 120_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "RedTerm")
            c.connect()
            // A declared length over the cap is not the artifact; -1 (chunked,
            // unknown length) is allowed and enforced by the running byte count.
            if (c.responseCode != 200 || c.contentLengthLong > MAX_BYTES) return false
            var written = 0L
            var complete = false
            c.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) {
                            complete = written > 0
                            break
                        }
                        if (written + r > MAX_BYTES) break // over the cap: reject
                        written += r
                        out.write(buf, 0, r)
                    }
                }
            }
            if (!complete) {
                to.delete() // partial or over-limit: do not leave a truncated archive
                return false
            }
            return true
        } catch (e: Exception) {
            to.delete()
            AppLog.w(context, "ext-toolkit", "download: ${e.message}")
            return false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Pure: the shell's PATH when the extended toolkit is present. The device
     * dirs lead (a real command wins), the extended bin comes before the lean
     * toolkit (a GNU `tar` beats the busybox applet), the toolkit last. A
     * null/blank [extBin] means no extended tools, so the path is unchanged
     * from today.
     */
    fun toolkitPath(devicePrefix: String, extBin: String?, toolkit: String): String =
        if (extBin.isNullOrBlank()) "$devicePrefix:$toolkit"
        else "$devicePrefix:$extBin:$toolkit"
}
