package com.example.nvdapocket

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object Store {
    class Profile(val name: String, val host: String, val port: Int, val key: String, val trust: Boolean)
    /** kind = "voice": data is steps separated by ";" (same words as voice commands). kind = "raw": recorded key events. */
    class Macro(val name: String, val phrase: String, val kind: String, val data: String)

    val gestureDefaults: LinkedHashMap<String, String> = linkedMapOf(
        "1U" to "up", "1D" to "down", "1L" to "left", "1R" to "right", "1T" to "enter", "1H" to "escape",
        "2R" to "tab", "2L" to "shift tab", "2U" to "page up", "2D" to "page down", "2T" to "space",
        "3U" to "home", "3D" to "end", "3L" to "backspace", "3R" to "delete", "3T" to "alt tab")

    val gestureLabels: LinkedHashMap<String, String> = linkedMapOf(
        "1U" to "1 finger: swipe up", "1D" to "1 finger: swipe down", "1L" to "1 finger: swipe left",
        "1R" to "1 finger: swipe right", "1T" to "1 finger: tap", "1H" to "1 finger: hold",
        "2R" to "2 fingers: swipe right", "2L" to "2 fingers: swipe left", "2U" to "2 fingers: swipe up",
        "2D" to "2 fingers: swipe down", "2T" to "2 fingers: tap",
        "3U" to "3 fingers: swipe up", "3D" to "3 fingers: swipe down", "3L" to "3 fingers: swipe left",
        "3R" to "3 fingers: swipe right", "3T" to "3 fingers: tap")

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("p", Context.MODE_PRIVATE)

    fun profiles(c: Context): MutableList<Profile> {
        val out = mutableListOf<Profile>()
        try {
            val a = JSONArray(prefs(c).getString("profiles", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Profile(o.getString("name"), o.getString("host"), o.optInt("port", 6837), o.getString("key"), o.optBoolean("trust")))
            }
        } catch (_: Exception) {}
        return out
    }

    fun saveProfiles(c: Context, list: List<Profile>) {
        val a = JSONArray()
        for (p in list) a.put(JSONObject().put("name", p.name).put("host", p.host).put("port", p.port).put("key", p.key).put("trust", p.trust))
        prefs(c).edit().putString("profiles", a.toString()).apply()
    }

    fun macros(c: Context): MutableList<Macro> {
        val out = mutableListOf<Macro>()
        try {
            val a = JSONArray(prefs(c).getString("macros", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Macro(o.getString("name"), o.getString("phrase"), o.getString("kind"), o.getString("data")))
            }
        } catch (_: Exception) {}
        return out
    }

    fun saveMacros(c: Context, list: List<Macro>) {
        val a = JSONArray()
        for (m in list) a.put(JSONObject().put("name", m.name).put("phrase", m.phrase).put("kind", m.kind).put("data", m.data))
        prefs(c).edit().putString("macros", a.toString()).apply()
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
