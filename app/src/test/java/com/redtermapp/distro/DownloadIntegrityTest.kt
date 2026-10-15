package com.redtermapp.distro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The checksum decision, tested without a download in sight.
 *
 * Every branch here used to be reachable only by rebuilding a release asset under the
 * same tag and installing on a device with an APK compiled before the rebuild. That is
 * why the logic was wrong for so long: it could not be exercised, so it was not
 * exercised.
 */
class DownloadIntegrityTest {

    private val shaA = "a".repeat(64)
    private val shaB = "b".repeat(64)

    @Test
    fun `matching checksums are accepted on the first download`() {
        assertEquals(
            DownloadIntegrity.Verdict.ACCEPT,
            DownloadIntegrity.verdict(shaA, shaA, alreadyRetried = false)
        )
    }

    @Test
    fun `a checksum match is case insensitive`() {
        assertEquals(
            DownloadIntegrity.Verdict.ACCEPT,
            DownloadIntegrity.verdict(shaA, shaA.uppercase(), alreadyRetried = false)
        )
    }

    /**
     * The resumed-download case.
     *
     * A mismatch on the first attempt is nearly always `Range:` bytes from an earlier
     * download spliced onto a rebuilt asset. It looks exactly like corruption and is
     * fixed by starting again from nothing.
     */
    @Test
    fun `a mismatch on the first download is retried from scratch`() {
        assertEquals(
            DownloadIntegrity.Verdict.RETRY_FROM_SCRATCH,
            DownloadIntegrity.verdict(shaA, shaB, alreadyRetried = false)
        )
    }

    /** After a clean second download, retrying again cannot change the answer. */
    @Test
    fun `a mismatch that survives a clean retry is refused`() {
        assertEquals(
            DownloadIntegrity.Verdict.REFUSE,
            DownloadIntegrity.verdict(shaA, shaB, alreadyRetried = true)
        )
    }

    /**
     * With nothing published for this architecture there is no way to verify anything,
     * so accepting the download would mean accepting any bytes at all.
     */
    @Test
    fun `no published checksum means the download cannot be verified`() {
        assertEquals(
            DownloadIntegrity.Verdict.REFUSE,
            DownloadIntegrity.verdict("", shaB, alreadyRetried = false)
        )
        assertEquals(
            DownloadIntegrity.Verdict.REFUSE,
            DownloadIntegrity.verdict("", shaB, alreadyRetried = true)
        )
    }

    /** An empty download does not match a real checksum, so it is retried once. */
    @Test
    fun `an empty file is retried once and then refused`() {
        assertEquals(
            DownloadIntegrity.Verdict.RETRY_FROM_SCRATCH,
            DownloadIntegrity.verdict(shaA, "", alreadyRetried = false)
        )
        assertEquals(
            DownloadIntegrity.Verdict.REFUSE,
            DownloadIntegrity.verdict(shaA, "", alreadyRetried = true)
        )
    }

    /**
     * The bug this whole file exists to prevent.
     *
     * The report used to be built after the downloaded file had been deleted, and the
     * hash was taken from that file, so `got:` was an empty string on every
     * distribution for as long as the bug was live -- the one number that said what
     * had actually arrived was never printed. If this test fails, the hashes are being
     * computed from something that has already been removed.
     */
    @Test
    fun `the report carries both hashes in full`() {
        val text = DownloadIntegrity.report(
            name = "arch",
            abi = "aarch64",
            url = "https://example.invalid/arch-aarch64.tar.xz",
            expected = shaA,
            actual = shaB
        )
        assertTrue("the expected hash is missing from the report:\n$text", text.contains(shaA))
        assertTrue("the actual hash is missing from the report:\n$text", text.contains(shaB))
        assertTrue("the URL is missing from the report:\n$text", text.contains("example.invalid"))
        assertTrue("the distribution is missing from the report:\n$text", text.contains("arch"))
        assertTrue("the architecture is missing from the report:\n$text", text.contains("aarch64"))
    }

    /** Truncation is what made the old report unreadable, so nothing here is cut. */
    @Test
    fun `the report never truncates a hash`() {
        val text = DownloadIntegrity.report("arch", "aarch64", "u", shaA, shaB)
        assertTrue("expected: was truncated to 16 characters", text.contains("expected: $shaA"))
        assertTrue("got: was truncated to 16 characters", text.contains("got:      $shaB"))
    }

    /**
     * A hash is 64 hex characters and must appear whole; a report that shortened them
     * cannot be compared against the index by hand, which is the entire reason to
     * print it.
     */
    @Test
    fun `hashes in the report are long enough to be worth printing`() {
        val text = DownloadIntegrity.report("d", "abi", "url", shaA, shaB)
        val printed = Regex("""[0-9a-f]{64}""").findAll(text).count()
        assertEquals("expected exactly the two hashes", 2, printed)
    }
}