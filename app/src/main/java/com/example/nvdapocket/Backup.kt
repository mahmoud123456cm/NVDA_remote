package com.example.nvdapocket

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One file (JSON text) that holds everything worth keeping: connection profiles, macros, gestures and settings.
 * Not included: remembered server certificates (they are re-learned on the next connect) and the chosen speech engine
 * and voice (they belong to this phone). Connection keys are left out unless the user asks for them, because the
 * export is plain text.
 */
object Backup {
    private const val FORMAT = "pocketlink-backup"
    private const val VERSION = 1

    private val boolKeys = listOf("auto", "ping", "shake", "volkeys", "cues", "cueCmd", "vib", "wakeOn", "clipIn", "taps", "gmBlack", "ignoreSelf")
    private val floatKeys = mapOf("rate" to (0.5f..2.0f), "pitch" to (0.5f..2.0f))
    private val intKeys = mapOf("dist" to (0..160))
    private val stringKeys = mapOf(
        "shareMode" to listOf("ask", "clip", "paste", "type"),
        "cueStyle" to listOf("both", "voice", "sound"),
        "vlang" to listOf("en", "fr", "ar"),
        "vengine" to listOf("silent", "android"),
        "wake" to null)            // free text, length limited

    class Parsed(
        val profiles: List<Store.Profile>,
        val hasKeys: Boolean,
        val macros: List<Store.Macro>,
        val gestures: Map<String, String>,
        val settings: Map<String, Any>)

    fun export(c: Context, includeKeys: Boolean): String {
        val p = Store.prefs(c)
        val o = JSONObject().put("format", FORMAT).put("version", VERSION).put("includesKeys", includeKeys)

        val pa = JSONArray()
        for (pr in Store.profiles(c)) {
            val j = JSONObject().put("name", pr.name).put("host", pr.host).put("port", pr.port).put("trust", pr.trust)
            if (includeKeys) j.put("key", pr.key)
            pa.put(j)
        }
        o.put("profiles", pa)
        o.put("macros", JSONArray(Store.macrosToJson(Store.macros(c))))

        val gm = JSONObject()
        for ((k, v) in Store.gestureMap(c)) gm.put(k, v)
        o.put("gestures", gm)

        val st = JSONObject()
        for (k in boolKeys) if (p.contains(k)) try { st.put(k, p.getBoolean(k, false)) } catch (_: ClassCastException) {}
        for (k in floatKeys.keys) if (p.contains(k)) try { st.put(k, p.getFloat(k, 1f).toDouble()) } catch (_: ClassCastException) {}
        for (k in intKeys.keys) if (p.contains(k)) try { st.put(k, p.getInt(k, 0)) } catch (_: ClassCastException) {}
        for (k in stringKeys.keys) if (p.contains(k)) try { st.put(k, p.getString(k, "") ?: "") } catch (_: ClassCastException) {}
        o.put("settings", st)
        return o.toString(2)
    }

