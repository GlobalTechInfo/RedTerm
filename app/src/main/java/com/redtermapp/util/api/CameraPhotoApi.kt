package com.redtermapp.util.api

import android.Manifest
import android.content.Context
import com.redtermapp.util.api.ApiRequestParser.ApiRequest
import com.redtermapp.util.api.ui.ApiCameraActivity
import com.redtermapp.util.api.ui.ApiUiBridge

/**
 * `termux-camera-photo`: take a still and write it to the caller's file.
 * A capture session needs a moment to open, so the request blocks on a
 * transparent activity that carries the Camera2 session and answers when
 * the JPEG has been written.
 */
object CameraPhotoApi : ApiFeature {

    override val name = "camera-photo"
    override val permissions = setOf(Manifest.permission.CAMERA)

    override fun run(context: Context, request: ApiRequest): String {
        val file = request.str("file") ?: return "ERROR: camera-photo needs an output path\n"
        val camera = request.str("camera") ?: "0"
        return ApiUiBridge.show(
            context,
            ApiCameraActivity::class.java,
            ApiUiBridge.ApiUiSpec(
                "camera",
                20,
                mapOf(
                    "redterm.api.camera" to camera,
                    "redterm.api.file" to file,
                )
            )
        )
    }
}
