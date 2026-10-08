package com.redtermapp.ui

import android.os.Bundle
import com.redtermapp.distro.DistroInstaller
import com.redtermapp.ui.filelist.BaseFileBrowserActivity
import com.redtermapp.ui.filelist.FileSource
import com.redtermapp.ui.filelist.LocalDistroSource
import java.io.File

/**
 * Browses an installed distribution's rootfs on the device.
 *
 * All of the behaviour lives in [BaseFileBrowserActivity]; this only says which
 * directory to browse.
 */
class FileBrowserActivity : BaseFileBrowserActivity() {

    private lateinit var distroName: String
    private lateinit var rootfsDir: File
    private val distroSource: LocalDistroSource by lazy { LocalDistroSource(rootfsDir, distroName) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Read before super.onCreate, which calls source() and initialPath().
        distroName = intent?.getStringExtra("distro") ?: "alpine"
        rootfsDir = DistroInstaller(applicationContext).getRootfsDir(distroName)
        super.onCreate(savedInstanceState)
    }

    override fun source(): FileSource = distroSource

    override val supportsOpenInTerminal: Boolean = true

    override fun initialPath(): String? {
        val startPath = intent?.getStringExtra("path")
        // Only honoured when it really is a directory: the caller may be passing
        // a file to open "beside", and falling back to the rootfs root is better
        // than showing a listing of a path that is not there.
        return startPath?.takeIf { File(it).isDirectory }
    }

    /** Maps a host path back to the path as seen inside the rootfs. */
    override fun openInTerminal(path: String) {
        val inner = path.removePrefix(rootfsDir.absolutePath).ifEmpty { "/" }
        TerminalActivity.launch(this, distroName, inner)
    }

    override fun titleSuffix(): String = distroName
}