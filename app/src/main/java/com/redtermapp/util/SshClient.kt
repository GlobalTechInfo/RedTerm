package com.redtermapp.util

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import com.redtermapp.distro.ProotLaunch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The bundled OpenSSH client.
 *
 * The binaries ship as assets, one directory per ABI, and are unpacked into a
 * minimal rootfs on first use.
 *
 * They cannot simply be executed from app storage: /data/user/0 is mounted
 * `noexec` on Android 12+, so execve fails with EACCES however the file is
 * chmod'ed. The app already solves this for every distro by running binaries
 * through proot's `-L` (kompat) flag, which loads them via libproot-loader.so
 * instead of exec'ing them, and the bundled client is run the same way. That is
 * also why no distribution needs to be installed: the client is part of the app.
 *
 * Because the client is statically linked, the rootfs needs no shared libraries,
 * and plain SSH does not need a CA bundle (host keys are verified through
 * known_hosts, not X.509).
 */
object SshClient {

    private const val TAG = "SshClient"
    private const val PREFS = "ssh_client"
    private const val KEY_READY = "ready_for"
    val PROGRAMS = listOf("ssh", "ssh-keygen")

    /**
     * Optional diagnostics built by native/build-openssh.sh with the client's
     * exact link recipe, used to localise a client that segfaults instead of
     * guessing. They are not shipped in release builds; native/build-openssh.sh
     * --probe regenerates them, and the diagnostic skips any that are absent.
     */
    private val PROBES = listOf("probe", "probe_ctor", "probe_ssl", "probe_hard")

