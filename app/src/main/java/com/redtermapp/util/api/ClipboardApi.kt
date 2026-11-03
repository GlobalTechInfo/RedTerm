package com.redtermapp.util.api

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.redtermapp.util.api.ApiRequestParser.ApiRequest
import java.io.File

/**
 * `termux-clipboard-set` / `termux-clipboard-get`. A read only returns a
 * clip while the app holds focus, so the last clip this bridge itself set
 * is kept in app storage: a get that finds nothing in the clipboard falls
 * back to that, and says so, rather than failing a script that just set
 * the value moments earlier.
 */
object ClipboardApi : ApiFeature {

    override val name = "clipboard"
    override val permissions = emptySet<String>()

    private fun lastSetFile(context: Context): File = File(ApiHelper.helperDir(context), "clipboard.txt")

    override fun run(context: Context, request: ApiRequest): String {
        val extras = request.extras
        val op = extras.str("op")
            ?: (if (extras.str("text") != null) "set" else "get")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (op == "set") {
            val text = extras.str("text")
                ?: return "ERROR: clipboard set needs text\n"
            clipboard.setPrimaryClip(ClipData.newPlainText("RedTerm", text))
            runCatching { lastSetFile(context).writeText(text) }
            return ""
        }
        val current = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        if (current != null) return current
        val last = runCatching { lastSetFile(context).readText() }.getOrNull()
        return last?.let { "# last set by RedTerm (clipboard now empty)\n$it" }
            ?: "ERROR: clipboard is empty and no RedTerm-set clip to fall back on\n"
    }
}
