package com.redtermapp.ui

import android.app.Activity
import android.content.Context

/**
 * Authenticates a connection by trying one credential at a time.
 *
 * The rule that matters: **a key with a passphrase must not hide a key without one.**
 * Offering every candidate in a single command and asking `SSH_ASKPASS` for a phrase
 * when one of them is refused cannot work — the helper supplies one value with no way
 * to say which key it is for — so the alternative is to offer them one at a time and
 * ask about the key actually in use. That is also how an ssh-agent behaves, and it is
 * the only arrangement in which every key on the device remains usable.
 *
 * A passphrase is asked only where an answer can still change the outcome. ssh's own
 * output cannot tell a key needing decryption apart from a server that does not
 * recognise the key at all, but the key file can, over no network at all — so a
 * refused attempt is settled by [SshKeyImporter.readsWithoutPassphrase] before any
 * question is put to the user. An open key is reported as refused and no phrase is
 * requested for it; an encrypted key is offered its dialog on every connection
 * attempt, whatever a previous one left behind, because a held phrase may itself
 * have been the failure.
 *
 * Keys are tried in [SshKeyStore.planIdentities] order: a bound key alone, otherwise
 * the newest first. A password, when the server accepts one, comes last: keys first
 * is what ssh does anyway, and a password prompt the user did not expect is worse than
 * one extra failed attempt.
 */
object SshKeyAuthenticator {

    sealed class Attempt<out T> {
        data class Ok<T>(val value: T) : Attempt<T>()

        /** The server turned this down. A different credential may still work. */
        data class Refused<T>(val detail: String) : Attempt<T>()

        /** Anything else. Trying another credential would not help. */
        data class Other<T>(val detail: String) : Attempt<T>()
    }

    sealed class Result<out T> {
        data class Connected<T>(val value: T) : Result<T>()
        /** No credential was accepted. [detail] is the last explanation ssh gave. */
        data class Rejected(val detail: String, val tries: Int) : Result<Nothing>()

        /** No usable key on the device for this server. */
        data object NoKey : Result<Nothing>()
    }

    /** The message a failed attempt leaves behind, whichever kind it was. */
    private fun detailOf(attempt: Attempt<*>): String = when (attempt) {
        is Attempt.Refused -> attempt.detail
        is Attempt.Other -> attempt.detail
        is Attempt.Ok -> ""
    }

    fun <T> authenticate(
        activity: Activity,
        context: Context,
        server: SshStore.Server,
        attempt: (key: SshKeyStore.Entry?, secret: String) -> Attempt<T>
    ): Result<T> {
        val keys = if (server.auth.wantsKeys) {
            SshKeyStore.planIdentities(context, server).keys
        } else {
            emptyList()
        }

        // A password-only server has no keys to offer, and reporting "no key" for it
        // would be a reason that names the wrong thing.
        if (keys.isEmpty() && !server.auth.wantsPassword) return Result.NoKey

        return authenticateFlow(
            keys = keys,
            wantsPassword = server.auth.wantsPassword,
            heldPassword = if (server.auth.wantsPassword) {
                SshPasswordPrompt.available(context, server)
            } else {
                null
            },
            attempt = attempt,
            ask = { key ->
                // An open key turns down every passphrase — the question for it is
                // only the device saying "the server does not know this key" in the
                // words of a passphrase prompt, which is how a key with no password
                // came to be asked about one.
                if (SshKeyImporter.readsWithoutPassphrase(context, key)) {
                    null
                } else {
                    SshKeyUnlock.requestPassphraseBlocking(activity, key)
                }
            },
            askPassword = { SshPasswordPrompt.requestBlocking(activity, server) }
        )
    }

    /**
     * The credential loop, with every dialog behind a lambda so a test can run it
     * without an activity.
     *
     * [ask] returns the phrase to retry a refused key with, or null for no retry —
     * "the dialog was dismissed" and "no passphrase could help this key" land in
     * the same place, because they both mean the attempt stands as refused.
     */
    internal fun <T> authenticateFlow(
        keys: List<SshKeyStore.Entry>,
        wantsPassword: Boolean,
        heldPassword: String?,
        attempt: (key: SshKeyStore.Entry?, secret: String) -> Attempt<T>,
        ask: (SshKeyStore.Entry) -> String?,
        askPassword: () -> String?
    ): Result<T> {
        var asked = false
        var detail = ""
        var tries = 0

        for (key in keys) {
            tries++
            var outcome = attempt(key, SshKeyUnlock.availableFor(key))
            if (outcome is Attempt.Refused && !asked) {
                asked = true
                val phrase = ask(key)
                if (!phrase.isNullOrEmpty()) outcome = attempt(key, phrase)
            }
            if (outcome is Attempt.Ok) return Result.Connected(outcome.value)
            detail = detailOf(outcome)
        }

        if (wantsPassword) {
            tries++
            var outcome = attempt(null, heldPassword ?: "")
            if (outcome is Attempt.Refused) {
                val password = askPassword()
                if (!password.isNullOrEmpty()) outcome = attempt(null, password)
            }
            if (outcome is Attempt.Ok) return Result.Connected(outcome.value)
            detail = detailOf(outcome)
        }

        if (keys.isEmpty() && !wantsPassword) return Result.NoKey
        return Result.Rejected(
            detail.ifBlank { "The server accepted none of the $tries credentials tried." },
            tries
        )
    }
}