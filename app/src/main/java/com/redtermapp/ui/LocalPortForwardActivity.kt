package com.redtermapp.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.redtermapp.R

/**
 * Open device ports that reach a target, and manage any number of them at
 * once.
 *
 * The target is either the device itself (a guest service bound to
 * `127.0.0.1` inside a proot distro) or a saved SSH server, in which case the
 * forward rides `ssh -N -L` and the remote host:port is reached through the
 * connection. Forwards are owned by [ForwardManager], so they stay open while
 * the activity is closed and any mix of servers is reachable at the same time.
 */
class LocalPortForwardActivity : AppCompatActivity() {

    private lateinit var active: LinearLayout
    private lateinit var target: Spinner
    private lateinit var presetRow: FlowLayout
    private lateinit var fromPort: EditText
    private lateinit var toHost: EditText
    private lateinit var toPort: EditText
    private lateinit var status: TextView
    private var servers: List<SshStore.Server> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        body.addView(TextView(this).apply {
            setText(R.string.local_port_forward)
            textSize = 20f
        })

        body.addView(sectionLabel(getString(R.string.lpf_active)))
        active = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(active)

        body.addView(sectionLabel(getString(R.string.lpf_target)))
        target = Spinner(this)
        target.layoutParams = spaced()
        body.addView(target)
        presetRow = FlowLayout(this)
        presetRow.layoutParams = spaced()
        body.addView(presetRow)
        fromPort = portField(getString(R.string.lpf_from))
        fromPort.layoutParams = spaced()
        body.addView(fromPort)
        toHost = hostField()
        toHost.layoutParams = spaced()
        body.addView(toHost)
        toPort = portField(getString(R.string.lpf_to))
        toPort.layoutParams = spaced()
        body.addView(toPort)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val addButton = ScreenWidgets.primaryButton(this, R.string.lpf_add) { addForward() }
        addButton.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        actions.addView(addButton)
        val saveButton = ScreenWidgets.actionButton(this, R.string.lpf_save_preset) { savePreset() }
        saveButton.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { leftMargin = (8 * resources.displayMetrics.density).toInt() }
        actions.addView(saveButton)
        actions.layoutParams = spaced()
        body.addView(actions)

        status = TextView(this).apply {
            textSize = 12f
            setPadding(0, 6, 0, 0)
            setTextColor(mutedTextColor())
        }
        body.addView(status)
        body.addView(laptopCard())

        val scroll = ScrollView(this).apply { addView(body) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        ScreenToolbar.install(this, root, getString(R.string.local_port_forward))
        setContentView(root)

        target.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                // Position 0 is the device itself; a remote host field is only
                // meaningful when a server is selected.
                toHost.visibility = if (position == 0) View.GONE else View.VISIBLE
                refreshPresets()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTargets()
        refreshActive()
        refreshPresets()
    }

    private fun refreshTargets() {
        servers = SshStore.load(applicationContext)
        val labels = mutableListOf(getString(R.string.lpf_this_device))
        labels.addAll(servers.map { it.label })
        target.adapter = ScreenWidgets.spinner(this, labels).adapter
        target.setSelection(0)
    }

    /** The server under the picker, or null when the device itself is chosen. */
    private fun selectedServer(): SshStore.Server? =
        if (target.selectedItemPosition == 0) null
        else servers.getOrNull(target.selectedItemPosition - 1)

    private fun refreshPresets() {
        presetRow.removeAllViews()
        val server = selectedServer() ?: return
        val presets = ForwardPresetStore.load(applicationContext)[server.id] ?: return
        for (p in presets) {
            presetRow.addView(
                presetChip("${p.fromPort} → ${p.toHost}:${p.toPort}") {
                    fromPort.setText(String.format(java.util.Locale.ROOT, "%d", p.fromPort))
                    toHost.setText(p.toHost)
                    toPort.setText(String.format(java.util.Locale.ROOT, "%d", p.toPort))
                }
            )
        }
    }

    private fun presetChip(text: String, onTap: () -> Unit): View {
        val d = resources.displayMetrics.density
        val color = accentColor()
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(color)
            setBackgroundColor(withAlpha(color, 0.18f))
            val px = (10 * d).toInt()
            val py = (4 * d).toInt()
            setPadding(px, py, px, py)
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap() }
            // FlowLayout spaces its children itself, so the chip carries no margins.
            layoutParams = FlowLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun savePreset() {
        val server = selectedServer()
        if (server == null) {
            status.text = getString(R.string.lpf_preset_server_only)
            return
        }
        val from = fromPort.text.toString().toIntOrNull()
        val to = toPort.text.toString().toIntOrNull()
        if (from == null || to == null || from !in 1..65535 || to !in 1..65535) {
            status.text = getString(R.string.lpf_failed, from ?: 0)
            return
        }
        val host = toHost.text.toString().trim().ifEmpty { "127.0.0.1" }
        ForwardPresetStore.addPreset(
            applicationContext, server.id,
            ForwardPresetStore.Preset(from, host, to)
        )
        status.text = getString(R.string.lpf_preset_saved)
        refreshPresets()
    }

