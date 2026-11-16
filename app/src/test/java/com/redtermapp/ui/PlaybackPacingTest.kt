package com.redtermapp.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPacingTest {

    @Test
    fun `one is the recording's own pace`() {
        assertEquals(4096, PlaybackPacing.chunkFor(1f, 4096))
    }

    @Test
    fun `faster is a wider read, slower a narrower one`() {
        assertEquals(2048, PlaybackPacing.chunkFor(0.5f, 4096))
        assertEquals(8192, PlaybackPacing.chunkFor(2f, 4096))
        assertEquals(16384, PlaybackPacing.chunkFor(4f, 4096))
    }

    @Test
    fun `no speed starves the feed to zero bytes`() {
        assertEquals(1, PlaybackPacing.chunkFor(0f, 4096))
    }
}
