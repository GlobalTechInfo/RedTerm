package com.redtermapp.util.api

import android.content.Context
import android.media.MediaPlayer
import com.redtermapp.util.api.ApiRequestParser.ApiRequest

/**
 * `termux-media-player`: a single process-wide [MediaPlayer]. `play`
 * loads and starts a local file, `resume`/`pause`/`stop`/`info` act on
 * the current one. Playback outlives the request that started it (the
 * engine is held in a companion), so a later `stop` or another `play`
 * replaces it.
 */
object MediaPlaybackApi : ApiFeature {

    override val name = "media-player"
    override val permissions = emptySet<String>()

    @Volatile private var player: MediaPlayer? = null

    override fun run(context: Context, request: ApiRequest): String {
        val op = request.str("op") ?: "info"
        return when (op) {
            "play" -> {
                val guestFile = request.str("file") ?: return "ERROR: play needs a file\n"
                val file = ApiPaths.hostPath(request, guestFile)
                    ?: return ApiPaths.unreachable(guestFile)
                val p = MediaPlayer().apply {
                    setDataSource(context, android.net.Uri.fromFile(java.io.File(file)))
                    prepare()
                    start()
                }
                player = p
                "PLAYING ${p.currentPosition}\n"
            }
            "resume" -> {
                val p = player ?: return "ERROR: nothing is playing\n"
                if (p.isPlaying) return "ALREADY PLAYING\n"
                p.start()
                "RESUMED\n"
            }
            "pause" -> {
                val p = player ?: return "ERROR: nothing is playing\n"
                p.pause()
                "PAUSED ${p.currentPosition}\n"
            }
            "stop" -> {
                val p = player
                if (p != null) {
                    p.stop()
                    p.release()
                    player = null
                }
                "STOPPED\n"
            }
            "info" -> {
                val p = player ?: return "NOT_PLAYING\n"
                val pos = p.currentPosition
                "PLAYING position=$pos isPlaying=${p.isPlaying}\n"
            }
            else -> "ERROR: unknown media-player op '$op'\n"
        }
    }
}
