package com.redtermapp.ui

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.redtermapp.R

/**
 * The connection settings on the server dialog, below the address.
 *
 * One view rather than four more dialogs, because these only make sense together:
 * a jump host with keepalives off, or compression on a jump connection, is a
 * combination nobody means to choose and everybody gets wrong. Each is still one
 * control, so the whole group still reads as a list of switches rather than a form.
 *
 * Everything here is optional and every default is "off" — a server saved without
 * touching this behaves exactly as it did before any of it existed.
 */
class ServerExtrasView(
    private val context: Context,
    existing: SshStore.Server?
) {

    data class Settings(
        val keepAliveSeconds: Int,
        val keepAliveCount: Int,
        val compress: Boolean,
        val forwardAgent: Boolean,
        val jump: SshStore.Jump?,
        val auth: SshStore.Auth,
        val password: String,
        val passwordChanged: Boolean,
        val verifyHostKey: Boolean,
        val forwards: List<SshStore.Forward>
    )

    val view: View

    private val keepAlive = EditText(context).apply {
        hint = context.getString(R.string.ssh_keepalive_seconds)
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
    }
    private val keepAliveCount = EditText(context).apply {
        hint = context.getString(R.string.ssh_keepalive_count)
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
    }
    private val compress = CheckBox(context).apply {
        setText(R.string.ssh_compress)
        isChecked = existing?.compress == true
    }
    private val forwardAgent = CheckBox(context).apply {
        setText(R.string.ssh_forward_agent)
        isChecked = existing?.forwardAgent == true
    }
    private val verifyHostKey = CheckBox(context).apply {
        setText(R.string.ssh_verify_host_key)
        isChecked = existing?.verifyHostKey == true
    }

    private val authGroup = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
    private val authKeys = RadioButton(context).apply { setText(R.string.ssh_auth_keys) }
    private val authPassword = RadioButton(context).apply { setText(R.string.ssh_auth_password) }
    private val authAny = RadioButton(context).apply { setText(R.string.ssh_auth_any) }
    private val password = EditText(context).apply {
        hint = context.getString(R.string.ssh_password)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setSingleLine(true)
    }
    private var passwordInitiallyBlank = false

    private val jumpEnabled = CheckBox(context).apply {
        setText(R.string.ssh_jump_enabled)
        isChecked = existing?.jump?.usable == true
    }
    private val jumpHost = EditText(context).apply {
        hint = context.getString(R.string.ssh_jump_host)
        setSingleLine(true)
    }
    private val jumpPort = EditText(context).apply {
        hint = context.getString(R.string.ssh_jump_port)
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
    }
    private val jumpUser = EditText(context).apply {
        hint = context.getString(R.string.ssh_jump_user)
        setSingleLine(true)
    }
    private val jumpRow = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private val forwards = EditText(context).apply {
        hint = context.getString(R.string.ssh_forwards_hint)
        minLines = 2
        maxLines = 5
        gravity = Gravity.TOP or Gravity.START
    }

    init {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val inner = (12 * context.resources.displayMetrics.density).toInt()
            setPadding(inner, 0, inner, 0)
        }
        val density = context.resources.displayMetrics.density

        keepAlive.setText(plainNumber(existing?.keepAliveSeconds))
        keepAliveCount.setText(plainNumber(existing?.keepAliveCount))
        existing?.jump?.let {
            jumpHost.setText(it.host)
            jumpUser.setText(it.user)
            if (it.port != 22) jumpPort.setText(plainNumber(it.port))
        }
        existing?.forwards?.filter { it.enabled }?.map { it.spec }?.let { specs ->
            if (specs.isNotEmpty()) forwards.setText(specs.joinToString("\n"))
        }

        for (button in listOf(authKeys, authPassword, authAny)) {
            authGroup.addView(
                button,
                RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        authKeys.id = View.generateViewId()
        authPassword.id = View.generateViewId()
        authAny.id = View.generateViewId()
        authGroup.check(
            when (existing?.auth ?: SshStore.Auth.Keys) {
                SshStore.Auth.Keys -> authKeys.id
                SshStore.Auth.Password -> authPassword.id
                SshStore.Auth.Any -> authAny.id
            }
        )

        // Never echoes an existing password back into an editable field: it would put
        // the plaintext on screen and into any screenshot. The field starts blank and
        // says so, and leaving it blank keeps what is stored.
        passwordInitiallyBlank = true
        password.setText("")
        password.hint = context.getString(
            if (existing?.passwordRef != null) R.string.ssh_password_unchanged else R.string.ssh_password
        )
        val passwordRow = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (authPassword.isChecked) View.VISIBLE else View.GONE
            addView(password)
        }
        authGroup.setOnCheckedChangeListener { _, _ ->
            passwordRow.visibility = if (authPassword.isChecked) View.VISIBLE else View.GONE
        }
        column.addView(passwordRow)

        column.addView(heading(R.string.ssh_section_auth))
        column.addView(authGroup)
        column.addView(keepAlive)
        column.addView(keepAliveCount)
        column.addView(compress)
        column.addView(forwardAgent)

        column.addView(heading(R.string.ssh_section_jump))
        column.addView(jumpEnabled)
        jumpRow.addView(jumpHost)
        jumpRow.addView(jumpPort)
        jumpRow.addView(jumpUser)
        jumpRow.visibility = if (jumpEnabled.isChecked) View.VISIBLE else View.GONE
        jumpEnabled.setOnCheckedChangeListener { _, checked ->
            jumpRow.visibility = if (checked) View.VISIBLE else View.GONE
        }
        column.addView(jumpRow)

        column.addView(heading(R.string.ssh_section_forwards))
        column.addView(forwards)
        column.addView(
            TextView(context).apply {
                setText(R.string.ssh_forwards_note)
                textSize = 11f
                setPadding(0, (2 * density).toInt(), 0, 0)
            }
        )
        column.addView(
            LinearLayout(context).apply {
                addView(
                    verifyHostKey,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * density).toInt() }
        )

        view = column
    }

    /**
     * A number for an editable field, in a fixed locale.
     *
     * `toString()` would follow the device locale, and in some locales that means
     * grouping separators — which then cannot be read back by `toIntOrNull`, so
     * opening a server and saving it unchanged would silently blank the setting.
     */
    private fun plainNumber(value: Int?): String =
        if (value == null || value <= 0) "" else String.format(java.util.Locale.US, "%d", value)

    private fun heading(textRes: Int) = TextView(context).apply {
        setText(textRes)
        textSize = 12f
        setTextColor(context.mutedTextColor())
        setPadding(0, (10 * context.resources.displayMetrics.density).toInt(), 0, 0)
    }

    /** Reads the controls back into plain data. */
    fun read(): Settings {
        val auth = when {
            authPassword.isChecked -> SshStore.Auth.Password
            authAny.isChecked -> SshStore.Auth.Any
            else -> SshStore.Auth.Keys
        }
        val typedPassword = password.text.toString()
        val jump = if (jumpEnabled.isChecked && jumpHost.text.toString().isNotBlank()) {
            SshStore.Jump(
                host = jumpHost.text.toString().trim(),
                port = jumpPort.text.toString().trim().toIntOrNull() ?: 22,
                user = jumpUser.text.toString().trim()
            )
        } else {
            null
        }
        return Settings(
            keepAliveSeconds = keepAlive.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: 0,
            keepAliveCount = keepAliveCount.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: 0,
            compress = compress.isChecked,
            forwardAgent = forwardAgent.isChecked,
            jump = jump,
            auth = auth,
            password = typedPassword,
            // Only "changed" if something was actually typed, so that re-saving an
            // untouched form does not re-encrypt the stored password.
            passwordChanged = passwordInitiallyBlank && typedPassword.isNotEmpty(),
            verifyHostKey = verifyHostKey.isChecked,
            forwards = forwards.text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { SshStore.Forward(it) }
                .toList()
        )
    }
}