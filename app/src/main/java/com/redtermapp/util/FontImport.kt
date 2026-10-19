package com.redtermapp.util

/**
 * What counts as a font once it has been picked.
 *
 * The document picker filters by MIME, and different providers report the same font
 * under different names — `font/ttf` from the system picker, `application/x-font-ttf`
 * or `application/font-sfnt` from third-party file managers, and plain
 * `application/octet-stream` for anything a browser downloaded. A MIME filter built
 * on the font wildcard therefore matched nothing on many devices: the folder opened
 * and listed no files at all, so no font could ever be selected. The picker is
 * opened unfiltered instead, and the picked names are validated here — the one
 * place the provider is on record about what the file is called.
 */
object FontImport {
    /** ttf and otf for single fonts, ttc and otc for collections of them. */
    val EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")

    /**
     * Only the final extension decides. A provider may hand the full document id
     * across as the name, including its owner prefix and directories, and a file
     * masquerading as a font (`a.ttf.jpg`) is still not a font.
     */
    fun isSupportedFontFile(name: String?): Boolean {
        val ext = name?.substringAfterLast('.', "")?.lowercase() ?: return false
        return ext in EXTENSIONS
    }

    /** Something readable in the rejection toast when the provider named nothing. */
    fun labelFor(name: String?): String =
        name?.takeIf { it.isNotBlank() } ?: "(name unknown)"
}
