package com.redtermapp.ui

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.redtermapp.R
import com.redtermapp.distro.DistroInstaller
import com.redtermapp.util.AppLog
import kotlinx.coroutines.launch

/**
 * Install a distro from a tarball the user holds, not from the registry.
 *
 * The registry installs are downloaded and checksummed; this path is the
 * opposite — a local `.tar.xz`, `.tar.gz` or plain `.tar` brought in from any
 * file picker. The compression is read from the file's own magic bytes, never
 * from its extension, because a wrong extension is how a gzip image silently
 * becomes a wall of garbage. The only real gate is that the tar reader
 * accepts it; a file that is not a tarball fails the extraction and says so.
 */
class LocalDistroInstallActivity : AppCompatActivity() {

    private val installer by lazy { DistroInstaller(applicationContext) }

    private lateinit var root: LinearLayout
    private lateinit var sourceSpinner: Spinner
    private lateinit var fileRow: LinearLayout
    private lateinit var fileName: TextView
    private lateinit var urlGroup: LinearLayout
    private lateinit var urlField: EditText
    private lateinit var regGroup: LinearLayout
    private lateinit var refField: EditText
    private lateinit var nameField: EditText
    private lateinit var installButton: com.google.android.material.button.MaterialButton
    private lateinit var progressGroup: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar

    private var chosen: Uri? = null

    // Whether an install is actually running. The install button is gated on this, not on
    // the progress view's visibility, because after a failure the progress area stays up
    // to show the error and the user must be able to tap install again to retry.
    private var installing = false

    private val pickDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        chosen = uri
        fileName.text = getString(R.string.ldi_chosen, displayName(uri))
        nameField.setText(suggestName(uri))
        refreshState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        body.addView(TextView(this).apply {
            text = getString(R.string.ldi_title)
            textSize = 20f
        })
        body.addView(TextView(this).apply {
            text = getString(R.string.ldi_what)
            textSize = 13f
            setTextColor(mutedTextColor())
        })

        body.addView(TextView(this).apply {
            text = getString(R.string.ldi_source_label)
            textSize = 13f
            setTextColor(mutedTextColor())
        })
        sourceSpinner = ScreenWidgets.spinner(this, listOf(
            getString(R.string.ldi_src_file),
            getString(R.string.ldi_src_url),
            getString(R.string.ldi_src_registry)
        ))
        body.addView(sourceSpinner)

