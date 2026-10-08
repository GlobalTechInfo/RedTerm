package com.redtermapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the pure parts of the key registry: label-to-filename mapping and
 * filename de-duplication.
 *
 * The persistence itself needs a Context and touches `~/.ssh`, so it is covered
 * by hand on device rather than here. What must not regress is the naming, since
 * a collision makes ssh-keygen refuse to write and the user is left with an error
 * they cannot act on.
 */
class SshKeyStoreTest {

    @Test
    fun `a plain label produces a readable filename`() {
        assertEquals("id_ed25519_work", SshKeyStore.fileNameFor("work", "ed25519", emptySet()))
    }

    @Test
    fun `characters that are unsafe in a path are replaced`() {
        assertEquals(
            "id_ed25519_work_laptop_2",
            SshKeyStore.fileNameFor("Work laptop 2", "ed25519", emptySet())
        )
    }

    @Test
    fun `a label with no usable characters still yields a usable name`() {
        assertEquals("id_ed25519_key", SshKeyStore.fileNameFor("!!!", "ed25519", emptySet()))
        assertEquals("id_ed25519_key", SshKeyStore.fileNameFor("   ", "ed25519", emptySet()))
    }

    @Test
    fun `running punctuation is collapsed instead of repeated`() {
        assertEquals("id_ed25519_a_b", SshKeyStore.fileNameFor("a...b", "ed25519", emptySet()))
    }

    /**
     * The important one: two keys may share a label, and neither may overwrite
     * the other.
     */
    @Test
    fun `a taken filename is given a suffix rather than reused`() {
        val first = SshKeyStore.fileNameFor("work", "ed25519", emptySet())
        val second = SshKeyStore.fileNameFor("work", "ed25519", setOf(first))
        assertEquals("id_ed25519_work_2", second)
        val third = SshKeyStore.fileNameFor("work", "ed25519", setOf(first, second))
        assertNotEquals(first, third)
        assertNotEquals(second, third)
    }

