package com.redtermapp.distro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URL

/**
 * Drives [RegistryPuller] against the real Docker Hub, the way a device would:
 * anonymous token, multi-arch manifest selection, ordered layer download.
 *
 * This is the path that 401'd because the token was requested for the registry host
 * instead of the `service` the registry's own 401 challenge names. It is gated on a
 * reachable registry so a network-less CI skips it rather than failing, and runs for
 * real wherever there is a connection.
 */
class RegistryPullerTest {

    private fun registryReachable(): Boolean = try {
        val conn = URL("https://registry-1.docker.io/v2/").openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.responseCode // a 401 challenge still means we reached the server
        true
    } catch (_: Exception) {
        false
    }

    @Test
    fun pullsAlpineLayersFromDockerHub() {
        assumeTrue("Docker Hub registry not reachable from this environment", registryReachable())

        val out = File("build/registry-test").apply { deleteRecursively(); mkdirs() }
        val result = RegistryPuller.pull("docker.io/library/alpine:3.19", "aarch64", out) { }

        assertFalse("no layers pulled", result.layers.isEmpty())
        for (layer in result.layers) {
            assertTrue("layer not on disk: $layer", layer.exists() && layer.length() > 0)
        }
    }

    @Test
    fun rejectsABadReferenceLoudly() {
        assumeTrue("Docker Hub registry not reachable from this environment", registryReachable())
        val out = File("build/registry-test-bad").apply { deleteRecursively(); mkdirs() }
        val e = try {
            RegistryPuller.pull("docker.io/library/does-not-exist-404:latest", "aarch64", out) { }
            null
        } catch (t: Throwable) {
            t
        }
        // A repository that does not exist must fail with a named HTTP status, not a
        // silent no-op.
        assertTrue(
            "expected a manifest error for a missing image, got ${e?.message}",
            e != null && e.message.orEmpty().contains("HTTP")
        )
    }
}
