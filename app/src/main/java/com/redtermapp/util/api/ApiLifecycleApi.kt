package com.redtermapp.util.api

import android.content.Context
import com.redtermapp.util.api.ApiRequestParser.ApiRequest

/**
 * `termux-api-start` / `termux-api-stop`: bring the bridge's socket
 * listener up or down. The listener is the whole bridge, so "start"
 * reports that it is already running when it is, and "stop" says what
 * will no longer reach it. Both are honest about the listener's actual
 * state rather than pretending.
 */
object ApiLifecycleApi : ApiFeature {

    override val name = "api-start"
    override val permissions = emptySet<String>()

    override fun run(context: Context, request: ApiRequest): String {
        val op = request.str("op") ?: "start"
        return when (op) {
            "start" -> {
                if (ApiSocket.isRunning()) "already running\n" else {
                    ApiSocket.start(context)
                    if (ApiSocket.isRunning()) "started\n" else "not started (this device's Android is below the bridge's floor)\n"
                }
            }
            "stop" -> {
                if (!ApiSocket.isRunning()) "not running\n" else {
                    ApiSocket.stop()
                    "stopped\n"
                }
            }
            "status" -> if (ApiSocket.isRunning()) "running\n" else "stopped\n"
            else -> "ERROR: unknown lifecycle op '$op'\n"
        }
    }
}
