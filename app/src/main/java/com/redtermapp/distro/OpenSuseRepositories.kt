package com.redtermapp.distro

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.redtermapp.util.AppLog
import java.io.File

/**
 * openSUSE's repository configuration, edited from the app rather than from the shell.
 *
 * A session could not install anything:
 *
 *   Error building the cache:
 *   Can't open solv-file: /var/cache/zypp/solv/openSUSE:repo-oss/solv
 *   Skipping repository 'repo-oss (16.0)' because of the above error.
 *   No provider of 'git-core' found.
 *
 * openSUSE is the only distribution whose cache path contains a colon, and the only one
 * that could not install a package. Its metadata for those same repositories lands in
 * `/var/cache/zypp/raw/<alias>` successfully; only the solv half fails.
 *
 * ## Why this is Kotlin and not a line in `.startup`
 *
 * It was a line in `.startup`, for several releases, and it did nothing. Two reasons,
 * both of them invisible at the time:
 *
 *  - **`sed -i` is not a single operation.** It writes a temporary file in the target
 *    directory and renames it over the original. Under proot, as the app's own uid,
 *    that rename is where it stops -- and `sed` reports nothing useful when it happens,
 *    so the block "ran" and changed nothing.
 *  - **Nothing checked whether it worked.** The session printed `Setup complete.` and
 *    every repository line was byte-identical afterwards.
 *
 * This code has the same filesystem access the installer already uses to write
 * `/etc/passwd`, `/etc/group` and `/etc/hosts` into a rootfs, which has always worked,
 * and it reports what it did and what it could not.
 *
 * ## What it does, and just as importantly what it does not
 *
 * It de-colons the section header, which is the alias libzypp uses in the cache path,
 * and removes the `service=` line, because the service rewrites the headers on every
 * invocation and puts the colon straight back. That narrow pair is the fix.
 *
 * It deliberately does **not** delete `openSUSE.service`, does not remove
 * `RepoManager.db`, does not rename any file, and does not drop a repository. An earlier
 * attempt did all of those across three releases; it fixed nothing and cost the user
 * distributions they could install from.
 */
object OpenSuseRepositories {

    private const val TAG = "OpenSuseRepositories"

    /** What a run did, so a session can be told rather than left to guess. */
    data class Result(
        val reposInspected: Int,
        val aliasesBefore: List<String>,
        val aliasesAfter: List<String>,
        val changed: List<String>,
        val unreadable: List<String>,
        val cacheDirs: List<String>
    ) {
        val didAnything: Boolean get() = changed.isNotEmpty() || cacheDirs.isNotEmpty()

        fun oneLine(): String = buildString {
            append("openSUSE: ")
            if (unreadable.isNotEmpty()) {
                append("could not read ${unreadable.joinToString(", ")}; ")
            }
            append(
                if (changed.isEmpty()) "repositories already correct"
                else "${changed.size} file(s) updated"
            )
            append("; aliases now: ")
            append(aliasesAfter.joinToString(", ").ifEmpty { "none found" })
        }
    }

