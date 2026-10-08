package com.redtermapp.distro

import kotlin.math.roundToInt

/**
 * Per-distro presentation: the badge colour and letters shown on its card.
 *
 * **The badge is a monogram, not the official logo.** The distro logos are trademarked
 * artwork, and whether a given app may ship them is the project's call, not something to be
 * smuggled in by a build script. So the default is a lettered badge in the distro's own
 * brand colour, which is unambiguous, weighs nothing, themes with everything else, and
 * carries no licence attached.
 *
 * **A real logo is a drop-in.** If `assets/distro-icons/<name>.png` exists it is used
 * instead — see [assetPath]. Nothing has to change in this file, or in any screen, to swap
 * one in.
 *
 * **Adding a distro needs no change here.** Anything not listed falls back to a colour
 * derived from the name, so the two letters are derived from the name too and a card is
 * never left blank.
 */
object DistroBrand {

    /** Monogram and background for a distro, keyed by [Distro.name]. */
    data class Badge(val letters: String, val background: Int, val foreground: Int)

    // Brand colours as published by each project. Recognisable at a glance, and the point
    // of a badge is recognition.
    private val BRAND: Map<String, Pair<String, Int>> = mapOf(
        "alpine" to ("Al" to 0xFF0D597F.toInt()),
        "arch" to ("Ar" to 0xFF1793D1.toInt()),
        "debian" to ("De" to 0xFFA80030.toInt()),
        "void" to ("Vo" to 0xFF478061.toInt()),
        "ubuntu" to ("Ub" to 0xFFE95420.toInt()),
        "kali" to ("Ka" to 0xFF367BF0.toInt()),
        "almalinux" to ("Alm" to 0xFF0F4266.toInt()),
        "rocky" to ("Ro" to 0xFF10B981.toInt()),
        "fedora" to ("Fe" to 0xFF294172.toInt()),
        "manjaro" to ("Ma" to 0xFF35BF5C.toInt()),
        "opensuse" to ("Su" to 0xFF73BA25.toInt())
    )

    /**
     * Where a real logo would live, or null when the project has not supplied one.
     *
     * Naming it is enough: the card looks for this path and falls back to the monogram when
     * it is absent, so shipping a logo is adding a file and nothing else.
     */
    fun assetPath(distroName: String): String = "distro-icons/$distroName.png"

    /**
     * The downloaded equivalent of [assetPath], under whatever base the project serves
     * from. Blank while that base is unset, which is what keeps a card lettered rather
     * than showing a broken image.
     */
    fun iconUrl(distroName: String): String? =
        if (DistroIconStore.BASE_URL.isBlank()) null
        else DistroIconStore.BASE_URL.trimEnd('/') + "/$distroName.png"

    fun badgeFor(distro: Distro): Badge = badgeFor(distro.name, distro.displayName)

    /**
     * The name to show for a distro, from its key.
     *
     * Every list outside the install screen used to capitalise the *key*, which is right
     * for "arch" and wrong for "almalinux" — it rendered "Almalinux" — and for "opensuse",
     * which rendered "Opensuse" instead of "openSUSE". Falling back to the capitalised key
     * keeps a distro that has since left the registry from showing as nothing at all.
     */
    fun displayNameFor(name: String): String =
        DistroRegistry.allDistros.firstOrNull { it.name == name }?.displayName
            ?: name.replaceFirstChar { it.uppercase() }

    fun badgeFor(name: String, displayName: String): Badge {
        val known = BRAND[name]
        val letters = known?.first ?: monogramFrom(displayName)
        val background = known?.second ?: colourFrom(name)
        return Badge(letters, background, readableOn(background))
    }

    /**
     * First two significant characters of a display name.
     *
     * Single letters were tried first and three distros came out as the same "A" — which is
     * the one thing a badge exists to avoid.
     */
    internal fun monogramFrom(displayName: String): String {
        val letters = displayName.filter { it.isLetterOrDigit() }
        return when {
            letters.isEmpty() -> "?"
            letters.length == 1 -> letters.uppercase()
            else -> letters.take(2).uppercase()
        }
    }

    /**
     * A stable colour for a distro nobody has a brand colour for.
     *
     * Derived from the name so the same distro is always the same colour, and picked for
     * a mid lightness so the foreground computed from it stays readable.
     */
    internal fun colourFrom(name: String): Int {
        val hash = name.fold(7) { acc, c -> acc * 31 + c.code }
        val hue = (((hash % 360) + 360) % 360).toDouble()
        // HSV done by hand rather than with android.graphics.Color: the maths is trivial,
        // and depending on a framework class for it means the contrast rules below cannot
        // be tested off a device at all.
        return hsv(hue, 0.55, 0.62)
    }

    /** HSV to packed RGB, the standard six-sector conversion. */
    private fun hsv(h: Double, s: Double, v: Double): Int {
        val c = v * s
        val x = c * (1 - Math.abs((h / 60.0) % 2.0 - 1.0))
        val m = v - c
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0.0)
            h < 120 -> Triple(x, c, 0.0)
            h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c)
            h < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val rgb = (to255(r + m).toLong() shl 16) or
            (to255(g + m).toLong() shl 8) or
            to255(b + m).toLong()
        return (0xFF000000L or rgb).toInt()
    }

    private fun to255(value: Double): Int = (value * 255.0).roundToInt().coerceIn(0, 255)

    /**
     * Black or white, whichever is readable on [background].
     *
     * Chosen by measured contrast rather than by a per-distro guess, so a new brand colour
     * cannot arrive with white-on-pale and be unreadable.
     */
    internal fun readableOn(background: Int): Int {
        val dark = 0xFF101014.toInt()
        val light = 0xFFFFFFFF.toInt()
        // Whichever reads better, not whichever is under a threshold. Switching on a
        // luminance of 0.5 looks equivalent and is not: it put Arch's badge at 3.43:1,
        // because its blue sits just above the cut while still favouring dark ink.
        return if (contrastOf(dark, background) >= contrastOf(light, background)) dark else light
    }

    /** WCAG relative-contrast ratio between two packed colours. */
    fun contrastOf(foreground: Int, background: Int): Double {
        val a = luminance(background)
        val b = luminance(foreground)
        val hi = maxOf(a, b)
        val lo = minOf(a, b)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun luminance(color: Int): Double =
        0.2126 * channel((color shr 16) and 0xFF) +
            0.7152 * channel((color shr 8) and 0xFF) +
            0.0722 * channel(color and 0xFF)

    private fun channel(value: Int): Double {
        val v = value / 255.0
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }
}