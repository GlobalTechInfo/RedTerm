package com.redtermapp.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.redtermapp.R
import com.redtermapp.distro.Distro
import com.redtermapp.distro.DistroInstaller
import com.redtermapp.distro.DistroRegistry
import com.redtermapp.proot.ProotInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WelcomeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SELECT_ONLY = "select_only"
    }

    private val installer by lazy { DistroInstaller(applicationContext) }
    private var selectedDistro: Distro? = null
    private var selectedCard: MaterialCardView? = null

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

        val selectOnly = intent?.getBooleanExtra(EXTRA_SELECT_ONLY, false) ?: false

        if (!selectOnly && hasInstalledDistro()) {
            navigateToMain()
            return
        }

        if (!ProotInstaller.isInstalled(this)) {
            lifecycleScope.launch {
                ProotInstaller.install(this@WelcomeActivity)
            }
        }

        val abi = android.os.Build.SUPPORTED_64_BIT_ABIS.firstOrNull()
            ?: android.os.Build.SUPPORTED_32_BIT_ABIS.firstOrNull()
            ?: "arm64-v8a"

        installer.setDeviceAbi(abi)

        val distroList = findViewById<LinearLayout>(R.id.distro_list)
        val installButton = findViewById<Button>(R.id.install_button)

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
                val card = createDistroCard(distro) {
                    selectedCard?.setCardBackgroundColor(tc(R.attr.extraKeysBg, 0xFF181825.toInt()))
                    selectedCard?.strokeWidth = 0
                    selectedDistro = distro
                    installButton.isEnabled = true
                    it.setCardBackgroundColor(tc(R.attr.terminalBg, 0xFF313244.toInt()))
                    it.strokeWidth = 4
                    it.strokeColor = 0xFF89B4FA.toInt()
                    selectedCard = it
                }
                distroList.addView(card)
            }
        }

        installButton.setOnClickListener {
            val distro = selectedDistro ?: return@setOnClickListener
            installDistro(distro)
        }
    }

    private fun createDistroCard(distro: Distro, onClick: (MaterialCardView) -> Unit): MaterialCardView {
        return MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 12) }
            setCardBackgroundColor(tc(R.attr.extraKeysBg, 0xFF181825.toInt()))
            radius = 12f
            strokeWidth = 0
            setOnClickListener {
                onClick(this)
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                addView(TextView(context).apply {
                    text = distro.displayName
                    setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                    textSize = 18f
                    isAllCaps = false
                })
                addView(TextView(context).apply {
                    text = "${distro.description}\n~${distro.installSizeMb}MB after install"
                    setTextColor(0xFF6C7086.toInt())
                    textSize = 14f
                })
            })
        }
    }

    private fun installDistro(distro: Distro) {
        val installButton = findViewById<Button>(R.id.install_button)
        installButton.isEnabled = false
        installButton.text = "Installing..."

        lifecycleScope.launch(Dispatchers.Main) {
            try {
                installer.install(distro) { progress ->
                    runOnUiThread {
                        try {
                            installButton.text = "${progress.percent}% - ${progress.speed}"
                        } catch (_: Exception) {}
                    }
                }
                navigateToMain()
            } catch (e: Throwable) {
                try {
                    installButton.text = "Install failed: ${e.message}"
                    installButton.isEnabled = true
                } catch (_: Exception) {}
            }
        }
    }

    private fun hasInstalledDistro(): Boolean {
        return installer.getInstalledDistros().isNotEmpty()
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun applyTheme() {
        val theme = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .getString("theme", "default")
        when (theme) {
            "green" -> setTheme(R.style.Theme_RedTermApp_Green)
            "light" -> setTheme(R.style.Theme_RedTermApp_Light)
            else -> setTheme(R.style.Theme_RedTermApp)
        }
    }
}