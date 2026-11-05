package com.redtermapp.util.api

import android.content.Context
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.redtermapp.util.api.ApiRequestParser.ApiRequest
import com.redtermapp.util.api.ui.ApiUiBridge

/**
 * `termux-saf-*`: the Storage Access Framework methods the reference
 * exposes, over the document and tree URIs the user granted the app. The
 * whole suite runs through the ContentResolver, so no storage permission
 * is needed — the grant on the URI is what makes each reachable.
 *
 *   getManagedDocumentTrees  list the granted tree roots
 *   listDirectory            children of a tree URI
 *   createDocument           a new file in a tree
 *   readDocument / writeDocument / removeDocument   by document URI
 */
object SafApi : ApiFeature {

    override val name = "saf"
    override val permissions = emptySet<String>()

    override fun run(context: Context, request: ApiRequest): String {
        return when (val method = request.str("safmethod") ?: "getManagedDocumentTrees") {
            "getManagedDocumentTrees" -> trees(context)
            "grant" -> grant(context)
            "listDirectory" -> listDirectory(context, request)
            "createDocument" -> createDocument(context, request)
            "readDocument" -> readDocument(context, request)
            "writeDocument" -> writeDocument(context, request)
            "removeDocument" -> removeDocument(context, request)
            else -> "ERROR: unsupported SAF method '$method'\n"
        }
    }

