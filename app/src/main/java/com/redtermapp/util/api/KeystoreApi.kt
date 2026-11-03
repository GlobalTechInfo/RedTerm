package com.redtermapp.util.api

import android.content.Context
import com.redtermapp.util.api.ApiRequestParser.ApiRequest
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/**
 * `termux-keystore`: keys and signatures in the platform keystore
 * (`AndroidKeyStore`, which is not deprecated and is what the reference's
 * on-device variant uses). Commands mirror the reference: `list`,
 * `create`, `sign`, `verify`, `destroy`. `destroy` goes through the
 * keystore's `deleteKey` reflectively, because that method is not on
 * this build's `KeyStore` stub but is present on the platform at
 * runtime.
 */
object KeystoreApi : ApiFeature {

    override val name = "keystore"
    override val permissions = emptySet<String>()

    override fun run(context: Context, request: ApiRequest): String = try {
        when (val command = request.str("command") ?: "list") {
            "list" -> list()
            "create" -> create(request)
            "sign" -> sign(request)
            "verify" -> verify(request)
            "destroy" -> destroy(request)
            else -> "ERROR: unknown keystore command '$command'\n"
        }
    } catch (e: Exception) {
        "ERROR: ${e.message ?: e.javaClass.simpleName}\n"
    }

    private fun keystore(): KeyStore =
        run { val ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null, null); ks }

    private fun list(): String {
        val sb = StringBuilder("[")
        var first = true
        for (alias in keystore().aliases()) {
            if (keystore().isKeyEntry(alias)) {
                if (!first) sb.append(",")
                first = false
                sb.append(json(alias))
            }
        }
        sb.append("]")
        return sb.toString() + "\n"
    }

    private fun create(request: ApiRequest): String {
        val alias = request.str("alias") ?: return "ERROR: create needs an alias\n"
        val algorithm = (request.str("algorithm") ?: "rsa").lowercase()
        val ks = keystore()
        if (ks.containsAlias(alias)) return "ERROR: key '$alias' already exists\n"

        val keyPair: KeyPair = if (algorithm == "ec") {
            val curve = (request.str("curve") ?: "P-256").uppercase()
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec(curve))
            }.generateKeyPair()
        } else {
            KeyPairGenerator.getInstance("RSA").apply {
                initialize(request.int("size") ?: 2048)
            }.generateKeyPair()
        }
        ks.setKeyEntry(alias, keyPair.private, null, null)
        return "OK\n"
    }

    private fun sign(request: ApiRequest): String {
        val alias = request.str("alias") ?: return "ERROR: sign needs an alias\n"
        val dataHex = request.str("data") ?: return "ERROR: sign needs hex data\n"
        val key = (keystore().getKey(alias, null) as? java.security.PrivateKey)
            ?: return "ERROR: key '$alias' not a private key\n"
        val signature = Signature.getInstance(signatureFor(key, request.str("digest") ?: "SHA-256"))
        signature.initSign(key)
        signature.update(fromHex(dataHex))
        return toHex(signature.sign()) + "\n"
    }

    private fun verify(request: ApiRequest): String {
        val alias = request.str("alias") ?: return "ERROR: verify needs an alias\n"
        val dataHex = request.str("data") ?: return "ERROR: verify needs hex data\n"
        val sigHex = request.str("signature") ?: return "ERROR: verify needs a signature\n"
        val cert = keystore().getCertificate(alias) as? X509Certificate
            ?: return "ERROR: no certificate for '$alias'\n"
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(cert)
        signature.update(fromHex(dataHex))
        val ok = signature.verify(fromHex(sigHex))
        return (if (ok) "true" else "false") + "\n"
    }

    private fun destroy(request: ApiRequest): String {
        val alias = request.str("alias") ?: return "ERROR: destroy needs an alias\n"
        val ks = keystore()
        if (!ks.containsAlias(alias)) return "NOT_FOUND\n"
        // deleteKey is not on this build's KeyStore stub but is on the
        // platform KeyStore at runtime.
        runCatching {
            ks.javaClass.getMethod("deleteKey", String::class.java).invoke(ks, alias)
        }.getOrElse { return "ERROR: could not delete '$alias': ${it.message}\n" }
        return "OK\n"
    }

    private fun signatureFor(key: java.security.Key, digest: String): String {
        val pad = if (key.algorithm.equals("EC", ignoreCase = true)) "ECDSA" else "RSA"
        val hash = when (digest.uppercase().removePrefix("SHA-")) {
            "1" -> "SHA1"
            "384" -> "SHA384"
            "512" -> "SHA512"
            else -> "SHA256"
        }
        return "$hash with$pad"
    }

    private fun json(s: String): String = "\"${s.replace("\"", "\\\"")}\""
    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    private fun fromHex(hex: String): ByteArray {
        val clean = hex.replace(" ", "").removePrefix("0x")
        val out = ByteArray((clean.length + 1) / 2)
        for (i in out.indices) {
            val start = i * 2
            val digits = clean.substring(start, minOf(start + 2, clean.length))
            out[i] = digits.toInt(16).toByte()
        }
        return out
    }
}
