package com.redtermapp.ui

import android.content.Context
import androidx.core.content.edit
import com.redtermapp.util.SshClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The keys saved on this device, each with a user-chosen label.
 *
 * Key material stays on disk exactly where ssh expects it — `~/.ssh` inside the
 * client rootfs, so the bundled binary can reach it without a bind — and this
 * only keeps the metadata: what each file is called, what type it is, and which
 * servers use it. The filename encodes the label so the path stays recognisable
 * to anyone who looks at it with `ls`.
 *
 * Earlier builds let the user create exactly one key, named `id_ed25519`, and
 * discovered keys by listing the directory. That listing also matched
 * `known_hosts`, `config` and `authorized_keys`, all of which live alongside the
 * private keys, so the old "delete key" action offered to remove them. This store
 * is now the single source of truth and adopts whatever it finds on first load.
 */
object SshKeyStore {

    const val TYPE_ED25519 = "ed25519"
    const val TYPE_ECDSA = "ecdsa"
    const val TYPE_RSA = "rsa"

    /** Selectable key types, with the default first. */
    val KEY_TYPES = listOf(TYPE_ED25519, TYPE_ECDSA, TYPE_RSA)

    const val RSA_BITS = 4096

    private const val KEY = "keys"

    /** Keeps a generated label short enough to leave room for the id_ prefix. */
    private const val MAX_SLUG = 32

    data class Entry(
        val id: String,
        val label: String,
        /** Filename inside `~/.ssh`, without the `.pub` half. */
        val fileName: String,
        val keyType: String,
        val bits: Int = 0,
        val comment: String = "",
        val createdAt: Long = 0L,
        /**
         * Whether the key was generated with a passphrase.
         *
         * Not cosmetic: a passphrase-protected key cannot be used by anything that
         * has no terminal to type it into, so file transfer and the connection test
         * fail on it with a bare "Permission denied" that looks like the server
         * refusing the key. Recorded at creation because that is the only moment
         * it is known without trying to load the key.
         */
        val encrypted: Boolean = false
    ) {
        /** "ed25519", or "rsa-4096" where the type carries a size. */
        val typeLabel: String get() = if (bits > 0) "$keyType-$bits" else keyType

        val isEcdsa: Boolean get() = keyType == TYPE_ECDSA

        val isRsa: Boolean get() = keyType == TYPE_RSA

        /** The `-t`/`-b` arguments ssh-keygen needs for this entry. */
        fun typeArguments(): List<String> = buildList {
            add("-t")
            add(keyType)
            if (isRsa) {
                add("-b")
                add(if (bits > 0) bits.toString() else RSA_BITS.toString())
            }
        }
    }

    /**
     * What a delete actually managed to remove.
     *
     * Reported rather than assumed: a key file can fail to unlink if something
     * else holds it open, and telling the user it is gone when it is not leaves
     * a private key sitting in app storage with no way to manage it.
     */
    data class DeleteResult(
        val removedPrivate: Boolean,
        val removedPublic: Boolean,
        val clearedServers: Int
    ) {
        val succeeded: Boolean get() = removedPrivate && removedPublic
    }

    fun load(context: Context): List<Entry> {
        val prefs = context.getSharedPreferences(SshStore.PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null)
        if (raw != null) return decode(raw)
        // No metadata yet: this is either a fresh install or a build from before
        // keys were labelled. Adopt whatever ssh-keygen already produced.
        val adopted = adoptLegacyKeys(context)
        save(context, adopted)
        return adopted
    }