    /**
     * The trees the app actually holds: the persisted URI grants. The
     * external-storage provider's roots URI is what an earlier version
     * queried, and on recent Android that query is refused outright
     * (Permission Denial: obtain access via ACTION_OPEN_DOCUMENT) when
     * the app has not been granted a tree — so the command failed with a
     * provider error instead of answering the question it was asked:
     * which trees does the app have? Persisted grants answer it, and are
     * the very set the document operations below are allowed to use.
     */
    private fun trees(context: Context): String {
        val perms = context.contentResolver.persistedUriPermissions
        val sb = StringBuilder("[")
        perms.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append('{')
                .append("\"uri\":").append(ApiJson.string(p.uri.toString()))
                .append(",\"tree\":").append(if (p.uri.path?.startsWith("/tree/") == true) "true" else "false")
                .append(",\"read\":").append(p.isReadPermission.toString())
                .append(",\"write\":").append(p.isWritePermission.toString())
                .append('}')
        }
        sb.append(']')
        return sb.toString() + "\n"
    }

    /**
     * The only way the grant exists: a picker the user completes. The
     * URI comes back through the transparent activity, which persists the
     * permission on the spot so the document operations keep working
     * after a reboot.
     */
    private fun grant(context: Context): String =
        ApiUiBridge.show(
            context,
            com.redtermapp.util.api.ui.ApiSafGrantActivity::class.java,
            ApiUiBridge.ApiUiSpec("saf-grant", 180, emptyMap())
        )

    private fun listDirectory(context: Context, request: ApiRequest): String {
        val treeUriStr = request.str("treeuri") ?: return "ERROR: listDirectory needs a tree URI\n"
        val treeUri = treeUriStr.toUri()
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val cursor = context.contentResolver.query(childrenUri, null, null, null, null)
                ?: return "ERROR: the provider refused the listing\n"
            val sb = StringBuilder("[")
            var first = true
            cursor.use { c ->
                while (c.moveToNext()) {
                    if (!first) sb.append(",")
                    first = false
                    sb.append('{')
                        .append("\"name\":").append(ApiJson.string(c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) ?: ""))
                        .append(",\"mime\":").append(ApiJson.string(c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)) ?: ""))
                        .append(",\"id\":").append(ApiJson.string(c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) ?: ""))
                        .append('}')
                }
            }
            sb.append(']')
            sb.toString() + "\n"
        } catch (e: java.lang.SecurityException) {
            "ERROR: no permission for that tree URI: grant it with redterm-saf-grant first\n"
        } catch (e: Exception) {
            "ERROR: listDirectory failed: ${e.message}\n"
        }
    }

    private fun createDocument(context: Context, request: ApiRequest): String {
        val treeUriStr = request.str("treeuri") ?: return "ERROR: createDocument needs a tree URI\n"
        val filename = request.str("filename") ?: return "ERROR: createDocument needs a filename\n"
        val mime = request.str("mimetype") ?: "application/octet-stream"
        return try {
            val treeUri = treeUriStr.toUri()
            val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            val docUri = DocumentsContract.createDocument(
                context.contentResolver, root, mime, filename
            )
            (docUri?.toString() ?: "ERROR: the provider refused the document\n") + "\n"
        } catch (e: java.lang.SecurityException) {
            "ERROR: no permission for that tree URI: grant it with redterm-saf-grant first\n"
        } catch (e: Exception) {
            "ERROR: createDocument failed: ${e.message}\n"
        }
    }

    private fun readDocument(context: Context, request: ApiRequest): String {
        val uriStr = request.str("uri") ?: return "ERROR: readDocument needs a document URI\n"
        if (!uriStr.startsWith("content://")) return "ERROR: only content: URIs are allowed\n"
        return try {
            context.contentResolver.openInputStream(uriStr.toUri()).use { input ->
                if (input == null) "ERROR: the provider refused the read\n"
                else {
                    val out = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > 0xFFFF) {
                            return "ERROR: the document is larger than the 65535-byte answer limit\n"
                        }
                        out.write(chunk, 0, n)
                    }
                    out.toByteArray().toString(Charsets.UTF_8)
                }
            }
        } catch (e: java.lang.SecurityException) {
            "ERROR: no permission for that document URI: grant its tree with redterm-saf-grant first\n"
        } catch (e: Exception) {
            "ERROR: readDocument failed: ${e.message}\n"
        }
    }

    private fun writeDocument(context: Context, request: ApiRequest): String {
        val uriStr = request.str("uri") ?: return "ERROR: writeDocument needs a document URI\n"
        if (!uriStr.startsWith("content://")) return "ERROR: only content: URIs are allowed\n"
        val data = request.str("data") ?: ""
        return try {
            context.contentResolver.openOutputStream(uriStr.toUri(), "wt").use { out ->
                if (out == null) return "ERROR: the provider refused the write\n"
                out.write(data.toByteArray(Charsets.UTF_8))
                "OK\n"
            }
        } catch (e: java.lang.SecurityException) {
            "ERROR: no permission for that document URI: grant its tree with redterm-saf-grant first\n"
        } catch (e: Exception) {
            "ERROR: writeDocument failed: ${e.message}\n"
        }
    }

    private fun removeDocument(context: Context, request: ApiRequest): String {
        val uri = request.str("uri") ?: return "ERROR: removeDocument needs a document URI\n"
        val docUri = uri.toUri()
        return try {
            // DocumentsContract.removeDocument needs the parent's document
            // URI, and the only portable signal for the parent is the
            // child's document ID: the DocumentsUI tree provider encodes
            // the path in it, so a slash means an inferable parent. A
            // provider with opaque child IDs has no ancestry in the ID,
            // and guessing one is how a delete lands on the wrong
            // document — so the honest answer is "not supported here".
            val docId = DocumentsContract.getDocumentId(docUri)
            if ('/' !in docId) {
                return "ERROR: this provider's document id '$docId' does not encode a parent; deletion is not supported for it\n"
            }
            val parent = docId.substringBeforeLast('/')
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(docUri, parent)
            val ok = DocumentsContract.removeDocument(context.contentResolver, docUri, parentUri)
            if (ok) "1\n" else "0\n"
        } catch (e: java.lang.SecurityException) {
            "ERROR: no permission for that document URI: grant its tree with redterm-saf-grant first\n"
        } catch (e: Exception) {
            "ERROR: removeDocument failed: ${e.message}\n"
        }
    }
}
