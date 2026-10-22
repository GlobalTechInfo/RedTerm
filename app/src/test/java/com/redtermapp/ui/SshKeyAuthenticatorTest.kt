package com.redtermapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the credential loop in [SshKeyAuthenticator.authenticateFlow] directly.
 *
 * Every dialog is bound behind the [SshKeyAuthenticator.authenticateFlow] ask
 * lambdas at the production call site, so the whole ask-on-refusal ordering is
 * testable without an activity. What the tests guard is the chain of failures
 * behind "the connection test and SFTP never asked for a passphrase": a prompt
 * dismissed once used to be remembered process-wide and every later connection
 * went on failing without ever asking again, and a wrong phrase held in memory
 * silenced the question the same way.
 */
/** The stock refusal, as ssh prints it, so every test reads the same failure. */
private fun refused(detail: String = "ubuntu@host: Permission denied (publickey).") =
    SshKeyAuthenticator.Attempt.Refused<String>(detail)

class SshKeyAuthenticatorTest {

    private val key: SshKeyStore.Entry =
        SshKeyStore.Entry("k1", "One", "id_ed25519_one", "ed25519")
    private val second: SshKeyStore.Entry =
        SshKeyStore.Entry("k2", "Two", "id_ed25519_two", "ed25519")

    private class Attempts {
        val seen = mutableListOf<Pair<String, String>>()
        private val answers = ArrayDeque<(SshKeyStore.Entry?, String) -> SshKeyAuthenticator.Attempt<String>>()

        fun answer(answer: SshKeyAuthenticator.Attempt<String>) {
            answers.addLast { _, _ -> answer }
        }

        fun answerBySecret(whenSecret: String, then: SshKeyAuthenticator.Attempt<String>) {
            answers.addLast { _, secret ->
                if (secret == whenSecret) then else refused("secret did not match")
            }
        }

        fun answerByKey(byKey: (SshKeyStore.Entry?, String) -> SshKeyAuthenticator.Attempt<String>) {
            answers.addLast(byKey)
        }

        fun run(): (SshKeyStore.Entry?, String) -> SshKeyAuthenticator.Attempt<String> = { key, secret ->
            seen.add("${key?.fileName ?: "<password>"}" to secret)
            answers.removeFirstOrNull()?.invoke(key, secret)
                ?: refused("no more scripted answers")
        }
    }

    @Test
    fun `a refused key is retried with the phrase the user typed`() {
        val script = Attempts().apply {
            answer(refused())
            answerBySecret("phrase", SshKeyAuthenticator.Attempt.Ok("done"))
        }
        val result = SshKeyAuthenticator.authenticateFlow(
            keys = listOf(key),
            wantsPassword = false,
            heldPassword = null,
            attempt = script.run(),
            ask = { SshKeyAuthenticator.Prompt.Answered("phrase") },
            askPassword = { "the password question never ran" }
        )

        assertTrue(result is SshKeyAuthenticator.Result.Connected)
        assertEquals(
            listOf("id_ed25519_one" to "", "id_ed25519_one" to "phrase"),
            script.seen
        )
    }

    @Test
    fun `the question is asked once per connection attempt, however many keys refuse`() {
        var questions = 0
        val script = Attempts().apply {
            answerByKey { _, _ -> refused() }
        }
        val result = SshKeyAuthenticator.authenticateFlow(
            keys = listOf(key, second),
            wantsPassword = false,
            heldPassword = null,
            attempt = script.run(),
            ask = { questions++; SshKeyAuthenticator.Prompt.Answered("phrase") },
            askPassword = { "" }
        )

        assertTrue(result is SshKeyAuthenticator.Result.Rejected)
        assertEquals(1, questions)
        // The first key is offered twice — once bare, once with the answer — and
        // the second key gets offered bare: the question was already spent, and
        // trying it silently again cannot help it either.
        assertEquals(
            listOf(
                "id_ed25519_one" to "",
                "id_ed25519_one" to "phrase",
                "id_ed25519_two" to ""
            ),
            script.seen
        )
    }

    @Test
    fun `a dismissed dialog moves on without a phrase`() {
        val script = Attempts().apply {
            answerByKey { _, _ -> refused() }
        }
        val result = SshKeyAuthenticator.authenticateFlow(
            keys = listOf(key, second),
            wantsPassword = false,
            heldPassword = null,
            attempt = script.run(),
            ask = { SshKeyAuthenticator.Prompt.Answered("") },
            askPassword = { "" }
        )

        assertTrue(result is SshKeyAuthenticator.Result.Rejected)
        assertEquals(
            listOf("id_ed25519_one" to "", "id_ed25519_two" to ""),
            script.seen
        )
    }

    @Test
    fun `a failure no credential can fix stops the loop`() {
        // An "anything else" failure is not a refusal: the server never said no to
        // this key. The old loop kept going — remaining keys, then the password
        // stage — so a host that accepted a key and then failed its own work (mosh
        // without mosh-server, say) drew a fresh connection and possibly a password
        // prompt for every credential still on the list, over a question that was
        // already answered. Other stops there, and carries its own detail out as
        // the result, rather than the last credential refusal masking it.
        val script = Attempts().apply {
            answer(SshKeyAuthenticator.Attempt.Other("mosh-server: command not found"))
        }
        val result = SshKeyAuthenticator.authenticateFlow(
            keys = listOf(key, second),
            wantsPassword = true,
            heldPassword = "held",
            attempt = script.run(),
            ask = { SshKeyAuthenticator.Prompt.NotNeeded },
            askPassword = { "the password question never ran" }
        )

        assertTrue(result is SshKeyAuthenticator.Result.Rejected)
        result as SshKeyAuthenticator.Result.Rejected
        assertEquals(1, result.tries)
        assertEquals("mosh-server: command not found", result.detail)
        // Neither the second key nor the password stage was ever offered.
        assertEquals(listOf("id_ed25519_one" to ""), script.seen)
    }

