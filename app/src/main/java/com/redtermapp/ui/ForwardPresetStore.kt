package com.redtermapp.ui

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Saved port-forward settings, one list per server.
 *
 * The forward screen re-enters three numbers on every visit; the ones the
 * user actually uses are the handful they keep using, and those are worth
 * remembering. A preset is per-server, because the device port is the
 * device's and the target is the server's: the same pair opened against two
 * servers is two different forwards.
 *
 * [encode]/[decode] are pure and round-trip tested. A deleted server leaves
 * its presets behind in the record, which is harmless: the picker only
 * offers presets for servers it lists.
 */
object ForwardPresetStore {

    private const val PREFS = "forward_presets"
    private const val KEY = "presets"

    data class Preset(val fromPort: Int, val toHost: String, val toPort: Int)

    /** Presets per server id. Servers without presets are simply absent. */
    fun load(context: Context): Map<String, List<Preset>> =
        decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(context: Context, presets: Map<String, List<Preset>>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY, encode(presets))
        }
    }

    fun addPreset(context: Context, serverId: String, preset: Preset): List<Preset> {
        val all = load(context)
        val list = (all[serverId] ?: emptyList()).filterNot { it == preset } + preset
        save(context, all + (serverId to list))
        return list
    }

    fun encode(presets: Map<String, List<Preset>>): String =
        JSONObject().apply {
            for ((serverId, list) in presets) {
                val arr = org.json.JSONArray()
                for (p in list) {
                    arr.put(
                        JSONObject()
                            .put("fromPort", p.fromPort)
                            .put("toHost", p.toHost)
                            .put("toPort", p.toPort)
                    )
                }
                put(serverId, arr)
            }
        }.toString()

    /** Corrupt entries are dropped, the rest kept: a preset record must never
     * take the forward screen down with it. */
    fun decode(json: String?): Map<String, List<Preset>> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val jsonObject = JSONObject(json)
            val result = HashMap<String, List<Preset>>()
            for (serverId in jsonObject.keys()) {
                val arr = jsonObject.optJSONArray(serverId) ?: continue
                val list = ArrayList<Preset>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val fromPort = o.optInt("fromPort")
                    val toPort = o.optInt("toPort")
                    if (fromPort !in 1..65535 || toPort !in 1..65535) continue
                    list.add(Preset(fromPort, o.optString("toHost", "127.0.0.1"), toPort))
                }
                result[serverId] = list
            }
            result
        }.getOrDefault(emptyMap())
    }
}