    @Test
    fun `different key types get different names for the same label`() {
        val names = SshKeyStore.KEY_TYPES.map { SshKeyStore.fileNameFor("work", it, emptySet()) }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `a long label is truncated so the filename stays short`() {
        val name = SshKeyStore.fileNameFor("a".repeat(200), "ed25519", emptySet())
        assertTrue("filename too long: $name", name.length <= 48)
    }

    @Test
    fun `an adopted key gets a label derived from its filename`() {
        assertEquals("Work", SshKeyStore.labelFromFileName("id_ed25519_work"))
        assertEquals("Ed25519", SshKeyStore.labelFromFileName("id_ed25519"))
        assertEquals("Rsa", SshKeyStore.labelFromFileName("id_rsa"))
    }

    @Test
    fun `rsa entries report their size and pass it to keygen`() {
        val entry = SshKeyStore.Entry("k", "work", "id_rsa_work", SshKeyStore.TYPE_RSA, 4096)
        assertEquals("rsa-4096", entry.typeLabel)
        assertEquals(listOf("-t", "rsa", "-b", "4096"), entry.typeArguments())
    }

    @Test
    fun `ed25519 entries carry no size`() {
        val entry = SshKeyStore.Entry("k", "work", "id_ed25519_work", SshKeyStore.TYPE_ED25519)
        assertEquals("ed25519", entry.typeLabel)
        assertEquals(listOf("-t", "ed25519"), entry.typeArguments())
        assertFalse(entry.isRsa)
    }

    /**
     * Every field has to survive being written and read back.
     *
     * Written because the `encrypted` flag was once readable but not writable, so
     * every passphrase-protected key came back looking unprotected and the app
     * stopped asking for its passphrase. No error, no log line — only a key the
     * server accepted being reported as refused, which is a miserable thing to
     * debug from a phone. This test is the only reason that cannot recur.
     */
    @Test
    fun `every field survives a round trip through storage`() {
        val entry = SshKeyStore.Entry(
            id = "id-1",
            label = "Oracle",
            fileName = "id_ed25519_oracle",
            keyType = "ed25519",
            bits = 256,
            comment = "redterm@phone",
            createdAt = 1_700_000_000_000L,
            encrypted = true
        )
        val decoded = SshKeyStore.decode(SshKeyStore.encode(listOf(entry)))
        assertEquals(1, decoded.size)
        assertEquals(entry, decoded.first())
    }

    @Test
    fun `a protected key is still protected after a round trip`() {
        val protected = SshKeyStore.Entry(
            id = "id-1", label = "Oracle", fileName = "id_ed25519_oracle",
            keyType = "ed25519", bits = 256, encrypted = true
        )
        val open = protected.copy(id = "id-2", fileName = "id_ed25519_work", encrypted = false)
        val decoded = SshKeyStore.decode(SshKeyStore.encode(listOf(protected, open)))
        assertTrue(decoded[0].encrypted)
        assertFalse(decoded[1].encrypted)
    }

    /**
     * A record written before the field existed has to load as unprotected rather
     * than throw, and its other fields must be intact.
     */
    @Test
    fun `a record without the encrypted field loads as unprotected`() {
        val legacy = org.json.JSONArray().put(
            org.json.JSONObject().apply {
                put("id", "old")
                put("label", "Legacy")
                put("fileName", "id_rsa_legacy")
                put("keyType", "rsa")
                put("bits", 4096)
                put("comment", "redterm@phone")
                put("createdAt", 1_600_000_000_000L)
            }
        ).toString()
        val decoded = SshKeyStore.decode(legacy)
        assertEquals(1, decoded.size)
        assertFalse(decoded.first().encrypted)
        assertEquals("Legacy", decoded.first().label)
        assertEquals(4096, decoded.first().bits)
    }

    /** Corrupt storage must not take the key list down with it. */
    @Test
    fun `unreadable storage yields no keys instead of throwing`() {
        assertTrue(SshKeyStore.decode("not json at all").isEmpty())
    }


    private fun key(label: String, createdAt: Long, id: String = label) =
        SshKeyStore.Entry(
            id = id, label = label, fileName = "id_ed25519_$label",
            keyType = "ed25519", bits = 256, createdAt = createdAt
        )

    /**
     * Store order is creation order, so taking the list as it comes means the oldest
     * key on the device is always tried first and a key made for a server being added
     * right now is never offered at all.
     */
    @Test
    fun `unbound candidates are tried newest first`() {
        val oldest = key("oldest", 1_000L)
        val middle = key("middle", 2_000L)
        val newest = key("newest", 3_000L)
        val ordered = SshKeyStore.orderCandidates(listOf(oldest, middle, newest), null)
        assertEquals(listOf(newest, middle, oldest), ordered)
    }

    /** A bound key is an explicit choice, so nothing else is offered. */
    @Test
    fun `a bound key is the only candidate`() {
        val oldest = key("oldest", 1_000L)
        val chosen = key("chosen", 2_000L)
        assertEquals(
            listOf(chosen),
            SshKeyStore.orderCandidates(listOf(oldest, chosen), chosen)
        )
    }

    /**
     * A bound key whose file has gone must not silently promote some other key: the
     * server said which key it wants, and offering a different one is not that.
     */
    @Test
    fun `a bound key that is no longer present yields nothing`() {
        val present = key("present", 1_000L)
        val gone = key("gone", 2_000L)
        assertTrue(SshKeyStore.orderCandidates(listOf(present), gone).isEmpty())
    }

    /**
     * Every key stays a candidate whatever its passphrase. Narrowing the list to one
     * key when another is encrypted makes the encrypted key decide which keys every
     * server on the device can use, and it is very often the wrong one.
     */
    @Test
    fun `an encrypted key does not remove the others`() {
        val encrypted = key("protected", 1_000L).copy(encrypted = true)
        val open = key("open", 2_000L)
        val ordered = SshKeyStore.orderCandidates(listOf(encrypted, open), null)
        assertEquals(listOf(open, encrypted), ordered)
    }

    @Test
    fun `no keys means no candidates`() {
        assertTrue(SshKeyStore.orderCandidates(emptyList(), null).isEmpty())
    }

    /** Two keys made in the same millisecond must not depend on list order. */
    @Test
    fun `keys created at the same moment keep a stable order`() {
        val first = key("a", 5_000L)
        val second = key("b", 5_000L)
        assertEquals(
            SshKeyStore.orderCandidates(listOf(first, second), null),
            SshKeyStore.orderCandidates(listOf(second, first), null)
        )
    }

}
