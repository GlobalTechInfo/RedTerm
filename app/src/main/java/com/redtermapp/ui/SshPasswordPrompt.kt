package com.redtermapp.ui

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.redtermapp.R
import com.redtermapp.util.sftp.SshAskpass

/**
 * Supplies a server password to a connection with no terminal to ask on.
 *
 * The same arrangement as a key passphrase: the password is filed in
 * [SshCredentialStore] encrypted, asked for once, and handed to ssh through
 * `SSH_ASKPASS`. It is not written into the command, which is logged and written
 * into a launcher script on the way past.
 */
object SshPasswordPrompt {

    /** The held password for [server], or an empty string. */
    fun available(context: Context, server: SshStore.Server): String {
        val ref = server.passwordRef ?: return ""
        val stored = SshCredentialStore.get(context, ref)
        if (stored.isEmpty()) return ""
        SshAskpass.rememberPassword(server.id, stored)
        return stored
    }

    /** Whether this server has anything usable stored for it. */
    fun hasStoredPassword(context: Context, server: SshStore.Server): Boolean =
        !available(context, server).isEmpty()

    /**
     * Asks on the main thread and blocks the calling worker until dismissed.
     *
     * The answer is the password itself, not a boolean: it has to reach ssh, so it is
     * cached on the way out.
     */
    fun requestBlocking(activity: Activity, server: SshStore.Server): String? {
        if (activity.isFinishing || activity.isDestroyed) return null
        if (SshAskpass.isPasswordDeclined(server.id)) return null
        synchronized(promptLock) {
            if (SshAskpass.isPasswordDeclined(server.id)) return null
            if (activity.isFinishing || activity.isDestroyed) return null

            val field = EditText(activity).apply {
                hint = activity.getString(R.string.ssh_password)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
            }
            var entered: String? = null
            val answer = SshKeyUnlock.AnswerLatch<String?>()
            AlertDialog.Builder(activity)
                .setTitle(R.string.ssh_password_title)
                .setMessage(activity.getString(R.string.ssh_password_message, server.label))
                .setView(SecretField.withRevealToggle(activity, field))
                .setPositiveButton(R.string.ok) { _, _ -> entered = field.text.toString() }
                .setNegativeButton(R.string.cancel) { _, _ -> entered = null }
                .create()
                .apply {
                    setOnDismissListener { answer.complete(entered) }
                    show()
                }

            val password = answer.await(TIMEOUT_SECONDS)
            if (password.isNullOrEmpty()) {
                SshAskpass.declinePassword(server.id)
                return null
            }
            SshAskpass.rememberPassword(server.id, password)
            // Kept for next time. A stored password is not a passphrase: it survives
            // the process, because the alternative is asking for it on every single
            // connection.
            val ref = server.passwordRef ?: SshCredentialStore.newRef()
            if (SshCredentialStore.put(activity, ref, password).isNotEmpty()) {
                onStored?.invoke(server.id, ref)
            }
            return password
        }
    }

    /**
     * Called with a new reference after a password has been stored, so the caller can
     * point the server at it. A hook rather than a save, because only the caller knows
     * whether the server is new or already in the list.
     */
    var onStored: ((serverId: String, ref: String) -> Unit)? = null

    fun forget(serverId: String) = SshAskpass.forgetPassword(serverId)

    private val promptLock = Object()

    private const val TIMEOUT_SECONDS = 180L
}