package com.redtermapp.util.sftp

import android.content.Context
import com.redtermapp.util.SshClient
import java.io.File

/**
 * Lets a passphrase-protected key be used by something with no terminal.
 *
 * The problem: a terminal session has a tty, so `ssh` prompts and works. The
 * connection test and file transfer do not, and with `BatchMode` — or simply with
 * no tty — `ssh` cannot ask, so it reports "Permission denied (publickey)" for a key
 * the server has already accepted. That reads as the server refusing the key, and
 * it is the single most confusing failure in this feature.
 *
 * The fix is the mechanism OpenSSH provides for exactly this: `SSH_ASKPASS` names a
 * program `ssh` runs to obtain a passphrase when it has nowhere to ask. That program
 * is a one-line shell script which prints the value from its own environment.
 *
 * The passphrase is held in memory for the life of the process and is never written
 * to disk or to preferences. It does travel through the environment of the
 * connection, which is unavoidable with this mechanism and is equivalent to the
 * private key already sitting in app-private storage next to it.
 */
object SshAskpass {

    /** Where the helper lives inside the client rootfs, where `ssh` can exec it. */
    const val HELPER_PATH = "/root/.askpass"

    /** Environment variable the helper reads. Never logged. */
    private const val ENV_PASSPHRASE = "REDTERM_KEY_PASSPHRASE"

    /** A key passphrase, cached by key file name. */
    const val KIND_PASSPHRASE = "key"

    /** A server password, cached by server id. */
    const val KIND_PASSWORD = "password"

    /** The slot a secret is filed under. */
    fun slot(kind: String, id: String): String = "$kind:$id"

    /** Names the helper program, per ssh(1). */
    private const val ENV_ASKPASS = "SSH_ASKPASS"

    /**
     * Makes ssh reach for the helper even though stdin is a pipe.
     *
     * Without it ssh only asks when it has no terminal at all, and the process we
     * spawn has a pipe for stdin that it reads as "there might be a user there".
     */
    private const val ENV_ASKPASS_REQUIRE = "SSH_ASKPASS_REQUIRE"

    /**
     * The helper program, assembled rather than written as a Kotlin string: its
     * body names a shell variable, which a Kotlin template would try to
     * interpolate and turn into a compile error.
     */
    private val HELPER_SCRIPT = buildString {
        append("#!/system/bin/sh\n")
        append("# RedTerm: prints the key passphrase the environment already carries.\n")
        append("printf '%s' \"")
        append('$').append(ENV_PASSPHRASE).append("\"\n")
    }

    /**
     * Passphrases by key file name, held only for the life of this process.
     *
     * An in-memory map rather than a preference: nothing survives a restart, so a
     * crash or a reboot cannot leave a passphrase lying around, and the user is
     * asked once per session rather than once per connection.
     */
    private val cached = HashMap<String, String>()

    /**
     * Keys whose passphrase the user declined to supply, for this process.
     *
     * Without this, a key that is simply not the server's is asked about on every
     * single operation: each folder listing and each transfer is a separate
     * connection, so dismissing the dialog once simply means being asked again on
     * the next one. Asked once and then left alone is the only behaviour that is
     * not nagging.
     */
    private val declined = HashSet<String>()

    @Synchronized
    fun remember(fileName: String, passphrase: String) {
        rememberSlot(slot(KIND_PASSPHRASE, fileName), passphrase)
    }

    /**
     * Files a server password under its own slot.
     *
     * A separate namespace from key passphrases on purpose: the helper answers every
     * question ssh asks with one value, so a server that needed both a key passphrase
     * and a password would have to be asked separately. Sharing a slot would let one
     * overwrite the other and produce a connection that fails as "permission denied".
     */
    @Synchronized
    fun rememberPassword(serverId: String, password: String) {
        rememberSlot(slot(KIND_PASSWORD, serverId), password)
    }

    @Synchronized
    fun cachedPassword(serverId: String): String? = cached[slot(KIND_PASSWORD, serverId)]

    private fun rememberSlot(slot: String, secret: String) {
        cached[slot] = secret
        declined.remove(slot)
    }

    /** The user dismissed the prompt for this key. Do not ask again this session. */
    @Synchronized
    fun decline(fileName: String) {
        declined.add(slot(KIND_PASSPHRASE, fileName))
    }

