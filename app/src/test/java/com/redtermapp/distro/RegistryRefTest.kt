package com.redtermapp.distro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RegistryRefTest {

    @Test
    fun `an unqualified name defaults to docker library and latest`() {
        val p = RegistryRef.parse("ubuntu")
        assertEquals("docker.io", p.registry)
        assertEquals("ubuntu", p.repo)
        assertEquals("latest", p.tag)
        assertEquals("library/ubuntu", p.repoPath)
        assertEquals("registry-1.docker.io", p.host)
    }

    @Test
    fun `a tagged docker image`() {
        val p = RegistryRef.parse("ubuntu:22.04")
        assertEquals("docker.io", p.registry)
        assertEquals("ubuntu", p.repo)
        assertEquals("22.04", p.tag)
    }

    @Test
    fun `a namespaced docker repo keeps its slash`() {
        val p = RegistryRef.parse("library/debian:bookworm")
        assertEquals("library/debian", p.repo)
        assertEquals("bookworm", p.tag)
    }

    @Test
    fun `a custom registry is the host`() {
        val p = RegistryRef.parse("ghcr.io/org/image:1")
        assertEquals("ghcr.io", p.registry)
        assertEquals("org/image", p.repo)
        assertEquals("1", p.tag)
        assertEquals("ghcr.io", p.host)
    }

    @Test
    fun `a host with a port is a registry`() {
        val p = RegistryRef.parse("registry.example.com:5000/team/app:v2")
        assertEquals("registry.example.com:5000", p.registry)
        assertEquals("team/app", p.repo)
        assertEquals("v2", p.tag)
    }

    @Test
    fun `a digest is ignored, the tag is used`() {
        val p = RegistryRef.parse("ubuntu:22.04@sha256:abcd")
        assertEquals("22.04", p.tag)
        assertEquals("ubuntu", p.repo)
    }

    @Test
    fun `an empty reference is refused`() {
        try {
            RegistryRef.parse("")
            fail("empty must throw")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `a bare host-port with no repository is a repo tag, not a host`() {
        // Without a slash there is no repository, so the whole string is
        // repo:tag on the default registry — never a host.
        val p = RegistryRef.parse("docker.io:443")
        assertEquals("docker.io", p.registry)
        assertEquals("docker.io", p.repo)
        assertEquals("443", p.tag)
    }

    @Test
    fun `build abis map to manifest architectures`() {
        assertEquals("amd64", RegistryRef.manifestArchForAbi("x86_64"))
        assertEquals("amd64", RegistryRef.manifestArchForAbi("x86"))
        assertEquals("arm64", RegistryRef.manifestArchForAbi("aarch64"))
        assertEquals("arm64", RegistryRef.manifestArchForAbi("arm64"))
        assertEquals("arm", RegistryRef.manifestArchForAbi("armeabi-v7a"))
        // an unknown abi still names an architecture, never crashes
        assertTrue(RegistryRef.manifestArchForAbi("riscv64").isNotEmpty())
    }

    @Test
    fun `architecture match is exact`() {
        assertTrue(RegistryRef.isMatchingArch("arm64", "arm64"))
        assertTrue(!RegistryRef.isMatchingArch("arm", "arm64"))
        assertTrue(!RegistryRef.isMatchingArch("amd64", "arm64"))
    }
}