    /** Returns null when the text is not a backup made by this app (or by a newer version of it). */
    fun parse(text: String): Parsed? {
        val o = try { JSONObject(text.trim()) } catch (_: Exception) { return null }
        if (o.optString("format") != FORMAT || o.optInt("version", 0) !in 1..VERSION) return null

        val profiles = ArrayList<Store.Profile>()
        var hasKeys = false
        val pa = o.optJSONArray("profiles")
        if (pa != null) for (i in 0 until pa.length()) {
            val j = pa.optJSONObject(i) ?: continue
            val name = j.optString("name").trim()
            val host = j.optString("host").trim()
            val port = j.optInt("port", 6837)
            if (name.isEmpty() || host.isEmpty() || port !in 1..65535) continue
            val key = j.optString("key", "")
            if (key.isNotEmpty()) hasKeys = true
            profiles.add(Store.Profile(name, host, port, key, j.optBoolean("trust", false)))
        }

        val ma = o.optJSONArray("macros")
        val macros: List<Store.Macro> = if (ma == null) emptyList() else (Store.macrosFromJson(ma.toString()) ?: return null)

        val gestures = LinkedHashMap<String, String>()
        val gj = o.optJSONObject("gestures")
        if (gj != null) for (k in Store.gestureDefaults.keys) if (gj.has(k)) gestures[k] = gj.optString(k, "").trim()

        val settings = LinkedHashMap<String, Any>()
        val sj = o.optJSONObject("settings")
        if (sj != null) {
            for (k in boolKeys) if (sj.has(k) && sj.opt(k) is Boolean) settings[k] = sj.getBoolean(k)
            for ((k, r) in floatKeys) if (sj.has(k)) { val v = sj.optDouble(k, Double.NaN).toFloat(); if (!v.isNaN() && v in r) settings[k] = v }
            for ((k, r) in intKeys) if (sj.has(k)) { val v = sj.optInt(k, -1); if (v in r) settings[k] = v }
            for ((k, allowed) in stringKeys) if (sj.has(k)) {
                val v = sj.optString(k, "")
                if (allowed == null) { if (v.length <= 40) settings[k] = v } else if (v in allowed) settings[k] = v
            }
        }
        return Parsed(profiles, hasKeys, macros, gestures, settings)
    }

    /** One line per kind of content, for the "Import?" question. */
    fun summary(b: Parsed): String {
        val sb = StringBuilder()
        sb.append(b.profiles.size).append(" connection profiles")
        if (b.profiles.isNotEmpty()) sb.append(" (").append(b.profiles.joinToString(", ") { it.name }).append(")")
        sb.append(if (b.hasKeys) ", with keys" else ", without keys. A profile that already exists here keeps its key; new ones need the key typed in").append('\n')
        sb.append(b.macros.size).append(" macros\n")
        sb.append(b.gestures.count { it.value.isNotEmpty() }).append(" gestures with an action\n")
        sb.append(b.settings.size).append(" settings\n\n")
        sb.append("Profiles and macros with the same name are replaced. The gestures in the backup replace your current ones.")
        return sb.toString()
    }

    fun apply(c: Context, b: Parsed) {
        val p = Store.prefs(c)

        // profiles: same name = replaced; a backup without keys keeps the key already saved under that name
        val existing = Store.profiles(c)
        val names = b.profiles.map { it.name }.toSet()
        val merged = existing.filter { it.name !in names }.toMutableList()
        for (n in b.profiles) {
            val key = if (n.key.isNotEmpty()) n.key else existing.firstOrNull { it.name == n.name }?.key.orEmpty()
            merged.add(Store.Profile(n.name, n.host, n.port, key, n.trust))
        }
        Store.saveProfiles(c, merged)

        // macros
        val mnames = b.macros.map { it.name }.toSet()
        val mm = Store.macros(c).filter { it.name !in mnames }.toMutableList()
        mm.addAll(b.macros)
        Store.saveMacros(c, mm)

        // gestures
        if (b.gestures.isNotEmpty()) {
            val gm = Store.gestureMap(c)
            gm.putAll(b.gestures)
            Store.saveGestureMap(c, gm)
        }

        // settings
        val ed = p.edit()
        for ((k, v) in b.settings) when (v) {
            is Boolean -> ed.putBoolean(k, v)
            is Float -> ed.putFloat(k, v)
            is Int -> ed.putInt(k, v)
            is String -> ed.putString(k, v)
        }
        ed.apply()

        // make the running app pick everything up
        Voice.reload()
        GestureConfig.shared.load(c)
        Cue.init(c)
        Remote.clipIn = p.getBoolean("clipIn", true)
        Remote.ttsRate = p.getFloat("rate", 1f)
        Remote.ttsPitch = p.getFloat("pitch", 1f)
        Remote.applyTts()
        Voice.lang = p.getString("vlang", "en") ?: "en"
        ConnLog.add("Imported a backup: ${b.profiles.size} profiles, ${b.macros.size} macros, ${b.settings.size} settings")
    }
}