    @Synchronized
    fun declinePassword(serverId: String) {
        declined.add(slot(KIND_PASSWORD, serverId))
    }

    @Synchronized
    fun isDeclined(fileName: String): Boolean =
        slot(KIND_PASSPHRASE, fileName) in declined

    @Synchronized
    fun isPasswordDeclined(serverId: String): Boolean =
        slot(KIND_PASSWORD, serverId) in declined

    /** Forgets both phrases and declines, e.g. when the key list changes. */
    @Synchronized
    fun forgetAll() {
        cached.clear()
        declined.clear()
    }

    @Synchronized
    fun cached(fileName: String): String? = cached[slot(KIND_PASSPHRASE, fileName)]

    /** Used when a key is deleted, so a stale phrase cannot linger in memory. */
    @Synchronized
    fun forget(fileName: String) {
        forgetSlot(slot(KIND_PASSPHRASE, fileName))
    }

    /** Used when a server is deleted. */
    @Synchronized
    fun forgetPassword(serverId: String) {
        forgetSlot(slot(KIND_PASSWORD, serverId))
    }

    private fun forgetSlot(slot: String) {
        cached.remove(slot)
        declined.remove(slot)
    }

    /**
     * Writes the helper if it is missing, and returns its in-rootfs path.
     *
     * It has to be inside the rootfs rather than in app storage: `ssh` runs under
     * proot, which binds nothing under `/data`, so a helper in `cacheDir` would be
     * invisible to it.
     */
    /**
     * Writes the helper if it is not there, and makes it executable.
     *
     * Null when the file could not be written or made executable, because ssh then
     * reports a missing helper as "Permission denied (publickey)" — indistinguishable
     * from a server refusing a key it accepted, which is the confusion this whole
     * mechanism exists to remove. Returning null lets the caller say so instead.
     */
    fun prepareHelper(context: Context): String? = runCatching {
        val host = File(SshClient.homeDir(context), ".askpass")
        if (!host.isFile) {
            host.parentFile?.mkdirs()
            host.writeText(HELPER_SCRIPT)
        }
        // Executable, and owner-only: the script holds no secret itself, but it is
        // the thing that turns a passphrase into output, so it should not be
        // readable or runnable by anything else.
        host.setExecutable(true, true)
        host.setReadable(false, false)
        host.setReadable(true, true)
        if (!host.canExecute()) return null
        HELPER_PATH
    }.getOrNull()

    /**
     * The environment a process needs in order to ask for [passphrase].
     *
     * An empty map when there is no passphrase, because then `ssh` must behave
     * exactly as it always has: no askpass, no BatchMode change, nothing.
     *
     * Returned as a map to be applied to the process rather than as text to be
     * prefixed onto the command, because the command is written into a launcher
     * script in app storage and logged on the way past. An inline prefix would
     * put the phrase on disk in the one place it was promised not to be.
     */
    fun environment(context: Context, passphrase: String?): Map<String, String> {
        if (passphrase.isNullOrEmpty()) return emptyMap()
        // A missing helper is worth saying out loud: ssh reports it as
        // "Permission denied (publickey)", which is indistinguishable from a server
        // refusing a key it accepted.
        if (prepareHelper(context) == null) {
            com.redtermapp.util.AppLog.w(
                context, "ssh", "the passphrase helper could not be written; ssh will refuse the key"
            )
        }
        return environmentFor(HELPER_PATH, passphrase)
    }

    /** The same environment, from an explicit helper path so it can be tested. */
    internal fun environmentForTest(helperPath: String, passphrase: String?): Map<String, String> =
        environmentFor(helperPath, passphrase.orEmpty())

    /** The helper program, so a test can execute the body that is shipped. */
    internal fun helperScriptForTest(): String = HELPER_SCRIPT

    private fun environmentFor(helperPath: String, passphrase: String): Map<String, String> {
        if (passphrase.isEmpty()) return emptyMap()
        return mapOf(
            ENV_ASKPASS to HELPER_PATH,
            ENV_ASKPASS_REQUIRE to "force",
            ENV_PASSPHRASE to passphrase
        )
    }



    /** POSIX single-quotes one value for the shell. */
}