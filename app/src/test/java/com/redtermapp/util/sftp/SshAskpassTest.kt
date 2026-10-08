package com.redtermapp.util.sftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The askpass environment decides what passphrase `ssh` actually receives.
 *
 * A mistake here is silent and nasty: the phrase arrives subtly altered, ssh
 * rejects it, and the server appears to have refused a key it had already accepted.
 * So every case is checked by handing the environment to a real process and reading
 * the variable back there, rather than by comparing strings.
 */
class SshAskpassTest {

    private val helper = "/root/.askpass"

    /**
     * Starts a child with the given askpass environment and returns the phrase that
     * child actually sees.
     *
     * A child process is used on purpose, and so is the shell inside it. The helper
     * is a shell script that expands `$REDTERM_KEY_PASSPHRASE`, so what matters is
     * the value a real shell reads out of a real environment — inspecting the map in
     * this process would prove nothing about either the handoff or the expansion.
     */
    private fun roundTrip(phrase: String): String {
        val output = child(phrase, "printf '%s' \"\$REDTERM_KEY_PASSPHRASE\"")
        return output.ifEmpty { "<missing>" }
    }


    private fun child(phrase: String, script: String): String {
        val builder = ProcessBuilder("/bin/sh", "-c", script)
            .redirectErrorStream(true)
        builder.environment().putAll(SshAskpass.environmentForTest(helper, phrase))
        val process = builder.start()
        // Deliberately untrimmed: a phrase with trailing whitespace must come back
        // exactly as entered, and trimming here would hide a bug that ssh would not.
        return process.inputStream.readBytes().toString(Charsets.UTF_8)
    }

    @Test
    fun `a plain phrase survives`() {
        assertEquals("correcthorsebattery", roundTrip("correcthorsebattery"))
    }

    @Test
    fun `a phrase with spaces survives`() {
        assertEquals("two words here", roundTrip("two words here"))
    }

    /**
     * The case that would otherwise be read as a server refusal: a single quote
     * ends a POSIX single-quoted string, so the phrase would be truncated.
     */
    @Test
    fun `a phrase containing a single quote survives`() {
        assertEquals("it's mine", roundTrip("it's mine"))
    }

    @Test
    fun `a phrase containing a double quote survives`() {
        assertEquals("say \"hi\"", roundTrip("say \"hi\""))
    }

    /**
     * The case this mechanism exists to get right. A phrase pasted from a password
     * manager routinely contains `$`, a backtick or a newline, and any of them
     * expanded by a shell turns a correct key into a refusal.
     */
    @Test
    fun `a phrase containing shell metacharacters is not expanded`() {
        assertEquals("\$HOME `id` $(whoami)", roundTrip("\$HOME `id` \$(whoami)"))
    }

    @Test
    fun `a backslash survives`() {
        assertEquals("back\\slash", roundTrip("back\\slash"))
    }

    @Test
    fun `a phrase with a newline survives whole`() {
        assertEquals("line one\nline two", roundTrip("line one\nline two"))
    }

    /** A trailing newline must not be trimmed away by anything in the path. */
    @Test
    fun `a phrase with trailing whitespace is not trimmed`() {
        assertEquals("padded ", roundTrip("padded "))
    }

    @Test
    fun `the askpass path and force flag reach the process as separate variables`() {
        val output = child("x", "env")
        // An earlier version built these as one string and they ran together, so ssh
        // would have looked for a helper at a path ending in "SSH_ASKPASS_REQUIRE=force".
        assertTrue(output, output.lineSequence().any { it == "SSH_ASKPASS=$helper" })
        assertTrue(output, output.lineSequence().any { it == "SSH_ASKPASS_REQUIRE=force" })
    }

    /**
     * Nothing at all for a key with no passphrase, so `ssh` keeps exactly the
     * behaviour it had before: BatchMode, no askpass, no environment.
     */
    @Test
    fun `no passphrase means no environment`() {
        assertTrue(SshAskpass.environmentForTest(helper, "").isEmpty())
        assertTrue(SshAskpass.environmentForTest(helper, null).isEmpty())
    }

    /** The phrase is cached per key and never written to preferences. */
    @Test
    fun `a cached phrase is returned for the same key and not for another`() {
        SshAskpass.remember("id_ed25519_work", "phrase-one")
        assertEquals("phrase-one", SshAskpass.cached("id_ed25519_work"))
        assertEquals(null, SshAskpass.cached("id_ed25519_personal"))
        SshAskpass.forget("id_ed25519_work")
        assertEquals(null, SshAskpass.cached("id_ed25519_work"))
    }

    /**
     * The helper has to print the phrase and nothing else: ssh reads its standard
     * output as the answer, so a stray character is a wrong passphrase.
     */
    /**
     * The helper's own output is the phrase ssh reads, so this runs the script that
     * is actually shipped rather than a restatement of it.
     */
    @Test
    fun `the helper prints the phrase and nothing else`() {
        assertEquals("hunter2", child("hunter2", SshAskpass.helperScriptForTest()))
    }

    /** A newline in the phrase must not truncate it the way `readLine` would. */
    @Test
    fun `the helper prints a multi-line phrase whole`() {
        assertEquals("one\ntwo", child("one\ntwo", SshAskpass.helperScriptForTest()))
    }
}