        // Source-specific input, one visible at a time.
        fileRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fileRow.addView(ScreenWidgets.actionButton(this, R.string.ldi_choose_file) {
            pickDocument.launch(arrayOf("*/*"))
        })
        fileName = TextView(this).apply {
            text = getString(R.string.ldi_no_file)
            textSize = 13f
            setTextColor(accentColor())
            setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        fileRow.addView(fileName)
        body.addView(fileRow)

        urlGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        urlField = EditText(this).apply {
            hint = getString(R.string.ldi_url_hint)
            setSingleLine(true)
            setTextColor(cardTextColor())
            setHintTextColor(mutedTextColor())
        }
        urlGroup.addView(urlField)
        body.addView(urlGroup)

        regGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        refField = EditText(this).apply {
            hint = getString(R.string.ldi_ref_hint)
            setSingleLine(true)
            setTextColor(cardTextColor())
            setHintTextColor(mutedTextColor())
        }
        regGroup.addView(refField)
        body.addView(regGroup)

        sourceSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    fileRow.visibility = if (position == 0) View.VISIBLE else View.GONE
                    urlGroup.visibility = if (position == 1) View.VISIBLE else View.GONE
                    regGroup.visibility = if (position == 2) View.VISIBLE else View.GONE
                    refreshState()
                }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        // refreshState() is what (dis)enables the install button, and it only ran on a
        // spinner change. So typing a URL or an image reference left the button disabled
        // and the tap did nothing: no log, no error, the coroutine never started. The
        // text fields recompute it on every change.
        val sourceWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refreshState() }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        }
        urlField.addTextChangedListener(sourceWatcher)
        refField.addTextChangedListener(sourceWatcher)

        body.addView(TextView(this).apply {
            text = getString(R.string.ldi_name_label)
            textSize = 13f
            setTextColor(mutedTextColor())
            setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0)
        })
        nameField = EditText(this).apply {
            hint = getString(R.string.ldi_name_hint)
            setSingleLine(true)
            setTextColor(cardTextColor())
            setHintTextColor(mutedTextColor())
        }
        body.addView(nameField)

        installButton = ScreenWidgets.primaryButton(this, R.string.ldi_install) { startInstall() }
        installButton.isEnabled = false
        body.addView(installButton)

        progressGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        progressText = TextView(this).apply {
            textSize = 13f
            setTextColor(mutedTextColor())
        }
        progressGroup.addView(progressBar)
        progressGroup.addView(progressText)
        body.addView(progressGroup)

        val scroll = ScrollView(this).apply { addView(body) }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        ScreenToolbar.install(this, root, getString(R.string.ldi_title))
        setContentView(root)
    }

    private fun refreshState() {
        val ready = when (sourceSpinner.selectedItemPosition) {
            0 -> chosen != null
            1 -> urlField.text.toString().trim().isNotEmpty()
            2 -> refField.text.toString().trim().isNotEmpty()
            else -> false
        }
        installButton.isEnabled = ready && !installing
    }

    /** The source selected in the spinner, or null when its field is empty. */
    private fun currentSource(): DistroInstaller.InstallSource? = when (sourceSpinner.selectedItemPosition) {
        0 -> chosen?.let { DistroInstaller.InstallSource.Local(it) }
        1 -> urlField.text.toString().trim().takeIf { it.isNotEmpty() }
            ?.let { DistroInstaller.InstallSource.Url(it) }
        2 -> refField.text.toString().trim().takeIf { it.isNotEmpty() }
            ?.let { DistroInstaller.InstallSource.Registry(it) }
        else -> null
    }

    private fun startInstall() {
        val source = currentSource()
        if (source == null) {
            Toast.makeText(this, R.string.ldi_source_required, Toast.LENGTH_LONG).show()
            return
        }
        val name = nameField.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.ldi_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        installing = true
        installButton.isEnabled = false
        progressGroup.visibility = View.VISIBLE
        progressBar.progress = 0
        progressText.text = getString(R.string.ldi_starting)
        lifecycleScope.launch {
            try {
                // The install runs on Dispatchers.IO; every progress callback posts back
                // to the UI thread, because a view touched from its owner's thread is the
                // whole thing that used to make a URL install die on its first line.
                installer.installFromSource(name, source) { msg ->
                    runOnUiThread {
                        progressText.text = msg
                        val pct = msg.trimEnd('%').toIntOrNull()
                        if (pct != null && pct in 0..100) progressBar.progress = pct
                    }
                }
                progressBar.progress = 100
                progressText.text = getString(R.string.ldi_done, name)
                AppLog.i(this@LocalDistroInstallActivity, "LocalInstall", "installed $name from ${sourceSummary(source)}")
                Toast.makeText(this@LocalDistroInstallActivity, getString(R.string.ldi_done, name), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                AppLog.e(this@LocalDistroInstallActivity, "LocalInstall", "install failed: ${e::class.java.simpleName}: ${e.message}")
                progressText.text = e.message ?: getString(R.string.ldi_failed)
                Toast.makeText(this@LocalDistroInstallActivity, e.message ?: getString(R.string.ldi_failed), Toast.LENGTH_LONG).show()
            } finally {
                installing = false
                refreshState()
            }
        }
    }

    private fun sourceSummary(s: DistroInstaller.InstallSource): String = when (s) {
        is DistroInstaller.InstallSource.Local -> "a picked file"
        is DistroInstaller.InstallSource.Url -> s.url
        is DistroInstaller.InstallSource.Registry -> "registry image ${s.reference}"
    }

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: uri.toString()
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
        }
        return name
    }

    /** A usable distro key from the file name: strip the archive suffixes. */
    private fun suggestName(uri: Uri): String =
        displayName(uri)
            .removeSuffix(".tar.xz").removeSuffix(".tar.gz").removeSuffix(".tar")
            .removeSuffix(".xz").removeSuffix(".gz")
            .trim()

    private val View.isVisible: Boolean get() = visibility == View.VISIBLE

    private fun applyTheme() {
        val prefs = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
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
