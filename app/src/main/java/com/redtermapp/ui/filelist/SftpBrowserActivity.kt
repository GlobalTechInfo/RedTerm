package com.redtermapp.ui.filelist

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.redtermapp.R
import com.redtermapp.ui.SshStore
import com.redtermapp.util.AppLog
import java.io.File

/**
 * Browses a saved SSH server's filesystem and moves files between it and the
 * device.
 *
 * Batch-mode SFTP cannot prompt for a password, so this screen only works when
 * the server has a key. That is checked once, up front and said plainly, rather
 * than letting every action fail with a transport error the user cannot interpret.
 * A terminal session is offered instead, where an interactive `scp` can ask for a
 * password — the one route that still works without a key.
 *
 * Device-side files are chosen through the Storage Access Framework, so no
 * storage permission is involved and there is no path to get wrong.
 */
class SftpBrowserActivity : BaseFileBrowserActivity() {

    private lateinit var server: SshStore.Server
    private var keyId: String? = null
    private lateinit var sftpSource: SftpSource

    /** Read before super.onCreate, which calls initialPath(). */
    private var serverId: String = ""
    private var startPath: String? = null

    /** Set while waiting for the user to pick where a download goes. */
    private var pendingDownload: FileEntry? = null
    private var transferThread: Thread? = null
    private var transferDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        serverId = intent?.getStringExtra(EXTRA_SERVER_ID).orEmpty()
        keyId = intent?.getStringExtra(EXTRA_KEY_ID)?.takeIf { it.isNotBlank() }
        startPath = intent?.getStringExtra(EXTRA_PATH)?.takeIf { it.isNotBlank() }

