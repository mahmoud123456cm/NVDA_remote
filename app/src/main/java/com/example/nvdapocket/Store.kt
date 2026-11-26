package com.example.nvdapocket

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object Store {
    class Profile(val name: String, val host: String, val port: Int, val key: String, val trust: Boolean)
    /** kind = "voice": data is steps separated by ";" (same words as voice commands). kind = "raw": recorded key events. */
    class Macro(val name: String, val phrase: String, val kind: String, val data: String, val gap: Int = 400) {
        /** What you can say to run it. "phrase" may hold several phrases separated by commas. */
        fun phrases(): List<String> = phrase.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        fun matches(low: String): Boolean = low == name.trim().lowercase() || low in phrases()
    }

    /** One gesture the pad can recognise. id is "<fingers><kind>", e.g. 2U (two fingers up), 3DT (three-finger double tap). */
    class GestureInfo(val id: String, val label: String, val group: String)

    /** Gestures that have an action out of the box. Everything else starts empty and the user decides what it does. */
    private val builtInActions = mapOf(
        "1U" to "up", "1D" to "down", "1L" to "left", "1R" to "right", "1T" to "enter", "1H" to "escape",
        "2R" to "tab", "2L" to "shift tab", "2U" to "page up", "2D" to "page down", "2T" to "space",
        "3U" to "home", "3D" to "end", "3L" to "backspace", "3R" to "delete", "3T" to "alt tab")

    val gestures: List<GestureInfo> = buildGestures()

    private fun buildGestures(): List<GestureInfo> {
        val out = ArrayList<GestureInfo>()
        for (n in 1..5) {
            val g = if (n == 1) "1 finger" else "$n fingers"
            out.add(GestureInfo("${n}U", "$g: swipe up", g))
            out.add(GestureInfo("${n}D", "$g: swipe down", g))
            out.add(GestureInfo("${n}L", "$g: swipe left", g))
            out.add(GestureInfo("${n}R", "$g: swipe right", g))
            out.add(GestureInfo("${n}T", "$g: tap", g))
            out.add(GestureInfo("${n}DT", "$g: double tap", g))
            out.add(GestureInfo("${n}TT", "$g: triple tap", g))
            out.add(GestureInfo("${n}H", "$g: hold", g))
            if (n >= 2) {
                out.add(GestureInfo("${n}PI", "$g: pinch in", g))
                out.add(GestureInfo("${n}PO", "$g: spread out", g))
            }
            if (n == 2) {
                out.add(GestureInfo("2RC", "$g: rotate clockwise", g))
                out.add(GestureInfo("2RA", "$g: rotate anticlockwise", g))
            }
            if (n == 1) {
                out.add(GestureInfo("1EL", "$g: swipe in from the left edge", g))
                out.add(GestureInfo("1ER", "$g: swipe in from the right edge", g))
                out.add(GestureInfo("1ET", "$g: swipe in from the top edge", g))
                out.add(GestureInfo("1EB", "$g: swipe in from the bottom edge", g))
                out.add(GestureInfo("1CTL", "$g: tap the top-left corner", g))
                out.add(GestureInfo("1CTR", "$g: tap the top-right corner", g))
                out.add(GestureInfo("1CBL", "$g: tap the bottom-left corner", g))
                out.add(GestureInfo("1CBR", "$g: tap the bottom-right corner", g))
            }
        }
        return out
    }

    val gestureDefaults: LinkedHashMap<String, String> = LinkedHashMap<String, String>().also { m ->
        for (g in gestures) m[g.id] = builtInActions[g.id] ?: ""
    }

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("p", Context.MODE_PRIVATE)

    fun profiles(c: Context): MutableList<Profile> {
        val out = mutableListOf<Profile>()
        try {
            val a = JSONArray(prefs(c).getString("profiles", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Profile(o.getString("name"), o.getString("host"), o.optInt("port", 6837), Secret.decrypt(o.getString("key")), o.optBoolean("trust")))
            }
        } catch (_: Exception) {}
        return out
    }

    fun saveProfiles(c: Context, list: List<Profile>) {
        val a = JSONArray()
        for (p in list) a.put(JSONObject().put("name", p.name).put("host", p.host).put("port", p.port).put("key", Secret.encrypt(p.key)).put("trust", p.trust))
        prefs(c).edit().putString("profiles", a.toString()).apply()
    }

    fun macros(c: Context): MutableList<Macro> =
        (macrosFromJson(prefs(c).getString("macros", "[]") ?: "[]") ?: emptyList()).toMutableList()

    fun saveMacros(c: Context, list: List<Macro>) {
        prefs(c).edit().putString("macros", macrosToJson(list)).apply()
    }

    fun macrosToJson(list: List<Macro>): String {
        val a = JSONArray()
        for (m in list) a.put(JSONObject().put("name", m.name).put("phrase", m.phrase).put("kind", m.kind).put("data", m.data).put("gap", m.gap))
        return a.toString(2)
    }

    /** Returns null when the text is not a list of macros. */
    fun macrosFromJson(text: String): List<Macro>? {
        return try {
            val a = JSONArray(text.trim())
            val out = ArrayList<Macro>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Macro(o.getString("name"), o.getString("phrase"), o.getString("kind"), o.getString("data"), o.optInt("gap", 400).coerceIn(0, 10000)))
            }
            out
        } catch (_: Exception) { null }
    }

    // ---------- remembered server certificates (trust on first use) ----------
    fun pin(c: Context, id: String): String? = try {
        JSONObject(prefs(c).getString("pins", "{}") ?: "{}").optString(id).ifEmpty { null }
    } catch (_: Exception) { null }

    fun setPin(c: Context, id: String, fingerprint: String) {
        val o = try { JSONObject(prefs(c).getString("pins", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        prefs(c).edit().putString("pins", o.put(id, fingerprint).toString()).apply()
    }

    fun clearPin(c: Context, id: String) {
        val o = try { JSONObject(prefs(c).getString("pins", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        o.remove(id)
        prefs(c).edit().putString("pins", o.toString()).apply()
    }

    fun gestureMap(c: Context): MutableMap<String, String> {
        val m = LinkedHashMap(gestureDefaults)
        try {
            val o = JSONObject(prefs(c).getString("gestures", "{}"))
            for (k in gestureDefaults.keys) if (o.has(k)) m[k] = o.getString(k)
        } catch (_: Exception) {}
        return m
    }

    fun saveGestureMap(c: Context, m: Map<String, String>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        prefs(c).edit().putString("gestures", o.toString()).apply()
    }
}
