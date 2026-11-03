package com.redtermapp.util.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure parts of the volume and network features: which stream name the
 * guest can spell, the JSON shapes, and how an interface list renders.
 */
class SystemApisTest {

    @Test
    fun `stream names map to the documented ids`() {
        assertEquals(3, VolumeApi.streamId("music"))
        assertEquals("an unknown name falls back to music", 3, VolumeApi.streamId("nonsense"))
        assertEquals("a raw id passes through", 6, VolumeApi.streamId("6"))
    }

    @Test
    fun `the volume answer is flat json the guest can grep`() {
        assertEquals("{\"volume\":4,\"maxVolume\":15,\"stream\":3}", VolumeApi.build(3, 4, 15))
    }

    @Test
    fun `the interface list renders as one json array per interface`() {
        val json = NetworkApi.buildList(
            listOf(
                NetworkApi.IfaceInfo("wlan0", true, listOf("10.0.0.5", "fe80::1")),
                NetworkApi.IfaceInfo("eth0", true, listOf("192.168.1.7"))
            )
        )
        assertEquals(
            "[{\"name\":\"wlan0\",\"up\":true,\"addresses\":[\"10.0.0.5\",\"fe80::1\"]}," +
                "{\"name\":\"eth0\",\"up\":true,\"addresses\":[\"192.168.1.7\"]}]",
            json
        )
    }

    @Test
    fun `an empty interface list is the empty array, not a crash`() {
        assertEquals("[]", NetworkApi.buildList(emptyList()))
    }
}
