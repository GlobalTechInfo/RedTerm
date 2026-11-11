package com.redtermapp.distro

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * A registry layer is a standard rootfs archive: many top-level directories, never wrapped
 * in a single one. The extractor's blind prefix-strip off the first entry hoisted the whole
 * bin/ tree to the root and dropped /bin/sh, which is how an installed Alpine came up with
 * no shell and busybox still to install. This pins that the new-feature path
 * (stripPrefix = false) keeps /bin/sh and /bin/busybox in place, against a real Docker Hub
 * layer.
 */
class DockerLayerExtractTest {

    private val work = File("build/docker-layer-extract")

    @Before
    fun setUp() {
        work.deleteRecursively()
        work.mkdirs()
        RootfsInstallExtractor.changeMode = { _, _ -> }
        RootfsInstallExtractor.makeSymlink = { target, path ->
            Files.createSymbolicLink(Paths.get(path), Paths.get(target))
        }
        RootfsInstallExtractor.modeFailures.clear()
    }

    private fun dockerReachable(): Boolean = try {
        (URL("https://registry-1.docker.io/v2/").openConnection() as java.net.HttpURLConnection)
            .apply { connectTimeout = 8000; readTimeout = 8000 }.responseCode
        true
    } catch (_: Exception) {
        false
    }

    @Test
    fun alpineLayerKeepsBinShAndBusybox() {
        assumeTrue("Docker Hub registry not reachable", dockerReachable())
        val layers = RegistryPuller.pull("docker.io/library/alpine:3.19", "aarch64", work) { }.layers
        val layer = layers.first()

        val dest = File(work, "rootfs").apply { mkdirs() }
        FileInputStream(layer).use { fis ->
            BufferedInputStream(java.util.zip.GZIPInputStream(fis), 65536).use { bis ->
                TarArchiveInputStream(bis).use { tarIn ->
                    // stripPrefix = false: the new-feature path. The default (true) is the
                    // catalogue behaviour this test does not exercise.
                    RootfsInstallExtractor.extract(tarIn, dest, 0L, {}, {}, {}, stripPrefix = false)
                }
            }
        }

        val binSh = File(dest, "bin/sh")
        val busybox = File(dest, "bin/busybox")
        assertTrue("bin/sh is not a symlink after extraction", Files.isSymbolicLink(binSh.toPath()))
        assertTrue("bin/busybox missing after extraction", busybox.isFile)
        // The bug hoisted bin/ to the root; assert that did not happen.
        assertFalse("bin/ was hoisted to the root (bug)", File(dest, "sh").exists() && !binSh.exists())
    }
}
