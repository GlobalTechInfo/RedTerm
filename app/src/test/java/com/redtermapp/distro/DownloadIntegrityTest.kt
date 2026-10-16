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
    private val shaC = "c".repeat(64)

    @Test
    fun `matching checksums are accepted on the first download`() {
        assertEquals(
            DownloadIntegrity.Verdict.ACCEPT,
            DownloadIntegrity.verdict(listOf(shaA), shaA, liveDigest = "", alreadyRetried = false)
        )
    }

    @Test
    fun `a checksum match is case insensitive`() {
        assertEquals(
            DownloadIntegrity.Verdict.ACCEPT,
            DownloadIntegrity.verdict(listOf(shaA), shaA.uppercase(), "", false)
        )
    }

    /**
     * The whole point of the change.
     *
     * The release is asked what it holds, after the download. If it agrees, the file
     * is correct -- even though every hash the app was carrying was out of date. This
     * is the case that made every install fail after an image was rebuilt under the
     * same URL, because the app trusted a separately published index that the rebuild
     * had already made wrong.
     */
    @Test
    fun `the release overrides a stale index`() {
        assertEquals(
            DownloadIntegrity.Verdict.ACCEPT,
            DownloadIntegrity.verdict(listOf(shaA), shaB, liveDigest = shaB, alreadyRetried = false)
        )
    }

    /** The release must never be able to talk us out of a file it does not claim. */
    @Test
    fun `the release agreeing with something else is not enough`() {
        assertEquals(
            DownloadIntegrity.Verdict.RETRY_FROM_SCRATCH,
            DownloadIntegrity.verdict(listOf(shaA), shaB, liveDigest = shaA, alreadyRetried = false)
        )
    }

    /** Any known checksum is enough; a catalogue may carry more than one source. */
    @Test
    fun `a match against any known checksum is accepted`() {
        assertEquals(
            DownloadIntegrity.Verdict.ACCEPT,
            DownloadIntegrity.verdict(listOf("", shaA, shaB), shaB, "", false)
        )
    }

    /** A resumed download can splice two versions together, so one clean retry. */
    @Test
    fun `a mismatch on the first download is retried from scratch`() {
        assertEquals(
            DownloadIntegrity.Verdict.RETRY_FROM_SCRATCH,
            DownloadIntegrity.verdict(listOf(shaA), shaB, liveDigest = "", alreadyRetried = false)
        )
    }

    /** After a clean download, restarting again only burns the user's data. */
    @Test
    fun `a mismatch that survives a clean retry is refused`() {
        assertEquals(
            DownloadIntegrity.Verdict.REFUSE,
            DownloadIntegrity.verdict(listOf(shaA), shaB, liveDigest = "", alreadyRetried = true)
        )
    }

    /**
     * No checksum anywhere means nothing was verified, so accepting would mean
     * accepting whatever arrived.
     */
    @Test
    fun `an unverifiable download is refused`() {
        assertEquals(
            DownloadIntegrity.Verdict.REFUSE,
            DownloadIntegrity.verdict(emptyList(), shaB, liveDigest = "", alreadyRetried = true)
        )
    }

    /** An empty file matches nothing and is retried once, then refused. */
    @Test
    fun `an empty file is retried once and then refused`() {
        assertEquals(
            DownloadIntegrity.Verdict.RETRY_FROM_SCRATCH,
            DownloadIntegrity.verdict(listOf(shaA), "", liveDigest = "", alreadyRetried = false)
        )
        assertEquals(
            DownloadIntegrity.Verdict.REFUSE,
            DownloadIntegrity.verdict(listOf(shaA), "", liveDigest = "", alreadyRetried = true)
        )
    }

    /**
     * The report must say what arrived and what the release claims, because those are
     * the only two facts that separate a bad transfer from a bad release.
     *
     * It used to be impossible to print this at all: the file was deleted on the line
     * above the message that hashed it, so `got:` was an empty string on every
     * distribution, always.
     */
    @Test
    fun `the report carries both sides of the disagreement`() {
        val text = DownloadIntegrity.report(
            name = "arch", abi = "aarch64", url = "https://example.invalid/arch.tar.xz",
            expected = listOf(shaA), actual = shaB, liveDigest = shaC
        )
        assertTrue("what arrived is missing:\n$text", text.contains(shaB))
        assertTrue("what the release claims is missing:\n$text", text.contains(shaC))
        assertTrue("the URL is missing:\n$text", text.contains("example.invalid"))
    }

    /** When the release could not be asked, the report has to admit that. */
    @Test
    fun `the report says so when the release could not be reached`() {
        val text = DownloadIntegrity.report(
            name = "arch", abi = "aarch64", url = "u", expected = listOf(shaA),
            actual = shaB, liveDigest = ""
        )
        assertTrue("an absent release answer is reported as one:\n$text", text.contains("no answer"))
        assertTrue("what arrived is still missing:\n$text", text.contains(shaB))
    }
}
