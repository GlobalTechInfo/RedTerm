package com.redtermapp.util.api

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.redtermapp.util.api.ApiRequestParser.ApiRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `termux-sensor`: `list` names the sensors the device has; `read`
 * samples one of them a bounded number of times and returns those
 * samples. The reference streams until interrupted, which a one-shot
 * bridge cannot model — but a bounded read is what a shell actually
 * wants, and the count is the caller's.
 */
object SensorApi : ApiFeature {

    override val name = "sensor"
    override val permissions = emptySet<String>()

    override fun run(context: Context, request: ApiRequest): String {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return when (val op = request.str("op") ?: "list") {
            "list" -> list(manager)
            "read" -> read(manager, request)
            else -> "ERROR: unknown sensor op '$op'\n"
        }
    }

    private fun list(manager: SensorManager): String {
        val sb = StringBuilder("[")
        for ((i, s) in manager.getSensorList(Sensor.TYPE_ALL).withIndex()) {
            if (i > 0) sb.append(",")
            sb.append('{')
                .append("\"name\":").append(json(s.name ?: ""))
                .append(",\"type\":").append(s.type)
                .append(",\"vendor\":").append(json(s.vendor ?: ""))
                .append(",\"resolution\":").append(s.resolution)
                .append('}')
        }
        sb.append(']')
        return sb.toString() + "\n"
    }

    private fun read(manager: SensorManager, request: ApiRequest): String {
        val wanted = request.str("sensor") ?: return "ERROR: read needs a sensor name\n"
        val limit = (request.int("limit") ?: 1).coerceIn(1, 100)
        val delayUs = ((request.int("delay") ?: 200) * 1000).coerceAtLeast(1000)
        val sensor = manager.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.name == wanted }
            ?: return "ERROR: no sensor named '$wanted'\n"
        val samples = ArrayList<FloatArray>()
        val latch = CountDownLatch(limit)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (samples.size < limit) {
                    samples.add(event.values.copyOf())
                    latch.countDown()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, delayUs)
        // Wait no longer than a bounded window even if the sensor is
        // silent, so a request thread can never be held forever.
        latch.await((limit * 500L).coerceAtLeast(2000L), TimeUnit.MILLISECONDS)
        manager.unregisterListener(listener)
        val sb = StringBuilder("[")
        for ((i, values) in samples.withIndex()) {
            if (i > 0) sb.append(",")
            sb.append('[').append(values.joinToString(",")).append(']')
        }
        sb.append(']')
        return sb.toString() + "\n"
    }

    private fun json(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
