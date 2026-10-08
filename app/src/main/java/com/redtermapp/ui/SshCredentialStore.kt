package com.redtermapp.ui

import android.content.Context
import androidx.core.content.edit
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Server passwords, encrypted under a key that only this app can use.
 *
 * Keystore-backed rather than a string in preferences, because preferences are
 * readable from a backup or an extracted data directory, and a password is the one
 * thing in this feature that is not already protected by a private key on disk. The
 * secret is only ever held as ciphertext here; the AES key never leaves the
 * Keystore, so the file is useless on its own.
 *
 * Deliberately not in `SshKeyStore`: that file is exported and imported for device
 * transfer, and a password must not travel with it.
 */
object SshCredentialStore {

    private const val TAG = "SshCredentialStore"
    private const val KEY_ALIAS = "redterm_ssh_passwords"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val PREFS = "ssh_credentials"
    private const val KEY = "passwords"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    /** A new identifier for a password not yet stored. */
    fun newRef(): String = SshStore.newId()

    fun has(context: Context, ref: String): Boolean =
        ref.isNotBlank() && prefs(context).contains(ref)

    /**
     * Stores [password] and returns the reference it is filed under.
     *
     * Returns the reference even on failure, so a server can still be saved when the
     * keystore is unavailable — it just will not remember the password between runs,
     * which is a far better outcome than refusing to save the server at all.
     */
    fun put(context: Context, ref: String, password: String): String {
        if (password.isEmpty()) {
            forget(context, ref)
            return ""
        }
        return runCatching {
            val cipher = encryptCipher()
            val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            val payload = cipher.iv + encrypted
            prefs(context).edit {
                putString(ref, Base64.encodeToString(payload, Base64.NO_WRAP))
            }
            ref
        }.getOrElse {
            Log.w(TAG, "could not encrypt the password", it)
            ""
        }
    }

    /** The stored password, or an empty string if there is none to be had. */
    fun get(context: Context, ref: String): String {
        if (ref.isBlank()) return ""
        val encoded = prefs(context).getString(ref, null) ?: return ""
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = decryptCipher()
            val iv = payload.copyOfRange(0, IV_BYTES)
            val body = payload.copyOfRange(IV_BYTES, payload.size)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrElse {
            // A key that no longer decrypts it — the keystore entry was invalidated by
            // a lock-screen change, or the data was restored onto another device.
            // Treated as "no password": the user is asked again rather than being
            // offered a wrong one.
            Log.w(TAG, "could not decrypt a stored password", it)
            forget(context, ref)
            ""
        }
    }

    fun forget(context: Context, ref: String) {
        if (ref.isNotBlank()) prefs(context).edit { remove(ref) }
    }

    /** Forgets every stored password. Called when a server is deleted. */
    fun forgetAll(context: Context) {
        prefs(context).edit { clear() }
    }

    // ------------------------------------------------------------------ crypto

    private fun encryptCipher(): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }

    private fun decryptCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding")

    /**
     * The Keystore-held key, created on first use.
     *
     * Not requiring user authentication: a terminal session has no way to satisfy a
     * prompt for it, and a password that cannot be read without a biometric check is
     * a password the app silently cannot use.
     */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.secretKey
            ?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}