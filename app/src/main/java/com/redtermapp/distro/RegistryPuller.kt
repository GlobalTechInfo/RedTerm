package com.redtermapp.distro

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Pulls the layers of a Docker/OCI image so a distro can be installed from a
 * registry reference.
 *
 * A registry image is not one tarball: it is an ordered set of layer blobs,
 * each a (usually gzip) tar, that must be unpacked in sequence — a later
 * layer overwrites an earlier file. This client fetches the manifest, picks
 * the entry that matches the device's architecture when the image is a
 * multi-arch list, and downloads the layers in order to files the installer
 * then unpacks one by one.
 *
 * Only the pure reference parsing and platform choice are unit-tested; the
 * network here is a thin, well-known registry v2 flow (anonymous token, then
 * bearer) and is verified on a real device, the way every other network path
 * in this app is.
 */
object RegistryPuller {

    data class Result(val layers: List<File>)

    /** The layer blobs of [reference] for [abi], in unpack order. */
    fun pull(
        reference: String,
        abi: String,
        outDir: File,
        onProgress: (String) -> Unit
    ): Result {
        val parsed = RegistryRef.parse(reference)
        val manifestArch = RegistryRef.manifestArchForAbi(abi)
        outDir.mkdirs()

        // 1. The manifest, with a token if the registry challenges for one.
        val manifestBase = "https://${parsed.host}/v2/${parsed.repoPath}/manifests/"
        var token = ""
        val first = fetchManifest(manifestBase + parsed.tag, token, parsed)
        var manifestCode = first.first
        var manifest: JSONObject? = first.second
        if (manifest == null) {
            token = acquireToken(parsed)
            val retry = fetchManifest(manifestBase + parsed.tag, token, parsed)
            manifestCode = retry.first
            manifest = retry.second
        }
        if (manifest == null) {
            throw Exception("Could not read the image manifest for $reference (HTTP $manifestCode).")
        }

        // 2. A manifest list points at one manifest per architecture; take ours.
        val mediaType = manifest.optString(
            "mediaType", "application/vnd.docker.distribution.manifest.v2+json"
        )
        val isList = mediaType.endsWith("manifest.list.v2+json") ||
            mediaType.endsWith("image.index.v1+json")
        val layersJson = if (isList) {
            val index = manifest.getJSONArray("manifests")
            var pick = JSONObject(index.getJSONObject(0).toString())
            for (i in 0 until index.length()) {
                val m = JSONObject(index.getJSONObject(i).toString())
                val arch = m.optJSONObject("platform")?.optString("architecture", "") ?: ""
                if (RegistryRef.isMatchingArch(arch, manifestArch)) { pick = m; break }
            }
            val digest = pick.getString("digest")
            val (subCode, sub) = fetchManifest(manifestBase + digest, token, parsed)
            if (sub == null) {
                throw IllegalStateException("Could not read the per-arch manifest for $reference (HTTP $subCode).")
            }
            sub.getJSONArray("layers")
        } else {
            manifest.getJSONArray("layers")
        }

        // 3. The blobs, in unpack order.
        onProgress("Downloading image layers…")
        val blobs = ArrayList<File>(layersJson.length())
        for (i in 0 until layersJson.length()) {
            val digest = layersJson.getJSONObject(i).getString("digest")
            val target = File(outDir, "layer-$i")
            if (!target.exists() || target.length() == 0L) {
                val blobUrl = "https://${parsed.host}/v2/${parsed.repoPath}/blobs/$digest"
                check(downloadToFile(blobUrl, token, target)) {
                    "Could not download layer $i of the image."
                }
            }
            blobs.add(target)
        }
        return Result(blobs)
    }

    private fun fetchManifest(
        url: String, token: String, parsed: RegistryRef.Parsed
    ): Pair<Int, JSONObject?> {
        val resp = get(url, acceptedMediaTypes(), token)
        if (resp.code != 200) return resp.code to null
        return resp.code to runCatching { JSONObject(String(resp.body)) }.getOrNull()
    }

    /** Ask the realm a 401 names for an anonymous pull token. */
    private fun acquireToken(parsed: RegistryRef.Parsed): String {
        val conn = openConnection("https://${parsed.host}/v2/")
        try {
            val code = conn.responseCode
            if (code != 401) return ""
            val challenge = conn.getHeaderField("WWW-Authenticate") ?: return ""
            if (!challenge.startsWith("Bearer")) return ""
            // The scheme prefix "Bearer " is glued to the first key ("Bearer realm=..."),
            // so it must be removed before splitting: otherwise the realm is never found
            // and no token is ever requested, leaving the manifest on a permanent 401.
            val params = challenge.removePrefix("Bearer").trim().split(',')
            fun param(key: String): String? =
                params.firstOrNull { it.trim().startsWith("$key=") }
                    ?.substringAfter("=")
                    ?.trim()
                    ?.removeSurrounding("\"")
            val realm = param("realm") ?: return ""
            val service = param("service") ?: parsed.host
            val scope = param("scope") ?: "repository:${parsed.repoPath}:pull"
            val tokenUrl = "$realm?service=${URLEncoder.encode(service, "UTF-8")}" +
                "&scope=${URLEncoder.encode(scope, "UTF-8")}"
            val resp = get(tokenUrl, emptyMap(), "")
            if (resp.code != 200) return ""
            val body = runCatching { JSONObject(String(resp.body)) }.getOrNull() ?: return ""
            val t = body.optString("token")
            return if (t.isNotEmpty()) t else body.optString("access_token")
        } catch (_: Exception) {
            return ""
        } finally {
            conn.disconnect()
        }
    }

    private fun acceptedMediaTypes(): Map<String, String> = mapOf(
        "Accept" to "application/vnd.oci.image.index.v1+json, " +
            "application/vnd.docker.distribution.manifest.list.v2+json, " +
            "application/vnd.oci.image.manifest.v1+json, " +
            "application/vnd.docker.distribution.manifest.v2+json"
    )

    private data class Small(val code: Int, val body: ByteArray)

    private fun get(url: String, headers: Map<String, String>, token: String): Small {
        val conn = openConnection(url)
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.use { it.readBytes() } ?: ByteArray(0)
        conn.disconnect()
        return Small(code, body)
    }

    private fun downloadToFile(url: String, token: String, target: File): Boolean {
        val conn = openConnection(url)
        if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
        val code = conn.responseCode
        if (code != 200) {
            conn.disconnect()
            target.delete()
            return false
        }
        target.parentFile?.mkdirs()
        var ok = true
        target.outputStream().use { out ->
            try {
                conn.inputStream.use { it.copyTo(out) }
            } catch (_: Exception) {
                ok = false
            }
        }
        conn.disconnect()
        if (!ok) target.delete()
        return ok
    }

    private fun openConnection(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        return conn
    }
}
