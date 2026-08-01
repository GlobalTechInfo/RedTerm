package com.redtermapp.util

import android.content.SharedPreferences
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

object AppLock {
    private var unlockedAt = 0L

    fun isUnlocked(prefs: SharedPreferences): Boolean {
        if (!prefs.getBoolean("lock_enabled", false)) return true
        return System.currentTimeMillis() - unlockedAt < 120_000
    }

    fun requireUnlock(
        activity: AppCompatActivity,
        prefs: SharedPreferences,
        onDone: () -> Unit
    ) {
        if (isUnlocked(prefs)) {
            onDone()
            return
        }
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter PIN"
            setTextColor(0xFFCDD6F4.toInt())
            setHintTextColor(0x66CDD6F4.toInt())
        }
        AlertDialog.Builder(activity)
            .setTitle("App locked")
            .setMessage("Enter your PIN to unlock RedTerm")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Unlock") { _, _ ->
                if (input.text.toString() == prefs.getString("lock_pin", "")) {
                    unlockedAt = System.currentTimeMillis()
                    onDone()
                } else {
                    Toast.makeText(activity, "Wrong PIN", Toast.LENGTH_SHORT).show()
                    requireUnlock(activity, prefs, onDone)
                }
            }
            .show()
    }

    fun setupPinDialog(
        activity: AppCompatActivity,
        prefs: SharedPreferences,
        onSaved: () -> Unit
    ) {
        val pin1 = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "New PIN (4-8 digits)"
            setTextColor(0xFFCDD6F4.toInt())
            setHintTextColor(0x66CDD6F4.toInt())
        }
        val pin2 = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Confirm PIN"
            setTextColor(0xFFCDD6F4.toInt())
            setHintTextColor(0x66CDD6F4.toInt())
        }
        showPinDialog(activity, prefs, pin1, pin2, onSaved)
    }

    private fun showPinDialog(
        activity: AppCompatActivity,
        prefs: SharedPreferences,
        pin1: EditText,
        pin2: EditText,
        onSaved: () -> Unit
    ) {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val error = TextView(activity).apply {
            setTextColor(0xFFFF6B6B.toInt())
            textSize = 13f
            visibility = android.view.View.GONE
        }
        layout.addView(pin1, lp)
        layout.addView(pin2, lp)
        layout.addView(error, lp)

        AlertDialog.Builder(activity)
            .setTitle("Set app lock PIN")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val p1 = pin1.text.toString()
                val p2 = pin2.text.toString()
                when {
                    p1.length < 4 || p1.length > 8 -> {
                        error.text = "PIN must be 4-8 digits"
                        error.visibility = android.view.View.VISIBLE
                        showPinDialog(activity, prefs, pin1, pin2, onSaved)
                    }
                    p1 != p2 -> {
                        error.text = "PINs do not match"
                        error.visibility = android.view.View.VISIBLE
                        showPinDialog(activity, prefs, pin1, pin2, onSaved)
                    }
                    else -> {
                        prefs.edit()
                            .putBoolean("lock_enabled", true)
                            .putString("lock_pin", p1)
                            .apply()
                        Toast.makeText(activity, "App lock enabled", Toast.LENGTH_SHORT).show()
                        onSaved()
                    }
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                prefs.edit().putBoolean("lock_enabled", false).apply()
                onSaved()
            }
            .show()
    }
}
