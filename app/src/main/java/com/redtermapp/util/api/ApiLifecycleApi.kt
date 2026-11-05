package com.redtermapp.util.api

import android.content.Context
import com.redtermapp.util.AppLog
import com.redtermapp.util.api.ApiRequestParser.ApiRequest

/**
 * `termux-api-start` / `termux-api-stop`: enable or disable the bridge.
 * Stopping never closes the listener — the guest reaches the app only
 * through this socket, so a stopped bridge stays reachable and answers
 * every feature request with "stopped" until `start` re-enables it. Both
 * ops report the bridge's real state rather than pretending.
 */
object ApiLifecycleApi : ApiFeature {

    override val name = "api-start"
    override val permissions = emptySet<String>()

    override fun run(context: Context, request: ApiRequest): String {
        val app = context.applicationContext
        val op = request.str("op") ?: "start"
        return when (op) {
            "start" -> {
                val was = ApiSocket.isEnabled()
                val up = ApiSocket.start(app)
                when {
                    !up -> "not started (this device's Android is below the bridge's floor)\n"
                    was -> "already running\n"
                    else -> {
                        AppLog.i(app, "api-socket", "API bridge enabled")
                        "started\n"
                    }
                }
            }
            "stop" -> {
                if (!ApiSocket.isEnabled()) "not running\n"
                else {
                    ApiSocket.stop()
                    AppLog.i(app, "api-socket", "API bridge stopped; redterm-api-start re-enables it")
                    "stopped\n"
                }
            }
            "status" -> if (ApiSocket.isEnabled()) "running\n" else "stopped\n"
            else -> "ERROR: unknown lifecycle op '$op'\n"
        }
    }
}
