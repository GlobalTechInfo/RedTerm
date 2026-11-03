package com.redtermapp.util.api

import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON is what guest scripts parse, key for key with Termux:API's
 * battery-status — so a script written for either reads the same answer,
 * and a key disappearing here would break one of them.
 */
class BatteryApiTest {

    private fun info(
        level: Int = 87,
        scale: Int = 100,
        health: Int = BatteryManager.BATTERY_HEALTH_GOOD,
        plugged: Int = BatteryManager.BATTERY_PLUGGED_AC,
        temperature: Int = 310,
        voltage: Int = 4100,
        technology: String? = "Li-ion",
        status: Int = BatteryManager.BATTERY_STATUS_DISCHARGING,
        current: Int? = null,
        currentAverage: Int? = null
    ) = BatteryApi.BatteryInfo(level, scale, health, plugged, temperature, voltage, technology, status, current, currentAverage)

    @Test
    fun `the full reading carries every field in termux key order`() {
        assertEquals(
            """{"batteryLevel":87,"batteryScale":100,"batteryStatus":"DISCHARGING","batteryHealth":"GOOD","batteryPlugged":"PLUGGED_AC","batteryTemp":310,"batteryVoltage":4100,"batteryTechnology":"Li-ion"}""",
            BatteryApi.build(info())
        )
    }

    @Test
    fun `a zero voltage and a null technology are omitted, not zeroed`() {
        assertEquals(
            """{"batteryLevel":87,"batteryScale":100,"batteryStatus":"DISCHARGING","batteryHealth":"GOOD","batteryPlugged":"PLUGGED_AC","batteryTemp":310}""",
            BatteryApi.build(info(voltage = 0, technology = null))
        )
    }

    @Test
    fun `currents are reported when known, under the termux key names`() {
        assertEquals(
            """{"batteryLevel":87,"batteryScale":100,"batteryStatus":"DISCHARGING","batteryHealth":"GOOD","batteryPlugged":"PLUGGED_AC","batteryTemp":310,"batteryVoltage":4100,"current":-250,"current_average":-100,"batteryTechnology":"Li-ion"}""",
            BatteryApi.build(info(current = -250, currentAverage = -100))
        )
    }

    @Test
    fun `an unknown status health or plugged state is the number, not a made-up word`() {
        val s = BatteryApi.build(info(status = 99, plugged = 99, health = 99))
        assertTrue(s, s.contains("\"batteryStatus\":\"99\""))
        assertTrue(s, s.contains("\"batteryHealth\":\"99\""))
        assertTrue(s, s.contains("\"batteryPlugged\":\"99\""))
    }

    @Test
    fun `plugged states use the termux spellings`() {
        val cases = mapOf(
            0 to "UNPLUGGED",
            BatteryManager.BATTERY_PLUGGED_AC to "PLUGGED_AC",
            BatteryManager.BATTERY_PLUGGED_USB to "PLUGGED_USB",
            BatteryManager.BATTERY_PLUGGED_WIRELESS to "PLUGGED_WIRELESS",
            BatteryManager.BATTERY_PLUGGED_DOCK to "PLUGGED_DOCK"
        )
        for ((code, word) in cases) {
            val s = BatteryApi.build(info(plugged = code))
            assertTrue("$code should read $word: $s", s.contains("\"batteryPlugged\":\"$word\""))
        }
    }
}
