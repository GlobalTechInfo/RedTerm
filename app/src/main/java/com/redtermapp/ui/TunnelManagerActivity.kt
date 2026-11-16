package com.redtermapp.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.redtermapp.R
import com.redtermapp.util.AppLog

/**
 * One place to open and close the standalone `ssh -N` tunnels. A forward
 * rides along with a session, but a tunnel is for when no terminal is open —
 * a database client on a forwarded port — so it is managed here.
 */
class TunnelManagerActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        body.addView(TextView(this).apply {
            setText(R.string.tunnel_manager)
            textSize = 20f
        })
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(container)

        val scroll = ScrollView(this).apply { addView(body) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        ScreenToolbar.install(this, root, getString(R.string.tunnel_manager))
        setContentView(root)
        load()
    }

    private fun load() {
        Thread({
            val servers = TunnelPlan.serversWithTunnels(SshStore.load(applicationContext))
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                container.removeAllViews()
                if (servers.isEmpty()) {
                    container.addView(TextView(this).apply {
                        setText(R.string.tunnel_none)
                        setTextColor(mutedTextColor())
                    })
                    return@runOnUiThread
                }
                for (s in servers) container.addView(card(s))
            }
        }, "redterm-tunnels").start()
    }

    private fun card(server: SshStore.Server): View {
        val pad = (10 * resources.displayMetrics.density).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(cardColor())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val b = (8 * resources.displayMetrics.density).toInt()
                setMargins(0, 0, 0, b)
            }
        }

        val alive = TunnelManager.isAlive(this, server.id)
        val head = ScreenWidgets.headerRow(this).apply {
            addView(TextView(this@TunnelManagerActivity).apply {
                text = server.label
                textSize = 16f
                setTextColor(cardTextColor())
            })
            addView(TextView(this@TunnelManagerActivity).apply {
                text = if (alive) getString(R.string.tunnel_running) else getString(R.string.tunnel_stopped)
                textSize = 12f
                setTextColor(if (alive) accentColor() else mutedTextColor())
            })
        }
        card.addView(head)

        val hostLine = TextView(this).apply {
            text = getString(R.string.tunnel_endpoint, server.user, server.host, server.port)
            textSize = 12f
            setTextColor(mutedTextColor())
        }
        card.addView(hostLine)
        for (spec in TunnelPlan.activeSpecs(server)) {
            card.addView(TextView(this).apply {
                text = spec
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(cardTextColor())
            })
        }

        val button = ScreenWidgets.actionButton(this, if (alive) R.string.tunnel_stop else R.string.tunnel_start) {
            toggle(server)
        }
        card.addView(button)
        return card
    }

    private fun toggle(server: SshStore.Server) {
        if (TunnelManager.isAlive(this, server.id)) {
            TunnelManager.stop(this, server.id)
            load()
            return
        }
        val identities = SshKeyStore.identitiesFor(applicationContext, server)
        Thread({
            val ok = TunnelManager.start(applicationContext, server, identities)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!ok) AppLog.w(applicationContext, "ssh", "tunnel start failed for ${server.label}")
                load()
            }
        }, "redterm-tunnel-toggle").start()
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
