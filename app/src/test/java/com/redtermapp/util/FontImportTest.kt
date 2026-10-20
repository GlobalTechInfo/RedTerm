package com.redtermapp.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validation happens after the pick, so it is this function that decides whether
 * the user ever gets a font.
 *
 * The document picker could not be trusted to pre-filter the way v2.2.0 assumed:
 * a MIME filter built on the font wildcard matched only files whose provider
 * reported a `font/ttf` MIME, while real fonts arrive reported as
 * `application/x-font-ttf`, `application/font-sfnt` or plain
 * `application/octet-stream` — so every folder with fonts in it showed up
 * empty and nothing could be selected. The picker now opens unfiltered and every
 * picked name is checked here instead.
 */
class FontImportTest {

    @Test
    fun `every shipped font kind is accepted`() {
        for (name in listOf(
            "JetBrainsMono-Regular.ttf",
            "ligated.otf"
        )) assertTrue(name, FontImport.isSupportedFontFile(name))
    }

    @Test
    fun `a font collection is not offered for import`() {
        // ttf/otf are what the terminal lists and loads. A .ttc or .otc accepted at
        // the picker was copied to filesDir/fonts and then appeared in no list and
        // no spinner, with no way to remove it.
        for (name in listOf(
            "coptic.ttc",
            "kanji-g3.otc"
        )) assertFalse(name, FontImport.isSupportedFontFile(name))
    }

    @Test
    fun `the extension is decided case-insensitively`() {
        assertTrue(FontImport.isSupportedFontFile("font.TTF"))
        assertTrue(FontImport.isSupportedFontFile("Font.Otf"))
        assertTrue(FontImport.isSupportedFontFile("mono.Ttf"))
    }

    @Test
    fun `only the final extension decides`() {
        // A masquerading file is rejected even when an earlier extension looks like
        // a font: a.ttf.jpg never becomes a font by being imported.
        assertFalse(FontImport.isSupportedFontFile("a.ttf.jpg"))
        assertFalse(FontImport.isSupportedFontFile("archive.tar.gz"))
        assertFalse(FontImport.isSupportedFontFile("capsule.mtf"))
    }

    @Test
    fun `a document id prefix around the name does not change anything`() {
        // Some providers hand the whole document id across as the display name.
        assertTrue(
            FontImport.isSupportedFontFile("primary:Download/JetBrainsMono-Regular.ttf")
        )
        assertFalse(FontImport.isSupportedFontFile("primary:Download/msf:100"))
    }

    @Test
    fun `nothing that is not a name is accepted`() {
        assertFalse("null", FontImport.isSupportedFontFile(null))
        assertFalse("empty", FontImport.isSupportedFontFile(""))
        assertFalse("no extension", FontImport.isSupportedFontFile("font"))
        assertFalse("trailing dot", FontImport.isSupportedFontFile("font."))
        assertFalse("blank", FontImport.isSupportedFontFile("   "))
    }

    @Test
    fun `a named rejection never renders as blank`() {
        // The toast is the whole explanation for a skipped file; a blank label in it
        // would tell the user nothing about which pick was dropped.
        assertTrue(FontImport.labelFor("a.ttf.jpg").contains("a.ttf.jpg"))
        assertFalse(FontImport.labelFor("").isEmpty())
        assertFalse(FontImport.labelFor(null).isEmpty())
        assertFalse(FontImport.labelFor("   ").isEmpty())
    }
}
