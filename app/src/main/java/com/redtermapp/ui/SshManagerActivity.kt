package com.redtermapp.ui

import android.content.Context
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
import androidx.core.content.edit
import com.redtermapp.R
import com.redtermapp.util.SshClient
import java.io.File
import java.util.UUID

/**
 * Saved SSH hosts plus key management.
 *
 * Connections run the distro's own ssh client in a normal terminal session
 * rather than bundling a second SSH implementation in the app: the distro
 * already has one, and it keeps the user's keys, config and known_hosts.
 */
class SshManagerActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var root: LinearLayout
    private var servers: MutableList<SshStore.Server> = mutableListOf()

    private companion object {
        const val PREFS = "ssh_prefs"
        const val KEY_LAST_DISTRO = "last_distro"
    }

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

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(
            MaterialButton(this).apply {
                setText(R.string.ssh_add_server)
                setOnClickListener { editServer(null) }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        buttons.addView(
            MaterialButton(this).apply {
                setText(R.string.ssh_keys)
                setOnClickListener { showKeyActions() }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(buttons)

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
                setPadding(0, pad(), 0, 0)
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
        body.addView(TextView(this).apply {
            text = server.label
            textSize = 15f
        })
        body.addView(TextView(this).apply {
            text = getString(R.string.ssh_server_row, server.user, server.host, server.port)
            textSize = 12f
            setPadding(0, 4, 0, 0)
        })
        // Two rows of two. Four buttons across a phone left each one about a
        // quarter of the width, which is too narrow for the labels, so they
        // wrapped onto a second line and clipped.
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun buttonRow(vararg buttons: MaterialButton) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (button in buttons) {
                line.addView(
                    button,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
            actions.addView(line)
        }
        fun actionButton(labelRes: Int, onClick: () -> Unit) = MaterialButton(this).apply {
            setText(labelRes)
            isAllCaps = false
            maxLines = 1
            setOnClickListener { onClick() }
        }
        buttonRow(
            actionButton(R.string.ssh_connect) { connect(server) },
            actionButton(R.string.ssh_test) { testConnection(server) }
        )
        buttonRow(
            actionButton(R.string.ssh_edit) { editServer(server) },
            actionButton(R.string.delete) {
                AlertDialog.Builder(this@SshManagerActivity)
                    .setTitle(getString(R.string.delete_item, server.label))
                    .setMessage(getString(R.string.ssh_delete_server, server.label))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        servers.remove(server)
                        SshStore.save(this@SshManagerActivity, servers)
                        reload()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        )
        body.addView(actions)
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
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.ssh_add_server else R.string.ssh_edit)
            .setView(holder)
            .setPositiveButton(R.string.ok) { _, _ ->
                val hostText = host.text.toString().trim()
                if (hostText.isBlank()) {
                    Toast.makeText(this, R.string.name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val saved = SshStore.Server(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    label = label.text.toString().trim().ifBlank { hostText },
                    host = hostText,
                    port = port.text.toString().trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 22,
                    user = user.text.toString().trim()
                )
                if (existing == null) servers.add(saved) else {
                    val index = servers.indexOfFirst { it.id == existing.id }
                    if (index >= 0) servers[index] = saved
                }
                SshStore.save(this, servers)
                reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
            val args = ArrayList<String>()
            args.add("/bin/ssh")
            args.add("-v")
            args.addAll(buildOptions(server))
            // No prompt, no remote shell, and give up rather than hang.
            args.add("-o")
            args.add("BatchMode=yes")
            args.add("-o")
            args.add("ConnectTimeout=10")
            args.add("-o")
            args.add("ConnectionAttempts=1")
            // A command that always succeeds, so reaching "remote" is unambiguous.
            args.add("true")
            val command = args.joinToString(" ") { shellQuote(it) }
            val (code, output) = SshClient.runInRootfs(this, command, "ssh-test.sh")
            com.redtermapp.util.AppLog.i(this, "ssh", "test ${server.label}: exit=$code\n$output")
            runOnUiThread {
                dialog.dismiss()
                AlertDialog.Builder(this)
                    .setTitle(
                        if (code == 0) R.string.ssh_test_ok
                        else R.string.ssh_test_failed
                    )
                    .setMessage(
                        output.trim().ifEmpty { getString(R.string.ssh_test_no_output) }
                            .takeLast(4000)
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
        val args = ArrayList<String>()
        // In-rootfs path: proot runs the client from its own rootfs.
        args.add("/bin/ssh")
        args.addAll(buildOptions(server))
        TerminalActivity.launchSsh(this, args.toTypedArray(), server.label)
    }

    /**
     * Builds the ssh arguments for a saved server.
     *
     * Known-hosts checking stays on, but the file is redirected into the app's
     * own storage so it does not depend on a HOME that may not be writable.
     */
    fun buildOptions(server: SshStore.Server): List<String> {
        val options = mutableListOf<String>()
        options.add("-p")
        options.add(server.port.toString())
        val identity = SshClient.defaultKey(this)
        if (identity != null) {
            // Inside the client rootfs the home directory is /root.
            val inRootfs = SshClient.inRootfs(this, identity)
            options.add("-i")
            options.add(inRootfs)
            options.add("-o")
            options.add("IdentitiesOnly=yes")
        }
        options.add("-o")
        options.add("UserKnownHostsFile=/root/.ssh/known_hosts")
        options.add("-o")
        options.add("StrictHostKeyChecking=accept-new")
        val target = if (server.user.isNotBlank()) "${server.user}@${server.host}" else server.host
        options.add(target)
        return options
    }

    /**
     * Key management backed by the bundled ssh-keygen, so it works with no
     * distro installed. Keys live in the app's own ssh directory and are the ones
     * a saved server actually uses.
     */
    private fun showKeyActions() {
        if (!SshClient.ensureInstalled(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.ssh_client_missing_title)
                .setMessage(R.string.ssh_client_unavailable)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        val keys = SshClient.privateKeys(this)
        val labels = mutableListOf<String>()
        val handlers = mutableListOf<() -> Unit>()

        for (key in keys) {
            val pub = SshClient.publicKeyFor(this, key)
            if (pub != null) {
                labels.add(getString(R.string.ssh_view_public_key, key.name))
                handlers.add { showText(pub.readText().trim(), R.string.ssh_public_key) }
            }
            labels.add(getString(R.string.ssh_use_key, key.name))
            handlers.add { showText(key.absolutePath, R.string.ssh_key_in_use) }
            // A key that cannot be removed is a key that is stuck: it keeps being
            // offered for servers and can never be replaced cleanly.
            labels.add(getString(R.string.ssh_delete_key, key.name))
            handlers.add { confirmDeleteKey(key) }
        }
        labels.add(getString(R.string.ssh_generate_key))
        handlers.add { generateKey(File(SshClient.sshDir(this), "id_ed25519")) }

        AlertDialog.Builder(this)
            .setTitle(R.string.ssh_keys)
            .setItems(labels.toTypedArray()) { _, which -> handlers[which]() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Removes a key pair, warning that any server still pointing at it will stop
     * authenticating and fall back to a password. The public half goes too,
     * otherwise a stale .pub is left behind.
     *
     * Saved servers do not store a key path, so nothing can be left dangling: the
     * key is chosen per connection.
     */
    private fun confirmDeleteKey(key: File) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ssh_delete_key, key.name))
            .setMessage(R.string.ssh_delete_key_warning)
            .setPositiveButton(R.string.delete) { _, _ ->
                val pub = File(key.absolutePath + ".pub")
                val removed = key.delete()
                pub.delete()
                com.redtermapp.util.AppLog.i(this, "ssh", "deleted key ${key.name}: private=$removed")
                Toast.makeText(
                    this,
                    if (removed) R.string.ssh_key_deleted else R.string.ssh_key_delete_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun generateKey(priv: File) {
        val pub = File(priv.absolutePath + ".pub")
        if (priv.exists()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.ssh_generate_key)
                .setMessage(getString(R.string.ssh_key_exists, priv.absolutePath))
                .setPositiveButton(R.string.overwrite) { _, _ -> runKeygen(priv, pub) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        runKeygen(priv, pub)
    }

    private fun runKeygen(priv: File, pub: File) {
        Toast.makeText(this, R.string.record_starting, Toast.LENGTH_SHORT).show()
        Thread({
            priv.parentFile?.mkdirs()
            // Paths are inside the client's rootfs, where home is /root.
            val inRootfs = SshClient.inRootfs(this, priv)
            val command = "/bin/ssh-keygen -t ed25519 -f '$inRootfs' -N '' " +
                "-C redterm@" + android.os.Build.MODEL.replace(' ', '_')
            com.redtermapp.util.AppLog.i(this, "ssh", "keygen: $command")
            val (code, output) = SshClient.run(this, command)
            com.redtermapp.util.AppLog.i(
                this, "ssh", "keygen exit=$code output=${output.takeLast(300)}"
            )
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (code == 0 && pub.exists()) {
                    priv.setReadable(true, true)
                    pub.setReadable(true, true)
                    showText(pub.readText().trim(), R.string.ssh_key_created)
                } else {
                    priv.delete()
                    pub.delete()
                    showKeyError(
                        output.trim().ifBlank { getString(R.string.ssh_key_failed, "exit $code") }
                    )
                }
            }
        }, "redterm-ssh-keygen").start()
    }

    private fun showKeyError(reason: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.ssh_generate_key)
            .setMessage(getString(R.string.ssh_key_failed, reason))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showText(text: String, titleRes: Int) {
        val view = TextView(this).apply {
            this.text = text
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            val inner = (16 * resources.displayMetrics.density).toInt()
            setPadding(inner, inner, inner, inner)
        }
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton(R.string.copied) { _, _ ->
                val clip = getSystemService(android.content.ClipboardManager::class.java)
                clip.setPrimaryClip(android.content.ClipData.newPlainText("ssh-key", text))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
