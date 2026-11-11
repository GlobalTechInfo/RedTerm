package com.redtermapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import com.redtermapp.util.GifFramePlan
import com.redtermapp.util.GifWriter
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalRenderer
import java.io.File

/**
 * Exports a recorded ANSI session to a looping GIF a phone can show anywhere.
 *
 * The recording is fed through a private [TerminalEmulator] exactly as the
 * player does — no session, no process, no stty — and every [GifFramePlan.
 * decimation]-th paced chunk is drawn with the library's [TerminalRenderer]
 * onto a bitmap, quantised into the recording's own colours and written as
 * one LZW-compressed frame. The GIF's delay is the real time the skipped
 * chunks took, so it plays at the session's own pace.
 *
 * Runs on a worker thread: it reads the file, allocates a bitmap and does the
 * LZW. Nothing here touches a view.
 */
object TerminalGif {

    /** A terminal is a small grid; 80x24 is the size any viewer is used to. */
    private const val COLS = 80
    private const val ROWS = 24
    private const val FONT_SIZE = 20
    private const val MAX_FRAMES = 240

    class Export(val ok: Boolean, val frames: Int, val file: File?, val detail: String?)

    fun export(recording: File, outGif: File, fg: Int, bg: Int, typeface: Typeface): Export {
        if (!recording.isFile) {
            return Export(false, 0, null, "the recording is gone")
        }
        val renderer = TerminalRenderer(FONT_SIZE, typeface)
        val fontWidth = renderer.getFontWidth().toInt().coerceAtLeast(1)
        val lineHeight = renderer.getFontLineSpacing().toInt().coerceAtLeast(1)

        val emulator = TerminalEmulator(
            TerminalTheming.headlessOutput,
            COLS, ROWS, fontWidth, lineHeight, null,
            TerminalTheming.headlessClient
        )
        val totalChunks = ((recording.length() + GifFramePlan.CHUNK_SIZE - 1L)
            / GifFramePlan.CHUNK_SIZE).toInt().coerceAtLeast(1)
        val skip = GifFramePlan.decimation(totalChunks, MAX_FRAMES)
        val delay = GifFramePlan.delayCentiseconds(skip)

        val width = COLS * fontWidth
        val height = ROWS * lineHeight
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val pixels = IntArray(width * height)
        val palette = GifWriter.palette(bg, fg, GifWriter.ANSI16)
        val indices = IntArray(pixels.size)

        var frames = 0
        outGif.outputStream().use { out ->
            val gif = GifWriter(out)
            gif.start(width, height, palette)
            recording.inputStream().use { input ->
                val buffer = ByteArray(GifFramePlan.CHUNK_SIZE)
                var chunk = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    emulator.append(buffer, read)
                    chunk++
                    if (chunk % skip == 0) {
                        canvas.drawColor(bg)
                        renderer.render(emulator, canvas, 0, -1, -1, 0, 0)
                        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                        for (i in pixels.indices) {
                            indices[i] = GifWriter.nearestIndex(pixels[i], palette)
                        }
                        gif.frame(indices, delay)
                        frames++
                    }
                }
            }
            gif.finish()
        }
        if (frames == 0) outGif.delete()
        return Export(frames > 0, frames, if (frames > 0) outGif else null,
            if (frames > 0) null else "nothing was drawn from the recording")
    }
}