    @Test
    fun `an open key's refusal leaves the question for an encrypted key`() {
        // The first key is open: the file probe means no prompt can ever help it,
        // and its refusal must not count as a question the user has had. The
        // server refuses it and accepts the encrypted second key — which is
        // exactly the device shape an open key and a newer encrypted one make —
        // so the prompt has to arrive for the second key, not be spent on the
        // first.
        val encrypted = second
        val prompted = mutableListOf<String>()
        val script = Attempts().apply {
            // Both keys are offered bare and refused; the open key's refusal never
            // asks, so the encrypted key's bare attempt is what follows it.
            answerByKey { _, _ -> refused("offered bare") }
            answerByKey { _, _ -> refused("offered bare") }
            answerBySecret("real-phrase", SshKeyAuthenticator.Attempt.Ok("done"))
        }
        val result = SshKeyAuthenticator.authenticateFlow(
            keys = listOf(key, encrypted),
            wantsPassword = false,
            heldPassword = null,
            attempt = script.run(),
            ask = { asked ->
                prompted.add(asked.fileName)
                if (asked == key) {
                    SshKeyAuthenticator.Prompt.NotNeeded
                } else {
                    SshKeyAuthenticator.Prompt.Answered("real-phrase")
                }
            },
            askPassword = { "" }
        )

        assertTrue(result is SshKeyAuthenticator.Result.Connected)
        // The asker is consulted for both refusals — the probe that answers
        // "not needed" lives inside the asker — but the encrypted key's question is
        // actually answered and retried, which the old code denied it: `asked` was
        // already spent by the open key.
        assertEquals(listOf("id_ed25519_one", "id_ed25519_two"), prompted)
        assertEquals(
            listOf(
                "id_ed25519_one" to "",
                "id_ed25519_two" to "",
                "id_ed25519_two" to "real-phrase"
            ),
            script.seen
        )
    }

    @Test
    fun `a held phrase does not stop the question for an encrypted key`() {
        // A phrase sitting in memory is also a possible answer to "why did the key
        // refuse": treating a held value as proof that no question could help was
        // how one mistyped phrase silenced every asking until the process died.
        val script = Attempts().apply {
            answer(refused())
            answerBySecret("right-phrase", SshKeyAuthenticator.Attempt.Ok("done"))
        }
        val result = SshKeyAuthenticator.authenticateFlow(
            keys = listOf(key),
            wantsPassword = false,
            heldPassword = null,
            attempt = script.run(),
            ask = { SshKeyAuthenticator.Prompt.Answered("right-phrase") },
            askPassword = { "" }
        )

        assertTrue(result is SshKeyAuthenticator.Result.Connected)
        assertEquals(
            listOf("id_ed25519_one" to "", "id_ed25519_one" to "right-phrase"),
            script.seen
        )
    }

    @Test
    fun `a password is asked when the key is refused and the server takes one`() {
        var passwordQuestions = 0
        val script = Attempts().apply {
            answer(refused()) // the key, offered bare
            answer(refused("the key was refused with its phrase")) // the key, with its phrase
            answer(refused()) // the password, offered before anyone typed one
            answerBySecret("server-password", SshKeyAuthenticator.Attempt.Ok("done"))
        }
        val result = SshKeyAuthenticator.authenticateFlow(
            keys = listOf(key),
            wantsPassword = true,
            heldPassword = null,
            attempt = script.run(),
            ask = { SshKeyAuthenticator.Prompt.Answered("key-phrase") },
            askPassword = { passwordQuestions++; "server-password" }
        )

        assertTrue(result is SshKeyAuthenticator.Result.Connected)
        assertEquals(1, passwordQuestions)
        assertEquals(
            listOf(
                "id_ed25519_one" to "",
                "id_ed25519_one" to "key-phrase",
                "<password>" to "",
                "<password>" to "server-password"
            ),
            script.seen
        )
    }

    @Test
    fun `a held password is offered before any question`() {
        var passwordQuestions = 0
        val script = Attempts().apply {
            answerBySecret("held-password", SshKeyAuthenticator.Attempt.Ok("done"))
        }
        val result = SshKeyAuthenticator.authenticateFlow(
            keys = emptyList(),
            wantsPassword = true,
            heldPassword = "held-password",
            attempt = script.run(),
            ask = { SshKeyAuthenticator.Prompt.Answered("no keys exist, so nothing is asked about") },
            askPassword = { passwordQuestions++; "unused" }
        )

        assertTrue(result is SshKeyAuthenticator.Result.Connected)
        assertEquals(0, passwordQuestions)
        assertEquals(listOf("<password>" to "held-password"), script.seen)
    }

    @Test
    fun `nothing on the device to offer a server with is reported as no key`() {
        val result = SshKeyAuthenticator.authenticateFlow(
            keys = emptyList(),
            wantsPassword = false,
            heldPassword = null,
            attempt = { _, _ -> SshKeyAuthenticator.Attempt.Ok("never reached") },
            ask = { SshKeyAuthenticator.Prompt.Answered("never reached") },
            askPassword = { "never reached" }
        )

        assertTrue(result == SshKeyAuthenticator.Result.NoKey)
    }
}