        // The server has to be resolved before super.onCreate, which lists a
        // directory through it. Falling back to a half-built placeholder was worse
        // than useless: the "no longer saved" check that followed then tested an
        // extra this app never sends, so it rejected every real server.
        val servers = SshStore.load(this)
        val byHost = intent?.getStringExtra(EXTRA_HOST)
        val resolved = servers.firstOrNull { it.id == serverId }
            ?: byHost?.let { host -> servers.firstOrNull { it.host == host } }
        if (resolved == null) {
            // Only now is "no longer saved" actually true: the id named a server
            // that is gone.
            Toast.makeText(this, R.string.sftp_server_gone, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        server = resolved
        sftpSource = SftpSource(this, server, keyId)

        super.onCreate(savedInstanceState)

        if (!sftpSource.canAuthenticate) {
            // Nothing on the device could authenticate at all. Said once, on the
            // way in, rather than per server: the keys that connected this server
            // in a terminal are the ones being used here.
            reportUnsupported(getString(R.string.sftp_no_keys_on_device))
        }
    }

    override fun source(): FileSource = sftpSource

    override fun initialPath(): String? = startPath

    override fun titleSuffix(): String = server.label

    override fun rowMenuExtras(entry: FileEntry): List<Pair<String, () -> Unit>> = listOf(
        getString(R.string.sftp_download_to_device) to { downloadToDevice(entry) },
        // Hidden on a symlink: its own permissions are not what the link uses, and
        // changing them there is a surprise rather than a convenience.
        getString(R.string.sftp_change_permissions) to { promptPermissions(entry) }
    ) + andThenFreeSpace()

    /**
     * Free space for the directory being shown.
     *
     * Only when the server can say. `statvfs` is an extension rather than part of
     * version 3, so a server without it answers with a refusal rather than a number,
     * and showing an empty figure would be worse than showing nothing.
     */
    private fun andThenFreeSpace(): List<Pair<String, () -> Unit>> {
        val free = sftpSource.freeSpace(currentPath)?.availableBytes ?: return emptyList()
        return listOf(
            getString(R.string.sftp_free_space, formatSize(free)) to { showFreeSpace() }
        )
    }

    private fun showFreeSpace() {
        Thread({
            val stats = sftpSource.freeSpace(currentPath)
            main {
                if (isFinishing || isDestroyed) return@main
                val message = if (stats == null) {
                    getString(R.string.sftp_free_space_unknown)
                } else {
                    getString(
                        R.string.sftp_free_space_detail,
                        formatSize(stats.availableBytes),
                        formatSize(stats.totalBytes)
                    )
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.sftp_free_space_title)
                    .setMessage(message)
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }, "redterm-sftp-statvfs").start()
    }

    /**
     * Changes a file's permissions.
     *
     * An octal field rather than a row of checkboxes: rwx for user, group and other is
     * nine bits, and the mode is what every other tool on the other side speaks, so a
     * user who has typed a mode before can type one here.
     */
    private fun promptPermissions(entry: FileEntry) {
        val current = entry.permissions
            ?.let { java.lang.Long.toOctalString(it.toLong() and 0x1FFFL) }
            .orEmpty()
        val field = android.widget.EditText(this).apply {
            hint = getString(R.string.sftp_permissions_hint)
            setText(current)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        val holder = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val inner = (16 * resources.displayMetrics.density).toInt()
            setPadding(inner, inner, inner, inner)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sftp_change_permissions_title, entry.name))
            .setMessage(R.string.sftp_permissions_message)
            .setView(holder)
            .setPositiveButton(R.string.ok) { _, _ ->
                val typed = field.text.toString().trim().removePrefix("0o")
                val parsed = typed.toLongOrNull(8)
                if (parsed == null || parsed > 0x1FFF) {
                    // Kept open rather than dismissed: an octal field that quietly
                    // refuses what you typed teaches nothing.
                    Toast.makeText(this, R.string.sftp_permissions_invalid, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                applyPermissions(entry, parsed)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyPermissions(entry: FileEntry, mode: Long) {
        Thread({
            val result = sftpSource.setPermissions(entry.path, mode)
            main {
                if (isFinishing || isDestroyed) return@main
                if (result.succeeded) {
                    loadDir(currentPath)
                } else {
                    reportError(result.message())
                }
            }
        }, "redterm-sftp-chmod").start()
    }

    // ------------------------------------------------------------------ downloads

    private fun downloadToDevice(entry: FileEntry) {
        if (entry.isDirectory) {
            // sftp can do `get -r`, but it would write into the rootfs staging
            // area, which has nowhere near the room for a large tree and is not
            // where the user expects the result.
            AlertDialog.Builder(this)
                .setTitle(entry.name)
                .setMessage(R.string.sftp_folder_download_note)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        pendingDownload = entry
        createDocument.launch(entry.name)
    }

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            val entry = pendingDownload
            pendingDownload = null
            if (uri != null && entry != null) download(entry, uri)
        }

    private fun download(entry: FileEntry, destination: Uri) {
        withProgress(getString(R.string.sftp_downloading, entry.name)) { onProgress ->
            // Fetched into the cache first, then streamed out to the location the
            // user picked. The picked location is a document, which may have no
            // file path behind it at all, so it cannot be written to directly.
            val staged = File(cacheDir, "sftp-download-${System.nanoTime()}-${entry.name}")
            val fetched = sftpSource.downloadTo(entry, staged, onProgress)
            if (fetched is OpResult.Failed) {
                staged.delete()
                return@withProgress fetched
            }
            val copied = try {
                contentResolver.openOutputStream(destination)?.use { out ->
                    staged.inputStream().use { it.copyTo(out) }
                    true
                } ?: false
            } catch (e: Exception) {
                AppLog.e(this, "sftp", "could not write the download: ${e.message}")
                false
            } finally {
                staged.delete()
            }
            if (copied) OpResult.Ok else OpResult.Failed(getString(R.string.sftp_write_failed))
        }
    }

    // -------------------------------------------------------------------- uploads

    private val pickDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) upload(uri)
        }

    private fun upload(uri: Uri) {
        val name = displayNameOf(uri)
        if (name == null) {
            Toast.makeText(this, R.string.sftp_upload_read_failed, Toast.LENGTH_LONG).show()
            return
        }
        val target = joinPath(currentPath, name)
        if (target == null) return
        // Read the picked document into a real file first: sftp reads from inside
        // the rootfs and cannot open a content:// stream.
        val staged = File(cacheDir, "sftp-upload-${System.nanoTime()}-$name")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("no stream for $uri")
        } catch (e: Exception) {
            AppLog.e(this, "sftp", "could not read the picked file: ${e.message}")
            staged.delete()
            Toast.makeText(this, R.string.sftp_upload_read_failed, Toast.LENGTH_LONG).show()
            return
        }
        withProgress(getString(R.string.sftp_uploading, name)) { onProgress ->
            val result = sftpSource.uploadFrom(staged, target, onProgress)
            staged.delete()
            result
        }
    }

    private fun displayNameOf(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (_: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    // -------------------------------------------------------------------- helpers

    /**
     * Runs a transfer behind a progress dialog and refreshes the listing after.
     *
     * The dialog's cancel button only stops the app following along; it does not
     * kill the transfer, because interrupting sftp mid-write would leave a
     * truncated file on the server. Saying so in the button's own copy is better
     * than offering a cancel that half-works.
     */
    private fun withProgress(
        title: String,
        work: (onProgress: (Long, Long) -> Unit) -> OpResult
    ) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(R.string.sftp_transfer_running)
            .setNegativeButton(R.string.ok, null)
            .create()
        dialog.show()
        transferDialog = dialog
        transferThread = Thread({
            val result = work { done, total ->
                // A view update from the transfer thread. It does not throw, but it is
                // not ordered against the dialog's own layout either, so the last
                // update of a fast transfer can land before the first is drawn.
                main {
                    dialog.setMessage(
                        if (total > 0) {
                            getString(
                                R.string.sftp_transfer_progress,
                                (done * 100 / total).toInt()
                            )
                        } else {
                            getString(R.string.sftp_transfer_bytes, formatSize(done))
                        }
                    )
                }
            }
            main {
                transferDialog = null
                transferThread = null
                dialog.dismiss()
                if (result.succeeded) {
                    Toast.makeText(this, R.string.sftp_transfer_done, Toast.LENGTH_SHORT).show()
                    loadDir(currentPath)
                } else {
                    reportError(result.message())
                }
            }
        }, "redterm-sftp-transfer").also { it.start() }
    }

    private fun reportUnsupported(reason: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.sftp_unavailable_title)
            .setMessage(reason)
            .setPositiveButton(R.string.ok) { _, _ -> finish() }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .show()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        val created = super.onCreateOptionsMenu(menu)
        menu.add(0, MENU_UPLOAD, 0, R.string.sftp_upload).setShowAsAction(
            android.view.MenuItem.SHOW_AS_ACTION_NEVER
        )
        return created
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == MENU_UPLOAD) {
            pickDocument.launch(arrayOf("*/*"))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        sftpSource.release()
        transferThread?.interrupt()
        transferDialog?.dismiss()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SERVER_ID = "ssh_server_id"
        const val EXTRA_KEY_ID = "ssh_key_id"
        const val EXTRA_PATH = "ssh_path"
        private const val EXTRA_HOST = "ssh_host"
        private const val EXTRA_PORT = "ssh_port"
        private const val EXTRA_USER = "ssh_user"
        private const val EXTRA_LABEL = "ssh_label"
        private const val MENU_UPLOAD = 9001
    }
}