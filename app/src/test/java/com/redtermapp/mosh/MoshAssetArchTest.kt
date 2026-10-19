package com.redtermapp.mosh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every ABI the app ships has to resolve to an asset directory that actually exists.
 *
 * The two vocabularies are `Build.SUPPORTED_ABIS` (`arm64-v8a`, `armeabi-v7a`, `x86_64`,
 * `x86`) and build-openssh.sh's asset names (`aarch64`, `armv7a`, `x86_64`, `i686`).
 * They differ in three of the four, and the failure mode is not an error: `assets.open`
 * throws on a missing path, so the only symptom is "could not install the mosh client"
 * on devices the binary was built for. Asserting the mapping is the whole test.
 */
class MoshAssetArchTest {

    @Test
    fun `every ABI RedTerm ships maps to an asset directory`() {
        assertEquals("aarch64", MoshClient.assetArch("arm64-v8a"))
        assertEquals("armv7a", MoshClient.assetArch("armeabi-v7a"))
        assertEquals("x86_64", MoshClient.assetArch("x86_64"))
        assertEquals("i686", MoshClient.assetArch("x86"))
    }

    /** The same names build-openssh.sh and build-mosh.sh write, so both are accepted. */
    @Test
    fun `the asset directory names themselves are accepted`() {
        assertEquals("aarch64", MoshClient.assetArch("aarch64"))
        assertEquals("armv7a", MoshClient.assetArch("armv7a"))
        assertEquals("i686", MoshClient.assetArch("i686"))
    }

    @Test
    fun `an unknown or absent ABI is refused rather than guessed`() {
        // Guessing would extract the wrong architecture's binary and produce a session
        // that dies on the first packet with nothing to say why.
        assertNull(MoshClient.assetArch("mips64"))
        assertNull(MoshClient.assetArch(null))
    }
}
