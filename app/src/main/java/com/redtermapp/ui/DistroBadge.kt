package com.redtermapp.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.redtermapp.R
import com.redtermapp.distro.Distro
import com.redtermapp.distro.DistroBrand
import com.redtermapp.distro.DistroIconStore

/**
 * The badge at the left of a distro row: its logo, or a lettered stand-in.
 *
 * Three sources in order — a bundled asset, an icon downloaded on an earlier visit, and
 * the letters — and the letters are always shown *first*. So a card is never blank, never
 * blocked on a network round trip, and does not change size when the logo turns up: the
 * letters and the image sit in one fixed-size frame and simply swap visibility.
 *
 * Built in code like the rest of this UI, and deliberately out of plain `TextView`,
 * `ImageView` and `FrameLayout` rather than a custom `View`. A custom view brings its own
 * measurement, and a row that measures differently from one next to it is exactly the bug
 * that only shows up on somebody else's phone.
 *
 * Takes a distro **key** rather than a `Distro`, because most of the places that want a
 * badge — the home list, the settings list, the backup list — only ever have the key. The
 * display name is looked up from it, which is also why the lists no longer capitalise the
 * key themselves and render "Almalinux".
 */
object DistroBadge {

    private const val SIZE_DP = 44
    private const val GAP_DP = 12

    fun create(context: Context, distro: Distro, sizeDp: Int = SIZE_DP): View =
        build(context, distro.name, distro.displayName, sizeDp)

    /** For the callers that have only the install key, which is most of them. */
    fun create(context: Context, distroName: String, sizeDp: Int = SIZE_DP): View =
        build(context, distroName, DistroBrand.displayNameFor(distroName), sizeDp)

    private fun build(context: Context, key: String, displayName: String, sizeDp: Int): View {
        val size = (sizeDp * context.resources.displayMetrics.density).toInt()
        val gap = (GAP_DP * context.resources.displayMetrics.density).toInt()
        val params = LinearLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginEnd = gap
        }

        val letters = DistroBrand.badgeFor(key, displayName)

        val image = ImageView(context).apply {
            // FIT_CENTER, never FIT_XY. openSUSE's mark is a 2:1 wordmark and every other
            // logo is square; stretching would distort it and cropping would cut the ends
            // off the name.
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, 0, 0, 0)
            contentDescription = displayName
            visibility = View.GONE
            layoutParams = params
        }
        val badge = TextView(context).apply {
            text = letters.letters
            gravity = Gravity.CENTER
            setTextColor(letters.foreground)
            setBackgroundResource(R.drawable.bg_distro_badge)
            background.mutate().setTint(letters.background)
            typeface = Typeface.DEFAULT_BOLD
            textSize = if (letters.letters.length > 2) 13f else 17f
            includeFontPadding = false
            // Read as the distro's name rather than its initials: "A l" tells a screen
            // reader nothing and "Alpine" does.
            contentDescription = context.getString(R.string.distro_badge_description, displayName)
            layoutParams = params
        }

        val frame = FrameLayout(context).apply {
            layoutParams = params
            addView(badge)
            addView(image)
        }

        // Already known — from memory, the bundle, or an earlier download. One lookup, and
        // a memory hit is a map read rather than a decode.
        val known = DistroIconStore.iconOrNull(context, key)
        if (known != null) {
            image.setImageBitmap(known)
            image.visibility = View.VISIBLE
            badge.visibility = View.GONE
            return frame
        }

        // Not known. Fetch in the background and leave the letters up meanwhile. The frame
        // is returned either way, so a row's size and layout never depend on whether the
        // network answered.
        DistroIconStore.fetch(context, key) { fetched ->
            image.setImageBitmap(fetched)
            image.visibility = View.VISIBLE
            badge.visibility = View.GONE
        }
        return frame
    }
}