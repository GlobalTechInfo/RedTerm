package com.redtermapp.util

/**
 * The frame layout of a recording's GIF export.
 *
 * A recording is fed to the terminal library in chunks at a fixed pace, the
 * way the player paces it, and one GIF frame is drawn per [decimation]
 * chunks. The decimation keeps a long recording under the frame cap, and the
 * delay is the real time those skipped chunks took — a GIF that plays
 * faster than the session ran is a misrecording of it.
 *
 * Pure, so the layout is unit-tested without a display.
 */
object GifFramePlan {

    /** The player's feed: how many bytes per tick, and the tick's duration. */
    const val CHUNK_SIZE = 4096
    const val PACE_MS = 12L

    /** How many chunks one GIF frame covers for a recording of [totalChunks]. */
    fun decimation(totalChunks: Int, maxFrames: Int): Int {
        if (totalChunks <= 0 || maxFrames <= 0) return 1
        return ((totalChunks + maxFrames - 1) / maxFrames).coerceAtLeast(1)
    }

    /** The frame delay in centiseconds: the real time the decimated chunks took. */
    fun delayCentiseconds(decimation: Int, tickMs: Long = PACE_MS): Int =
        (decimation.toLong() * tickMs / 10L).toInt().coerceIn(1, 500)
}
