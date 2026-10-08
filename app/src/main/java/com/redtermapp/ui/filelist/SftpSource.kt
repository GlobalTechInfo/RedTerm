package com.redtermapp.ui.filelist

import android.content.Context
import com.redtermapp.R
import com.redtermapp.ui.SshKeyAuthenticator
import com.redtermapp.ui.SshKeyStore
import com.redtermapp.ui.SshStore
import com.redtermapp.util.AppLog
import com.redtermapp.util.sftp.SftpAttrs
import com.redtermapp.util.sftp.StatVfs
import com.redtermapp.util.sftp.SftpClient
import com.redtermapp.util.sftp.SftpException
import com.redtermapp.util.sftp.SftpName
import java.io.File

/**
 * A server's filesystem, reached over SFTP.
 *
 * Every call opens a fresh connection and closes it again. Reusing one connection
 * would be faster for a long browse, but a session that dies mid-browse then takes
 * the whole screen with it, and a transfer on a big file holds the connection for
 * a long time. One short-lived connection per operation keeps each failure
 * contained and each operation independently retryable.
 */
class SftpSource(
    private val activity: android.app.Activity,
    private val server: SshStore.Server,
    keyId: String?
) : FileSource {

    private val context: Context get() = activity.applicationContext

    /** The one connection the browser keeps open for its whole life. */
    private val connectionLock = Object()
    private var connection: SftpClient? = null

    /**
     * The keys to authenticate with, as seen inside the client rootfs.
     *
     * Resolved once: the SFTP stream has no terminal, so a prompt cannot be
     * answered and the answer cannot change mid-operation.
     */
    private val identities: List<String> =
        SshKeyStore.identitiesFor(context, server.copy(keyId = keyId))

    override val label: String = server.label

    /** Remote files are fetched before a local viewer can open them. */
    override val canOpenLocally: Boolean = false

    private var error: String = ""
    override val lastError: String get() = error

    /** Where the remote filesystem starts. `/` is the only honest answer. */
    override val root: String = "/"

    /**
     * The server's own idea of the login user's home directory.
     *
     * Lazy, because asking costs a connection and the value is only ever read from
     * a worker thread. Guessing `/root` would be wrong for every non-root login,
     * which is most of them.
     */
    override val home: String by lazy { withConnection { it.home() } ?: "/root" }

    /**
     * Whether anything on the device could authenticate.
     *
     * False only when the device has no key at all. It is not a question about
     * this particular server: a saved server is one the user has already connected
     * to, so the keys that got them there are the ones used here.
     */
    val canAuthenticate: Boolean get() = identities.isNotEmpty()

    /**
     * Runs [work] against a connection, translating a failure into a message.
     *
     * Everything here blocks: an SFTP round trip is a network request, and the
     * browser always calls this from a worker thread.
     */
    private fun <T> withConnection(work: (SftpClient) -> T): T? {
        if (!canAuthenticate) {
            error = "no-key"
            return null
        }
        // Serialised: the connection is one ssh process and one request stream, and
        // a listing and a transfer can otherwise overlap on it.
        synchronized(connectionLock) {
            for (attemptNumber in 1..2) {
                var client = connection
                if (client == null) {
                    when (val opened = open()) {
                        is SftpClient.OpenResult.Opened -> {
                            connection = opened.client
                            client = opened.client
                        }
                        // ssh said why on stderr; pass it on rather than reporting a
                        // dead connection, which is not what happened.
                        is SftpClient.OpenResult.Failed -> {
                            error = opened.reason
                            return null
                        }
                    }
                }
                try {
                    val result = work(client!!)
                    error = ""
                    return result
                } catch (e: Exception) {
                    // A connection dropped by an idle timeout looks the same as a bad
                    // path, and the one cheap way to tell them apart is to reconnect
                    // and try the same thing again.
                    val worthAnotherTry = attemptNumber == 1 && looksLikeLostConnection(e)
                    AppLog.e(
                        context, "sftp",
                        "${e.javaClass.simpleName} in ${describe(e)}: ${e.message}" +
                            if (worthAnotherTry) " (retrying on a new connection)" else ""
                    )
                    runCatching { client.close() }
                    connection = null
                    if (!worthAnotherTry) {
                        error = describe(e)
                        return null
                    }
                }
            }
        }
        error = describe(SftpException("The server closed the connection"))
        return null
    }

    /** Drops the connection. Called when the browser goes away. */
    fun release() = synchronized(connectionLock) {
        connection?.let { runCatching { it.close() } }
        connection = null
    }

    private fun looksLikeLostConnection(e: Exception): Boolean {
        val status = (e as? SftpException)?.statusCode
        return status == 6 || status == 7 || e is java.io.EOFException
    }

    /**
     * Opens a connection, trying each candidate key in turn.
     *
     * One key per connection on purpose. Asking a phrase for whichever key happens to
     * be first would leave the others untried, so one encrypted key on the device was
     * enough to make every server unreachable — including servers the right key for
     * was sitting right there, unused.
     */
    private fun open(): SftpClient.OpenResult {
        var detail = ""
        val outcome = SshKeyAuthenticator.authenticate(activity, context, server) { key, secret ->
            // Which credential was tried, so a refusal is diagnosable from the log
            // rather than being just another "Permission denied" with nothing attached.
            AppLog.d(
                context, "sftp",
                if (key == null) {
                    "try a password for ${server.host}"
                } else {
                    "try ${key.label} (${key.fileName})" +
                        if (secret.isEmpty()) "" else " with a held passphrase"
                }
            )
            val result = SftpClient.connect(
                context = context,
                server = server,
                identities = key?.let { listOf(SshKeyStore.identityPathFor(context, it)) }
                    ?: emptyList(),
                passphrase = secret
            )
            when (result) {
                is SftpClient.OpenResult.Opened -> SshKeyAuthenticator.Attempt.Ok(result)
                is SftpClient.OpenResult.Failed -> if (result.authenticationRefused) {
                    SshKeyAuthenticator.Attempt.Refused(result.reason)
                } else {
                    SshKeyAuthenticator.Attempt.Other(result.reason)
                }
            }
        }
        return when (outcome) {
            is SshKeyAuthenticator.Result.Connected -> outcome.value
            is SshKeyAuthenticator.Result.Rejected -> {
                detail = outcome.detail
                AppLog.w(context, "sftp", "no candidate key was accepted: $detail")
                SftpClient.OpenResult.Failed(detail)
            }
            SshKeyAuthenticator.Result.NoKey -> {
                AppLog.w(context, "sftp", "no usable key for ${server.host}")
                SftpClient.OpenResult.Failed(context.getString(R.string.sftp_no_keys_on_device))
            }
        }
    }

    /**
     * Turns a protocol failure into something a user can act on.
     *
     * The status code is more useful than the message, because OpenSSH sends
     * terse text like "No such file" and the distinction that matters is often
     * between "missing" and "not permitted".
     */
    private fun describe(e: Exception): String {
        val sftp = e as? com.redtermapp.util.sftp.SftpException
        return when (sftp?.statusCode) {
            2 -> "That path does not exist on the server."
            3 -> "The server refused access. Check the user and its permissions there."
            7, 6 -> "The connection to the server was lost."
            else -> sftp?.message?.takeIf { it.isNotBlank() }
                ?: (e.message ?: "The transfer failed.")
        }
    }

    override fun list(path: String): List<FileEntry>? =
        withConnection { client ->
            client.list(path).map { name -> name.toEntry(path) }
        }

    override fun setPermissions(path: String, mode: Long): OpResult =
        withConnection { client -> client.setPermissions(path, mode); OpResult.Ok }
            ?: OpResult.Failed(error)

    override fun makeSymlink(target: String, linkPath: String): OpResult =
        withConnection { client -> client.symlink(target, linkPath); OpResult.Ok }
            ?: OpResult.Failed(error)

    /** Free space where [path] lives, or null when the server cannot report it. */
    fun freeSpace(path: String): StatVfs? = withConnection { it.freeSpace(path) }

    override fun delete(path: String, recursive: Boolean): OpResult =
        withConnection { client -> client.remove(path, recursive); OpResult.Ok }
            ?: OpResult.Failed(error)

    override fun mkdir(path: String): OpResult =
        withConnection { client -> client.mkdir(path); OpResult.Ok }
            ?: OpResult.Failed(error)

    override fun rename(from: String, to: String): OpResult =
        withConnection { client -> client.rename(from, to); OpResult.Ok }
            ?: OpResult.Failed(error)

    /**
     * Copying within one server is a download followed by an upload.
     *
     * The SFTP protocol has no server-side copy, and shelling out to `cp` would
     * need a second connection and a shell the user may not have. The staged copy
     * is slower but uses only what is already authenticated.
     */
    override fun copy(from: String, to: String, move: Boolean): OpResult {
        if (move) return rename(from, to)
        val staged = File(context.cacheDir, "sftp-copy-${System.nanoTime()}")
        val downloaded = withConnection { client ->
            client.download(from, staged) { _, _ -> }
            OpResult.Ok
        }
        if (downloaded == null) {
            staged.deleteRecursively()
            return OpResult.Failed(error)
        }
        val uploaded = withConnection { client ->
            if (staged.isDirectory) {
                // A tree is walked entry by entry, because SFTP has no recursive
                // put.
                uploadTree(client, staged, to)
            } else {
                client.upload(staged, to) { _, _ -> }
            }
            OpResult.Ok
        }
        staged.deleteRecursively()
        return uploaded ?: OpResult.Failed(error)
    }

    private fun uploadTree(client: SftpClient, localRoot: File, remoteRoot: String) {
        val base = localRoot.parentFile?.absolutePath ?: ""
        localRoot.walkTopDown().forEach { file ->
            val relative = file.absolutePath.removePrefix(base).trimStart('/')
            val target = SftpClient.join(remoteRoot, relative)
            when {
                file.isDirectory -> runCatching { client.mkdir(target) }
                else -> client.upload(file, target) { _, _ -> }
            }
        }
    }

    override fun summarise(path: String): FolderSummary =
        withConnection { client ->
            val totals = client.sizeOf(path)
            FolderSummary(totals.files, totals.folders, totals.bytes)
        } ?: FolderSummary(0, 0, 0L)

    /**
     * Searches only the current directory.
     *
     * A recursive remote search is one round trip per directory, which on a deep
     * tree is minutes of nothing happening. A one-level result is honest about
     * what was searched; pretending to search the whole server is not.
     */
    override fun search(from: String, query: String, limit: Int): Pair<List<FileEntry>, Boolean> {
        val entries = list(from).orEmpty()
        val matches = entries.filter { it.name.contains(query, ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
        return matches to (matches.size > limit)
    }

    /** Fetches a remote file somewhere a local viewer can open it. */
    override fun cacheCopy(entry: FileEntry): File? {
        val target = File(context.cacheDir, "sftp-${System.nanoTime()}-${entry.name}")
        return if (downloadTo(entry, target) { _, _ -> } is OpResult.Ok) target else null
    }

    /**
     * Downloads [entry] to [destination], reporting byte progress.
     *
     * Separate from [cacheCopy] because the caller may want a different place to
     * land the file than the viewer cache.
     */
    fun downloadTo(
        entry: FileEntry,
        destination: File,
        onProgress: (Long, Long) -> Unit
    ): OpResult {
        AppLog.i(context, "sftp", "download ${entry.path} -> ${destination.name}")
        return withConnection { client ->
            client.download(entry.path, destination, onProgress)
            AppLog.i(context, "sftp", "downloaded ${entry.path}, ${destination.length()} bytes")
            OpResult.Ok
        } ?: OpResult.Failed(error)
    }

    /** Uploads [hostFile] to [remotePath], with byte progress. */
    fun uploadFrom(
        hostFile: File,
        remotePath: String,
        onProgress: (Long, Long) -> Unit
    ): OpResult {
        AppLog.i(context, "sftp", "upload ${hostFile.name} (${hostFile.length()} bytes) -> $remotePath")
        return withConnection { client ->
            if (hostFile.isDirectory) {
                uploadTreeWithProgress(client, hostFile, remotePath, onProgress)
            } else {
                client.upload(hostFile, remotePath, onProgress)
            }
            AppLog.i(context, "sftp", "uploaded $remotePath")
            OpResult.Ok
        } ?: OpResult.Failed(error)
    }

    private fun uploadTreeWithProgress(
        client: SftpClient,
        localRoot: File,
        remoteRoot: String,
        onProgress: (Long, Long) -> Unit
    ) {
        val base = localRoot.parentFile?.absolutePath ?: ""
        val files = localRoot.walkTopDown().filter { it.isFile }.toList()
        val total = files.sumOf { it.length() }
        var done = 0L
        localRoot.walkTopDown().forEach { file ->
            val relative = file.absolutePath.removePrefix(base).trimStart('/')
            val target = SftpClient.join(remoteRoot, relative)
            if (file.isDirectory) {
                runCatching { client.mkdir(target) }
            } else {
                client.upload(file, target) { sent, _ ->
                    done = sent + done
                    onProgress(done, total)
                }
            }
        }
    }

    private fun SftpName.toEntry(under: String): FileEntry {
        // READDIR reports bare names, so the full path has to be rebuilt. This is
        // what makes a "." or ".." entry harmless to drop rather than a bug.
        val base = if (under.endsWith("/")) under else "$under/"
        return FileEntry(
            path = base + filename,
            name = filename,
            isDirectory = attrs.isDirectory,
            size = attrs.size ?: 0L,
            modified = formatTime(attrs),
            isSymlink = attrs.isSymlink,
            permissions = attrs.permissions
        )
    }

    /** The server sends seconds since the epoch, or nothing at all. */
    private fun formatTime(attrs: SftpAttrs): String {
        val seconds = attrs.modifyTime ?: return ""
        return try {
            android.text.format.DateFormat.getDateFormat(context)
                .format(java.util.Date(seconds * 1000L))
        } catch (_: Exception) {
            ""
        }
    }
}