    /**
     * launcher() calls back into ensureInstalled() for every run, and the
     * verification run happens *while* installing, so the two recurse until the
     * stack is exhausted. This tracks the install already in flight.
     */
    private val installing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val diagnosing = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun archName(): String? = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a", "aarch64" -> "aarch64"
        "armeabi-v7a", "arm" -> "armv7a"
        "x86_64" -> "x86_64"
        "x86", "i686" -> "i686"
        else -> null
    }

    /** Root of the throwaway rootfs the client runs in. */
    fun rootfs(context: Context): File =
        File(context.filesDir, "ssh-rootfs").apply { if (!exists()) mkdirs() }

    /** Inside the rootfs the client's home is /root, so keys live under it. */
    fun homeDir(context: Context): File = File(rootfs(context), "root").apply {
        if (!exists()) mkdirs()
        File(this, ".ssh").apply { if (!exists()) mkdirs() }
    }

    fun sshDir(context: Context): File = File(homeDir(context), ".ssh")

    fun knownHosts(context: Context): File = File(sshDir(context), "known_hosts")

    fun binary(context: Context, prog: String): File = File(rootfs(context), "bin/$prog")

    /** Absolute path of a program as seen inside the rootfs. */
    private fun inRootfs(prog: String) = "/bin/$prog"

    /**
     * Unpacks the client and builds the minimal rootfs around it.
     *
     * Idempotent, and cheap after the first call: the result is remembered
     * against the ABI so a new install is the only thing that redoes the work.
     */
    fun ensureInstalled(context: Context): Boolean {
        // Work is already under way, so report the client as present rather than
        // starting it again.
        if (installing.get()) return true
        if (!installing.compareAndSet(false, true)) return true
        return try {
            ensureInstalledNow(context)
        } finally {
            installing.set(false)
        }
    }

    private fun ensureInstalledNow(context: Context): Boolean {
        val arch = archName() ?: run {
            Log.w(TAG, "unsupported ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            return false
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_READY, null) == arch &&
            PROGRAMS.all { binary(context, it).length() > 0 }
        ) {
            return true
        }
        val root = rootfs(context)
        for (dir in listOf(File(root, "bin"), File(root, "etc"), File(root, "dev"))) {
            if (!dir.exists() && !dir.mkdirs()) return false
        }
        for (prog in PROGRAMS + PROBES) {
            val out = binary(context, prog)
            // The probes are diagnostics and are not shipped; their absence must
            // never make the client look uninstalled.
            val optional = prog in PROBES
            if (out.exists() && out.length() > 0) {
                out.setExecutable(true, true)
                continue
            }
            try {
                context.assets.open("ssh/$arch/$prog").use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                out.delete()
                if (optional) continue
                Log.w(TAG, "could not extract $arch/$prog", e)
                return false
            }
            out.setExecutable(true, true)
            if (out.length() <= 0L) return false
        }
        writeEtc(context, root)
        homeDir(context)
        clearCrashBuffer()
        // Confirm the client really runs through proot. The binaries are loaded
        // by libproot-loader.so rather than exec'ed, and if that fails the ssh
        // session would simply exit with nothing on screen, so the result is
        // recorded for the log.
        val (code, output) = runInRootfs(context, "/bin/ssh -V", "ssh-check.sh")
        AppLog.i(context, "ssh", "install check: exit=$code out=${output.trim()}")
        if (code == 0 && output.contains("OpenSSH_")) {
            prefs.edit { putString(KEY_READY, arch) }
            return true
        }
        // A wrong link recipe and a broken OpenSSH fail identically, so record
        // which one it is before giving up.
        diagnose(context, "exit=$code out=${output.trim()}")
        return false
    }

    /**
     * Explains a failing client. A native SIGSEGV leaves a tombstone in logcat's
     * crash buffer naming the faulting address, and /bin/probe runs the identical
     * link recipe, so the two together separate "our link flags are wrong" from
     * "OpenSSL/OpenSSH is wrong".
     */
    private fun diagnose(context: Context, failure: String) {
        val appContext = context.applicationContext
        if (!diagnosing.compareAndSet(false, true)) return
        // Off the calling thread: this starts processes and reads logcat, and
        // every caller is a UI thread, which ANRs on a five second block.
        Thread({
            // Both probes use the client's exact link recipe, and differ only in
            // whether they carry a constructor, so together they separate the
            // loader from OpenSSL.
            for (probe in PROBES) {
                if (binary(appContext, probe).length() <= 0L) continue
                val (code, output) =
                    runInRootfs(appContext, "/bin/$probe", "ssh-probe.sh")
                AppLog.i(
                    appContext, "ssh",
                    "diagnose: $probe exit=$code " +
                        "out=${output.trim().replace('\n', ' ')}"
                )
            }
            AppLog.e(appContext, "ssh", "client failed: $failure\n${crashBuffer()}")
        }, "ssh-diagnose").start()
    }

    /** A native crash is only reported here, not in the main log. */
    private fun crashBuffer(): String = try {
        val process = ProcessBuilder(
            "/system/bin/logcat", "-b", "crash", "-d", "-v", "threadtime"
        ).start()
        val text = process.inputStream.readBytes().toString(Charsets.UTF_8)
        if (text.isBlank()) "crash buffer empty" else text.takeLast(2500)
    } catch (e: Exception) {
        "crash buffer unavailable: ${e.message}"
    }

    private fun clearCrashBuffer() {
        runCatching {
            val process = ProcessBuilder("/system/bin/logcat", "-b", "crash", "-c").start()
            // The timeout form is API 26+, and logcat -c returns immediately.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                process.waitFor(2, TimeUnit.SECONDS)
            } else {
                process.waitFor()
            }
        }
    }

    /**
     * The little bit of /etc the client needs.
     *
     * A passwd entry keeps `$USER` and the prompt sane, and nsswitch.conf stops
     * libc from trying to reach an NSS module that does not exist on Android.
     */
    private fun writeEtc(context: Context, root: File) {
        val etc = File(root, "etc")
        fun write(name: String, body: String) {
            try {
                File(etc, name).writeText(body)
            } catch (e: Exception) {
                Log.w(TAG, "could not write etc/$name", e)
            }
        }
        write("passwd", "root:x:0:0:root:/root:/bin/sh\n")
        write("group", "root:x:0:\n")
        write("hosts", "127.0.0.1 localhost\n::1 localhost\n")
        write("nsswitch.conf", "hosts: files dns\npasswd: files\ngroup: files\n")
        write("resolv.conf", "nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        write("ssh_config", "Host *\n    HashKnownHosts yes\n")
    }

    /**
     * Writes a proot launcher that runs [command] inside the client's rootfs.
     *
     * Passing a null [command] starts an interactive shell, which is what the
     * terminal session wants; passing one runs it and exits, used for key
     * generation.
     *
     * @return the launcher script path, or null if the rootfs is not ready.
     */
    fun launcher(context: Context, command: String?, scriptName: String): String? {
        if (!ensureInstalled(context)) return null
        return try {
            ProotLaunch.writeLauncher(
                context = context,
                rootfsDir = rootfs(context),
                startInner = "/root",
                command = command,
                scriptName = scriptName
            ).absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "could not write the ssh launcher", e)
            null
        }
    }

    /** Runs a command in the client's rootfs and returns its exit code. */
    fun run(
        context: Context,
        command: String,
        timeoutMinutes: Long = 2
    ): Pair<Int, String> {
        val script = launcher(context, command, "ssh-command.sh")
            ?: return -1 to "no ssh rootfs"
        return execScript(context, script, command, timeoutMinutes)
    }

    /**
     * Like [run], but for callers that have already installed the rootfs, such
     * as the install check and the diagnostic. Going through [run] would re-enter
     * [ensureInstalled] from inside it.
     */
    internal fun runInRootfs(
        context: Context,
        command: String,
        scriptName: String,
        timeoutMinutes: Long = 2
    ): Pair<Int, String> {
        val script = try {
            ProotLaunch.writeLauncher(
                context = context,
                rootfsDir = rootfs(context),
                startInner = "/root",
                command = command,
                scriptName = scriptName
            ).absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "could not write the ssh launcher", e)
            return -1 to "no ssh rootfs"
        }
        return execScript(context, script, command, timeoutMinutes)
    }

    private fun execScript(
        context: Context,
        script: String,
        command: String,
        timeoutMinutes: Long
    ): Pair<Int, String> {
        AppLog.d(context, "ssh", "run: $command")
        val scriptBody = try {
            java.io.File(script).readText().take(400)
        } catch (_: Exception) {
            "<unreadable>"
        }
        AppLog.d(context, "ssh", "script: $scriptBody")
        return try {
            val process = ProcessBuilder("/system/bin/sh", script)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            val code = waitForCompat(process, timeoutMinutes)
            AppLog.i(
                context, "ssh",
                "exit=$code out=${output.trim().takeLast(400)}"
            )
            code to output
        } catch (e: Exception) {
            Log.w(TAG, "command failed: $command", e)
            -1 to (e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Process.waitFor(timeout, unit) is API 26+, so older devices poll instead.
     * Shared with the distro command runner for the same reason.
     */
    fun waitForCompat(process: Process, timeoutMinutes: Long): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return if (process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                process.exitValue()
            } else {
                process.destroy()
                -1
            }
        }
        val deadline = System.currentTimeMillis() + timeoutMinutes * 60_000L
        while (System.currentTimeMillis() < deadline) {
            try {
                return process.exitValue()
            } catch (_: IllegalThreadStateException) {
                Thread.sleep(200)
            }
        }
        process.destroy()
        return -1
    }

    /**
     * Maps a host-side path to its path inside the client's rootfs.
     *
     * The home directory is /root there, and stripping the prefix leaves a
     * leading slash, so the result has to be rebuilt rather than trimmed.
     */
    fun inRootfs(context: Context, file: File): String {
        val root = homeDir(context).absolutePath
        val absolute = file.absolutePath
        if (absolute.startsWith(root)) {
            val relative = absolute.removePrefix(root).trimStart('/')
            return if (relative.isEmpty()) "/root" else "/root/$relative"
        }
        return "/root/${file.name}"
    }

    fun defaultKey(context: Context): File? =
        listOf("id_ed25519", "id_ecdsa", "id_rsa")
            .map { File(sshDir(context), it) }
            .firstOrNull { it.exists() }

    fun privateKeys(context: Context): List<File> =
        sshDir(context).listFiles { f -> f.isFile && !f.name.endsWith(".pub") }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun publicKeyFor(context: Context, privateKey: File): File? {
        val pub = File(privateKey.absolutePath + ".pub")
        return if (pub.exists()) pub else null
    }
}