    /**
     * Idempotent. Safe to run on every session start, which is where it is called from.
     *
     * Returns what it did. [log] writes a line to the app log whether or not anything
     * changed, because "it ran and found nothing to do" and "it never ran" look
     * identical from the outside and must not.
     */
    fun prepare(rootfs: File, log: ((String) -> Unit)? = null): Result {
        var inspected = 0
        val before = mutableListOf<String>()
        val after = mutableListOf<String>()
        val changed = mutableListOf<String>()
        val unreadable = mutableListOf<String>()
        val cacheDirs = mutableListOf<String>()

        for (dir in listOf("etc/zypp/repos.d", "usr/share/zypp/repos.d")) {
            val repos = File(rootfs, dir)
            val files = repos.listFiles { f: File -> f.name.endsWith(".repo") } ?: emptyArray()
            for (file in files) {
                inspected++
                val original = try {
                    file.readText()
                } catch (e: Exception) {
                    // Say so rather than counting it as correct. A repository this app
                    // cannot read is the most likely reason the whole thing looks inert.
                    unreadable += "${dir}/${file.name}: ${e.message ?: e.javaClass.simpleName}"
                    continue
                }
                before += aliasesIn(original)
                val rewritten = rewrite(original)
                if (rewritten != original) {
                    try {
                        file.writeText(rewritten)
                        changed += "${dir}/${file.name}"
                    } catch (e: Exception) {
                        unreadable += "${dir}/${file.name}: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
                val now = try {
                    file.readText()
                } catch (e: Exception) {
                    rewritten
                }
                after += aliasesIn(now)
            }
        }

        for (name in listOf("solv", "raw", "packages")) {
            val d = File(rootfs, "var/cache/zypp/$name")
            val existed = d.isDirectory
            if (!existed && !d.mkdirs()) {
                unreadable += "var/cache/zypp/$name: could not be created"
                continue
            }
            if (!existed) cacheDirs += "var/cache/zypp/$name"
            // Every time, not only when it was just created. The image builds these as
            // root and the session runs as the app's uid, so an existing root-owned
            // directory is one the session still cannot write -- which is the original
            // fault, and skipping it here would leave it exactly as it was.
            //
            // The per-repository directories libzypp creates are left to libzypp: they
            // are named after the alias, which is the part de-coloned above.
            try {
                // 0777, not owner-write: an owner-writable directory is not writable by
                // the session's uid. It is a cache of re-downloadable repository
                // metadata and holds no secret.
                android.system.Os.chmod(d.absolutePath, 0x1FF)
            } catch (_: Exception) {
                // Reached only where `Os` is unavailable, which is the JVM test
                // classpath -- on a device the chmod above is what runs. SetWorldReadable
                // and SetWorldWritable are correct here and nowhere else: these are the
                // metadata directories libzypp writes repository indexes into, they hold
                // no credential, and the alternative is a cache the session's uid cannot
                // write, which is the fault being fixed.
                @SuppressLint("SetWorldReadable", "SetWorldWritable")
                fun widen() {
                    d.setReadable(true, false)
                    d.setWritable(true, false)
                    d.setExecutable(true, false)
                }
                widen()
            }
        }

        val result = Result(
            reposInspected = inspected,
            aliasesBefore = before.distinct(),
            aliasesAfter = after.distinct(),
            changed = changed,
            unreadable = unreadable,
            cacheDirs = cacheDirs
        )
        log?.invoke(result.oneLine())
        return result
    }

    /**
     * A cache directory that exists but holds no index is worse than no directory.
     *
     * Every attempt to build the solv cache left a `solv/<alias>/` behind containing
     * part of the result. From then on zypper tries to *read* that directory rather
     * than build it, fails, and reports:
     *
     *   Error building the cache:
     *   Can't open solv-file: /var/cache/zypp/solv/repo-oss/solv
     *   Try 'zypper refresh', or even 'zypper clean -m' before doing so.
     *
     * which is zypper naming its own remedy. Every session therefore failed on the
     * leftovers of the first failure, which is why the symptom never changed no
     * matter what was done to the configuration.
     *
     * A directory with no usable index in it is definitionally worthless, so it is
     * removed and libzypp rebuilds it from the raw metadata it already has. A
     * directory that does have one is left alone, so a working cache is never thrown
     * away and re-downloaded.
     *
     * @return the directories removed, for the log.
     */
    fun dropHalfBuiltCaches(rootfs: File): List<String> {
        val removed = mutableListOf<String>()
        for (half in listOf("solv", "raw")) {
            val parent = File(rootfs, "var/cache/zypp/$half")
            val dirs = parent.listFiles() ?: continue
            for (dir in dirs) {
                if (!dir.isDirectory) continue
                val index = File(dir, half)
                val usable = index.isFile && index.length() > 0
                if (usable) continue
                // @System is libzypp's own directory of installed packages, not a
                // repository cache, and it is rebuilt rather than parsed.
                if (dir.name == "@System") continue
                if (dir.deleteRecursively()) removed += "var/cache/zypp/$half/${dir.name}"
            }
        }
        return removed
    }

    /** The section header, which is the alias, not the `name=` line beside it. */
    private fun aliasesIn(text: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("[") && it.endsWith("]") }
            .map { it.trim('[', ']') }
            .toList()

    /**
     * Idempotent down to the byte.
     *
     * It runs on every session start, so a rewrite that adds a trailing newline each
     * time would report work on every launch forever and make it impossible to tell a
     * real change from noise.
     */
    private fun rewrite(text: String): String {
        val trailingNewline = text.endsWith("\n")
        val body = if (trailingNewline) text.dropLast(1) else text
        val out = StringBuilder()
        for (line in body.split("\n")) {
            when {
                // service=openSUSE makes libzypp namespace the repo as `service:alias`,
                // which is how the colon comes back on every later invocation.
                line.substringBefore('=').trim() == "service" && '=' in line -> Unit

                // http -> https. Both were measured serving Leap 16.0 aarch64
                // repodata; this is the better default, not a fix for Curl error (28).
                line.startsWith("baseurl=") || line.startsWith("gpgkey=") ->
                    out.append(
                        line
                            .replace("http://cdn.opensuse.org", "https://download.opensuse.org")
                            .replace("http://download.opensuse.org", "https://download.opensuse.org")
                    ).append('\n')

                // [openSUSE:repo-oss] -> [repo-oss]. The second group alone: writing
                // both back would put the colon straight back in.
                line.trimStart().startsWith("[") -> {
                    val t = line.trim()
                    out.append(
                        Regex("""^\[([^:\]]*):([^\]]*)\]$""").replace(t) { "[${it.groupValues[2]}]" }
                    ).append('\n')
                }

                else -> out.append(line).append('\n')
            }
        }
        if (!trailingNewline && out.isNotEmpty()) out.setLength(out.length - 1)
        return out.toString()
    }

    /** Convenience for the session, which has a Context and wants the line in the log. */
    fun prepareAndLog(context: Context, rootfs: File, distroName: String): Result {
        val result = try {
            prepare(rootfs) { line ->
                AppLog.i(context, TAG, "$distroName: $line")
                Log.i(TAG, "$distroName: $line")
            }
        } catch (e: Exception) {
            AppLog.w(context, TAG, "$distroName: repository setup failed: ${e.message}")
            Log.w(TAG, "$distroName: repository setup failed", e)
            return Result(0, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
        try {
            val dropped = dropHalfBuiltCaches(rootfs)
            if (dropped.isNotEmpty()) {
                AppLog.i(
                    context, TAG,
                    "$distroName: removed ${dropped.size} incomplete cache director" +
                        (if (dropped.size > 1) "ies: " else "y: ") + dropped.joinToString(", ")
                )
                Log.i(TAG, "$distroName: removed incomplete caches: $dropped")
            }
        } catch (e: Exception) {
            AppLog.w(context, TAG, "$distroName: could not clear incomplete caches: ${e.message}")
        }
        return result
    }
}
