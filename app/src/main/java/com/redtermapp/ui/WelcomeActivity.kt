package com.redtermapp.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

        requestSetupPermissions()

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

        val available = DistroRegistry.forDevice(abi)
        if (available.isEmpty()) {
            distroList.addView(TextView(this).apply {
                text = "No distributions available for your device architecture ($abi)"
                setTextColor(0xFFFF6B6B.toInt())
                textSize = 14f
                setPadding(16, 16, 16, 16)
            })
        } else {
            for (distro in available) {
                val card = createDistroCard(distro)
                distroCardMap[distro.name] = card
                distroList.addView(card)
            }
        }
        refreshDistroStates()

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

    private fun requestSetupPermissions() {
        val toAsk = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toAsk.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                toAsk.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (toAsk.isNotEmpty()) {
            requestPermissions(toAsk.toTypedArray(), 1001)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun refreshDistroStates() {
        for (distro in DistroRegistry.allDistros) {
            val card = distroCardMap[distro.name] ?: continue
            val statusTv = distroStatusText[distro.name] ?: continue
            if (installer.isInstalled(distro.name)) {
                statusTv.text = "Launch \u203A"
                statusTv.setTextColor(0xFF89B4FA.toInt())
                statusTv.textSize = 18f
                statusTv.setPadding(12, 4, 12, 4)
            } else {
                statusTv.text = ""
            }
        }
    }

    private fun createDistroCard(distro: Distro): MaterialCardView {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 12) }
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
                setPadding(24, 24, 24, 24)
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
                    text = "${distro.description}\nPackage manager: ${distro.packageManager}"
                    setTextColor(0xFF6C7086.toInt())
                    textSize = 14f
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
                installer.uninstall(distro.name)
                refreshDistroStates()
                selectedCard?.setCardBackgroundColor(tc(R.attr.extraKeysBg, 0xFF181825.toInt()))
                selectedCard?.strokeWidth = 0
                selectedCard = null
                selectedDistro = null
                installButton.isEnabled = false
                installButton.text = getString(R.string.install)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startInstall(distro: Distro) {
        if (isInstalling) {
            Toast.makeText(this, "Already installing...", Toast.LENGTH_SHORT).show()
            return
        }

        installer.uninstall(distro.name)
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
        progressText.text = "Installing ${distro.displayName}..."
        progressBar.progress = 0

        installJob = lifecycleScope.launch {
            try {
                installer.install(distro) { progress ->
                    runOnUiThread {
                        try {
                            progressBar.progress = progress.percent
                            progressText.text = "${progress.percent}% - ${progress.speed}"
                        } catch (_: Exception) {}
                    }
                }
                runOnUiThread {
                    isInstalling = false
                    progressGroup.visibility = android.view.View.GONE
                    cancelButton.visibility = android.view.View.GONE
                    refreshDistroStates()
                    Toast.makeText(this@WelcomeActivity, "${distro.displayName} installed", Toast.LENGTH_SHORT).show()
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
                    retryButton.text = "Retry"
                    progressGroup.visibility = android.view.View.VISIBLE
                    progressBar.visibility = android.view.View.GONE
                    val fullMsg = e.message ?: "Unknown error"
                    progressText.text = "Install failed:\n$fullMsg"
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

    override fun onResume() {
        super.onResume()
        refreshDistroStates()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isInstalling) {
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
