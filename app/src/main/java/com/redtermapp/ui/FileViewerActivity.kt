package com.redtermapp.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.redtermapp.R
import java.io.File

class FileViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        private const val MAX_PREVIEW_BYTES = 2L * 1024L * 1024L
        private const val BINARY_SNIFF_BYTES = 8192
        private const val MAX_IMAGE_EDGE = 2048

        private val IMAGE_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "heic", "heif", "avif"
        )
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "webm", "mkv", "3gp", "ogv", "mov"
        )
    }

    private var loadedFile: File? = null
    private var editable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_viewer)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.viewer_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_back_chip)

        val path = intent?.getStringExtra(EXTRA_PATH)
        val file = path?.let { File(it) }
        if (file == null || !file.isFile || !file.canRead()) {
            supportActionBar?.title = getString(R.string.open_file_failed)
            findViewById<TextView>(R.id.viewer_content).text = getString(R.string.open_file_failed)
            finish()
            return
        }

        loadedFile = file
        supportActionBar?.title = file.name
        findViewById<TextView>(R.id.viewer_path).text = file.absolutePath
        loadPreview(file)
    }

    private fun setStatus(text: String) {
        findViewById<TextView>(R.id.viewer_status).apply {
            visibility = android.view.View.VISIBLE
            this.text = text
        }
    }

    private fun loadPreview(file: File) {
        val content = findViewById<TextView>(R.id.viewer_content)
        val status = findViewById<TextView>(R.id.viewer_status)
        Thread({
            val length = file.length()
            val readBytes = minOf(length, MAX_PREVIEW_BYTES)
            val bytes = try {
                file.inputStream().use { input ->
                    val buffer = ByteArray(readBytes.toInt())
                    var filled = 0
                    while (filled < buffer.size) {
                        val read = input.read(buffer, filled, buffer.size - filled)
                        if (read == -1) break
                        filled += read
                    }
                    buffer.copyOf(filled)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    content.text = "${e.message ?: getString(R.string.open_file_failed)}"
                }
                return@Thread
            }

            if (looksLikeImage(file, bytes)) {
                loadImage(file)
                return@Thread
            }
            if (isVideo(file)) {
                loadVideo(file)
                return@Thread
            }

            val binary = bytes.size > 0 &&
                bytes.copyOf(minOf(bytes.size, BINARY_SNIFF_BYTES))
                    .any { it == 0.toByte() }

            val text = if (binary) {
                runOnUiThread {
                    content.text = getString(R.string.file_is_binary, file.name)
                }
                return@Thread
            } else {
                String(bytes, Charsets.UTF_8)
            }

            runOnUiThread {
                if (binary) return@runOnUiThread
                val truncated = length > MAX_PREVIEW_BYTES
                content.text = if (truncated) {
                    getString(R.string.file_truncated, MAX_PREVIEW_BYTES / 1024 / 1024) + "\n\n" + text
                } else {
                    text
                }
                status.visibility = if (truncated) android.view.View.VISIBLE else android.view.View.GONE
                status.text = if (truncated) {
                    getString(R.string.file_size_and_preview, length, MAX_PREVIEW_BYTES)
                } else {
                    getString(R.string.file_size_format, length)
                }
                // The editor refuses anything it cannot round-trip, so only offer
                // it when the whole file is loaded and is really text.
                editable = !truncated
                invalidateOptionsMenu()
            }
        }, "redterm-file-viewer").apply { isDaemon = true }.start()
    }

    private fun looksLikeImage(file: File, head: ByteArray): Boolean {
        val ext = file.extension.lowercase()
        if (ext in IMAGE_EXTENSIONS) return true
        if (ext.isNotEmpty() && ext !in VIDEO_EXTENSIONS) return false
        // No usable extension: fall back to magic numbers.
        return when {
            head.size >= 8 && head[0] == 0x89.toByte() && head[1] == 0x50.toByte() &&
                head[2] == 0x4E.toByte() && head[3] == 0x47.toByte() -> true // PNG
            head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> true // JPEG
            head.size >= 6 && head.copyOfRange(0, 3).decodeToString() == "GIF" -> true
            head.size >= 2 && head[0] == 'B'.code.toByte() && head[1] == 'M'.code.toByte() -> true
            head.size >= 12 && head.copyOfRange(0, 4).decodeToString() == "RIFF" &&
                head.copyOfRange(8, 12).decodeToString() == "WEBP" -> true
            else -> false
        }
    }

    private fun isVideo(file: File): Boolean =
        file.extension.lowercase() in VIDEO_EXTENSIONS

    private fun loadImage(file: File) {
        val image = findViewById<ImageView>(R.id.viewer_image)
        val content = findViewById<TextView>(R.id.viewer_content)
        // Show a placeholder immediately so a slow or failed decode never looks
        // like an empty screen.
        content.text = getString(R.string.decoding_image)
        setStatus(getString(R.string.file_size_format, file.length()))
        Thread({
            val result = decodeScaled(file)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is DecodeResult.Ok -> {
                        findViewById<ScrollView>(R.id.viewer_scroll).visibility =
                            android.view.View.GONE
                        image.visibility = android.view.View.VISIBLE
                        image.setImageBitmap(result.bitmap)
                        setStatus(
                            getString(
                                R.string.image_preview_size,
                                result.width, result.height,
                                result.bitmap.byteCount / 1024
                            )
                        )
                    }
                    is DecodeResult.Failed -> {
                        content.visibility = android.view.View.VISIBLE
                        content.text = getString(R.string.image_preview_failed, result.reason)
                        setStatus(getString(R.string.file_size_format, file.length()))
                    }
                }
            }
        }, "redterm-image-preview").apply { isDaemon = true }.start()
    }

    private sealed interface DecodeResult {
        data class Ok(val bitmap: android.graphics.Bitmap, val width: Int, val height: Int) :
            DecodeResult

        data class Failed(val reason: String) : DecodeResult
    }

    /**
     * Decodes off the main thread and downsamples so a 50 MP photo cannot OOM the
     * heap. Cameras routinely produce images far larger than any screen, so
     * keeping the full-resolution bitmap around only wastes memory.
     */
    private fun decodeScaled(file: File): DecodeResult {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return DecodeResult.Failed(getString(R.string.not_a_readable_image))
        }
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            // ARGB_8888 rather than RGB_565: 565 drops the alpha channel, which
            // makes transparent PNG/WebP decode to solid black.
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val decoded = android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: return DecodeResult.Failed(getString(R.string.not_a_readable_image))
        val rotated = applyExifRotation(file, decoded)
        return DecodeResult.Ok(rotated, rotated.width, rotated.height)
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= MAX_IMAGE_EDGE) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun applyExifRotation(file: File, bitmap: android.graphics.Bitmap):
        android.graphics.Bitmap {
        val degrees = try {
            val exif = androidx.exifinterface.media.ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            )) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) {
            0f
        }
        if (degrees == 0f) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        return try {
            val rotated = android.graphics.Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (_: OutOfMemoryError) {
            bitmap
        }
    }

    private fun loadVideo(file: File) {
        val video = findViewById<VideoView>(R.id.viewer_video)
        val content = findViewById<TextView>(R.id.viewer_content)
        runOnUiThread {
            findViewById<ScrollView>(R.id.viewer_scroll).visibility = android.view.View.GONE
            video.visibility = android.view.View.VISIBLE
            setStatus(getString(R.string.video_preview_size, formatSize(file.length())))
            video.setVideoPath(file.absolutePath)
            video.setOnErrorListener { _, _, _ ->
                video.visibility = android.view.View.GONE
                content.visibility = android.view.View.VISIBLE
                content.text = getString(R.string.video_preview_failed, file.name)
                true
            }
            video.setOnPreparedListener { player ->
                player.isLooping = false
                content.visibility = android.view.View.GONE
                video.start()
            }
        }
    }

    private var editItem: MenuItem? = null

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val copy = menu.add(Menu.NONE, 1, 1, R.string.action_copy)
        copy.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        val edit = menu.add(Menu.NONE, 2, 2, R.string.action_edit)
        edit.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        editItem = edit
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        editItem?.isVisible = editable
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        if (item.itemId == 1) {
            val text = findViewById<TextView>(R.id.viewer_content).text.toString()
            if (text.isNotEmpty()) {
                val clip = getSystemService(android.content.ClipboardManager::class.java)
                clip.setPrimaryClip(android.content.ClipData.newPlainText(loadedFile?.name ?: "file", text))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            return true
        }
        if (item.itemId == 2) {
            val file = loadedFile ?: return true
            if (!file.canWrite()) {
                Toast.makeText(this, R.string.editor_read_only, Toast.LENGTH_LONG).show()
                return true
            }
            startActivity(
                android.content.Intent(this, TextEditorActivity::class.java)
                    .putExtra(TextEditorActivity.EXTRA_PATH, file.absolutePath)
            )
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1000 -> "$bytes B"
        bytes < 1_000_000 -> "${bytes / 1000} KB"
        bytes < 1_000_000_000 -> "${"%.1f".format(bytes / 1_000_000.0)} MB"
        else -> "${"%.2f".format(bytes / 1_000_000_000.0)} GB"
    }

    private fun applyTheme() {
        val prefs = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        when (NightModeReceiver.effectiveTheme(prefs)) {
            "red" -> setTheme(R.style.Theme_RedTermApp_Red)
            "amoled" -> setTheme(R.style.Theme_RedTermApp_AMOLED)
            "green" -> setTheme(R.style.Theme_RedTermApp_Green)
            "light" -> setTheme(R.style.Theme_RedTermApp_Light)
            "dracula" -> setTheme(R.style.Theme_RedTermApp_Dracula)
            "nord" -> setTheme(R.style.Theme_RedTermApp_Nord)
            "tokyo" -> setTheme(R.style.Theme_RedTermApp_Tokyo)
            "gruvbox" -> setTheme(R.style.Theme_RedTermApp_Gruvbox)
            "custom" -> setTheme(R.style.Theme_RedTermApp_Custom)
            else -> setTheme(R.style.Theme_RedTermApp)
        }
    }
}