    fun save(context: Context, keys: List<Entry>) {
        context.getSharedPreferences(SshStore.PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY, encode(keys))
        }
    }

    /**
     * The stored form, as pure data.
     *
     * Split from the SharedPreferences call so a test can round-trip it. A field
     * added to [Entry] and to [decode] but not to this writer is lost on the next
     * load, silently and with no error anywhere — which is exactly what happened to
     * `encrypted`, leaving every passphrase-protected key looking unprotected.
     */
    internal fun encode(keys: List<Entry>): String {
        val array = JSONArray()
        for (k in keys) {
            array.put(
                JSONObject().apply {
                    put("id", k.id)
                    put("label", k.label)
                    put("fileName", k.fileName)
                    put("keyType", k.keyType)
                    put("bits", k.bits)
                    put("comment", k.comment)
                    put("createdAt", k.createdAt)
                    put("encrypted", k.encrypted)
                }
            )
        }
        return array.toString()
    }

    /** The counterpart of [encode]. Tolerates records written before a field existed. */
    internal fun decode(raw: String): List<Entry> = try {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val fileName = o.optString("fileName")
            if (fileName.isBlank()) return@mapNotNull null
            Entry(
                id = o.optString("id", fileName),
                label = o.optString("label", fileName),
                fileName = fileName,
                keyType = o.optString("keyType", TYPE_ED25519),
                bits = o.optInt("bits", 0),
                comment = o.optString("comment", ""),
                createdAt = o.optLong("createdAt", 0L),
                encrypted = o.optBoolean("encrypted", false)
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun add(context: Context, entry: Entry) {
        val existing = load(context).toMutableList()
        existing.removeAll { it.id == entry.id }
        existing.add(entry)
        save(context, existing)
    }

    fun rename(context: Context, id: String, label: String) {
        val trimmed = label.trim().ifEmpty { return }
        save(
            context,
            load(context).map { if (it.id == id) it.copy(label = trimmed) else it }
        )
    }

    /**
     * Removes a key pair completely: both halves of the file, the metadata
     * entry, and every server that pointed at it.
     *
     * Leaving a server referencing the file would not be a dangling reference in
     * the "it will fail later" sense — ssh would just fall back to the default
     * identities — but it would mean the connection silently stops using the key
     * the user deliberately bound to it. Clearing the binding turns that into a
     * visible "no key selected" instead.
     *
     * Entries are matched by id *or* filename, because the in-memory copy the
     * caller holds may predate the first-load adoption and therefore disagree
     * with the stored entry about the id.
     */
    fun delete(context: Context, entry: Entry): DeleteResult {
        val privateKey = fileFor(context, entry)
        val publicKey = File(privateKey.absolutePath + ".pub")
        val removedPrivate = !privateKey.exists() || privateKey.delete()
        val removedPublic = !publicKey.exists() || publicKey.delete()

        save(
            context,
            load(context).filterNot { it.id == entry.id || it.fileName == entry.fileName }
        )

        val servers = SshStore.load(context)
        val stale = servers.count { it.keyId == entry.id }
        if (stale > 0) {
            SshStore.save(context, servers.map { if (it.keyId == entry.id) it.copy(keyId = null) else it })
        }
        return DeleteResult(removedPrivate, removedPublic, stale)
    }

    fun fileFor(context: Context, entry: Entry): File =
        File(SshClient.sshDir(context), entry.fileName)

    fun publicFileFor(context: Context, entry: Entry): File =
        File(SshClient.sshDir(context), entry.fileName + ".pub")

    /** How many saved servers authenticate with this key. */
    fun usedBy(context: Context, entry: Entry): Int =
        SshStore.load(context).count { it.keyId == entry.id }

    /** The key a server is bound to, or null when it has none selected. */
    fun boundKey(context: Context, server: SshStore.Server): Entry? {
        val id = server.keyId ?: return null
        return load(context).firstOrNull { it.id == id }
    }

    /**
     * The private keys a connection to [server] should offer, as paths inside the
     * client rootfs.
     *
     * The bound key alone when there is one. Otherwise *every* key on the device,
     * because a server that was reachable before is reachable now: asking the user
     * which key to use would be asking about something they already answered when
     * they saved the server, and the labels make the filenames
     * `id_<type>_<label>`, which are not the names ssh would find on its own.
     *
     * Empty only when the device genuinely has no key, in which case the
     * connection cannot authenticate by key at all and the caller should say so
     * once rather than block the feature.
     */
    /**
     * Identity paths to offer, for a server that authenticates with keys.
     *
     * Empty for a password-only server. Offering identities anyway makes ssh try each
     * key first and burn the server's MaxAuthTries before it ever asks for the
     * password the user is the only person who can supply.
     */
    fun identitiesFor(context: Context, server: SshStore.Server): List<String> {
        if (!server.auth.wantsKeys) return emptyList()
        return planIdentities(context, server).keys
            .map { entry -> SshClient.inRootfs(context, fileFor(context, entry)) }
    }

    /**
     * Which keys a connection will offer.
     *
     * Every candidate is kept, encrypted or not. Narrowing the list to a single key
     * when one of them has a passphrase looks tidy and is quietly catastrophic: the
     * narrowest choice wins, and if the *right* key for the server is the one left
     * out then nothing on the device can reach it any more. `SSH_ASKPASS` carries
     * one phrase with no way to say which key it is asking about, so a connection
     * with no terminal must try these one at a time and ask about the one it is
     * actually using — which is what [SshLaunchOptions] callers do — while a
     * terminal, which can prompt per key, simply offers them all.
     */
    fun planIdentities(context: Context, server: SshStore.Server): IdentityPlan {
        val candidates = candidateKeys(context, server)
        return IdentityPlan(
            keys = candidates.take(SshLaunchOptions.MAX_IDENTITIES),
            primaryKey = candidates.firstOrNull()
        )
    }

    /**
     * What a connection will offer.
     *
     * [primaryKey] is the key it authenticates with — the bound one, or the first
     * candidate — and is what a passphrase prompt should be about. It is reported
     * whether or not the key is encrypted, because whether it is cannot be known
     * for a key this app did not create: a key pasted in, or one made by an older
     * build of the app, has no such record. So the recorded flag is a hint and
     * nothing is blocked on it; a connection that is refused prompts instead.
     */
    data class IdentityPlan(val keys: List<Entry>, val primaryKey: Entry?)

    /**
     * The keys a connection to [server] would use, in order.
     *
     * The bound key alone when there is one, otherwise every key on the device.
     * Entries whose file is missing are dropped: naming one on the command line
     * makes ssh refuse the connection outright rather than move on to the next.
     */
    /**
     * The keys a connection may use, best first.
     *
     * A bound key is the user's explicit choice and is used alone: offering others
     * too would make a server that pins one key quietly authenticate with another.
     *
     * Otherwise the order is **newest first**, not the order the keys happen to be
     * stored in, which is creation order. The common case is a user making a key
     * and then adding a server for it, and taking the list in store order means the
     * oldest key on the device wins and the key that was just created is never even
     * offered — which reads as the app having ignored the new key.
     */
    fun candidateKeys(context: Context, server: SshStore.Server): List<Entry> =
        orderCandidates(
            load(context).filter { fileFor(context, it).isFile },
            boundKey(context, server)
        )

    /**
     * The ordering, as a pure function so it can be tested.
     *
     * Getting this backwards is invisible in every other way: the connection still
     * works for a server whose key happened to be tried, and fails for one whose key
     * was not — which reads as the app having ignored the key that was just created
     * for it.
     */
    internal fun orderCandidates(keys: List<Entry>, bound: Entry?): List<Entry> {
        if (bound != null) return if (bound in keys) listOf(bound) else emptyList()
        // The file name breaks ties, so two keys made in the same millisecond always
        // come out in the same order. Without it the result depends on the order the
        // list happened to be built in, which means the same device can prefer a
        // different key on two runs.
        return keys.sortedWith(
            compareByDescending<SshKeyStore.Entry> { it.createdAt }.thenBy { it.fileName }
        )
    }

    /** The in-rootfs path of one key, for a command line offering exactly that key. */
    fun identityPathFor(context: Context, entry: Entry): String =
        SshClient.inRootfs(context, fileFor(context, entry))

    /** Whether this device has any key that could authenticate a connection. */
    fun hasAnyKey(context: Context): Boolean =
        load(context).any { fileFor(context, it).isFile }

    /** Next entry for a newly generated key, with a filename nothing else uses. */
    fun newEntry(
        context: Context,
        label: String,
        keyType: String,
        bits: Int,
        encrypted: Boolean = false
    ): Entry {
        val taken = load(context).map { it.fileName }.toMutableSet()
        // A file that survived a delete we could not complete must not be reused.
        taken.addAll(
            SshClient.sshDir(context).list()?.toMutableSet() ?: mutableSetOf()
        )
        val now = System.currentTimeMillis()
        return Entry(
            id = UUID.randomUUID().toString(),
            label = label.trim().ifEmpty { keyType },
            fileName = fileNameFor(label, keyType, taken),
            keyType = keyType,
            bits = bits,
            createdAt = now,
            encrypted = encrypted
        )
    }

    /**
     * Builds a filesystem-safe filename that says what the key is.
     *
     * Two users creating "work" keys would otherwise both get `id_ed25519_work`
     * and the second ssh-keygen would refuse to overwrite the first, leaving the
     * user with a failure they cannot act on. The numeric suffix keeps both.
     */
    fun fileNameFor(label: String, keyType: String, taken: Set<String>): String {
        val base = "id_${keyType}_${slugify(label)}"
        if (base !in taken) return base
        for (n in 2..99) {
            val candidate = "${base}_$n"
            if (candidate !in taken) return candidate
        }
        return "${base}_${taken.size}"
    }

    /** Lowercases a label and reduces it to characters that are safe in a path. */
    fun slugify(label: String): String {
        val slug = label.trim().lowercase()
            .replace(NON_SLUG_CHARS, "_")
            .replace(REPEATED_UNDERSCORES, "_")
            .trim('_')
        return if (slug.isEmpty()) "key" else slug.take(MAX_SLUG).trim('_').ifEmpty { "key" }
    }

    /**
     * Derives a label from a pre-existing filename, so an adopted key still
     * looks like something the user recognises: `id_ed25519_work` becomes "Work".
     */
    fun labelFromFileName(fileName: String): String {
        val base = fileName.removePrefix("id_")
        val label = base.substringAfter('_', base).replace('_', ' ').trim()
        if (label.isEmpty()) return base.ifEmpty { fileName }
        return label.replaceFirstChar { it.uppercase() }
    }

    private val NON_SLUG_CHARS = Regex("[^a-z0-9_-]+")
    private val REPEATED_UNDERSCORES = Regex("_{2,}")

    /**
     * Keys that predate this store, discovered from the directory.
     *
     * [SshClient.legacyPrivateKeys] deliberately only matches `id_*`, so
     * known_hosts and the ssh_config fragments that share the directory are not
     * adopted as keys.
     */
    private fun adoptLegacyKeys(context: Context): List<Entry> =
        SshClient.legacyPrivateKeys(context).map { file ->
            Entry(
                id = UUID.randomUUID().toString(),
                label = labelFromFileName(file.name),
                fileName = file.name,
                keyType = typeFromFileName(file.name),
                bits = 0,
                comment = "",
                createdAt = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
            )
        }

    /** Recovers the type from a filename so an adopted key can be regenerated with. */
    private fun typeFromFileName(fileName: String): String {
        val base = fileName.removePrefix("id_")
        return KEY_TYPES.firstOrNull { base.startsWith(it) } ?: TYPE_ED25519
    }
}