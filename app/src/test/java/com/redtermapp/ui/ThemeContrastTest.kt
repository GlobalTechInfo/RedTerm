package com.redtermapp.ui

import com.redtermapp.distro.DistroBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The colours the code-built screens use have to be ones a theme actually declared.
 *
 * This exists because of a bug no unit test could see and every dark theme could. The
 * screens took their text from `android:attr/textColorPrimary`, which none of the ten
 * themes declares, so the hardcoded fallback was used on all of them — and that fallback
 * was the app's *background* colour rather than its text colour. Every label on every card
 * was near-black on a near-black card. The same mistake a second time took the accent from
 * `android:attr/colorAccent`, which left the `mosh` chip and "protected by a passphrase"
 * unreadable while everything around them was fine.
 *
 * So the themes are read the same way the code reads them: by resolving the `@color/...`
 * references each style declares, rather than by guessing a name from the theme.
 */
class ThemeContrastTest {

    private fun colors(): Map<String, Int> {
        val file = File("src/main/res/values/colors.xml")
        assertTrue("colors.xml not found at ${file.absolutePath}", file.isFile)
        val map = HashMap<String, Int>()
        Regex("""<color name="([^"]+)">#([0-9A-Fa-f]{6,8})</color>""")
            .findAll(file.readText()).forEach { m ->
                val hex = m.groupValues[2]
                map[m.groupValues[1]] = (if (hex.length == 8) hex else "FF$hex").toLong(16).toInt()
            }
        return map
    }

    /** Each `Theme.RedTermApp*` style, by name, with its body. */
    private fun themes(): List<Pair<String, String>> {
        val file = File("src/main/res/values/themes.xml")
        assertTrue("themes.xml not found at ${file.absolutePath}", file.isFile)
        return Regex(
            """<style name="(Theme\.RedTermApp[^"]*)"[^>]*>(.*?)</style>""",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(file.readText()).map { it.groupValues[1] to it.groupValues[2] }.toList()
    }

    /** The colour a theme declares for [attr], resolved through colors.xml. */
    private fun resolve(theme: String, body: String, attr: String): Int {
        val ref = Regex("""<item name="$attr">@color/([a-z_]+)</item>""")
            .find(body)?.groupValues?.get(1)
            ?: error("$theme does not set $attr to a @color reference")
        val value = colors()[ref] ?: error("$theme: $attr points at @color/$ref, not in colors.xml")
        return value
    }

    /**
     * Every theme's text has to be readable on that theme's own card.
     *
     * Resolved per theme rather than from a `<prefix>_extra_keys_text` naming convention,
     * because the Custom theme declares the default theme's colours and is overridden at
     * runtime: there is no `custom_extra_keys_bg` to look up, and a test that derived a
     * prefix from the theme's name would fail on it for no reason.
     */
    @Test
    fun `every theme's text is readable on its own card`() {
        val themes = themes()
        assertTrue("expected several themes, found ${themes.size}", themes.size >= 8)
        for ((name, body) in themes) {
            val bg = resolve(name, body, "extraKeysBg")
            val text = resolve(name, body, "extraKeysText")
            val ratio = DistroBrand.contrastOf(text, bg)
            assertTrue(
                "$name: text ${hex(text)} on card ${hex(bg)} is " +
                    "${ratio.oneDecimal()}:1, below the 4.5:1 body text needs",
                ratio >= 4.5,
            )
        }
    }

    /**
     * Every theme's accent has to be readable on that theme's own card.
     *
     * Chips draw their text in the accent, so an accent that is fine as a filled button
     * with white text can still be unreadable at 11sp. The light theme's primary was
     * exactly that: 2.53:1 against its own card, and invisible as a chip label.
     */
    @Test
    fun `every theme's accent is readable on its own card`() {
        for ((name, body) in themes()) {
            val bg = resolve(name, body, "extraKeysBg")
            val accent = resolve(name, body, "accentColor")
            val ratio = DistroBrand.contrastOf(accent, bg)
            assertTrue(
                "$name: accent ${hex(accent)} on card ${hex(bg)} is " +
                    "${ratio.oneDecimal()}:1, below the 4.5:1 a chip label needs",
                ratio >= 4.5,
            )
        }
    }

    /**
     * Every theme declares every attribute the code-built screens read.
     *
     * This is the mechanism behind the original bug rather than its cause. A theme that
     * omits one does not fail — it silently falls back to a literal, which is how nine
     * dark themes ended up drawing near-black labels on near-black cards with nothing
     * anywhere reporting it.
     */
    @Test
    fun `every theme declares the attributes the screens read`() {
        val missing = mutableListOf<String>()
        for ((name, body) in themes()) {
            for (attr in listOf("extraKeysBg", "extraKeysText", "accentColor")) {
                if (!Regex("""<item name="$attr">""").containsMatchIn(body)) {
                    missing += "$name:$attr"
                }
            }
        }
        assertEquals("themes missing an attribute the screens read: $missing", emptyList<String>(), missing)
    }

    /** No card colour in colors.xml is left without the text colour that goes with it. */
    @Test
    fun `no card colour is left without its text`() {
        val colors = colors()
        val orphans = colors.keys.filter { it.endsWith("_extra_keys_bg") }
            .map { it.removeSuffix("_extra_keys_bg") }
            .filterNot { colors.containsKey("${it}_extra_keys_text") }
        assertEquals("card colours with no text colour: $orphans", emptyList<String>(), orphans)
    }

    private fun Double.oneDecimal(): Double = Math.round(this * 10) / 10.0

    private fun hex(color: Int): String = "#%06X".format(color and 0xFFFFFF)
}
