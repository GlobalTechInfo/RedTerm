package com.redtermapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.redtermapp.R
import com.redtermapp.distro.DistroInstaller
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class BashTemplatesActivity : AppCompatActivity() {

    private val installer by lazy { DistroInstaller(applicationContext) }

    private data class Template(val id: String, val name: String, val desc: String)

    private data class CustomTemplate(val name: String, val content: String)

    private val templates = listOf(
        Template("default", "Stock default", "Classic prompt with failure bell, colors and safe aliases"),
        Template("powerline", "Powerline", "Two-line prompt: git branch, exit status, clock, bold colors"),
        Template("minimal", "Minimal", "Single line with a green/red status marker"),
        Template("devops", "DevOps", "Git branch + dirty flag in prompt, full git/docker/kubectl alias set"),
        Template("hacker", "Hacker", "Green-on-black framed two-line prompt with OK/FAIL status"),
        Template("starship", "Starship", "Segmented two-line prompt: dir, git branch, clock, live status"),
        Template("matrix", "Matrix", "Green-on-black with date, clock, git branch and +/- status"),
        Template("retro", "Retro CRT", "Boot banner plus green console prompt with date and time"),
        Template("redterm", "RedTerm", "RedTerm-branded power-user prompt with aliases, functions, and colored status")
    )

    private val prefs by lazy { getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }

    private fun loadCustom(): List<CustomTemplate> {
        val raw = prefs.getString("custom_bash_templates", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CustomTemplate(o.getString("name"), o.getString("content"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveCustom(list: List<CustomTemplate>) {
        val arr = JSONArray()
        for (t in list) {
            arr.put(JSONObject().put("name", t.name).put("content", t.content))
        }
        prefs.edit().putString("custom_bash_templates", arr.toString()).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bash_templates)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.bt_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_back_chip)
        supportActionBar?.title = "Bash templates"

        findViewById<TextView>(R.id.bt_reset_btn).setOnClickListener { pickDistros("Reset to default") { resetToDefault(it) } }

        renderTemplates()
    }

    private fun templateName(id: String): String {
        if (id.startsWith("custom:")) {
            val name = id.removePrefix("custom:")
            return loadCustom().firstOrNull { it.name == name }?.name ?: "custom (removed)"
        }
        return templates.firstOrNull { it.id == id }?.name ?: id
    }

    private fun renderTemplates() {
        val container = findViewById<LinearLayout>(R.id.bt_list)
        container.removeAllViews()

        val statusParts = mutableListOf<String>()
        val installed = installer.getInstalledDistros()
        for (d in installed) {
            val applied = prefs.getString("bashrc_template_$d", null)
            if (applied != null) {
                statusParts.add("$d → ${templateName(applied)}")
            }
        }
        findViewById<TextView>(R.id.bt_status).text =
            if (statusParts.isEmpty()) "No templates applied yet."
            else "Applied:\n" + statusParts.joinToString("\n")

        for (t in templates) {
            val appliedTo = installed.filter { prefs.getString("bashrc_template_$it", null) == t.id }
            container.addView(templateCard(t.name, t.desc, t.id, null, appliedTo))
        }

        container.addView(TextView(this).apply {
            text = "Custom templates"
            setTextColor(0xFF89B4FA.toInt())
            textSize = 13f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setPadding(0, 12, 0, 8)
        })

        container.addView(MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 12) }
            setCardBackgroundColor(tc(R.attr.terminalBg, 0xFF1E1E2E.toInt()))
            radius = 12f
            setOnClickListener { showCustomDialog() }
            addView(TextView(context).apply {
                text = "＋ Add custom template"
                setTextColor(0xFF89B4FA.toInt())
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 16, 0, 16)
            })
        })

        for (c in loadCustom()) {
            val appliedTo = installed.filter { prefs.getString("bashrc_template_$it", null) == "custom:${c.name}" }
            container.addView(templateCard(c.name, "${c.content.length} chars — tap to apply, ✎ edit, ✕ delete", "custom:${c.name}", c, appliedTo))
        }
    }

    private fun templateCard(
        name: String,
        desc: String,
        id: String,
        custom: CustomTemplate?,
        appliedTo: List<String>
    ): MaterialCardView = MaterialCardView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 12) }
        setCardBackgroundColor(tc(R.attr.extraKeysBg, 0xFF181825.toInt()))
        radius = 12f
        setOnClickListener {
            if (custom != null) {
                pickDistros("Apply '${custom.name}'") { applyContent("custom:${custom.name}", custom.name, custom.content, it) }
            } else {
                pickDistros("Apply '$name'") { applyTemplate(id, it) }
            }
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 12)
            addView(TextView(context).apply {
                text = name
                setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                textSize = 16f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            })
            addView(TextView(context).apply {
                text = desc
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
            if (custom != null) {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 8, 0, 0)
                    addView(TextView(context).apply {
                        text = "Edit"
                        setTextColor(0xFF89B4FA.toInt())
                        textSize = 12f
                        setPadding(0, 8, 20, 8)
                        setOnClickListener { showCustomDialog(custom) }
                    })
                    addView(TextView(context).apply {
                        text = "Delete"
                        setTextColor(0xFFFF6B6B.toInt())
                        textSize = 12f
                        setPadding(0, 8, 0, 8)
                        setOnClickListener {
                            android.app.AlertDialog.Builder(this@BashTemplatesActivity)
                                .setTitle("Delete '${custom.name}'?")
                                .setMessage("Distros using this template will be reset to their original .bashrc.")
                                .setPositiveButton("Delete") { _, _ ->
                                    val affected = installedDistrosForId("custom:${custom.name}")
                                    val list = loadCustom().toMutableList()
                                    list.removeAll { it.name == custom.name }
                                    saveCustom(list)
                                    val editor = prefs.edit()
                                    for (d in affected) {
                                        resetDistroBashrc(d, editor)
                                    }
                                    editor.apply()
                                    renderTemplates()
                                    if (affected.isNotEmpty()) {
                                        Toast.makeText(
                                            this@BashTemplatesActivity,
                                            "Reset ${affected.joinToString(", ")} to default",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    })
                })
            }
        })
    }

    private fun installedDistrosForId(id: String): List<String> =
        installer.getInstalledDistros().filter { prefs.getString("bashrc_template_$it", null) == id }

    private fun showCustomDialog(existing: CustomTemplate? = null) {
        val nameInput = EditText(this).apply {
            hint = "Template name"
            setText(existing?.name ?: "")
            setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
            setHintTextColor(0xFF7F849C.toInt())
            textSize = 14f
            setSingleLine(true)
        }
        val contentInput = EditText(this).apply {
            hint = "Paste your .bashrc content here"
            setText(existing?.content ?: "")
            setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
            setHintTextColor(0xFF7F849C.toInt())
            textSize = 12f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            minLines = 10
            gravity = Gravity.TOP or Gravity.START
            setInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
            addView(nameInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 12) })
            addView(contentInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 12) })
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New custom template" else "Edit '${existing.name}'")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                val content = contentInput.text.toString()
                if (name.isEmpty() || content.isEmpty()) {
                    Toast.makeText(this, "Name and content required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val list = loadCustom().toMutableList()
                if (list.any { it.name.equals(name, ignoreCase = true) && it.name != existing?.name }) {
                    Toast.makeText(this, "A template with that name already exists", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (existing != null) {
                    val idx = list.indexOfFirst { it.name == existing.name }
                    if (idx >= 0) {
                        val oldId = "custom:${existing.name}"
                        list[idx] = CustomTemplate(name, content)
                        val editor = prefs.edit()
                        for (d in installedDistrosForId(oldId)) {
                            editor.putString("bashrc_template_$d", "custom:$name")
                        }
                        editor.apply()
                    }
                } else {
                    list.add(CustomTemplate(name, content))
                }
                saveCustom(list)
                renderTemplates()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyTemplate(id: String, distros: List<String>) {
        val content = try {
            assets.open("bashrc/$id.bashrc").bufferedReader().readText()
        } catch (e: Exception) {
            Toast.makeText(this, "Template not found", Toast.LENGTH_SHORT).show()
            return
        }
        applyContent(id, templates.firstOrNull { it.id == id }?.name ?: id, content, distros)
    }

    private fun applyContent(id: String, name: String, content: String, distros: List<String>) {
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
            editor.putString("bashrc_template_$d", id)
        }
        editor.apply()
        renderTemplates()
        Toast.makeText(this, "Applied '$name' to ${distros.joinToString(", ")}", Toast.LENGTH_LONG).show()
    }

    private fun resetDistroBashrc(d: String, editor: android.content.SharedPreferences.Editor) {
        val rootfs = installer.getRootfsDir(d)
        val bashrc = File(File(rootfs, "root"), ".bashrc")
        val orig = prefs.getString("bashrc_orig_$d", null)
        if (orig != null && orig.isNotEmpty()) {
            bashrc.writeText(orig)
        } else {
            bashrc.delete()
        }
        editor.remove("bashrc_template_$d")
    }

    private fun resetToDefault(distros: List<String>) {
        val editor = prefs.edit()
        for (d in distros) {
            resetDistroBashrc(d, editor)
        }
        editor.apply()
        renderTemplates()
        Toast.makeText(this, "Reset ${distros.joinToString(", ")} to default", Toast.LENGTH_LONG).show()
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
