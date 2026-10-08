package com.redtermapp.ui

import android.content.Context
import android.util.Log
import com.redtermapp.distro.ProotLaunch
import com.redtermapp.util.AppLog
import com.redtermapp.util.SshClient
import java.io.File

/**
 * Brings in a key made somewhere else, and changes an existing passphrase.
 *
 * Generation was the only way to get a key into the app, which makes it useless to
 * anyone whose keys were made years ago on a machine they no longer have — which is
 * most people. Importing is the single most requested feature of any SSH client for
 * that reason.
 *
 * Everything goes through the bundled `ssh-keygen`, including deriving the public
 * half. Re-deriving it here would mean parsing OpenSSH private key formats, which are
 * padded, encrypted and versioned; `ssh-keygen -y` is the supported way to get the
 * public key out of any private key it can read, and it knows about every format the
 * bundled ssh will later accept.
 */
object SshKeyImporter {

    private const val TAG = "SshKeyImporter"

    data class Result(
        val entry: SshKeyStore.Entry?,
        val fingerprint: String,
        val message: String
    ) {
        val succeeded: Boolean get() = entry != null
    }

    /**
     * Writes [privateKeyText] into the client rootfs and records it.
     *
     * [encrypted] is recorded but not trusted: it says what the user says about their
     * own key, and nothing is gated on it. A connection refused anyway prompts for the
     * passphrase, which is what makes an imported key that lied about being unprotected
     * work rather than fail.
     */
    fun import(
        context: Context,
        label: String,
        privateKeyText: String,
        encrypted: Boolean = false
    ): Result {
        val key = privateKeyText.trim()
        if (key.isEmpty()) {
            return Result(null, "", "The key is empty.")
        }
        // Written by the user or pasted from somewhere else, so it is checked rather
        // than trusted: a file that is not a private key produces a confusing error
        // from ssh much later otherwise.
        val looksLikePrivateKey =
            key.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----") ||
                key.startsWith("-----BEGIN RSA PRIVATE KEY-----") ||
                key.startsWith("-----BEGIN EC PRIVATE KEY-----") ||
                key.startsWith("-----BEGIN DSA PRIVATE KEY-----") ||
                key.startsWith("-----BEGIN PGP PRIVATE KEY BLOCK-----") ||
                key.startsWith("PuTTY-User-Key-File-")
        if (!looksLikePrivateKey) {
            return Result(
                null, "",
                "That is not a private key. It has to begin with -----BEGIN ... PRIVATE KEY-----."
            )
        }

        val entry = SshKeyStore.newEntry(
            context = context,
            label = label,
            keyType = detectType(key),
            bits = 0,
            encrypted = encrypted
        )
        val target = SshKeyStore.fileFor(context, entry)
        target.parentFile?.mkdirs()
        try {
            target.writeText(key.trim() + "\n")
            // Owner-only. Anything looser and every other app with storage access can
            // read the user's private key; ssh refuses to use it as well.
            target.setReadable(false, false)
            target.setReadable(true, true)
        } catch (e: Exception) {
            Log.w(TAG, "could not write the imported key", e)
            return Result(null, "", "The key could not be written: ${e.message}")
        }

        val derived = derivePublicKey(context, target)
        if (!derived) {
            target.delete()
            return Result(
                null, "",
                "The key could not be read back by ssh-keygen, so it was not saved."
            )
        }

        SshKeyStore.add(context, entry)
        val fingerprint = fingerprintOf(context, target)
        AppLog.i(context, "ssh", "imported key ${entry.fileName} ($fingerprint)")
        return Result(entry, fingerprint, "")
    }

