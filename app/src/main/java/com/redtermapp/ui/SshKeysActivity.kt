package com.redtermapp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.redtermapp.R
import com.redtermapp.distro.ProotLaunch
import com.redtermapp.util.AppLog
import com.redtermapp.util.SshClient

/**
 * One card per SSH key, with the actions that apply to that key.
 *
 * Previously the keys screen was a single list dialog where every key
 * contributed three rows and the rows carried no indication of which key they
 * belonged to beyond the filename in their text. With only one key ever
 * possible that was survivable; with several, picking "Delete key work" from a
 * flat list is exactly the kind of tap that destroys the wrong thing.
 *
 * Generation is backed by the bundled ssh-keygen, so it works with no distro
 * installed.
 */
class SshKeysActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)

        val pad = pad()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad, 0, 0)
        }
        // The buttons sit outside the scroller, pinned to the bottom of the screen.
        //
        // They were moved inside it to stop them being squeezed, which made them
        // appear directly under the list instead — immediately below the empty state
        // on a fresh install, and in the middle of the screen once a key existed.
        // The squeeze was not caused by being here: it was FlowLayout reporting a
        // height smaller than the buttons needed, which is fixed there.
        root.addView(
            ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        root.addView(
            FlowLayout(this).apply {
                addView(
                    ScreenWidgets.actionButton(this@SshKeysActivity, R.string.ssh_generate_key) {
                        promptGenerate()
                    }
                )
                addView(
                    ScreenWidgets.actionButton(this@SshKeysActivity, R.string.ssh_import_key) {
                        promptImport()
                    }
                )
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        // The button row at the bottom must clear the navigation bar.
        ScreenInsets.applyBottom(root)
        ScreenToolbar.install(this, root, getString(R.string.ssh_keys))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun pad() = (12 * resources.displayMetrics.density).toInt()

    /** Short date, or nothing at all for an entry with no usable timestamp. */
    private fun formatDate(millis: Long): String =
        if (millis <= 0L) "" else android.text.format.DateFormat.getDateFormat(this)
            .format(java.util.Date(millis))

    private fun reload() {
        if (!SshClient.ensureInstalled(this)) {
            list.removeAllViews()
            list.addView(TextView(this).apply {
                setText(R.string.ssh_client_unavailable)
                setTextColor(cardTextColor())
                setPadding(0, pad(), 0, 0)
            })
            return
        }
        val keys = SshKeyStore.load(this)
        list.removeAllViews()
        if (keys.isEmpty()) {
            // Centred and muted: an empty list is a normal state on a device that has
            // not made a key yet, and a full-width block of text at the top of the
            // page reads as an error.
            list.addView(TextView(this).apply {
                setText(R.string.ssh_no_keys)
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setTextColor(mutedTextColor())
                val gap = (32 * resources.displayMetrics.density).toInt()
                setPadding(pad(), gap, pad(), gap)
            })
            return
        }
        for (key in keys) {
            list.addView(keyCard(key))
        }
    }

    private fun keyCard(key: SshKeyStore.Entry): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, pad()) }
            radius = 12f
            setCardBackgroundColor(cardColor())
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), pad(), pad(), pad())
        }
        card.addView(body)

        // Label on the left, status on the right: the name is what identifies the
        // key and the type and protection are what you check at a glance, so putting
        // them on the same line makes the card scannable instead of a paragraph.
        val header = ScreenWidgets.headerRow(this)
        header.addView(
            TextView(this).apply {
                text = key.label
                textSize = 16f
                setTextColor(cardTextColor())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val statusGap = (6 * resources.displayMetrics.density).toInt()
        header.addView(
            ScreenWidgets.chip(this, key.typeLabel, accentColor()),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(statusGap, 0, 0, 0) }
        )
        body.addView(header)

        val facts = mutableListOf(getString(R.string.ssh_key_created_on, formatDate(key.createdAt)))
        val usedBy = SshKeyStore.usedBy(this, key)
        if (usedBy > 0) {
            facts.add(resources.getQuantityString(R.plurals.ssh_key_used_by, usedBy, usedBy))
        }
        if (key.encrypted) {
            val statusRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            statusRow.addView(
                ScreenWidgets.chip(
                    this, getString(R.string.ssh_key_passphrase_protected), accentColor()
                )
            )
            body.addView(statusRow)
        }
        body.addView(TextView(this).apply {
            text = facts.joinToString(" \u00b7 ")
            textSize = 12f
            setTextColor(mutedTextColor())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
        })

        // Three neutral actions in one row, and Delete on its own line in the
        // destructive colour. Sitting Delete beside "Copy public key" in an identical
        // button is how the wrong thing gets destroyed on a phone.
        // Three neutral actions and Delete, each needing its own width for its own
        // label. Equal weights made a quarter-of-a-phone label out of every one of
        // them, which is how the row turned into stacked text.
        val actions = FlowLayout(this)
        fun add(labelRes: Int, onClick: () -> Unit) {
            actions.addView(ScreenWidgets.actionButton(this, labelRes, onClick))
        }
        add(R.string.ssh_copy_public_key) { copyPublicKey(key) }
        add(R.string.ssh_copy_path) { copyPath(key) }
        add(R.string.ssh_rename_key) { promptRename(key) }
        add(R.string.ssh_change_passphrase) { promptChangePassphrase(key) }
        // Left on its own line by wrapping, and in the destructive colour: sitting
        // beside "Copy public key" in an identical button is how the wrong thing gets
        // destroyed on a phone.
        actions.addView(
            ScreenWidgets.dangerButton(this, R.string.ssh_delete_key_action) { confirmDelete(key) }
        )

        body.addView(
            actions,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
        )
        return card
    }

    private fun copyPublicKey(key: SshKeyStore.Entry) {
        val pub = SshKeyStore.publicFileFor(this, key)
        if (!pub.isFile) {
            Toast.makeText(this, R.string.ssh_key_no_public, Toast.LENGTH_SHORT).show()
            return
        }
        copyToClipboard(pub.readText().trim(), "ssh-public-key")
    }

    private fun copyPath(key: SshKeyStore.Entry) {
        copyToClipboard(SshKeyStore.fileFor(this, key).absolutePath, "ssh-key-path")
    }

    private fun copyToClipboard(text: String, label: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * Changes a key's passphrase, or removes one by clearing both fields.
     *
     * Prefilled with a guess rather than asked twice: the user may not remember
     * whether they set one, and asking "does it have a passphrase" as a separate
     * question when they are already typing one is a question they can answer by
     * looking at what they typed.
     */
    private fun promptChangePassphrase(key: SshKeyStore.Entry) {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inner = (16 * resources.displayMetrics.density).toInt()
            setPadding(inner, inner, inner, inner)
        }
        val current = EditText(this).apply {
            hint = getString(R.string.ssh_passphrase_current)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val replacement = EditText(this).apply {
            hint = getString(R.string.ssh_passphrase_new)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        column.addView(current)
        column.addView(SecretField.withRevealToggle(this, replacement))

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ssh_change_passphrase_title, key.label))
            .setMessage(R.string.ssh_change_passphrase_message)
            .setView(column)
            .setPositiveButton(R.string.ok) { _, _ -> changePassphrase(key, current, replacement) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun changePassphrase(
        key: SshKeyStore.Entry,
        current: EditText,
        replacement: EditText
    ) {
        val progress = AlertDialog.Builder(this)
            .setTitle(getString(R.string.ssh_change_passphrase_title, key.label))
            .setMessage(R.string.ssh_key_generating_running)
            .setNegativeButton(R.string.cancel, null)
            .create()
        progress.show()
        Thread({
            val result = SshKeyImporter.changePassphrase(
                context = this,
                entry = key,
                oldPassphrase = current.text.toString(),
                newPassphrase = replacement.text.toString()
            )
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.dismiss()
                if (result.succeeded) {
                    reload()
                    showText(
                        result.fingerprint,
                        getString(R.string.ssh_passphrase_changed),
                        "ssh-key-fingerprint"
                    )
                } else {
                    showKeyError(result.message)
                }
            }
        }, "redterm-ssh-passphrase").start()
    }

    /**
     * Imports a key the user already has.
     *
     * Pasted rather than picked: the file is on the device the user is coming *from*,
     * not this one, and every other place they could have it — another terminal app,
     * a note, a password manager — can put it on the clipboard. A file picker would
     * find nothing.
     */
    private fun promptImport() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inner = (16 * resources.displayMetrics.density).toInt()
            setPadding(inner, inner, inner, inner)
        }
        val label = EditText(this).apply {
            hint = getString(R.string.ssh_key_label_hint)
            setSingleLine(true)
        }
        val keyText = EditText(this).apply {
            hint = getString(R.string.ssh_import_key_hint)
            minLines = 4
            maxLines = 8
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        column.addView(label)
        column.addView(keyText)

        AlertDialog.Builder(this)
            .setTitle(R.string.ssh_import_key)
            .setMessage(R.string.ssh_import_key_message)
            .setView(column)
            .setPositiveButton(R.string.ssh_import_key_action) { _, _ ->
                val name = label.text.toString().trim()
                val text = keyText.text.toString()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                importKey(name, text)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun importKey(label: String, text: String) {
        val progress = AlertDialog.Builder(this)
            .setTitle(getString(R.string.ssh_import_key))
            .setMessage(R.string.ssh_key_generating_running)
            .setNegativeButton(R.string.cancel, null)
            .create()
        progress.show()
        Thread({
            // Recorded as unprotected: nothing is gated on the flag, and a wrong guess
            // costs one refused connection and a prompt rather than a key that cannot
            // be used at all.
            val result = SshKeyImporter.import(this, label, text)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.dismiss()
                if (result.succeeded) {
                    reload()
                    showText(
                        publicKeyText(result.entry!!),
                        getString(R.string.ssh_key_imported),
                        "ssh-public-key"
                    )

                } else {
                    showKeyError(result.message)
                }
            }
        }, "redterm-ssh-import").start()
    }

    private fun publicKeyText(entry: SshKeyStore.Entry): String {
        val file = SshKeyStore.publicFileFor(this, entry)
        return if (file.isFile) file.readText().trim() else ""
    }

    private fun promptRename(key: SshKeyStore.Entry) {
        promptForText(getString(R.string.ssh_rename_key), key.label) { value ->
            SshKeyStore.rename(this, key.id, value)
            reload()
        }
    }

    /**
     * Removes both halves of the pair, the registry entry, and every server that
     * named this key.
     *
     * The public half matters as much as the private one: leaving `id_x.pub`
     * behind means the next `ssh-keygen` for the same name refuses to write and
     * the stale public key keeps being offered for copy.
     */
    private fun confirmDelete(key: SshKeyStore.Entry) {
        val usedBy = SshKeyStore.usedBy(this, key)
        val message = if (usedBy > 0) {
            resources.getQuantityString(R.plurals.ssh_delete_key_used_warning, usedBy, usedBy)
        } else {
            getString(R.string.ssh_delete_key_warning)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ssh_delete_key, key.label))
            .setMessage(message)
            .setPositiveButton(R.string.delete) { _, _ ->
                val result = SshKeyStore.delete(this, key)
                AppLog.i(
                    this, "ssh",
                    "deleted key ${key.fileName}: private=${result.removedPrivate} " +
                        "public=${result.removedPublic} servers=${result.clearedServers}"
                )
                if (!result.succeeded) {
                    // Saying "deleted" when the file is still there would leave an
                    // unmanaged private key in app storage.
                    AlertDialog.Builder(this)
                        .setTitle(R.string.ssh_delete_key)
                        .setMessage(R.string.ssh_key_delete_failed)
                        .setPositiveButton(R.string.ok, null)
                        .show()
                } else if (result.clearedServers > 0) {
                    Toast.makeText(
                        this,
                        resources.getQuantityString(
                            R.plurals.ssh_key_deleted_servers,
                            result.clearedServers,
                            result.clearedServers
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this, R.string.ssh_key_deleted, Toast.LENGTH_SHORT).show()
                }
                reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptGenerate() {
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inner = (16 * resources.displayMetrics.density).toInt()
            setPadding(inner, inner, inner, inner)
        }
        // Labels above the fields rather than hints inside them: a hint disappears
        // the moment the field has content, which leaves a half-filled dialog with
        // two unlabelled boxes and no way back.
        fun fieldLabel(text: String) = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(mutedTextColor())
            setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        val label = EditText(this).apply {
            hint = getString(R.string.ssh_key_label_hint)
            setSingleLine(true)
        }
        val passphrase = EditText(this).apply {
            hint = getString(R.string.ssh_key_passphrase)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        var type = SshKeyStore.TYPE_ED25519
        holder.addView(fieldLabel(getString(R.string.ssh_key_label)))
        holder.addView(label)
        holder.addView(fieldLabel(getString(R.string.ssh_key_passphrase)))
        holder.addView(SecretField.withRevealToggle(this, passphrase))

        // Radio buttons rather than a row of toggles: the choice is exclusive, and
        // a group makes that exclusive by construction instead of by remembering
        // to un-highlight the others.
        val types = SshKeyStore.KEY_TYPES
        val checked = types.indexOf(type).coerceAtLeast(0)
        holder.addView(fieldLabel(getString(R.string.ssh_key_type)))
        holder.addView(
            RadioGroup(this).apply {
                orientation = RadioGroup.HORIZONTAL
                for (candidate in types) {
                    addView(
                        RadioButton(this@SshKeysActivity).apply {
                            text = candidate
                            id = View.generateViewId()
                            tag = candidate
                        },
                        RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                }
                check(getChildAt(checked).id)
                setOnCheckedChangeListener { _, checkedId ->
                    type = findViewById<RadioButton>(checkedId)?.tag as? String ?: type
                }
            }
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.ssh_generate_key)
            .setView(holder)
            .setPositiveButton(R.string.ok) { _, _ ->
                val chosen = label.text.toString().trim()
                if (chosen.isEmpty()) {
                    Toast.makeText(this, R.string.name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                generate(chosen, type, passphrase.text.toString())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun generate(label: String, keyType: String, passphrase: String) {
        val entry = SshKeyStore.newEntry(
            context = this,
            label = label,
            keyType = keyType,
            bits = bitsFor(keyType),
            // Recorded, because a passphrase-protected key is unusable by anything
            // without a terminal and the connection test then blames the server.
            encrypted = passphrase.isNotEmpty()
        )
        val progress = AlertDialog.Builder(this)
            .setTitle(getString(R.string.ssh_key_generating, label))
            .setMessage(R.string.ssh_key_generating_running)
            .setNegativeButton(R.string.cancel, null)
            .create()
        progress.show()

        Thread({
            val privateKey = SshKeyStore.fileFor(this, entry)
            val publicKey = SshKeyStore.publicFileFor(this, entry)
            privateKey.parentFile?.mkdirs()
            // Paths are inside the client's rootfs, where home is /root.
            val inRootfs = SshClient.inRootfs(this, privateKey)
            val args = mutableListOf("/bin/ssh-keygen")
            args.addAll(entry.typeArguments())
            args.addAll(listOf("-f", "'$inRootfs'", "-C", "'" + defaultComment() + "'"))
            // An empty passphrase has to survive as a genuinely empty argument.
            // ProotLaunch quotes the whole command, so the inner quotes here are
            // what keep -N from swallowing whatever follows it.
            args.addAll(
                listOf(
                    "-N",
                    if (passphrase.isEmpty()) "''" else ProotLaunch.quoteForShell(passphrase)
                )
            )
            val command = args.joinToString(" ")
            // The marker is the whole "-N '<passphrase>'" argument, not the
            // passphrase alone. Redacting the bare secret also hit every other
            // place it happened to appear — a passphrase of "oracle" turned
            // id_ed25519_oracle into id_ed25519_*** and hid the filename in the
            // one log that explains these failures.
            val secretArgument = "-N " + ProotLaunch.quoteForShell(passphrase)
            val (code, output) = SshClient.run(this, command, redacted = secretArgument)
            AppLog.i(this, "ssh", "keygen ${entry.fileName} exit=$code ${output.takeLast(300)}")

            val created = code == 0 && publicKey.isFile
            if (!created) {
                privateKey.delete()
                publicKey.delete()
            } else {
                privateKey.setReadable(true, true)
                publicKey.setReadable(true, true)
                SshKeyStore.add(this, entry)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.dismiss()
                if (created) {
                    reload()
                    showText(
                        publicKey.readText().trim(),
                        getString(R.string.ssh_key_created),
                        "ssh-public-key"
                    )
                } else {
                    showKeyError(
                        output.trim().ifBlank { getString(R.string.ssh_key_failed, "exit $code") }
                    )
                }
            }
        }, "redterm-ssh-keygen").start()
    }

    private fun bitsFor(keyType: String): Int =
        if (keyType == SshKeyStore.TYPE_RSA) SshKeyStore.RSA_BITS else 0

    private fun defaultComment(): String =
        "redterm@" + android.os.Build.MODEL.replace(' ', '_')

    private fun showKeyError(reason: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.ssh_generate_key)
            .setMessage(getString(R.string.ssh_key_failed, reason))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /** Shows text for reading, with a button that copies it to the clipboard. */
    fun showText(text: String, title: CharSequence, clipLabel: String) {
        val view = TextView(this).apply {
            this.text = text
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            val inner = (16 * resources.displayMetrics.density).toInt()
            setPadding(inner, inner, inner, inner)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton(R.string.copied) { _, _ -> copyToClipboard(text, clipLabel) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptForText(title: CharSequence, initial: String, onName: (String) -> Unit) {
        val field = ValidatedDialog.nameField(this, initial)
        ValidatedDialog.show<String>(
            context = this,
            title = title,
            view = field,
            validate = {
                val value = field.text.toString().trim()
                if (value.isEmpty()) {
                    field.error = getString(R.string.name_required)
                    ValidatedDialog.Reject
                } else {
                    value
                }
            },
            onAccepted = onName
        )
    }

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