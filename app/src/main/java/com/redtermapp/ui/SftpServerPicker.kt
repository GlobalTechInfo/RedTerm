package com.redtermapp.ui

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import com.redtermapp.R
import com.redtermapp.ui.filelist.SftpBrowserActivity

/**
 * Asks which saved server to browse.
 *
 * The only question file transfer needs to ask. Keys are never a question: a saved
 * server is one the user has already connected to, so the keys that got them
 * there are the ones the transfer uses, and interrogating them about it would be
 * asking something they have already answered.
 */
object SftpServerPicker {

    /** Opens the browser for [server], or explains why it cannot be opened. */
    fun open(activity: Activity, server: SshStore.Server) {
        if (!SshKeyStore.hasAnyKey(activity)) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.sftp_unavailable_title)
                .setMessage(R.string.sftp_no_keys_on_device)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        activity.startActivity(
            Intent(activity, SftpBrowserActivity::class.java).apply {
                putExtra(SftpBrowserActivity.EXTRA_SERVER_ID, server.id)
            }
        )
    }

    /** A server, or null when the user backed out. */
    fun choose(activity: Activity, onChosen: (SshStore.Server) -> Unit) {
        val servers = SshStore.load(activity)
        when {
            // Nothing to browse. Saying so plainly beats an empty list or a
            // screen that cannot connect.
            servers.isEmpty() -> {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.sftp_choose_server)
                    .setMessage(R.string.sftp_no_servers)
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
            // One server: there is no choice to present, and a dialog with a
            // single row is only something to tap past.
            servers.size == 1 -> onChosen(servers.first())
            else -> {
                val labels = servers.map { server ->
                    val key = SshKeyStore.boundKey(activity, server)
                    val suffix = if (key != null) {
                        activity.getString(R.string.ssh_server_key, key.label)
                    } else {
                        activity.getString(R.string.ssh_server_no_key)
                    }
                    // The host, not just the label: two saved servers with the same
                    // name are indistinguishable without it, and picking the wrong one
                    // looks like the connection failing for no reason.
                    val who = SshLaunchOptions.target(server.host, server.user)
                    activity.getString(
                        R.string.session_option_with_host, server.label, who, suffix
                    )
                }
                AlertDialog.Builder(activity)
                    .setTitle(R.string.sftp_choose_server)
                    .setItems(labels.toTypedArray()) { _, which -> onChosen(servers[which]) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }
}
