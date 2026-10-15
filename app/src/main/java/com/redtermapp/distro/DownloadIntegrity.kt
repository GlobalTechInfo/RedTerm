package com.redtermapp.distro

/**
 * Deciding whether a downloaded image is the one the release says it is.
 *
 * This is deliberately a pure object with no Android in it, because the whole failure
 * mode of the code it replaced was that nobody could test it. The logic was inside a
 * `withContext(Dispatchers.IO)` block doing real downloads, so every branch was only
 * reachable by rebuilding a distribution image under the same tag and finding a device
 * with a stale APK.
 *
 * ## What it replaces, and why that was wrong
 *
 * The old order was: check against the hash this APK was compiled with, and on a
 * mismatch re-read the index and check again, and on a second mismatch refuse.
 *
 * Two things were wrong with that, independently of each other.
 *
 * **The first check could never be right.** The APK is built from the repository and
 * the index is what the release is serving *now*, so when they differ -- which is
 * exactly the case the retry exists for -- the index is by definition the newer of the
 * two. Checking against the APK first guarantees a mismatch, every time, for any user
 * whose release was rebuilt after the APK was compiled. It then gets un-mismatched, so
 * the whole first stage is a bug generator with a success path.
 *
 * **A resumed download splices two different files.** `downloadTarball` sends
 * `Range: bytes=N-` when a `.part` file exists. The asset URL never changes when a
 * release asset is replaced, so resuming onto bytes from a previous download of a
 * *rebuilt* asset produces a file that is neither version: a checksum mismatch that is
 * not corruption, that no amount of re-reading the index can fix, and that looks
 * identical to a genuinely corrupt transfer.
 *
 * So a mismatch gets exactly one retry, from an empty file, before anything is
 * concluded. That is the difference between "the connection broke" and "this release
 * is not what the index says", and the old code could not tell them apart.
 */
internal object DownloadIntegrity {

    enum class Verdict {
        /** The bytes are the ones the release describes. */
        ACCEPT,

        /** Differs, but only once, and a resumed download is the usual cause. */
        RETRY_FROM_SCRATCH,

        /** Still differs after a clean download: refuse, with both hashes. */
        REFUSE
    }

    /**
     * @param alreadyRetried true when this verdict is being asked about the second
     *   download, which started from nothing. There is no third attempt: a file that
     *   does not match after being fetched cleanly is not going to match because it is
     *   fetched again, and retrying forever is how a real mismatch turns into an
     *   install that appears to succeed.
     */
    fun verdict(expected: String, actual: String, alreadyRetried: Boolean): Verdict = when {
        expected.isEmpty() -> Verdict.REFUSE
        actual.equals(expected, ignoreCase = true) -> Verdict.ACCEPT
        alreadyRetried -> Verdict.REFUSE
        else -> Verdict.RETRY_FROM_SCRATCH
    }

    /**
     * The failure report.
     *
     * Both hashes in full, and the URL, because this is the only place the information
     * exists. The version that came before printed `got:` followed by nothing: it
     * deleted the downloaded file on the line above the message that hashed it, and
     * `sha256Of` returns an empty string for a file that is not there. So the one
     * number that would have said what actually arrived could never be shown, for any
     * distribution, and the report named a checksum mismatch while saying nothing at
     * all about what had been received.
     */
    fun report(name: String, abi: String, url: String, expected: String, actual: String): String =
        buildString {
            append("Checksum mismatch for $name ($abi).\n\n")
            append("expected: $expected\n")
            append("got:      $actual\n")
            append("url:      $url\n\n")
            append(
                "The image was downloaded twice from scratch and neither copy matches the " +
                    "checksum the release index publishes for it. That is either a release " +
                    "whose index and asset were published from different builds, or a " +
                    "transfer that is being altered in transit. Reporting the two hashes " +
                    "above is the only way to tell those apart.\n"
            )
        }
}