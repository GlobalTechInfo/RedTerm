package com.redtermapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.redtermapp.R
import com.redtermapp.distro.DistroInstaller
import java.io.File

class BashTemplatesActivity : AppCompatActivity() {

    private val installer by lazy { DistroInstaller(applicationContext) }

    private data class Template(val id: String, val name: String, val desc: String)

    private val templates = listOf(
        Template("default", "Stock default", "Classic prompt with failure bell, colors and safe aliases"),
        Template("powerline", "Powerline", "Two-line prompt: git branch, exit status, clock, bold colors"),
        Template("minimal", "Minimal", "Single line with a green/red status marker"),
        Template("devops", "DevOps", "Git branch + dirty flag in prompt, full git/docker/kubectl alias set"),
        Template("hacker", "Hacker", "Green-on-black framed two-line prompt with OK/FAIL status"),
        Template("starship", "Starship", "Segmented two-line prompt: dir, git branch, clock, live status"),
        Template("matrix", "Matrix", "Green-on-black with date, clock, git branch and +/- status"),
        Template("retro", "Retro CRT", "Boot banner plus green console prompt with date and time")
    )

    private val prefs by lazy { getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bash_templates)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.bt_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Bash templates"

        findViewById<TextView>(R.id.bt_reset_btn).setOnClickListener { pickDistros("Reset to default") { resetToDefault(it) } }

        renderTemplates()
    }

    private fun renderTemplates() {
        val container = findViewById<LinearLayout>(R.id.bt_list)
        container.removeAllViews()

        val statusParts = mutableListOf<String>()
        val installed = installer.getInstalledDistros()
        for (d in installed) {
            val applied = prefs.getString("bashrc_template_$d", null)
            if (applied != null) {
                val t = templates.firstOrNull { it.id == applied }
                statusParts.add("$d → ${t?.name ?: applied}")
            }
        }
        findViewById<TextView>(R.id.bt_status).text =
            if (statusParts.isEmpty()) "No templates applied yet."
            else "Applied:\n" + statusParts.joinToString("\n")

        for (t in templates) {
            val appliedTo = installed.filter { prefs.getString("bashrc_template_$it", null) == t.id }
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 12) }
                setCardBackgroundColor(tc(R.attr.extraKeysBg, 0xFF181825.toInt()))
                radius = 12f
                setOnClickListener { pickDistros("Apply '${t.name}'") { applyTemplate(t, it) } }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(20, 16, 20, 16)
                    addView(TextView(context).apply {
                        text = t.name
                        setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                        textSize = 16f
                        setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                    })
                    addView(TextView(context).apply {
                        text = t.desc
                        setTextColor(0xFF7F849C.toInt())
                        textSize = 12f
                        setPadding(0, 4, 0, 0)
                    })
                    if (appliedTo.isNotEmpty()) {
                        addView(TextView(context).apply {
                            text = "Applied to: ${appliedTo.joinToString(", ")}"
                            setTextColor(0xFFA6E3A1.toInt())
                            textSize = 11f
                            setPadding(0, 8, 0, 0)
                        })
                    }
                })
            }
            container.addView(card)
        }
    }

    private fun pickDistros(action: String, onPicked: (List<String>) -> Unit) {
        val installed = installer.getInstalledDistros()
            .filter { installer.getRootfsDir(it).exists() }
        if (installed.isEmpty()) {
            Toast.makeText(this, "No distros installed", Toast.LENGTH_SHORT).show()
            return
        }
        if (installed.size == 1) {
            onPicked(installed)
            return
        }
        val checked = BooleanArray(installed.size) { true }
        android.app.AlertDialog.Builder(this)
            .setTitle(action)
            .setMultiChoiceItems(installed.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Apply") { _, _ ->
                val picked = installed.filterIndexed { i, _ -> checked[i] }
                if (picked.isEmpty()) {
                    Toast.makeText(this, "No distro selected", Toast.LENGTH_SHORT).show()
                } else {
                    onPicked(picked)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyTemplate(t: Template, distros: List<String>) {
        val content = try {
            assets.open("bashrc/${t.id}.bashrc").bufferedReader().readText()
        } catch (e: Exception) {
            Toast.makeText(this, "Template not found", Toast.LENGTH_SHORT).show()
            return
        }
        val editor = prefs.edit()
        for (d in distros) {
            val rootfs = installer.getRootfsDir(d)
            val rootDir = File(rootfs, "root")
            rootDir.mkdirs()
            val bashrc = File(rootDir, ".bashrc")
            if (!prefs.contains("bashrc_orig_$d")) {
                editor.putString("bashrc_orig_$d", if (bashrc.exists()) bashrc.readText() else "")
            }
            bashrc.writeText(content)
            editor.putString("bashrc_template_$d", t.id)
        }
        editor.apply()
        renderTemplates()
        Toast.makeText(this, "Applied '${t.name}' to ${distros.joinToString(", ")}", Toast.LENGTH_LONG).show()
    }

    private fun resetToDefault(distros: List<String>) {
        val editor = prefs.edit()
        for (d in distros) {
            val rootfs = installer.getRootfsDir(d)
            val bashrc = File(File(rootfs, "root"), ".bashrc")
            val orig = prefs.getString("bashrc_orig_$d", null)
            if (orig != null) {
                bashrc.writeText(orig)
            } else {
                bashrc.delete()
            }
            editor.remove("bashrc_template_$d")
        }
        editor.apply()
        renderTemplates()
        Toast.makeText(this, "Reset ${distros.joinToString(", ")} to default", Toast.LENGTH_LONG).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun tc(attr: Int, default: Int): Int {
        val ta = theme.obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, default)
        ta.recycle()
        return c
    }

    private fun applyTheme() {
        val prefs = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val theme = NightModeReceiver.effectiveTheme(prefs)
        when (theme) {
            "red" -> setTheme(R.style.Theme_RedTermApp_Red)
            "amoled" -> setTheme(R.style.Theme_RedTermApp_AMOLED)
            "green" -> setTheme(R.style.Theme_RedTermApp_Green)
            "light" -> setTheme(R.style.Theme_RedTermApp_Light)
            "dracula" -> setTheme(R.style.Theme_RedTermApp_Dracula)
            "nord" -> setTheme(R.style.Theme_RedTermApp_Nord)
            "tokyo" -> setTheme(R.style.Theme_RedTermApp_Tokyo)
            "gruvbox" -> setTheme(R.style.Theme_RedTermApp_Gruvbox)
            "custom" -> setTheme(R.style.Theme_RedTermApp_Custom)
            else -> setTheme(R.style.Theme_RedTermApp)
        }
    }
}
