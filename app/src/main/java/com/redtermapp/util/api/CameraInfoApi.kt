package com.redtermapp.util.api

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.redtermapp.util.api.ApiRequestParser.ApiRequest

/**
 * `termux-camera-info`: one JSON object per camera, with the fields the
 * other termux camera scripts key off. No permission needed: reading
 * camera characteristics does not open the camera.
 */
object CameraInfoApi : ApiFeature {

    override val name = "camera-info"
    override val permissions = emptySet<String>()

    override fun run(context: Context, request: ApiRequest): String {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = manager.cameraIdList
        val sb = StringBuilder("[")
        for ((i, id) in ids.withIndex()) {
            val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
            if (c == null) continue
            if (i > 0) sb.append(",")
            sb.append('{')
            sb.append("\"id\":\"").append(id).append('"')
            sb.append(",\"facing\":").append(facing(c))
            sb.append(",\"hasFlash\":").append(
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            )
            sb.append(",\"hasSensor\":").append(
                c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) != null
            )
            sb.append('}')
        }
        sb.append(']')
        return sb.toString() + "\n"
    }

    private fun facing(c: CameraCharacteristics): String =
        when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> "\"front\""
            CameraCharacteristics.LENS_FACING_BACK -> "\"back\""
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "\"external\""
            else -> "null"
        }
}