    /**
     * How a forwarded port reaches a machine the user can type on.
     *
     * The forward binds a port on the *device*, so a laptop cannot reach it
     * by address alone; it goes through adb (USB or over the network) or
     * the phone's Wi-Fi address. Naming both, with USB first, is the answer
     * that actually works on a phone that has no public address.
     */
    private fun laptopCard(): View {
        val d = resources.displayMetrics.density
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (10 * d).toInt()
            setPadding(p, p, p, p)
            setBackgroundColor(cardColor())
            layoutParams = spaced()
        }
        card.addView(TextView(this).apply {
            text = getString(R.string.lpf_laptop_title)
            textSize = 12f
            setTextColor(accentColor())
        })
        card.addView(TextView(this).apply {
            text = getString(R.string.lpf_laptop_usb)
            textSize = 11f
            setTextColor(mutedTextColor())
            setPadding(0, (4 * d).toInt(), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = getString(R.string.lpf_laptop_wifi)
            textSize = 11f
            setTextColor(mutedTextColor())
            setPadding(0, (2 * d).toInt(), 0, 0)
        })
        return card
    }

    private fun refreshActive() {
        active.removeAllViews()
        val ids = ForwardManager.activeIds().filter { ForwardManager.isAlive(it) }
        if (ids.isEmpty()) {
            active.addView(TextView(this).apply {
                setText(R.string.lpf_none_active)
                setTextColor(mutedTextColor())
                textSize = 12f
            })
            return
        }
        for (id in ids) active.addView(activeCard(id))
    }

    private fun activeCard(id: String): View {
        val spec = ForwardManager.specFor(id)
        val mapping = spec?.forward ?: ""
        // The origin is what tells a device forward apart from a server one:
        // "This device (guest)" versus the server's own label.
        val origin = when (spec) {
            is ForwardManager.SshSpec -> spec.server.label
            is ForwardManager.LocalSpec -> getString(R.string.lpf_this_device)
            else -> ""
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 10, 10, 10)
            setBackgroundColor(cardColor())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, (6 * resources.displayMetrics.density).toInt())
            }
        }
        card.addView(TextView(this).apply {
            text = origin
            textSize = 11f
            setTextColor(accentColor())
            setPadding(0, 0, 0, (4 * resources.displayMetrics.density).toInt())
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply {
            text = mapping
            textSize = 13f
            setTextColor(cardTextColor())
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        row.addView(ScreenWidgets.dangerButton(this, R.string.lpf_stop) {
            ForwardManager.stop(applicationContext, id)
            refreshActive()
        })
        card.addView(row)
        return card
    }

    private fun addForward() {
        val from = fromPort.text.toString().toIntOrNull()
        val to = toPort.text.toString().toIntOrNull()
        if (from == null || to == null || from !in 1..65535 || to !in 1..65535) {
            status.text = getString(R.string.lpf_failed, from ?: 0)
            return
        }
        // The picker's first entry is "This device" and the rest are the saved
        // servers, so position 0 is local and position N is servers[N - 1].
        val pos = target.selectedItemPosition
        val server = if (pos == 0) null else servers.getOrNull(pos - 1)
        val id = if (server == null) {
            ForwardManager.start(
                this,
                ForwardManager.LocalSpec(from, "127.0.0.1", to)
            )
        } else {
            val host = toHost.text.toString().trim().ifEmpty { "127.0.0.1" }
            ForwardManager.start(
                this,
                ForwardManager.SshSpec(
                    from, host, to, server,
                    SshKeyStore.identitiesFor(applicationContext, server)
                )
            )
        }
        if (id == null) {
            status.text = getString(R.string.lpf_failed, from)
        } else {
            fromPort.text = null
            toPort.text = null
            toHost.text = null
            status.text = ""
            refreshActive()
        }
    }

    /** Full-width, with a top gap so form fields don't crowd the line above. */
    private fun spaced() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() }

    private fun portField(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        textSize = 14f
        // Room below the text so the hint sits above the field's underline,
        // not on it. Scaled by density: a raw pixel value is only a couple of
        // dp on a phone, which is what left the text crowding the line.
        val d = resources.displayMetrics.density
        setPadding(0, (8 * d).toInt(), 0, (18 * d).toInt())
        setTextColor(cardTextColor())
        setHintTextColor(mutedTextColor())
    }

    private fun hostField(): EditText = EditText(this).apply {
        hint = getString(R.string.lpf_to_host)
        textSize = 14f
        val d = resources.displayMetrics.density
        setPadding(0, (8 * d).toInt(), 0, (18 * d).toInt())
        setTextColor(cardTextColor())
        setHintTextColor(mutedTextColor())
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0)
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
