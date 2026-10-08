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

    /**
     * Runs [attempt] against each candidate and returns the first success.
     *
     * [attempt] is given the key to offer — null when trying a password — and the
     * secret to arm the askpass helper with, which is empty when there is none.
     *
     * A passphrase is asked for at most once per call, and only for a key that has no
     * phrase held and has not been declined already, so a device full of keys the
     * server does not know cannot produce a queue of dialogs.
     */
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

        var asked = false
        var detail = ""
        var tries = 0

        for (key in keys) {
            tries++
            val held = SshKeyUnlock.availableFor(key)
            var outcome = attempt(key, held)
            if (outcome is Attempt.Refused && held.isEmpty() && !asked) {
                asked = true
                val phrase = SshKeyUnlock.requestPassphraseBlocking(activity, key)
                if (!phrase.isNullOrEmpty()) outcome = attempt(key, phrase)
            }
            if (outcome is Attempt.Ok) return Result.Connected(outcome.value)
            detail = detailOf(outcome)
        }

        if (server.auth.wantsPassword) {
            tries++
            var held = SshPasswordPrompt.available(context, server)
            var outcome = attempt(null, held)
            if (outcome is Attempt.Refused && held.isEmpty()) {
                val asked2 = SshPasswordPrompt.requestBlocking(activity, server)
                if (!asked2.isNullOrEmpty()) {
                    held = asked2
                    outcome = attempt(null, asked2)
                }
            }
            if (outcome is Attempt.Ok) return Result.Connected(outcome.value)
            detail = detailOf(outcome)
        }

        if (keys.isEmpty() && !server.auth.wantsPassword) return Result.NoKey
        return Result.Rejected(
            detail.ifBlank { "The server accepted none of the $tries credentials tried." },
            tries
        )
    }
}