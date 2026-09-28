package com.redtermapp.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.view.updateLayoutParams
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.redtermapp.R
import com.redtermapp.distro.Distro
import com.redtermapp.distro.DistroInstaller
import com.redtermapp.distro.DistroRegistry
import com.redtermapp.proot.ProotInstaller
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class WelcomeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SELECT_ONLY = "select_only"
    }

    private val installer by lazy { DistroInstaller(applicationContext) }
    private var selectedDistro: Distro? = null
    private var selectedCard: MaterialCardView? = null
    private var installJob: Job? = null
    private var isInstalling = false

    private var latestError: Throwable? = null

    private lateinit var distroList: LinearLayout
    private lateinit var installButton: Button
    private lateinit var cancelButton: Button
    private lateinit var retryButton: Button
    private lateinit var progressGroup: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar

    private val distroCardMap = mutableMapOf<String, MaterialCardView>()
    private val distroStatusText = mutableMapOf<String, TextView>()

    private fun tc(attr: Int, default: Int): Int {
        val ta = theme.obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, default)
        ta.recycle()
        return c
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        val prefs = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        if (com.redtermapp.util.AppLock.isUnlocked(prefs)) {
            finishSetup()
        } else {
            com.redtermapp.util.AppLock.requireUnlock(this, prefs) { finishSetup() }
        }
    }

    private fun finishSetup() {
        val selectOnly = intent?.getBooleanExtra(EXTRA_SELECT_ONLY, false) ?: false

        if (!selectOnly && hasInstalledDistro()) {
            navigateToMain()
            return
        }

        distroList = findViewById(R.id.distro_list)
        installButton = findViewById(R.id.install_button)
        cancelButton = findViewById(R.id.cancel_button)
        retryButton = findViewById(R.id.retry_button)
        progressGroup = findViewById(R.id.progress_group)
        progressText = findViewById(R.id.progress_text)
        progressBar = findViewById(R.id.progress_bar)

        findViewById<android.view.View>(R.id.welcome_home).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        findViewById<android.view.View>(R.id.welcome_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        if (!ProotInstaller.isInstalled(this)) {
            lifecycleScope.launch {
                val ok = ProotInstaller.install(this@WelcomeActivity)
                if (!ok) {
                    installButton.isEnabled = false
                    installButton.text = getString(R.string.proot_extraction_failed)
                }
            }
        }

        val abi = android.os.Build.SUPPORTED_64_BIT_ABIS.firstOrNull()
            ?: android.os.Build.SUPPORTED_32_BIT_ABIS.firstOrNull()
            ?: "arm64-v8a"

        installer.setDeviceAbi(abi)

        applyHeaderMetricsForOrientation()
        rebuildDistroCards()

        installButton.setOnClickListener {
            val distro = selectedDistro ?: return@setOnClickListener
            startInstall(distro)
        }

        cancelButton.setOnClickListener {
            cancelInstall()
        }

        retryButton.setOnClickListener {
            val distro = selectedDistro ?: return@setOnClickListener
            latestError = null
            startInstall(distro)
        }
    }

    private fun refreshDistroStates() {
        for (distro in DistroRegistry.allDistros) {
            val card = distroCardMap[distro.name] ?: continue
            val statusTv = distroStatusText[distro.name] ?: continue
            if (installer.isInstalled(distro.name)) {
                statusTv.text = getString(R.string.launch_chevron)
                statusTv.setTextColor(0xFF89B4FA.toInt())
                statusTv.textSize = 18f
                statusTv.setPadding(12, 4, 12, 4)
            } else {
                statusTv.text = ""
            }
        }
    }

    /**
     * How many distro cards to place side by side.
     *
     * Portrait keeps one per row, which already worked. Landscape phones are short,
     * so a single full-width card left barely one name on screen once the title,
     * subtitle and install button had taken their share, and two or three columns fit
     * several cards in the same height.
     */
    private fun columnsForScreen(): Int {
        val landscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (!landscape) return 1
        return if (resources.configuration.smallestScreenWidthDp >= 600) 3 else 2
    }

    private fun createDistroCard(distro: Distro, columns: Int = 1): MaterialCardView {
        val compact = columns > 1
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                if (compact) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                if (compact) 1f else 0f
            ).apply { setMargins(0, 0, if (compact) 6 else 0, 12) }
            setCardBackgroundColor(tc(R.attr.extraKeysBg, 0xFF181825.toInt()))
            radius = 12f
            strokeWidth = 0
            setOnClickListener { v ->
                if (isInstalling) return@setOnClickListener
                if (installer.isInstalled(distro.name)) {
                    TerminalActivity.launch(this@WelcomeActivity, distro.name)
                    return@setOnClickListener
                }
                selectedCard?.setCardBackgroundColor(tc(R.attr.extraKeysBg, 0xFF181825.toInt()))
                selectedCard?.strokeWidth = 0
                selectedDistro = distro
                installButton.isEnabled = true
                installButton.text = getString(R.string.install)
                installButton.visibility = android.view.View.VISIBLE
                retryButton.visibility = android.view.View.GONE
                cancelButton.visibility = android.view.View.GONE
                val card = v as MaterialCardView
                card.setCardBackgroundColor(tc(R.attr.terminalBg, 0xFF313244.toInt()))
                card.strokeWidth = 4
                card.strokeColor = 0xFF89B4FA.toInt()
                selectedCard = card
            }
            setOnLongClickListener {
                if (installer.isInstalled(distro.name)) {
                    showDeleteDialog(distro)
                    true
                } else {
                    false
                }
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = if (compact) 14 else 24
                setPadding(pad, pad, pad, pad)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(TextView(context).apply {
                        text = distro.displayName
                        setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                        textSize = 18f
                        isAllCaps = false
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    })
                    addView(TextView(context).apply {
                        text = ""
                        textSize = 18f
                        id = android.R.id.text1
                    }.also { distroStatusText[distro.name] = it })
                })
                addView(TextView(context).apply {
                    text = getString(R.string.distro_description_format, distro.description, distro.packageManager)
                    setTextColor(0xFF6C7086.toInt())
                    textSize = if (compact) 12f else 14f
                    // Bounded so a long description can never grow the card past the
                    // visible area. The full text is still readable on a single column,
                    // which is what portrait uses.
                    if (compact) {
                        maxLines = 3
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                })
            })
        }
        return card
    }

    private fun showDeleteDialog(distro: Distro) {
        AlertDialog.Builder(this)
            .setTitle(distro.displayName)
            .setMessage("Delete this distro?")
            .setPositiveButton("Delete") { _, _ ->
                installer.uninstallAsync(distro.name) {
                    runOnUiThread {
                        refreshDistroStates()
                        selectedCard?.setCardBackgroundColor(tc(R.attr.extraKeysBg, 0xFF181825.toInt()))
                        selectedCard?.strokeWidth = 0
                        selectedCard = null
                        selectedDistro = null
                        installButton.isEnabled = false
                        installButton.text = getString(R.string.install)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "?"
        return DistroInstaller.formatSize(bytes)
    }

    private fun startInstall(distro: Distro) {
        if (isInstalling) {
            Toast.makeText(this, "Already installing...", Toast.LENGTH_SHORT).show()
            return
        }

        val space = installer.spaceFor(distro)
        if (!space.sufficient) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.not_enough_space_title, distro.displayName))
                .setMessage(
                    getString(
                        R.string.not_enough_space,
                        formatBytes(space.requiredBytes),
                        formatBytes(space.availableBytes)
                    )
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        installer.prepareForInstall(distro.name)
        refreshDistroStates()

        isInstalling = true
        latestError = null
        selectedDistro = distro
        selectedCard?.setCardBackgroundColor(tc(R.attr.extraKeysBg, 0xFF181825.toInt()))
        selectedCard?.strokeWidth = 0
        selectedCard = null

        installButton.isEnabled = false
        installButton.visibility = android.view.View.GONE
        retryButton.visibility = android.view.View.GONE
        cancelButton.visibility = android.view.View.VISIBLE
        progressGroup.visibility = android.view.View.VISIBLE
        progressText.text = getString(R.string.installing_format, distro.displayName)
        progressBar.progress = 0

        installJob = lifecycleScope.launch {
            try {
                installer.install(distro) { progress ->
                    runOnUiThread {
                        try {
                            progressBar.progress = progress.percent
                            progressText.text = getString(R.string.install_progress_format, progress.percent, progress.speed)
                        } catch (_: Exception) {}
                    }
                }
                runOnUiThread {
                    isInstalling = false
                    progressGroup.visibility = android.view.View.GONE
                    cancelButton.visibility = android.view.View.GONE
                    refreshDistroStates()
                    Toast.makeText(this@WelcomeActivity, getString(R.string.distro_installed_format, distro.displayName), Toast.LENGTH_SHORT).show()
                    navigateToMain()
                }
            } catch (e: DistroInstaller.CancelledException) {
                runOnUiThread {
                    isInstalling = false
                    progressGroup.visibility = android.view.View.GONE
                    cancelButton.visibility = android.view.View.GONE
                    installButton.visibility = android.view.View.VISIBLE
                    installButton.isEnabled = false
                    installButton.text = getString(R.string.install)
                    refreshDistroStates()
                    Toast.makeText(this@WelcomeActivity, "Installation cancelled", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    isInstalling = false
                    latestError = e
                    cancelButton.visibility = android.view.View.GONE
                    installButton.visibility = android.view.View.GONE
                    retryButton.visibility = android.view.View.VISIBLE
                    retryButton.text = getString(R.string.retry)
                    progressGroup.visibility = android.view.View.VISIBLE
                    progressBar.visibility = android.view.View.GONE
                    val fullMsg = e.message ?: "Unknown error"
                    progressText.text = if (installer.hasPartialDownload(distro.name)) {
                        getString(R.string.install_failed_resume, fullMsg)
                    } else {
                        getString(R.string.install_failed_format, fullMsg)
                    }
                    progressText.setTextColor(0xFFFF6B6B.toInt())
                    android.util.Log.e("WelcomeActivity", "Install failed", e)
                }
            }
        }
    }

    private fun cancelInstall() {
        if (!isInstalling) return
        installer.cancel()
        installJob?.cancel()
        isInstalling = false
        progressGroup.visibility = android.view.View.GONE
        cancelButton.visibility = android.view.View.GONE
        installButton.visibility = android.view.View.VISIBLE
        installButton.isEnabled = false
        installButton.text = getString(R.string.install)
        Toast.makeText(this, "Cancelling...", Toast.LENGTH_SHORT).show()
    }

    private fun hasInstalledDistro(): Boolean {
        return installer.getInstalledDistros().isNotEmpty()
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    /**
     * WelcomeActivity handles rotation itself so that an in-progress install is not
     * killed, which means it is not recreated and the card rows are not rebuilt. Without
     * this the screen kept the portrait layout after rotating, leaving a single column
     * squeezed into a narrow strip.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyHeaderMetricsForOrientation()
        rebuildDistroCards()
    }

    /**
     * The header is collapsed in landscape.
     *
     * This used to rely on a values-land override of the header dimensions. But
     * WelcomeActivity handles rotation itself so an in-progress install survives, which
     * means it is not recreated, and the collapsed values were not reliably applied: the
     * header stayed tall, the distro list was left a narrow strip, and only one card's
     * name was visible. Applying the metrics directly, from the configuration the
     * activity actually has, does not depend on the resource system re-resolving.
     */
    private fun applyHeaderMetricsForOrientation() {
        val landscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val title = findViewById<android.widget.TextView>(R.id.title)
        val home = findViewById<android.widget.TextView>(R.id.welcome_home)
        val settings = findViewById<android.widget.TextView>(R.id.welcome_settings)
        val subtitle = findViewById<android.widget.TextView>(R.id.subtitle)
        val scroll = findViewById<android.view.View>(R.id.distro_scroll)
        title.setTextSize(if (landscape) 20f else 36f)
        title.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
            topMargin = dp(if (landscape) 2 else 48)
        }
        for (v in listOf(home, settings)) {
            v.setTextSize(if (landscape) 12f else 14f)
            v.setPadding(dp(8), dp(4), dp(8), dp(4))
            v.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                topMargin = dp(if (landscape) 0 else 16)
            }
        }
        subtitle.setTextSize(if (landscape) 12f else 16f)
        subtitle.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
            topMargin = dp(if (landscape) 0 else 8)
        }
        scroll.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
            topMargin = dp(if (landscape) 2 else 24)
        }
    }

    private fun rebuildDistroCards() {
        val abi = android.os.Build.SUPPORTED_64_BIT_ABIS.firstOrNull()
            ?: android.os.Build.SUPPORTED_32_BIT_ABIS.firstOrNull()
            ?: "arm64-v8a"
        val available = DistroRegistry.forDevice(abi)
    if (available.isEmpty()) {
        distroList.addView(TextView(this).apply {
        text = getString(R.string.no_distributions_for_arch, abi)
        setTextColor(0xFFFF6B6B.toInt())
        textSize = 14f
        setPadding(16, 16, 16, 16)
        })
    } else {
        // A landscape phone is short, so one full-width card at a time left only
        // a strip of the list on screen: the card was tall, the title, subtitle
        // and install button took the rest, and barely one name was visible.
        // Side-by-side columns fit several cards in the same height. Portrait is
        // left at one column, which was already right.
        val columns = columnsForScreen()
        var row: LinearLayout? = null
        for (distro in available) {
        val card = createDistroCard(distro, columns)
        distroCardMap[distro.name] = card
        if (columns == 1) {
            distroList.addView(card)
        } else {
            if (row == null || row.childCount == columns) {
            row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                // Measured by content. Left at the default this could
                // inherit a height that clipped the description text, and a
                // landscape phone has far less room than portrait.
                layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            distroList.addView(row)
            }
            row.addView(card)
        }
        }
    }
    refreshDistroStates()
    }

    override fun onResume() {
        super.onResume()
        refreshDistroStates()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only a real departure cancels the download. onDestroy also runs when the
        // activity is recreated by a configuration change, and cancelling there aborted
        // the install and reported "installation cancelled" even though the user had
        // cancelled nothing - rotating the phone mid-install was enough. isFinishing is
        // false for a configuration-change recreation and true when the user leaves.
        // The real cancellation used to come from lifecycleScope: installJob is launched
        // there, and the scope dies with the activity, so a rotation stopped the download
        // even though onDestroy deliberately did not cancel it. WelcomeActivity now
        // handles the configuration change itself, so it is not destroyed on rotation and
        // the job survives. This guard remains as the genuine "user left" case.
        if (isInstalling && isFinishing) {
            installer.cancel()
            installJob?.cancel()
        }
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
