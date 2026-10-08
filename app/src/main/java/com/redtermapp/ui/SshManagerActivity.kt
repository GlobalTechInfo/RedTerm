package com.redtermapp.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import androidx.appcompat.app.AppCompatActivity
import com.redtermapp.R
import com.redtermapp.util.SshClient
import com.redtermapp.util.sftp.SshAskpass
import java.util.UUID

/**
 * Saved SSH hosts, with the key each one authenticates with.
 *
 * Connections run the bundled OpenSSH client in a normal terminal session
 * rather than bundling a second SSH implementation in the app: the distro
 * already has one, and it keeps the user's keys, config and known_hosts.
 */
class SshManagerActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var root: LinearLayout
    private var servers: MutableList<SshStore.Server> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            setText(R.string.ssh_client)
            textSize = 20f
        })

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad, 0, 0)
        }
        root.addView(
            ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        // A wrapping row, not four equal weights. Weights divide the width before
        // anything is measured, so four of them on a phone give each label a quarter
        // of the screen, and "Trusted hosts" becomes one word per line.
        val buttons = FlowLayout(this)
        fun topAction(labelRes: Int, onClick: () -> Unit) {
            buttons.addView(ScreenWidgets.actionButton(this, labelRes, onClick))
        }
        topAction(R.string.ssh_add_server) { editServer(null) }
        topAction(R.string.ssh_manage_keys) {
            startActivity(
                android.content.Intent(this@SshManagerActivity, SshKeysActivity::class.java)
            )
        }
        topAction(R.string.ssh_trusted_hosts) { showTrustedHosts() }
        topAction(R.string.ssh_import_config) { promptImportConfig() }
        root.addView(
            buttons,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // The button row at the bottom must clear the navigation bar.
        ScreenInsets.applyBottom(root)
        ScreenToolbar.install(this, root, getString(R.string.ssh_client))
        setContentView(root)
        reload()
    }

    private fun reload() {
        servers = SshStore.load(this).toMutableList()
        list.removeAllViews()
        if (servers.isEmpty()) {
            list.addView(TextView(this).apply {
                setText(R.string.ssh_no_servers)
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setTextColor(mutedTextColor())
                val gap = (32 * resources.displayMetrics.density).toInt()
                setPadding(pad(), gap, pad(), gap)
            })
            return
        }
        for (server in servers) {
            list.addView(serverRow(server))
        }
    }

    private fun pad() = (12 * resources.displayMetrics.density).toInt()

    private fun serverRow(server: SshStore.Server): View {
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
        val gap = (6 * resources.displayMetrics.density).toInt()
        val header = ScreenWidgets.headerRow(this)
        header.addView(
            TextView(this).apply {
                text = server.label
                textSize = 16f
                setTextColor(cardTextColor())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        // A non-default port is worth a chip: it is the one detail about a server
        // that is easy to forget having typed and impossible to guess later.
        if (server.port != 22) {
            header.addView(
                ScreenWidgets.chip(
                    this, getString(R.string.ssh_server_port, server.port), accentColor()
                ),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(gap, 0, 0, 0) }
            )
        }
        body.addView(header)
        body.addView(TextView(this).apply {
            text = getString(R.string.ssh_server_row, server.user, server.host, server.port)
            textSize = 12f
            setTextColor(mutedTextColor())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
        })
        val key = SshKeyStore.boundKey(this, server)
        val keyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        keyRow.addView(
            if (key != null) {
                ScreenWidgets.chip(this, key.label, accentColor())
            } else {
                // Not a warning: an unbound server still works, by trying the keys on
                // the device. Say which, so it is not read as a missing setting.
                ScreenWidgets.chip(this, getString(R.string.ssh_key_chip_any_key), mutedTextColor())
            }
        )
        body.addView(keyRow)
        // A wrapping row rather than rows of equal weights. Weights divide the width
        // before anything is measured, so three buttons on a phone each get a third of
        // the screen and their labels wrap one word per line — which is what this card
        // did with four.
        val actions = FlowLayout(this)
        fun action(labelRes: Int, onClick: () -> Unit) {
            actions.addView(ScreenWidgets.actionButton(this, labelRes, onClick))
        }
        action(R.string.ssh_connect) { connect(server) }
        action(R.string.ssh_test) { testConnection(server) }
        if (server.forwards.any { it.enabled && it.usable }) {
            action(
                if (TunnelManager.isAlive(this, server.id)) R.string.ssh_tunnel_stop
                else R.string.ssh_tunnel_start
            ) { toggleTunnel(server) }
        }
        action(R.string.ssh_edit) { editServer(server) }
        action(R.string.ssh_browse_files) { browseFiles(server) }
        fun delete() {
            AlertDialog.Builder(this@SshManagerActivity)
                    .setTitle(getString(R.string.delete_item, server.label))
                    .setMessage(getString(R.string.ssh_delete_server, server.label))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        servers.remove(server)
                        SshStore.save(this@SshManagerActivity, servers)
                        // Close its sessions before the entry goes, so nothing is
                        // left connected to a host the user just asked to forget.
                        // A running SSH shell has no other way to notice.
                        val closed = TerminalViewModel.get(application)
                            .removeSessionsForServer(server.id, server.host)
                        if (closed > 0) {
                            com.redtermapp.util.AppLog.i(
                                this@SshManagerActivity, "ssh",
                                "closed $closed session(s) for ${server.label}"
                            )
                        }
                        reload()
                    }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        actions.addView(
            ScreenWidgets.dangerButton(this, R.string.delete) { delete() }
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

    private fun editServer(existing: SshStore.Server?) {
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inner = (16 * resources.displayMetrics.density).toInt()
            setPadding(inner, inner, inner, inner)
        }
        val label = EditText(this).apply { hint = getString(R.string.ssh_alias) }
        val host = EditText(this).apply { hint = getString(R.string.ssh_host) }
        val user = EditText(this).apply { hint = getString(R.string.ssh_user) }
        val port = EditText(this).apply {
            hint = getString(R.string.ssh_port)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        listOf(label, host, user, port).forEach { field ->
            holder.addView(field)
            if (existing != null) {
                when (field) {
                    label -> field.setText(existing.label)
                    host -> field.setText(existing.host)
                    user -> field.setText(existing.user)
                    port -> field.setText(getString(R.string.ssh_port_number, existing.port))
                }
            }
        }

        // A key chooser rather than a free-text path: the paths live inside a
        // rootfs the user cannot browse, so typing one is not a real option, and
        // a typo would fail only at connect time with no useful message.
        var keyId = existing?.keyId
        holder.addView(
            keyPickerButton(keyId) { picked -> keyId = picked },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val extras = ServerExtrasView(this, existing)
        holder.addView(extras.view)

        ValidatedDialog.show<SshStore.Server>(
            context = this,
            title = getString(if (existing == null) R.string.ssh_add_server else R.string.ssh_edit),
            view = holder,
            validate = {
                val hostText = host.text.toString().trim()
                if (hostText.isBlank()) {
                    // Kept open rather than dismissed with a toast: a server saved
                    // with a blank host cannot be reached, and the dialog closing
                    // while the mistake is still on screen looks like the tap was
                    // ignored.
                    host.error = getString(R.string.ssh_host_required)
                    return@show ValidatedDialog.Reject
                }
                val typedPort = port.text.toString().trim().toIntOrNull()
                if (typedPort != null && typedPort !in 1..65535) {
                    port.error = getString(R.string.ssh_port_invalid)
                    return@show ValidatedDialog.Reject
                }
                host.error = null
                extras.read().let { e ->
                    SshStore.Server(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        label = label.text.toString().trim().ifBlank { hostText },
                        host = hostText,
                        port = typedPort ?: 22,
                        user = user.text.toString().trim(),
                        keyId = keyId,
                        keepAliveSeconds = e.keepAliveSeconds,
                        keepAliveCount = e.keepAliveCount,
                        compress = e.compress,
                        forwardAgent = e.forwardAgent,
                        jump = e.jump,
                        auth = e.auth,
                        // Only kept if it changed or already existed: re-saving an
                        // untouched password would re-encrypt it for nothing.
                        passwordRef = if (e.passwordChanged || existing?.passwordRef != null) {
                            SshCredentialStore.put(
                                this, existing?.passwordRef ?: SshCredentialStore.newRef(), e.password
                            ).ifEmpty { existing?.passwordRef }
                        } else {
                            null
                        },
                        verifyHostKey = e.verifyHostKey,
                        forwards = e.forwards
                    )
                }
            },
            onAccepted = { saved ->
                if (existing == null) servers.add(saved) else {
                    val index = servers.indexOfFirst { it.id == existing.id }
                    if (index >= 0) servers[index] = saved
                }
                SshStore.save(this, servers)
                reload()
            }
        )
    }

    /**
     * A button that reads as the current key selection and opens a chooser when
     * tapped. "No key" is a real choice, not an absence: it is how a server that
     * only accepts passwords is configured.
     */
    private fun keyPickerButton(selectedId: String?, onPicked: (String?) -> Unit): MaterialButton {
        val keys = SshKeyStore.load(this)
        val none = getString(R.string.ssh_key_none)
        val button = MaterialButton(this).apply {
            text = keys.firstOrNull { it.id == selectedId }?.label ?: none
            isAllCaps = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        button.setOnClickListener {
            val labels = keys.map { it.label } + none
            val ids: List<String?> = keys.map { it.id } + null
            AlertDialog.Builder(this@SshManagerActivity)
                .setTitle(R.string.ssh_choose_key)
                .setItems(labels.toTypedArray()) { _, which ->
                    onPicked(ids[which])
                    button.text = labels[which]
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        return button
    }

    /**
     * Runs one non-interactive probe and shows why it failed.
     *
     * A connection that dies with no message on screen is impossible to act on:
     * the terminal is already gone by the time the error scrolls past, and
     * "it didn't work" does not say whether the server refused the port, rejected
     * the key, or disagreed about algorithms. BatchMode plus a fixed remote
     * command makes the attempt fail fast and deterministically, and the verbose
     * trace names which side gave up.
     */
    /**
     * One non-interactive attempt.
     *
     * [key] is null for a password attempt, in which case no identity is offered and
     * the secret is the password.
     */
    private fun runTest(
        server: SshStore.Server,
        key: SshKeyStore.Entry?,
        secret: String
    ): Pair<Int, String> {
        // Options first, then the target, then the remote command. The previous
        // order put -o after the host, where ssh is entitled to read anything as
        // part of the command it runs remotely.
        val batch = if (secret.isEmpty()) listOf("BatchMode=yes") else emptyList()
        val args = mutableListOf("/bin/ssh", "-v")
        args.addAll(
            SshLaunchOptions.forServer(
                server = server,
                identities = key?.let { listOf(SshKeyStore.identityPathFor(this, it)) } ?: emptyList(),
                extraOptions = batch + listOf("ConnectTimeout=10", "ConnectionAttempts=1"),
                // A test is a moment-long command; opening the server's tunnels would
                // leave them listening for as long as the check took to time out.
                withForwards = false
            )
        )
        args.add("true")
        val command = args.joinToString(" ")
        return SshClient.runInRootfs(
            context = this,
            command = command,
            scriptName = "ssh-test-${System.nanoTime()}.sh",
            environment = SshAskpass.environment(this, secret)
        )
    }

    /**
     * Whether the trace says the *key* was turned down.
     *
     * Deliberately narrow: a refused key is worth asking about a passphrase for,
     * while a refused host or a refused subsystem is not, and asking about those
     * would send the user looking in entirely the wrong place.
     */
    private fun looksLikeAuthRefusal(output: String): Boolean =
        output.contains("Permission denied (publickey)", ignoreCase = true) ||
            output.contains("incorrect passphrase", ignoreCase = true) ||
            output.contains("Enter passphrase", ignoreCase = true)

    /**
     * Lists what the app trusts, and lets each one be forgotten.
     *
     * The removal is the point. Every client that checks host keys eventually refuses
     * to connect to a host whose key changed — a server that was rebuilt, an address
     * that was reassigned — and the refusal is correct. What the app did not have was
     * any way to act on it, so the only remedy was clearing its data.
     */
    /**
     * Imports hosts from an `ssh_config`.
     *
     * Pasted rather than picked for the same reason keys are: the file lives on
     * another machine, and the easiest thing that can hold it is the clipboard.
     */
    private fun promptImportConfig() {
        val field = EditText(this).apply {
            hint = getString(R.string.ssh_import_config_hint)
            minLines = 6
            maxLines = 12
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inner = (16 * resources.displayMetrics.density).toInt()
            setPadding(inner, inner, inner, inner)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.ssh_import_config)
            .setMessage(R.string.ssh_import_config_message)
            .setView(holder)
            .setPositiveButton(R.string.ssh_import_config_action) { _, _ ->
                importConfig(field.text.toString())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun importConfig(text: String) {
        val progress = AlertDialog.Builder(this)
            .setTitle(R.string.ssh_import_config)
            .setMessage(R.string.ssh_import_config_running)
            .setNegativeButton(R.string.cancel, null)
            .create()
        progress.show()
        Thread({
            val summary = SshConfigImporter.importInto(this, text)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.dismiss()
                reload()
                reportConfigImport(summary)
            }
        }, "redterm-ssh-config").start()
    }

    /**
     * Says what happened, including what did not.
     *
     * A silent partial import is the worst outcome here: the user pastes a config
     * with twenty hosts, the screen reappears with one more, and no way to tell which
     * nineteen were dropped or why.
     */
    private fun reportConfigImport(summary: SshConfigImporter.ImportSummary) {
        val message = buildString {
            append(
                resources.getQuantityString(
                    R.plurals.ssh_import_config_added, summary.added, summary.added
                )
            )
            if (summary.skippedExisting > 0) {
                append("\n\n")
                append(
                    resources.getQuantityString(
                        R.plurals.ssh_import_config_existing,
                        summary.skippedExisting,
                        summary.skippedExisting
                    )
                )
            }
            if (summary.skippedPatterns.isNotEmpty()) {
                append("\n\n")
                append(getString(R.string.ssh_import_config_patterns, summary.skippedPatterns.joinToString(", ")))
            }
            if (summary.unrecognised.isNotEmpty()) {
                append("\n\n")
                append(
                    resources.getQuantityString(
                        R.plurals.ssh_import_config_ignored,
                        summary.unrecognised.size,
                        summary.unrecognised.size
                    )
                )
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.ssh_import_config)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showTrustedHosts() {
        val entries = com.redtermapp.util.KnownHosts.entries(this)
        if (entries.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.ssh_trusted_title)
                .setMessage(R.string.ssh_trusted_none)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        val labels = entries.map { entry ->
            entry.host + "\n" + entry.type
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.ssh_trusted_title)
            .setItems(labels) { _, which -> confirmForgetHost(entries[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmForgetHost(entry: com.redtermapp.util.KnownHosts.Entry) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ssh_trusted_forget_title, entry.host))
            .setMessage(R.string.ssh_trusted_forget_message)
            .setPositiveButton(R.string.ssh_trusted_forget) { _, _ ->
                val removed = com.redtermapp.util.KnownHosts.forget(this, entry.host)
                com.redtermapp.util.AppLog.i(
                    this, "ssh", "forget host key ${entry.host}: removed=$removed"
                )
                if (removed) {
                    showTrustedHosts()
                } else {
                    Toast.makeText(this, R.string.ssh_trusted_forget_failed, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun testConnection(server: SshStore.Server) {
        if (!SshClient.ensureInstalled(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.ssh_client_missing_title)
                .setMessage(R.string.ssh_client_unavailable)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.ssh_testing, server.label))
            .setMessage(R.string.ssh_test_running)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        Thread({
            // Each key on its own, best first, asking for a passphrase only for a key
            // the server actually refuses. Offering them all in one command cannot be
            // made to work: `SSH_ASKPASS` supplies one phrase with no way to say which
            // key it is for, so a single protected key would otherwise decide which
            // keys every server on the device can use.
            var code = -1
            var output = ""
            var usedKey: SshKeyStore.Entry? = null
            var usedPassword = false
            val outcome = SshKeyAuthenticator.authenticate(this, this, server) { key, secret ->
                val attempt = runTest(server, key, secret)
                // Last attempt wins, so the message shown is the one from the key that
                // got furthest rather than whichever key happened to be tried first.
                code = attempt.first
                output = attempt.second
                when {
                    attempt.first == 0 -> {
                        usedKey = key
                        usedPassword = key == null
                        SshKeyAuthenticator.Attempt.Ok(key ?: server)
                    }
                    looksLikeAuthRefusal(attempt.second) ->
                        SshKeyAuthenticator.Attempt.Refused(attempt.second)
                    else -> SshKeyAuthenticator.Attempt.Other(attempt.second)
                }
            }
            if (outcome is SshKeyAuthenticator.Result.NoKey) {
                code = -1
                output = getString(R.string.sftp_no_keys_on_device)
            } else if (outcome is SshKeyAuthenticator.Result.Rejected) {
                com.redtermapp.util.AppLog.i(
                    this, "ssh",
                    "test ${server.label}: nothing accepted (${outcome.tries} tried)"
                )
            }
            val how = when {
                usedPassword -> getString(R.string.ssh_session_key_password)
                usedKey != null -> usedKey!!.label
                else -> ""
            }
            if (how.isNotEmpty()) {
                com.redtermapp.util.AppLog.i(
                    this, "ssh", "test ${server.label}: authenticated with $how"
                )
            }
            com.redtermapp.util.AppLog.i(
                this, "ssh",
                if (code == 0) {
                    "test ${server.label}: ok"
                } else {
                    "test ${server.label}: exit=$code\n${output.takeLast(1200)}"
                }
            )
            runOnUiThread {
                dialog.dismiss()
                AlertDialog.Builder(this)
                    .setTitle(
                        if (code == 0) R.string.ssh_test_ok
                        else R.string.ssh_test_failed
                    )
                    .setMessage(
                        // "Permission denied (publickey)" on its own reads as the
                        // server refusing the key, which is the wrong conclusion
                        // when the real cause is a passphrase nobody can type.
                        buildString {
                            if (usedKey != null) {
                                append(getString(R.string.ssh_test_passphrase_hint))
                                append("\n\n")
                            }
                            append(
                                output.trim().ifEmpty { getString(R.string.ssh_test_no_output) }
                                    .takeLast(4000)
                            )
                        }
                    )
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }, "ssh-test").start()
    }

    /** POSIX single-quotes one argument, escaping any it contains. */
    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun connect(server: SshStore.Server) {
        // ensureInstalled() also verifies the client actually runs, so a false
        // here means the bundled binaries are unusable on this device.
        if (!SshClient.ensureInstalled(this)) {
            com.redtermapp.util.AppLog.e(this, "ssh", "client unusable")
            AlertDialog.Builder(this)
                .setTitle(R.string.ssh_client_missing_title)
                .setMessage(R.string.ssh_client_unavailable)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        // The activity rebuilds the arguments from the server and the key it is bound
        // to, so that a session launched from here and one launched from the
        // session picker authenticate identically.
        TerminalActivity.launchSsh(this, server)
    }

    private fun browseFiles(server: SshStore.Server) =
        SftpServerPicker.open(this, server)

    /**
     * Opens or closes a standalone tunnel for this server's forwards.
     *
     * A warning rather than a refusal: a forward that fails to bind is reported by
     * ssh after the fact, and the user is better served by being told which spec
     * ssh objected to than by a refusal that explains nothing.
     */
    private fun toggleTunnel(server: SshStore.Server) {
        if (TunnelManager.isAlive(this, server.id)) {
            TunnelManager.stop(this, server.id)
            reload()
            return
        }
        val started = Thread({
            val ok = TunnelManager.start(this, server, identitiesFor(server))
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!ok) {
                    Toast.makeText(
                        this, R.string.ssh_tunnel_failed, Toast.LENGTH_LONG
                    ).show()
                }
                reload()
            }
        }, "redterm-ssh-tunnel").also { it.start() }
    }

    /**
     * The private key this server authenticates with, as seen inside the client
     * rootfs, or null when the server has no key bound.
     */
    fun identitiesFor(server: SshStore.Server): List<String> =
        SshKeyStore.identitiesFor(this, server)

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
