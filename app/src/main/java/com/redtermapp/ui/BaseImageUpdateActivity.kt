package com.redtermapp.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.redtermapp.R
import com.redtermapp.distro.BaseImageUpdate
import com.redtermapp.distro.DistroInstaller
import com.redtermapp.distro.DistroRegistry
import com.redtermapp.util.Notifier

/**
 * Checks each installed distro against the newest published base image and
 * offers to swap it in, keeping /root and /home.
 */
class BaseImageUpdateActivity : AppCompatActivity() {

    private companion object {
        const val NOTIFICATION_ID = 4203
    }

    private lateinit var container: LinearLayout
    private lateinit var header: TextView
    private val installer by lazy { DistroInstaller(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            setText(R.string.base_image_update)
            textSize = 20f
        })
        header = TextView(this).apply {
            textSize = 12f
            setPadding(0, 6, 0, 10)
        }
        root.addView(header)
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            container,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        ScreenToolbar.install(this, root, getString(R.string.base_image_update))
        setContentView(root)
        Notifier.ensureChannel(this)
        load()
    }

    private fun load() {
        container.removeAllViews()
        container.addView(ProgressBar(this))
        header.text = ""
        Thread({
            val names = installer.getInstalledDistros()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                container.removeAllViews()
                if (names.isEmpty()) {
                    container.addView(TextView(this).apply { setText(R.string.no_distros_installed) })
                    return@runOnUiThread
                }
                for (name in names) {
                    container.addView(distroCard(name))
                }
            }
        }, "redterm-base-list").start()
    }

    private fun distroCard(name: String): View {
        val updater = BaseImageUpdate(this)
        val installedAsset = updater.installedAsset(name)
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 12) }
            setCardBackgroundColor(tc(R.attr.extraKeysBg, 0xFF181825.toInt()))
            radius = 12f
            isClickable = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 20, 24, 20)
                addView(TextView(context).apply {
                    text = name.replaceFirstChar { it.uppercase() }
                    setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                    textSize = 18f
                    setMinimumWidth(dp(140))
                })
                addView(TextView(context).apply {
                    id = View.generateViewId()
                    text = getString(
                        R.string.installed_image_format,
                        installedAsset.ifBlank { getString(R.string.unknown) }
                    )
                    setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                    textSize = 12f
                    alpha = 0.7f
                    setPadding(0, 4, 0, 10)
                })
                addView(TextView(context).apply {
                    text = getString(R.string.check_for_update)
                    setTextColor(tc(androidx.appcompat.R.attr.colorPrimary, 0xFF89B4FA.toInt()))
                    textSize = 14f
                    setPadding(0, 6, 0, 0)
                    setOnClickListener { check(name, this) }
                })
            })
        }
        return card
    }

    private fun check(distroName: String, status: TextView) {
        status.text = getString(R.string.checking)
        status.setOnClickListener(null)
        Thread({
            val comparison = try {
                BaseImageUpdate(applicationContext).latestRelease(distroName)
            } catch (e: Exception) {
                BaseImageUpdate.Comparison(
                    BaseImageUpdate(applicationContext).installedAsset(distroName),
                    null,
                    e.message ?: e.javaClass.simpleName
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    comparison.error != null -> {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.base_image_update)
                            .setMessage(getString(R.string.update_check_failed, comparison.error))
                            .setPositiveButton(R.string.ok, null)
                            .show()
                    }
                    comparison.latest == null -> {
                        status.text = getString(R.string.check_failed_again)
                        status.setOnClickListener { check(distroName, status) }
                        AlertDialog.Builder(this)
                            .setTitle(R.string.base_image_update)
                            .setMessage(
                                getString(
                                    R.string.update_up_to_date,
                                    comparison.installedTag.ifBlank { getString(R.string.unknown) }
                                )
                            )
                            .setPositiveButton(R.string.ok, null)
                            .show()
                    }
                    comparison.hasUpdate -> {
                        status.text = getString(R.string.update_available, comparison.latest.assetName)
                        confirm(distroName, comparison.latest)
                        status.setOnClickListener { check(distroName, status) }
                    }
                    else -> {
                        status.text = getString(R.string.update_up_to_date, comparison.latest.assetName)
                        status.setOnClickListener { check(distroName, status) }
                        AlertDialog.Builder(this)
                            .setTitle(R.string.base_image_update)
                            .setMessage(getString(R.string.update_up_to_date, comparison.latest.assetName))
                            .setPositiveButton(R.string.ok, null)
                            .show()
                    }
                }
            }
        }, "redterm-base-check").start()
    }

    private fun confirm(distroName: String, release: BaseImageUpdate.Release) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available, release.assetName))
            .setMessage(getString(R.string.update_base_confirm_message, distroName, release.assetName))
            .setPositiveButton(R.string.update) { _, _ -> run(distroName, release) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun run(distroName: String, release: BaseImageUpdate.Release) {
        val distro = DistroRegistry.allDistros.firstOrNull { it.name == distroName }
        if (distro == null) {
            Toast.makeText(this, R.string.distro_not_installed, Toast.LENGTH_SHORT).show()
            return
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val progress = TextView(this).apply {
            text = getString(R.string.update_base_downloading, release.assetName)
            setPadding(pad, pad, pad, pad)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.base_image_update)
            .setView(progress)
            .setCancelable(false)
            .create()
        dialog.show()
        Notifier.notify(
            this, NOTIFICATION_ID, getString(R.string.base_image_update),
            progress.text.toString(), ongoing = true
        )

        Thread({
            val context = applicationContext
            val result = kotlinx.coroutines.runBlocking {
                BaseImageUpdate(context).applyUpdate(distro, release) { line ->
                    runOnUiThread { progress.text = line }
                }
            }
            runOnUiThread {
                dialog.dismiss()
                val message = if (result.succeeded) getString(R.string.update_base_done)
                else getString(R.string.update_base_failed, result.error ?: "")
                Notifier.notify(
                    this@BaseImageUpdateActivity, NOTIFICATION_ID,
                    getString(R.string.base_image_update), message
                )
                AlertDialog.Builder(this)
                    .setTitle(R.string.base_image_update)
                    .setMessage(message)
                    .setPositiveButton(R.string.ok, null)
                    .show()
                load()
            }
        }, "redterm-base-update").start()
    }

    private fun tc(attr: Int, default: Int): Int {
        val ta = theme.obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, default)
        ta.recycle()
        return c
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

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
