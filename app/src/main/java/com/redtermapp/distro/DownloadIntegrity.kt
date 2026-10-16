package com.redtermapp.distro

/**
 * Deciding whether a downloaded image is the one the release is serving.
 *
 * A pure object with no Android and no network in it, because the whole failure mode
 * of the code it replaced was that nobody could test it: every branch was reachable
 * only by rebuilding a release asset under the same tag and installing on a device
 * with an APK compiled before the rebuild. That is not a test plan, it is a rumour.
 *
 * ## What counts as correct
 *
 * The bytes are correct when the release says so. In order of authority:
 *
 *  1. **The release itself**, asked after the download, for the sha256 of the asset it
 *     is holding right now. This is the answer, and when it matches, the question is
 *     closed. It cannot be stale in the way a published index can, because it is read
 *     at the moment of use.
 *  2. A checksum the app already had — the published index, or the table compiled into
 *     the APK — as a fast path that avoids the network entirely on the common case
 *     where nothing has been rebuilt.
 *
 * The index used to be the gate and the release was never consulted, which meant every
 * republish broke every install until the index was rebuilt too. That is the inversion
 * this fixes.
 *
 * ## Why a mismatch retries at all
 *
 * `downloadTarball` sends `Range: bytes=N-` onto an existing `.part` file. Release
 * assets are replaced under the same URL, so resuming onto bytes from an earlier
 * download of a *rebuilt* asset produces a file that is neither version: a checksum
 * mismatch that is not corruption, and that re-reading anything cannot fix.
 *
 * So one retry, from an empty file. But only once, and only after asking the release
 * what it actually holds -- because if the release says the file is correct and we
 * still say it is not, restarting a nearly-complete download is the single most
 * destructive thing this code can do to the user. That was reported as "the install
 * went to 99% and then started again", which is exactly what it looked like from the
 * outside.
 */
internal object DownloadIntegrity {

    enum class Verdict {
        /** The bytes are the ones the release is serving. */
        ACCEPT,

        /** Not those bytes, and not explained yet: start over, once. */
        RETRY_FROM_SCRATCH,

        /** Not those bytes, and the release does not claim them either. */
        REFUSE
    }

    /**
     * @param liveDigest what the release reports for this asset right now, or "" if
     *   that could not be determined. It outranks everything else.
     * @param alreadyRetried true when judging the second, clean download. There is no
     *   third attempt: a file that does not match after being fetched from nothing is
     *   not going to match because it is fetched again, and retrying for ever is how a
     *   real corruption turns into an install that appears to succeed.
     */
    fun verdict(
        expected: List<String>,
        actual: String,
        liveDigest: String,
        alreadyRetried: Boolean
    ): Verdict {
        // The release is the authority. If it agrees, the file is right even if every
        // hash the app was carrying was out of date, which is the common case after a
        // rebuild.
        if (liveDigest.isNotEmpty() && liveDigest.equals(actual, ignoreCase = true)) {
            return Verdict.ACCEPT
        }
        if (expected.any { it.isNotEmpty() && it.equals(actual, ignoreCase = true) }) {
            return Verdict.ACCEPT
        }
        // Nothing claims these bytes. Ask once more from nothing before refusing --
        // unless we already have, in which case restarting again only wastes the
        // user's data to arrive at the same answer.
        return if (alreadyRetried) Verdict.REFUSE else Verdict.RETRY_FROM_SCRATCH
    }

    /**
     * The failure report.
     *
     * Everything needed to tell the two remaining possibilities apart, and the
     * previous version of this could not: it deleted the downloaded file on the line
     * above the message that hashed it, so `got:` was an empty string for every
     * distribution, always. A report that cannot say what arrived is not a report.
     */
    fun report(
        name: String,
        abi: String,
        url: String,
        expected: List<String>,
        actual: String,
        liveDigest: String
    ): String = buildString {
        append("Checksum mismatch for $name ($abi).\n\n")
        append("url:      $url\n")
        append("downloaded: $actual\n")
        append("release says: ${liveDigest.ifEmpty { "no answer" }}\n")
        append("app expected: ${expected.filter { it.isNotEmpty() }.joinToString(", ")}\n\n")
        if (liveDigest.isEmpty()) {
            append(
                "The release did not say what it holds for this asset, and the bytes " +
                    "downloaded do not match any checksum this app knows. That is either " +
                    "a connection being altered in transit or a release whose index and " +
                    "asset disagree.\n"
            )
        } else {
            append(
                "The release reports a different checksum for this asset, so the file " +
                    "downloaded is not the one the release is serving. Nothing about " +
                    "this install can be trusted.\n"
            )
        }
    }
}