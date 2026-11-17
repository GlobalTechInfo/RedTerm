package com.redtermapp.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.redtermapp.R

/**
 * One place for the secondary screens, grouped the way the bashrc template
 * button groups its templates. Each row names the tool and opens it; the
 * settings screen reaches this hub through a single "More tools" row rather
 * than one row per tool.
 */
class MoreToolsActivity : AppCompatActivity() {

    private data class Tool(
        val titleRes: Int,
        val descRes: Int,
        val target: Class<*>
    )

    private val tools = listOf(
        Tool(R.string.workspaces, R.string.more_tools_workspaces_desc, WorkspaceActivity::class.java),
        Tool(R.string.startup_hook, R.string.more_tools_startup_desc, StartupHookActivity::class.java),
        Tool(R.string.snippets, R.string.more_tools_snippets_desc, SnippetLibraryActivity::class.java),
        Tool(R.string.distro_dashboard, R.string.more_tools_dashboard_desc, DistroDashboardActivity::class.java),
        Tool(R.string.scheduled_jobs, R.string.more_tools_jobs_desc, ScheduledJobsActivity::class.java),
        Tool(R.string.ldi_title, R.string.more_tools_local_install_desc, LocalDistroInstallActivity::class.java)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        body.addView(TextView(this).apply {
            setText(R.string.more_tools)
            textSize = 20f
            setTextColor(cardTextColor())
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (tool in tools) list.addView(row(tool))
        body.addView(list)

        val scroll = ScrollView(this).apply { addView(body) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        ScreenToolbar.install(this, root, getString(R.string.more_tools))
        setContentView(root)
    }

    private fun row(tool: Tool): View {
        val pad = (10 * resources.displayMetrics.density).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(cardColor())
            setOnClickListener {
                startActivity(Intent(this@MoreToolsActivity, tool.target))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val b = (8 * resources.displayMetrics.density).toInt()
                setMargins(0, 0, 0, b)
            }
        }
        card.addView(TextView(this).apply {
            text = getString(tool.titleRes)
            textSize = 15f
            setTextColor(cardTextColor())
        })
        card.addView(TextView(this).apply {
            text = getString(tool.descRes)
            textSize = 12f
            setTextColor(mutedTextColor())
        })
        return card
    }

    private fun applyTheme() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        when (NightModeReceiver.effectiveTheme(prefs)) {
            "red" -> setTheme(R.style.Theme_RedTermApp_Red)
            "amoled" -> setTheme(R.style.Theme_RedTermApp_AMOLED)
            "green" -> setTheme(R.style.Theme_RedTermApp_Green)
            "light" -> setTheme(R.style.Theme_RedTermApp_Light)
            "dracula" -> setTheme(R.style.Theme_RedTermApp_Dracula)
            "nord" -> setTheme(R.style.Theme_RedTermApp_Nord)
            "tokyo" -> setTheme(R.style.Theme_RedTermApp_Tokyo)
            "gruvbox" -> setTheme(R.style.Theme_RedTermApp_Gruvbox)
            else -> setTheme(R.style.Theme_RedTermApp)
        }
    }
}
