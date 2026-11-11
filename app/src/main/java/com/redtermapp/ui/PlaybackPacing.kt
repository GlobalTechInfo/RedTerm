package com.redtermapp.ui

/**
 * How fast a recording replays.
 *
 * The player's pacing is a fixed tick period; the speed only changes how many
 * bytes are fed per tick. That keeps the tick cheap and the speed switch
 * instant — no re-reading the file, just a wider or narrower read.
 */
object PlaybackPacing {

    /** The offered speeds, slowest first. One is the recording's own pace. */
    val SPEEDS = floatArrayOf(0.5f, 1f, 2f, 4f)

    /** The bytes fed per tick at [speed] for a base chunk of [baseChunk]. */
    fun chunkFor(speed: Float, baseChunk: Int): Int =
        (baseChunk * speed).toInt().coerceAtLeast(1)
}
