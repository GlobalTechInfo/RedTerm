package com.redtermapp.util.sftp

import android.content.Context
import com.redtermapp.distro.ProotLaunch
import com.redtermapp.ui.SshLaunchOptions
import com.redtermapp.ui.SshStore
import com.redtermapp.util.AppLog
import com.redtermapp.util.SshClient
import java.io.Closeable
import java.io.File

/**
 * File transfer to an SSH server, by speaking SFTP over the bundled `ssh`.
 *
 * The alternative was shipping OpenSSH's `sftp` binary. That costs roughly 32 MB
 * of extra assets and four more cross-compiled programs to keep in step, and a
 * batch command line to quote correctly; `ssh -s host sftp` starts the same
 * subsystem from the binary already in the app. So there is exactly one SSH
 * implementation here, and the APK does not grow.
 *
 * The trade-off is that each operation is implemented here rather than delegated
 * to a mature client, which is why [SftpSession] is written against plain streams
 * and is covered by tests that drive it against a server implemented in the test.
 */
class SftpClient internal constructor(
    internal val session: SftpSession,
    private val process: Process,
    /** What ssh wrote to stderr, kept so a failure can explain itself. */
    private val diagnostics: Diagnostics,
    /** Generated launcher, deleted on close: it carries the passphrase. */
    private val launcher: java.io.File?
) : Closeable {

    /** Why a connection could not be opened, in the user's terms. */
    sealed class OpenResult {
        data class Opened(val client: SftpClient) : OpenResult()
        data class Failed(val reason: String) : OpenResult() {
            /**
             * True when the server refused the key.
             *
             * Worth distinguishing because a passphrase-protected key and a key the
             * server has no record of are indistinguishable here — both produce
             * "Permission denied (publickey)" — and only the first can be fixed by
             * supplying a phrase.
             */
            val authenticationRefused: Boolean
                get() = reason.contains("rejected the key", ignoreCase = true) ||
                    reason.contains("every key on this device", ignoreCase = true)
        }
    }

    /** Bounded capture of ssh's stderr, for error messages. */
    class Diagnostics {
        private val text = StringBuilder()

        /**
         * A monitor of its own, because the text is a StringBuilder and locking on
         * it would mean waiting on an object that another thread may be mutating.
         */
        private val lock = Object()

        fun append(line: String) {
            synchronized(lock) {
                if (text.length > MAX) return
                text.append(line).append('\n')
                lock.notifyAll()
            }
        }

        fun tail(): String = synchronized(lock) { text.toString().trim() }

        /**
         * [tail], after a short wait for the first line if there is none yet.
         *
         * stderr is drained on its own thread, so a failure that closes the stream
         * races it: the handshake can give up and report before a single word of
         * ssh's explanation has been appended. The reason is then read off an empty
         * buffer and every such failure looks the same — a stopped connection — no
         * matter what the server actually said. A few hundred milliseconds is enough
         * for the reader to catch up and costs nothing on a successful connection,
         * which never calls this.
         */
        fun tailAwaitingFirstLine(millis: Long = 400): String =
            tail().ifBlank {
                synchronized(lock) {
                    val deadline = System.currentTimeMillis() + millis
                    while (text.isEmpty()) {
                        val remaining = deadline - System.currentTimeMillis()
                        if (remaining <= 0) break
                        try {
                            lock.wait(remaining)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                    text.toString().trim()
                }
            }

        private companion object {
            const val MAX = 8 * 1024
        }
    }

    private var closed = false

    // ------------------------------------------------------------------ reading

    /** Everything in [path], excluding `.` and `..`. */
    fun list(path: String): List<SftpName> = session.list(normalise(path))

    /** Attributes of [path], or null when it is not there. */
    fun stat(path: String): SftpAttrs? = session.stat(normalise(path))

    /** The server's own answer about the login user's home directory. */
    fun home(): String = try {
        session.realPath(".")
    } catch (_: Exception) {
        "/root"
    }

    // ---------------------------------------------------------------- transfers

    /**
     * Copies a remote file to [destination].
     *
     * @param onProgress receives bytes done and total, where total is -1 when the
     *   server did not report a size.
     */
    fun download(remotePath: String, destination: File, onProgress: (Long, Long) -> Unit) {
        val total = stat(remotePath)?.size ?: -1L
        val handle = session.open(normalise(remotePath), write = false)
        destination.parentFile?.mkdirs()
        try {
            destination.outputStream().use { out ->
                var offset = 0L
                while (true) {
                    val chunk = session.read(handle, offset, Sftp.MAX_CHUNK) ?: break
                    if (chunk.isEmpty()) break
                    out.write(chunk)
                    offset += chunk.size
                    onProgress(offset, total)
                }
                out.flush()
            }
        } finally {
            runCatching { session.close(handle) }
        }
    }

    /**
     * Copies [source] to [remotePath], creating or replacing the target.
     *
     * @param onProgress receives bytes done and the file's own size, which is
     *   known even though the server has nothing to report yet.
     */
    fun upload(source: File, remotePath: String, onProgress: (Long, Long) -> Unit) {
        val total = source.length()
        val handle = session.open(
            normalise(remotePath),
            write = true,
            create = true,
            truncate = true
        )
        try {
            source.inputStream().use { input ->
                var offset = 0L
                val buffer = ByteArray(Sftp.MAX_CHUNK)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    session.write(handle, offset, buffer.copyOf(read))
                    offset += read
                    onProgress(offset, total)
                }
            }
        } finally {
            runCatching { session.close(handle) }
        }
    }

    // ------------------------------------------------------------------ changes

    fun mkdir(path: String) = session.mkdir(normalise(path))

    /** Changes permissions. [mode] is permission bits only, e.g. 0x1B5 for rwxr-xr-x. */
    fun setPermissions(path: String, mode: Long) =
        session.setPermissions(normalise(path), mode)

    /** Makes a symbolic link at [linkPath] pointing at [target]. */
    fun symlink(target: String, linkPath: String) =
        session.symlink(target, normalise(linkPath))

    /** Free space on the filesystem holding [path], or null if the server cannot say. */
    fun freeSpace(path: String): StatVfs? = session.statvfs(normalise(path))

    fun rename(from: String, to: String) = session.rename(normalise(from), normalise(to))

    /**
     * Removes a file, or a whole directory tree.
     *
     * Depth-first, because a server refuses to remove a directory that still has
     * entries in it — which is the whole purpose of RMDIR.
     */
    fun remove(path: String, recursive: Boolean) {
        val target = normalise(path)
        val isDirectory = session.lstat(target)?.isDirectory == true
        if (recursive && isDirectory) {
            for (child in session.list(target)) {
                remove(join(target, child.filename), recursive = true)
            }
            session.rmdir(target)
        } else {
            session.remove(target)
        }
    }

    /**
     * Counts the immediate contents of a directory.
     *
     * One level only: a remote walk is a round trip per directory, so a recursive
     * summary of a large tree would take minutes with no way to interrupt it.
     */
    fun sizeOf(path: String): FolderTotals {
        var files = 0
        var folders = 0
        var bytes = 0L
        for (entry in session.list(normalise(path))) {
            if (entry.attrs.isDirectory) folders++ else {
                files++
                bytes += entry.attrs.size ?: 0L
            }
        }
        return FolderTotals(files, folders, bytes)
    }

    data class FolderTotals(val files: Int, val folders: Int, val bytes: Long)

    /**
     * Reads stderr to EOF and logs it.
     *
     * It has to be drained, not ignored: ssh blocks writing once the pipe is full,
     * which would stall the transfer. It cannot be merged into stdout either,
     * which is why the launcher is started with merging off.
     */
    private fun drainStderr(context: Context, diagnostics: Diagnostics) {
        Thread({
            try {
                process.errorStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (isPlatformNoise(line)) continue
                        AppLog.d(context, "sftp", line)
                        diagnostics.append(line)
                    }
                }
            } catch (_: Exception) {
                // The process ended.
            }
        }, "redterm-sftp-stderr").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Whether a line is the Android runtime complaining, not ssh.
     *
     * Every cross-compiled binary announces itself this way on startup, so without
     * this the SFTP log is mostly linker warnings that say nothing and are repeated
     * per connection. Filtered on its own, and never mixed into [Diagnostics], since
     * a failure report must contain only what ssh actually said.
     */
    private fun isPlatformNoise(line: String): Boolean =
        line.startsWith("WARNING: linker:") ||
            line.startsWith("WARNING: dlsym") ||
            line.startsWith("WARNING: type property ") ||
            line.contains("linkerconfig/ld.config.txt")

    override fun close() {
        if (closed) return
        closed = true
        runCatching { session.close() }
        runCatching { launcher?.delete() }
        // stderr is drained by a daemon thread; closing stdout and destroying the
        // process is what actually ends it.
        runCatching { process.outputStream.close() }
        process.destroy()
    }

    companion object {
        /**
         * Starts the subsystem and completes the version handshake.
         *
         * @return null when the transport could not be brought up. The reason is
         *   logged rather than returned, because a failure at this point is only
         *   visible on the streams, and the caller has nothing better to show.
         */
        fun connect(
            context: Context,
            server: SshStore.Server,
            identities: List<String>,
            passphrase: String? = null
        ): OpenResult {
            if (identities.isEmpty()) {
                return OpenResult.Failed("There is no SSH key on this device to connect with.")
            }
            val script = SshClient.launcher(
                context = context,
                command = buildCommand(server, identities, passphrase),
                scriptName = "sftp-${System.nanoTime()}.sh",
                // Essential here: ssh writes host-key warnings and the login banner
                // to stderr, and one of those landing inside a packet would leave
                // the client reading from the wrong offset for the rest of the
                // session.
                mergeStderr = false
            ) ?: return OpenResult.Failed("The bundled SSH client could not be prepared.")

            val process = try {
                ProcessBuilder("/system/bin/sh", script)
                    .apply { environment().putAll(SshAskpass.environment(context, passphrase)) }
                    .start()
            } catch (e: Exception) {
                AppLog.w(context, "sftp", "could not start ssh: ${e.message}")
                return OpenResult.Failed("The SSH client could not be started.")
            }
            val diagnostics = Diagnostics()
            val client = SftpClient(
                SftpSession(process.inputStream, process.outputStream),
                process,
                diagnostics,
                java.io.File(script)
            )
            // Drained from the moment the process starts, not after the handshake.
            // A refusal is reported by ssh on stderr and then the stream closes, so
            // waiting to read it until after a successful handshake meant the one
            // message that explained the failure was thrown away.
            client.drainStderr(context, diagnostics)
            return try {
                client.session.version()
                OpenResult.Opened(client)
            } catch (e: Exception) {
                val detail = diagnostics.tailAwaitingFirstLine()
                AppLog.w(
                    context, "sftp",
                    "handshake failed: ${e.message}\n$detail"
                )
                client.close()
                OpenResult.Failed(describe(e.message, detail))
            }
        }

        /**
         * Names the cause from what ssh said on stderr.
         *
         * The raw text is terse and jargon-heavy ("subsystem request failed on
         * channel 0"), and the failure is otherwise reported as a stopped
         * connection, which is not what happened. Anything unrecognised is passed
         * through rather than replaced with a guess.
         */
        private fun describe(failure: String?, stderr: String): String {
            fun mentions(pattern: String) = stderr.contains(pattern, ignoreCase = true)
            return when {
                mentions("Permission denied") ->
                    "The server rejected the key for this connection."
                mentions("subsystem request failed") ||
                    mentions("allows sftp connections only") ->
                    "This server does not offer SFTP. Its sshd needs the sftp-server subsystem enabled."
                mentions("Host key verification failed") ||
                    mentions("REMOTE HOST IDENTIFICATION HAS CHANGED") ->
                    "The server's host key changed or is not trusted yet. Connect once from a terminal session to review it."
                mentions("Connection refused") || mentions("Connection timed out") ->
                    "Could not reach the server."
                mentions("Too many authentication failures") ->
                    "The server rejected every key on this device."
                mentions("no matching host key") || mentions("no matching key found") ->
                    "This server cannot be reached with the key selected for it."
                stderr.isNotBlank() -> stderr.lineSequence().last { it.isNotBlank() }.trim()
                failure?.contains("stopped responding") == true ->
                    "The server accepted the connection but never started the SFTP subsystem."
                else -> failure ?: "The connection could not be opened."
            }
        }

        internal fun buildCommand(
            server: SshStore.Server,
            identities: List<String>,
            passphrase: String?
        ): String = buildList {
            // BatchMode is what makes a passphrase-protected key unusable here, so
            // it is only set when there is no passphrase to ask for. With one, the
            // askpass helper supplies it and ssh is allowed to try.
            val batch = if (passphrase.isNullOrEmpty()) listOf("BatchMode=yes") else emptyList()
            add("/bin/ssh")
            // Subsystem, not command. Without -s the trailing "sftp" is a program to
            // run on the *remote* host — and the program called sftp there is the
            // client, which prints its own usage and exits. The connection is then
            // refused for a reason that looks nothing like its cause, and the trace
            // leads nowhere near the missing flag.
            add("-s")
            add("-p"); add(ProotLaunch.quoteForShell(server.port.toString()))
            for (argument in SshLaunchOptions.jumpArgs(server)) {
                add(ProotLaunch.quoteForShell(argument))
            }
            if (server.compress) add("-C")
            for (argument in SshLaunchOptions.options(
                identities = identities,
                extraOptions = batch + listOf("ConnectTimeout=20", "ConnectionAttempts=1"),
                // The server's own settings belong here too: a transfer to a server
                // behind a jump host, or one that authenticates with a password, was
                // reaching it with none of that applied.
                server = server
            )) {
                add(ProotLaunch.quoteForShell(argument))
            }
            // Ends the options, so a host beginning with a dash cannot be taken
            // for a flag.
            add("--")
            add(ProotLaunch.quoteForShell(SshLaunchOptions.target(server.host, server.user)))
            // The subsystem name is a bare word defined by the server, not ours to
            // quote.
            add("sftp")
        }.joinToString(" ")

        /** Joins a directory and a name without doubling the separator. */
        fun join(dir: String, name: String): String =
            if (dir.endsWith("/")) "$dir$name" else "$dir/$name"

        /**
         * Strips a trailing slash so `/srv` and `/srv/` are one path.
         *
         * A path built by joining could otherwise end up as `//`, which some
         * servers treat as a distinct empty-named entry.
         */
        internal fun normalise(path: String): String =
            if (path.length > 1 && path.endsWith("/")) path.trimEnd('/') else path
    }
}
