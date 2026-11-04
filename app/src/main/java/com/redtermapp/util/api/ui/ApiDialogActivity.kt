package com.redtermapp.util.api.ui

import android.app.Activity
import android.os.Bundle
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The transparent host for a blocking dialog API request. It renders the
 * widget named by `redterm.api.kind` as an AlertDialog and posts the
 * result back to the waiting request thread via [ApiUiBridge]. The
 * Activity's own window is invisible; the dialog is the only thing the
 * user sees, and it is what carries the input.
 *
 * The answer is a JSON value: a string result is quoted, no input is
 * `null`, a confirm is `true`/`false`, and a multi-pick is a
 * comma-joined string of the chosen values.
 */
class ApiDialogActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra("redterm.api.kind") ?: ""
        val title = intent.getStringExtra("redterm.api.title") ?: ""
        val hint = intent.getStringExtra("redterm.api.hint") ?: ""
        val values = (intent.getStringExtra("redterm.api.values") ?: "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val range = intent.getStringExtra("redterm.api.range") ?: ""
        when (kind) {
            "text", "input" -> editText(hint, title)
            "confirm" -> confirm(title, hint)
            "spinner", "radio", "sheet" -> spinner(title, values)
            "checkbox" -> checkbox(title, values)
            "date" -> datePicker(title)
            "time" -> timePicker(title)
            "counter" -> counter(title, range)
            else -> answer("ERROR: unknown dialog kind '$kind'\n")
        }
    }

    private fun editText(hint: String, title: String) {
        val input = EditText(this)
        input.hint = hint
        val padding = (24 * resources.displayMetrics.density).toInt()
        val host = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        val b = MaterialAlertDialogBuilder(this).setView(host)
        if (title.isNotEmpty()) b.setTitle(title)
        b.setPositiveButton(android.R.string.ok) { _, _ ->
            answer("\"" + input.text.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        }
        b.setNegativeButton(android.R.string.cancel) { _, _ -> answer("null") }
        b.setOnCancelListener { answer("null") }
        b.show()
    }

    private fun confirm(title: String, message: String) {
        val b = MaterialAlertDialogBuilder(this).setMessage(message)
        if (title.isNotEmpty()) b.setTitle(title)
        b.setPositiveButton(android.R.string.ok) { _, _ -> answer("true") }
        b.setNegativeButton(android.R.string.cancel) { _, _ -> answer("false") }
        b.setOnCancelListener { answer("false") }
        b.show()
    }

    private fun spinner(title: String, values: List<String>) {
        if (values.isEmpty()) {
            answer("ERROR: no values for the picker\n")
            return
        }
        val arr = values.toTypedArray()
        var selected = 0
        val b = MaterialAlertDialogBuilder(this)
            .setSingleChoiceItems(arr, 0) { _, which -> selected = which }
        if (title.isNotEmpty()) b.setTitle(title)
        b.setPositiveButton(android.R.string.ok) { _, _ -> answer("\"${arr[selected]}\"") }
        b.setNegativeButton(android.R.string.cancel) { _, _ -> answer("null") }
        b.setOnCancelListener { answer("null") }
        b.show()
    }

    private fun checkbox(title: String, values: List<String>) {
        if (values.isEmpty()) {
            answer("ERROR: no values for the picker\n")
            return
        }
        val arr = values.toTypedArray()
        val checked = BooleanArray(arr.size)
        val b = MaterialAlertDialogBuilder(this)
            .setMultiChoiceItems(arr, checked) { _, which, isChecked -> checked[which] = isChecked }
        if (title.isNotEmpty()) b.setTitle(title)
        b.setPositiveButton(android.R.string.ok) { _, _ ->
            val picked = arr.indices.filter { checked[it] }.joinToString(",") { arr[it] }
            answer("\"$picked\"")
        }
        b.setNegativeButton(android.R.string.cancel) { _, _ -> answer("null") }
        b.setOnCancelListener { answer("null") }
        b.show()
    }

    private fun datePicker(title: String) {
        val dp = android.widget.DatePicker(this)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val host = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(dp)
        }
        val b = MaterialAlertDialogBuilder(this).setView(host)
        if (title.isNotEmpty()) b.setTitle(title)
        b.setPositiveButton(android.R.string.ok) { _, _ ->
            answer("\"${dp.year}-${dp.month + 1}-${dp.dayOfMonth}\"")
        }
        b.setNegativeButton(android.R.string.cancel) { _, _ -> answer("null") }
        b.setOnCancelListener { answer("null") }
        b.show()
    }

    private fun timePicker(title: String) {
        val tp = android.widget.TimePicker(this)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val host = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(tp)
        }
        val b = MaterialAlertDialogBuilder(this).setView(host)
        if (title.isNotEmpty()) b.setTitle(title)
        b.setPositiveButton(android.R.string.ok) { _, _ ->
            answer("\"${tp.hour}:${tp.minute}\"")
        }
        b.setNegativeButton(android.R.string.cancel) { _, _ -> answer("null") }
        b.setOnCancelListener { answer("null") }
        b.show()
    }

    private fun counter(title: String, range: String) {
        val parts = range.split(',')
        val min = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val max = parts.getOrNull(1)?.toIntOrNull() ?: 100
        if (min > max) {
            // coerceIn(min, max) below would throw on such a range and
            // kill the process; the answer says so instead.
            answer("ERROR: counter range minimum is greater than maximum\n")
            return
        }
        val start = parts.getOrNull(2)?.toIntOrNull() ?: min
        val input = EditText(this).apply {
            setText(String.format(java.util.Locale.getDefault(), "%d", start))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val host = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        val b = MaterialAlertDialogBuilder(this).setView(host)
        if (title.isNotEmpty()) b.setTitle(title)
        b.setPositiveButton(android.R.string.ok) { _, _ ->
            val v = input.text.toString().toIntOrNull()?.coerceIn(min, max) ?: min
            answer("\"$v\"")
        }
        b.setNegativeButton(android.R.string.cancel) { _, _ -> answer("null") }
        b.setOnCancelListener { answer("null") }
        b.show()
    }

    private fun answer(result: String) {
        ApiUiBridge.postResult(this, result)
        finish()
    }
}
