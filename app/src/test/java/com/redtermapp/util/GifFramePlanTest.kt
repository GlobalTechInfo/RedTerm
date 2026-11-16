package com.redtermapp.util

import org.junit.Assert.assertEquals
import org.junit.Test

class GifFramePlanTest {

    @Test
    fun `short recordings need no decimation`() {
        assertEquals(1, GifFramePlan.decimation(1, 240))
        assertEquals(1, GifFramePlan.decimation(240, 240))
    }

    @Test
    fun `long recordings spread over the frame cap`() {
        assertEquals(2, GifFramePlan.decimation(241, 240))
        assertEquals(2, GifFramePlan.decimation(480, 240))
        assertEquals(5, GifFramePlan.decimation(1000, 240))
        // the decimation never leaves more frames than the cap
        for (total in listOf(241, 1000, 9999, 60_000)) {
            val skip = GifFramePlan.decimation(total, 240)
            val frames = (total + skip - 1) / skip
            org.junit.Assert.assertTrue("frames $frames for $total", frames <= 240)
        }
    }

    @Test
    fun `nonsense input is one frame per chunk, not zero`() {
        assertEquals(1, GifFramePlan.decimation(0, 240))
        assertEquals(1, GifFramePlan.decimation(500, 0))
    }

    @Test
    fun `the delay is the real time the skipped chunks took`() {
        // one chunk per frame is the player's own tick: 12ms -> 1 centisecond
        assertEquals(1, GifFramePlan.delayCentiseconds(1))
        // ten chunks: 120ms -> 12 centiseconds
        assertEquals(12, GifFramePlan.delayCentiseconds(10))
        // a huge decimation still stays a valid GIF delay
        assertEquals(500, GifFramePlan.delayCentiseconds(10_000))
    }

    @Test
    fun `the delay never drops below a single centisecond`() {
        assertEquals(1, GifFramePlan.delayCentiseconds(1, tickMs = 3L))
    }
}
