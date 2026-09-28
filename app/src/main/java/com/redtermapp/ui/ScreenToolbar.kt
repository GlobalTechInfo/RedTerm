package com.redtermapp.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.redtermapp.R

/**
 * Shared toolbar for the screens that build their layout in code.
 *
 * Without a real Toolbar in the hierarchy there is no action bar, so
 * `setDisplayHomeAsUpEnabled` silently has no effect and the screen shows no
 * back affordance at all.
 */
object ScreenToolbar {

    fun install(
        activity: AppCompatActivity,
        parent: ViewGroup,
        title: CharSequence? = null
    ): Toolbar {
        val toolbar = LayoutInflater.from(activity)
            .inflate(R.layout.view_screen_toolbar, parent, false) as Toolbar
        // Insert at the top: these screens build their content before the
        // toolbar is installed, so appending would leave it stranded at the
        // bottom of the page.
        parent.addView(
            toolbar,
            0,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        toolbar.title = title ?: ""
        activity.setSupportActionBar(toolbar)
        activity.supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // Explicit icon rather than the theme's default so every screen uses the
        // same back affordance as the rest of the app.
        toolbar.setNavigationIcon(R.drawable.ic_back_chip)
        toolbar.setNavigationOnClickListener { activity.finish() }
        return toolbar
    }
}
