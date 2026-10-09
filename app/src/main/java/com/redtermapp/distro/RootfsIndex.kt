package com.redtermapp.distro

import java.io.File

/**
 * The published rootfs catalogue, as fetched from `images/index.json` in the
 * rootfs-images repository.
 *
 * This exists so the distro list is not compiled into the APK. The compiled-in
 * [DistroRegistry] table stays as the fallback: a device that cannot reach the index
 * installs exactly what it always did, and an install is never blocked on a network
 * fetch. That is the whole reason this is additive rather than a replacement.
 *
 * A URL from the index is only used when the index also supplies a sha256 for that
 * architecture. An image whose checksum is unknown is not installed at all: the
 * registry's whole guarantee is that the bytes fetched are the bytes that were
 * published, and an entry without a checksum cannot make that claim.
 */
object RootfsIndex {

    const val DEFAULT_INDEX_URL =
        "https://raw.githubusercontent.com/GlobalTechInfo/rootfs-images/main/images/index.json"

    /** One entry as published. Unknown or absent fields are left null rather than defaulted. */
    data class Entry(
        val distro: String,
        val arch: String,
        val upstreamVersion: String,
        val displayName: String,
        val url: String,
        val baseUrlTemplate: String,
        val sha256: String,
        val size: Long,
        val buildVersion: String,
        val recipe: String
    )

    data class Index(
        val schema: Int,
        val version: String,
        val images: List<Entry>
    ) {
        /** Every entry for [distro], newest build first when several are published. */
        fun forDistro(distro: String): List<Entry> = images.filter { it.distro == distro }

        /**
         * The entry to install for [distro] on [deviceArch], or null when there is none.
         *
         * Entries without a usable checksum are skipped rather than returned: an image
         * with no published sha256 would be installed unverified, which is the one thing
         * the index exists to prevent.
         */
        fun resolve(distro: String, deviceArch: String): Entry? {
            val arch = abiToProotArch(deviceArch)
            return forDistro(distro)
                .filter { it.arch == arch && it.sha256.isNotBlank() && it.url.isNotBlank() }
                .maxByOrNull { it.buildVersion }
        }

        /** Distros this index knows about, in the order first seen. */
        val distros: List<String> get() = images.map { it.distro }.distinct()
    }

    class ParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Parses an index document.
     *
     * Deliberately strict about the fields a download depends on and lenient about the
     * rest. A malformed index should fail loudly rather than produce a Distro with an
     * empty URL, because that surfaces at install time as a download that cannot start,
     * which reads as a network fault rather than as bad data.
     */
    fun parse(json: String): Index {
        val root = try {
            org.json.JSONObject(json)
        } catch (e: Exception) {
            throw ParseException("index is not a JSON object", e)
        }

        val schema = root.optInt("schema", -1)
        if (schema != SUPPORTED_SCHEMA) {
            // A future index format is not something to half-understand. Using it anyway
            // would mean guessing at fields whose meaning we cannot see.
            throw ParseException("unsupported index schema $schema, expected $SUPPORTED_SCHEMA")
        }

        val array = root.optJSONArray("images")
            ?: throw ParseException("index has no 'images' array")

        val entries = ArrayList<Entry>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val distro = o.optString("distro")
            val arch = o.optString("arch")
            val url = o.optString("url")
            if (distro.isEmpty() || arch.isEmpty() || url.isEmpty()) {
                // Skipped, not fatal: one bad row should not cost the user every other
                // distro in the catalogue.
                continue
            }
            entries.add(
                Entry(
                    distro = distro,
                    arch = arch,
                    upstreamVersion = o.optString("upstreamVersion"),
                    displayName = o.optString("displayName").ifEmpty { distro },
                    url = url,
                    baseUrlTemplate = o.optString("baseUrlTemplate"),
                    sha256 = o.optString("sha256"),
                    size = o.optLong("size", 0L),
                    buildVersion = o.optString("buildVersion"),
                    recipe = o.optString("recipe")
                )
            )
        }

        return Index(
            schema = schema,
            version = root.optString("version"),
            images = entries
        )
    }

    const val SUPPORTED_SCHEMA = 1

    /**
     * Applies an index to the compiled-in registry.
     *
     * Only [distro]s that already exist in the registry are overridden. An index naming
     * a distro the app has no metadata for is ignored rather than invented: a new entry
     * needs a description, a package manager, an install size and a first-start plan,
     * and guessing those produces an entry that installs and then behaves wrongly.
     * That is the trigger for adding a real recipe, which is a deliberate step.
     */
    fun apply(index: Index, registry: List<Distro> = DistroRegistry.allDistros): List<Distro> =
        registry.map { distro -> applyTo(distro, index) }

    /**
     * The published form of one compiled-in distro, or the distro unchanged when the
     * index has nothing usable for it.
     *
     * Returns the original on every path where the answer is not clearly safe, so a
     * defect in the index degrades to today's behaviour rather than to a download that
     * cannot start.
     */
    fun applyTo(distro: Distro, index: Index): Distro {
        // Only distros that already exist are overridden. An index naming a distro the
        // app has no metadata for is ignored rather than invented: a new entry needs a
        // description, a package manager, an install size and a first-start plan, and
        // guessing those gives an entry that installs and then behaves wrongly. Adding a
        // real distro is a deliberate step, in the registry and with a recipe.
        val published = index.forDistro(distro.name)
            .filter { it.sha256.isNotBlank() && it.url.isNotBlank() && it.baseUrlTemplate.isNotBlank() }
        if (published.isEmpty()) return distro

        val sums = published.associate { it.arch to it.sha256 }
        val templates = published.map { it.baseUrlTemplate }.distinct()
        // The registry holds one URL template per distro. Arches whose files do not share
        // one cannot be expressed by that model, and picking one arbitrarily would fail
        // as a download error naming nothing, so the distro is left alone.
        if (templates.size != 1) return distro

        // Only architectures the built-in distro already claims, so the index cannot
        // widen what a device is offered. It is a supply of newer images, not a new
        // platform.
        val arches = (sums.keys intersect distro.prootArchs.toSet())
        if (arches.isEmpty()) return distro

        return distro.copy(
            baseUrl = templates.single(),
            sha256 = sums.filterKeys { it in arches },
            prootArchs = distro.prootArchs.filter { it in arches }
        )
    }

    /** Where a fetched index is cached, so a failed fetch does not re-download every launch. */
    fun cacheFile(cacheDir: File): File = File(cacheDir, "rootfs-index.json")
}