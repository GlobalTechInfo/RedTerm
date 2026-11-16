package com.redtermapp.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for a real device crash: `ScreenToolbar.install` adds a
 * toolbar view into the view it is given, and a `ScrollView` hosts exactly
 * one direct child. Every tool screen that passed its own `ScrollView` as the
 * toolbar target crashed on start with "ScrollView can host only one direct
 * child". The pattern is: `root` is a `LinearLayout`, the scrollable content is
 * a `ScrollView` *child* of it, and the toolbar is installed into the
 * `LinearLayout`. This test pins that shape in the source so it cannot
 * regress back into the ScrollView-as-root form.
 */
class ToolScreenRootTest {

    private val screens = listOf(
        "WorkspaceActivity", "SnippetLibraryActivity", "StartupHookActivity",
        "DistroDashboardActivity", "TunnelManagerActivity", "TransferQueueActivity",
        "ScheduledJobsActivity", "LocalPortForwardActivity", "MoreToolsActivity",
        "RecordingActivity", "DiskUsageActivity", "PackageUpdateActivity"
    )

    private fun sourceRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main/java").isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the repo root from ${File(".").absolutePath}")
    }

    @Test
    fun `the toolbar is never installed into a ScrollView`() {
        val root = sourceRoot()
        for (name in screens) {
            val src = File(root, "app/src/main/java/com/redtermapp/ui/$name.kt")
            assertTrue("$name.kt is missing", src.isFile)
            val text = src.readText()
            val installIdx = text.indexOf("ScreenToolbar.install")
            assertTrue(
                "$name has no ScreenToolbar.install",
                installIdx >= 0
            )
            val usesScrollViewAsRoot =
                text.contains("root = ScrollView(this)") ||
                text.contains("lateinit var root: ScrollView")
            assertTrue(
                "$name installs the toolbar into a ScrollView root (crash form)",
                !usesScrollViewAsRoot
            )
            // And it must actually install a toolbar, into a LinearLayout-family root.
            assertTrue(
                "$name has no ScreenToolbar.install or a LinearLayout root",
                installIdx >= 0 && (text.contains("root = LinearLayout") || text.contains("root: LinearLayout"))
            )
        }
    }
}
