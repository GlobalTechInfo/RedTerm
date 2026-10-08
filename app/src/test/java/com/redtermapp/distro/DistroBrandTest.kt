package com.redtermapp.distro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The distro badge: letters, colour, and whether they can actually be read.
 *
 * A badge that cannot be read is worse than no badge, and the failure is invisible until
 * somebody picks the colour. So the foreground is chosen by measured contrast rather than
 * per distro, and that is asserted here for every distro the app ships.
 */
class DistroBrandTest {

    private fun contrast(foreground: Int, background: Int): Double {
        fun lum(color: Int): Double {
            fun ch(v: Int): Double {
                val x = v / 255.0
                return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
            }
            // Channels read by shifting rather than with android.graphics.Color, which is
            // not available off a device — and the same trick the production code uses.
            return 0.2126 * ch((color shr 16) and 0xFF) +
                0.7152 * ch((color shr 8) and 0xFF) +
                0.0722 * ch(color and 0xFF)
        }
        val a = lum(foreground)
        val b = lum(background)
        val hi = maxOf(a, b)
        val lo = minOf(a, b)
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `every shipped distro has a badge`() {
        for (distro in DistroRegistry.allDistros) {
            val badge = DistroBrand.badgeFor(distro)
            assertTrue("${distro.name} has no letters", badge.letters.isNotEmpty())
            assertTrue("${distro.name} badge is too wide", badge.letters.length <= 3)
        }
    }

    /**
     * The reason the foreground is computed rather than chosen.
     *
     * A pale brand colour with white letters is a card nobody can read, and it would look
     * entirely correct in code.
     */
    @Test
    fun `every badge is readable`() {
        for (distro in DistroRegistry.allDistros) {
            val badge = DistroBrand.badgeFor(distro)
            val ratio = contrast(badge.foreground, badge.background)
            assertTrue(
                "${distro.name} badge contrast is only %.2f".format(ratio),
                ratio >= 4.5
            )
        }
    }

    @Test
    fun `the badge says which distro it is`() {
        // Single letters were tried and three distros came out identical, which is the one
        // thing a badge exists to prevent.
        val badges = DistroRegistry.allDistros.map { DistroBrand.badgeFor(it).letters }
        assertEquals(
            "two distros share a badge: $badges",
            badges.size,
            badges.toSet().size
        )
    }

    @Test
    fun `a distro nobody has a colour for still gets one`() {
        val badge = DistroBrand.badgeFor("notadistro", "Not A Distro")
        assertEquals("NO", badge.letters)
        assertTrue(contrast(badge.foreground, badge.background) >= 4.5)
    }

    /** The same name must always give the same colour, or cards change between visits. */
    @Test
    fun `the fallback colour is stable per name`() {
        assertEquals(
            DistroBrand.colourFrom("somethingnew"),
            DistroBrand.colourFrom("somethingnew")
        )
        assertNotEquals(
            DistroBrand.colourFrom("alpha"),
            DistroBrand.colourFrom("beta")
        )
    }

    @Test
    fun `the monogram skips punctuation`() {
        // The fallback takes the first two letters of the display name with the spaces
        // dropped. It cannot know that "openSUSE" means "OS", which is why the registry
        // supplies that one explicitly; this is only the behaviour for a distro nobody has
        // thought about yet.
        assertEquals("NO", DistroBrand.monogramFrom("Not A Distro"))
        assertEquals("UB", DistroBrand.monogramFrom("ubuntu"))
        assertEquals("X", DistroBrand.monogramFrom("X"))
        assertEquals("?", DistroBrand.monogramFrom(""))
    }

    /** The registered monogram wins over the fallback, which is the point of listing them. */
    @Test
    fun `a registered distro uses its own monogram`() {
        assertEquals("Su", DistroBrand.badgeFor("opensuse", "openSUSE").letters)
    }

    /** Shipping a real logo is adding a file under this name and nothing else. */
    @Test
    fun `the logo asset path is derived from the distro name`() {
        assertEquals("distro-icons/alpine.png", DistroBrand.assetPath("alpine"))
        assertEquals("distro-icons/almalinux.png", DistroBrand.assetPath("almalinux"))
    }

    /**
     * With no host configured there is no URL to follow, and no broken-image placeholder.
     *
     * A guessed URL that 404s is the failure mode this exists to prevent: the card would
     * show letters anyway, and nothing anywhere would say why.
     */
    @Test
    fun `no base url means no download url`() {
        assertEquals(null, DistroBrand.iconUrl("alpine"))
    }

    /**
     * The name shown for a distro, which the install key cannot supply.
     *
     * Every list outside the install screen used to capitalise the key itself, which is
     * right for "arch" and wrong for the other two: "almalinux" rendered as "Almalinux"
     * and "opensuse" as "Opensuse".
     */
    @Test
    fun `the shown name is the distro's own, not the capitalised key`() {
        assertEquals("Alma", DistroBrand.displayNameFor("almalinux"))
        assertEquals("openSUSE", DistroBrand.displayNameFor("opensuse"))
        assertEquals("Arch", DistroBrand.displayNameFor("arch"))
        assertEquals("Manjaro", DistroBrand.displayNameFor("manjaro"))
    }

    /**
     * A distro the registry no longer lists must still show something.
     *
     * Uninstalling support for a distro does not uninstall it, and a blank row is worse
     * than a raw key.
     */
    @Test
    fun `a distro the registry has dropped still has a name`() {
        assertEquals("Oldlinux", DistroBrand.displayNameFor("oldlinux"))
    }

    @Test
    fun `every shipped distro has an asset name a host could serve`() {
        for (distro in DistroRegistry.allDistros) {
            val asset = DistroBrand.assetPath(distro.name)
            assertTrue(
                "${distro.name} cannot be served as ${asset}",
                asset.startsWith("distro-icons/") &&
                    asset.endsWith(".png") &&
                    !asset.contains("//") &&
                    !asset.contains("..")
            )
        }
    }
}