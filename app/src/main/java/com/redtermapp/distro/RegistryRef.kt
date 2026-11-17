package com.redtermapp.distro

/**
 * A Docker/OCI image reference: `registry/repo[:tag]`, the same grammar registries
 * accept.
 *
 * The default registry is docker.io and the default tag is `latest`, both the way
 * every registry client resolves an unqualified `ubuntu:22.04` to
 * `registry-1.docker.io/v2/library/ubuntu/manifests/22.04`. The parsing and the
 * platform choice are pure, so they are unit-tested: a reference that resolves to
 * the wrong manifest is a wrong image, and a manifest-list that picks the wrong
 * architecture is an image that cannot start.
 */
object RegistryRef {

    data class Parsed(
        val registry: String,
        val repo: String,
        val tag: String
    ) {
        /** The repository path as a registry addresses it: `library/ubuntu` for `ubuntu`. */
        val repoPath: String get() = if (repo.contains('/')) repo else "library/$repo"

        /** The registry host that actually serves this repository. */
        val host: String get() = if (registry == "docker.io") "registry-1.docker.io" else registry
    }

    /**
     * Split a reference into registry / repository / tag.
     *
     * A registry is the first component only when it looks like a host — it has a
     * dot, is `localhost`, or carries a port. Everything else before a `/` is the
     * repository, and docker.io is the implicit home for single-component names.
     */
    fun parse(reference: String): Parsed {
        val ref = reference.trim().ifEmpty { throw IllegalArgumentException("empty image reference") }
        // strip any digest; we pull by tag only
        val noDigest = ref.substringBefore('@')
        val slashIdx = noDigest.indexOf('/')
        // A registry host only appears before a repository, i.e. with a slash in it:
        // `host/repo`. A bare `repo:tag` with no slash is never a host.
        val head = if (slashIdx < 0) noDigest else noDigest.substring(0, slashIdx)
        val looksLikeHost = slashIdx >= 0 &&
            (head.contains('.') || head == "localhost" || head.contains(':'))
        val registry = if (looksLikeHost) head else "docker.io"
        val repoAndTag = if (looksLikeHost) noDigest.substring(slashIdx + 1) else noDigest
        if (repoAndTag.isEmpty()) throw IllegalArgumentException("no repository in $ref")
        val tag = if (repoAndTag.contains(':')) repoAndTag.substringAfterLast(':') else "latest"
        val repo = if (repoAndTag.contains(':')) repoAndTag.substringBeforeLast(':') else repoAndTag
        if (repo.isEmpty()) throw IllegalArgumentException("no repository in $ref")
        return Parsed(registry, repo, tag)
    }

    /**
     * The architecture name a registry manifest list uses for this device's ABI.
     * Docker/OCI spell it amd64/arm64/arm; the build ABIs spell it x86_64/aarch64/armv7l.
     */
    fun manifestArchForAbi(abi: String): String = when (abi) {
        "x86_64", "x86" -> "amd64"
        "aarch64", "arm64" -> "arm64"
        "armeabi-v7a", "armv7l", "arm" -> "arm"
        else -> "amd64"
    }

    /** Whether a manifest entry's platform matches the device's architecture. */
    fun isMatchingArch(platformArch: String, manifestArch: String): Boolean =
        platformArch == manifestArch
}