    /**
     * Writes the public half next to the private one.
     *
     * `ssh-keygen -y` prints the public key to stdout. Deriving it is not optional:
     * an imported key without a `.pub` cannot be copied to a server, and a stale one
     * from a previous key with the same name would be worse than none.
     */
    private fun derivePublicKey(context: Context, privateKey: File): Boolean {
        val inRootfs = SshClient.inRootfs(context, privateKey)
        val (code, output) = SshClient.run(
            context = context,
            command = "/bin/ssh-keygen -y -f " + ProotLaunch.quoteForShell(inRootfs),
            timeoutMinutes = 1
        )
        if (code != 0) return false
        val line = output.lineSequence().firstOrNull {
            it.startsWith("ssh-") || it.startsWith("ecdsa-") || it.startsWith("sk-")
        } ?: return false
        val publicKey = File(privateKey.absolutePath + ".pub")
        return runCatching {
            publicKey.writeText(line.trim() + "\n")
            publicKey.setReadable(true, true)
            true
        }.getOrDefault(false)
    }

    /** The key's own `SHA256:…` fingerprint, for showing the user what they imported. */
    fun fingerprintOf(context: Context, privateKey: File): String {
        val inRootfs = SshClient.inRootfs(context, privateKey)
        val (code, output) = SshClient.run(
            context = context,
            command = "/bin/ssh-keygen -lf " + ProotLaunch.quoteForShell(inRootfs),
            timeoutMinutes = 1
        )
        if (code != 0) return ""
        return output.split(Regex("\\s+")).firstOrNull { it.startsWith("SHA256:") }.orEmpty()
    }

    /**
     * Changes a key's passphrase, or removes one.
     *
     * `ssh-keygen -p` rewrites the key in place with the new protection, which is the
     * only safe way to do it: anything that rebuilt the file from text could get the
     * permissions or the format wrong.
     *
     * [oldPassphrase] and [newPassphrase] may be empty for an unprotected key.
     */
    fun changePassphrase(
        context: Context,
        entry: SshKeyStore.Entry,
        oldPassphrase: String,
        newPassphrase: String
    ): Result {
        val file = SshKeyStore.fileFor(context, entry)
        if (!file.isFile) {
            return Result(null, "", "The key file is not there.")
        }
        val inRootfs = SshClient.inRootfs(context, file)
        val oldArgument = if (oldPassphrase.isEmpty()) "''" else ProotLaunch.quoteForShell(oldPassphrase)
        val newArgument = if (newPassphrase.isEmpty()) "''" else ProotLaunch.quoteForShell(newPassphrase)
        val command = "/bin/ssh-keygen -p -f '$inRootfs' -P $oldArgument -N $newArgument"
        val (code, output) = SshClient.run(
            context = context,
            command = command,
            timeoutMinutes = 2,
            // Both phrases are arguments on this command line, so both argument pairs
            // are what gets taken out of the log. Redacting one value on its own used
            // to corrupt any filename that happened to contain it.
            redacted = "-P $oldArgument -N $newArgument"
        )
        if (code != 0) {
            AppLog.i(
                context, "ssh",
                "change passphrase for ${entry.fileName} failed: exit=$code ${output.takeLast(200)}"
            )
            return Result(
                null, "",
                if (output.contains("incorrect passphrase")) {
                    "The current passphrase is not right."
                } else {
                    "The passphrase could not be changed."
                }
            )
        }
        // The cached phrase belongs to the old protection and would now be wrong.
        com.redtermapp.util.sftp.SshAskpass.forget(entry.fileName)
        val updated = entry.copy(encrypted = newPassphrase.isNotEmpty())
        SshKeyStore.add(context, updated)
        AppLog.i(context, "ssh", "changed passphrase for ${entry.fileName}")
        return Result(updated, fingerprintOf(context, file), "")
    }

    /**
     * Guesses the key type from the header, so an imported key is labelled correctly
     * without asking. Defaulted rather than required: a wrong label is cosmetic and
     * the user can rename it.
     */
    private fun detectType(text: String): String = when {
        text.contains("BEGIN RSA PRIVATE KEY") -> "rsa"
        text.contains("BEGIN EC PRIVATE KEY") -> "ecdsa"
        text.contains("BEGIN DSA PRIVATE KEY") -> "dsa"
        text.contains("PuTTY-User-Key-File-2:") &&
            text.contains("ssh-ed25519") -> "ed25519"
        else -> "ed25519"
    }
}