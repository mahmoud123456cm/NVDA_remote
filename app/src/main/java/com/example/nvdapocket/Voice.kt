package com.example.nvdapocket

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/** Turns spoken or typed command text into key presses. Also runs custom macros. */
object Voice {
    private val ui = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var macrosCache: List<Store.Macro> = emptyList()
    private val memo = HashMap<String, List<Act>>()
    var lang = "en"
        set(v) { field = v; memo.clear() }

    class Act(val mods: List<Pair<Int, Boolean>>, val key: Pair<Int, Boolean>?, val count: Int, val text: String? = null)

    fun init(c: Context) { app = c.applicationContext; reload() }
    fun reload() { app?.let { macrosCache = Store.macros(it) }; memo.clear() }

    // ---------------- vocabulary ----------------
    private val numWords = mapOf("zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12)
    private val homoWords = mapOf("to" to 2, "too" to 2, "for" to 4)

    private fun buildKeyWords(): Map<String, Pair<Int, Boolean>> {
        val m = HashMap<String, Pair<Int, Boolean>>()
        fun k(vk: Int, ext: Boolean, vararg names: String) { for (n in names) m[n] = Pair(vk, ext) }
        k(0x0D, false, "enter", "return")
        k(0x09, false, "tab")
        k(0x1B, false, "escape", "esc")
        k(0x20, false, "space", "spacebar")
        k(0x08, false, "backspace")
        k(0x2E, true, "delete", "del")
        k(0x26, true, "up"); k(0x28, true, "down"); k(0x25, true, "left"); k(0x27, true, "right")
        k(0x24, true, "home"); k(0x23, true, "end")
        k(0x21, true, "pageup"); k(0x22, true, "pagedown")
        k(0x2D, true, "ins")
        k(0x14, false, "capslock")
        k(0x5D, true, "menu", "applications")
        k(0x2C, true, "printscreen")
        k(0x91, false, "scrolllock")
        k(0x13, false, "pause", "break")
        k(0x90, true, "numlock")
        k(0xBD, false, "minus", "dash", "hyphen")
        k(0xBB, false, "equals", "equal")
        k(0xDB, false, "openbracket")
        k(0xDD, false, "closebracket")
        k(0xDC, false, "backslash")
        k(0xBA, false, "semicolon")
        k(0xDE, false, "quote", "apostrophe")
        k(0xBC, false, "comma")
        k(0xBE, false, "period", "dot")
        k(0xBF, false, "slash")
        k(0xC0, false, "backtick", "grave", "tilde")
        val dw = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
        for (d in 0..9) { m["numpad$d"] = Pair(0x60 + d, false); m["numpad" + dw[d]] = Pair(0x60 + d, false) }
        k(0x6B, false, "numpadplus", "numpadadd")
        k(0x6D, false, "numpadminus", "numpadsubtract")
        k(0x6A, false, "numpadmultiply", "numpadstar")
        k(0x6F, true, "numpaddivide", "numpadslash")
        k(0x6E, false, "numpaddot", "numpaddecimal", "numpadperiod")
        k(0x0D, true, "numpadenter")
        return m
    }
    private val keyWords = buildKeyWords()

    private val modWords = mapOf(
        "control" to Pair(0x11, false), "ctrl" to Pair(0x11, false), "alt" to Pair(0x12, false),
        "shift" to Pair(0x10, false),
        "insert" to Pair(0x2D, true), "nvda" to Pair(0x2D, true), "nvidia" to Pair(0x2D, true),
        "invidia" to Pair(0x2D, true), "envy" to Pair(0x2D, true),
        "windows" to Pair(0x5B, true), "window" to Pair(0x5B, true), "win" to Pair(0x5B, true))

    private val nato = mapOf("alpha" to 'a', "bravo" to 'b', "charlie" to 'c', "delta" to 'd', "echo" to 'e',
        "foxtrot" to 'f', "golf" to 'g', "hotel" to 'h', "india" to 'i', "juliet" to 'j', "kilo" to 'k',
        "lima" to 'l', "mike" to 'm', "november" to 'n', "oscar" to 'o', "papa" to 'p', "quebec" to 'q',
        "romeo" to 'r', "sierra" to 's', "tango" to 't', "uniform" to 'u', "victor" to 'v', "whiskey" to 'w',
        "xray" to 'x', "yankee" to 'y', "zulu" to 'z')

    private val letterAlias = mapOf(
        "be" to 'b', "bee" to 'b', "see" to 'c', "sea" to 'c', "dee" to 'd', "ee" to 'e', "eff" to 'f', "ef" to 'f',
        "gee" to 'g', "aitch" to 'h', "eye" to 'i', "jay" to 'j', "kay" to 'k', "el" to 'l', "em" to 'm',
        "en" to 'n', "in" to 'n', "and" to 'n', "an" to 'n', "oh" to 'o', "owe" to 'o', "pee" to 'p', "pea" to 'p',
        "cue" to 'q', "queue" to 'q', "are" to 'r', "ess" to 's', "es" to 's', "tea" to 't', "tee" to 't',
        "you" to 'u', "vee" to 'v', "dub" to 'w', "ex" to 'x', "why" to 'y', "zee" to 'z', "zed" to 'z')

    private val norm = listOf(
        "page up" to "pageup", "page down" to "pagedown", "caps lock" to "capslock", "back space" to "backspace",
        "num lock" to "numlock", "scroll lock" to "scrolllock", "print screen" to "printscreen",
        "forward slash" to "slash", "back slash" to "backslash", "open bracket" to "openbracket",
        "left bracket" to "openbracket", "close bracket" to "closebracket", "right bracket" to "closebracket",
        "windows key" to "windows", "context menu" to "menu", "double u" to "w", "n v d a" to "nvda")

    private val macroList = listOf(
        "select all" to "control a", "copy" to "control c", "cut" to "control x", "paste" to "control v",
        "undo" to "control z", "redo" to "control y", "save" to "control s", "find" to "control f",
        "new tab" to "control t", "close tab" to "control w", "close window" to "alt f4",
        "switch window" to "alt tab", "next window" to "alt tab", "start menu" to "windows",
        "show desktop" to "windows d", "lock screen" to "windows l", "run dialog" to "windows r",
        "file explorer" to "windows e", "task manager" to "control shift escape", "refresh" to "f5",
        "stop speech" to "control", "pause speech" to "shift",
        "nvda menu" to "nvda n", "say all" to "nvda down", "read all" to "nvda down", "read line" to "nvda up",
        "read title" to "nvda t", "where am i" to "nvda tab", "elements list" to "nvda f7",
        "speech mode" to "nvda s", "nvda settings" to "nvda control g",
        "next heading" to "h", "previous heading" to "shift h", "next link" to "k", "next button" to "b",
        "next table" to "t", "next landmark" to "d", "next form field" to "f", "next edit field" to "e"
    ).sortedByDescending { it.first.length }
    private val macroRegex = macroList.map { Regex("\\b" + Regex.escape(it.first) + "\\b") to " ${it.second} " }

    private val fr = listOf(
        "retour arrière" to "backspace", "retour arriere" to "backspace", "échappement" to "escape",
        "echappement" to "escape", "échap" to "escape", "echap" to "escape", "entrée" to "enter", "entree" to "enter",
        "espace" to "space", "supprimer" to "delete", "effacer" to "backspace", "haut" to "up", "bas" to "down",
        "gauche" to "left", "droite" to "right", "début" to "home", "debut" to "home", "accueil" to "home",
        "fin" to "end", "page précédente" to "page up", "page precedente" to "page up", "page suivante" to "page down",
        "tabulation" to "tab", "contrôle" to "control", "controle" to "control", "majuscule" to "shift",
        "touche windows" to "windows", "tout sélectionner" to "select all", "tout selectionner" to "select all",
        "sélectionner tout" to "select all", "copier" to "copy", "coller" to "paste", "couper" to "cut",
        "annuler" to "undo", "rétablir" to "redo", "enregistrer" to "save", "rechercher" to "find",
        "nouvel onglet" to "new tab", "fermer l'onglet" to "close tab", "fermer la fenêtre" to "close window",
        "changer de fenêtre" to "switch window", "menu démarrer" to "start menu", "bureau" to "show desktop",
        "tout lire" to "say all", "lire la ligne" to "read line", "lire le titre" to "read title",
        "un" to "1", "deux" to "2", "trois" to "3", "quatre" to "4", "cinq" to "5", "six" to "6", "sept" to "7",
        "huit" to "8", "neuf" to "9", "dix" to "10", "fois" to "", "touche" to "").sortedByDescending { it.first.length }

    private val ar = listOf(
        "إدخال" to "enter", "ادخال" to "enter", "انتر" to "enter", "مسافة" to "space", "حذف" to "delete",
        "مسح" to "backspace", "أعلى" to "up", "اعلى" to "up", "فوق" to "up", "أسفل" to "down", "اسفل" to "down",
        "تحت" to "down", "يسار" to "left", "يمين" to "right", "بداية" to "home", "نهاية" to "end", "تاب" to "tab",
        "هروب" to "escape", "إسكيب" to "escape", "اسكيب" to "escape", "تحكم" to "control", "كونترول" to "control",
        "شيفت" to "shift", "ويندوز" to "windows", "نسخ" to "copy", "لصق" to "paste", "قص" to "cut",
        "تراجع" to "undo", "حفظ" to "save", "تحديد الكل" to "select all", "بحث" to "find",
        "قراءة الكل" to "say all", "قراءة السطر" to "read line", "ان في دي ايه" to "nvda", "إن في دي إيه" to "nvda",
        "واحد" to "1", "اثنان" to "2", "اثنين" to "2", "ثلاثة" to "3", "أربعة" to "4", "خمسة" to "5"
    ).sortedByDescending { it.first.length }

    private fun translate(s: String): String {
        val list = when (lang) { "fr" -> fr; "ar" -> ar; else -> return s }
        var t = " $s "
        for ((a, b) in list) {
            t = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(a) + "(?![\\p{L}\\p{N}])").replace(t) { " $b " }
        }
        return t
    }

    private fun numOf(t: String, loose: Boolean): Int? =
        t.toIntOrNull() ?: numWords[t] ?: (if (loose) homoWords[t] else null)


    // ---------------- vocabulary for the offline recogniser ----------------
    /** Changes whenever the custom macros change, so the offline recogniser knows to rebuild its vocabulary. */
    fun grammarStamp(): Int = lang.hashCode() * 31 + macrosCache.joinToString("|") { it.name + "=" + it.phrase }.hashCode()

    /**
     * Everything that can be said as a command, in the voice language. The offline recogniser is restricted to this
     * vocabulary, so "copy" can no longer be heard as "coffee". Free text ("type ...") and dictation are not in it:
     * they fall back to the open recogniser.
     */
    fun grammarPhrases(wake: String): List<String> {
        val out = LinkedHashSet<String>()
        fun add(s: String) { val t = s.trim().lowercase(); if (t.isNotEmpty()) out.add(t) }
        if (wake.isNotBlank()) add(wake)
        for (m in macrosCache) { add(m.name); m.phrases().forEach { add(it) } }
        builtinPhrases().forEach { add(it) }
        val mods = ArrayList<String>(); val keys = ArrayList<String>()
        fun addAll(map: List<Pair<String, String>>) {
            for ((spoken, meaning) in map) {
                val first = meaning.trim().split(' ').firstOrNull().orEmpty()
                when {
                    meaning.isBlank() -> add(spoken)                           // filler like "fois" / "touche"
                    meaning.contains(' ') -> add(spoken)                       // a whole command ("select all")
                    modWords.containsKey(first) -> { mods.add(spoken); add(spoken) }
                    else -> { keys.add(spoken); add(spoken) }
                }
            }
        }
        when (lang) {
            "fr" -> addAll(fr)
            "ar" -> addAll(ar)
            else -> {
                macroList.forEach { add(it.first) }
                modWords.keys.filter { it != "nvidia" && it != "invidia" && it != "envy" && it != "window" }.forEach { mods.add(it); add(it) }
                keys.addAll(listOf("enter", "tab", "escape", "space", "backspace", "delete", "up", "down", "left", "right",
                    "home", "end", "page up", "page down", "caps lock", "menu", "print screen", "numpad plus", "numpad minus"))
                keys.addAll(nato.keys); keys.addAll(numWords.keys.filter { it != "eleven" && it != "twelve" })
                for (n in 1..12) keys.add("f " + (numWords.entries.firstOrNull { it.value == n }?.key ?: n.toString()))
                for (c in 'a'..'z') keys.add(c.toString())
                keys.forEach { add(it) }
                for (n in listOf("two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")) {   // "down three times"
                    for (k in listOf("up", "down", "left", "right", "tab", "enter", "backspace", "delete", "page up", "page down")) {
                        add("$k $n"); add("$k $n times")
                    }
                }
                add("nvda"); add("stop listening"); add("start dictation")
            }
        }
        val m1 = mods.distinct().take(8)
        val k1 = keys.distinct()
        for (m in m1) for (k in k1) add("$m $k")
        val m2 = m1.filter { it in setOf("control", "alt", "shift", "windows", "contrôle", "majuscule", "تحكم", "شيفت", "ويندوز") }
        for (a in m2) for (b in m2) if (a != b) for (k in k1) add("$a $b $k")
        return out.toList()
    }

    // ---------------- parsing ----------------
    fun parse(raw: String, fromVoice: Boolean): List<Act> {
        if (!fromVoice) memo[raw]?.let { return it }
        val r = doParse(raw, fromVoice)
        if (!fromVoice) memo[raw] = r
        return r
    }

    private fun doParse(raw: String, fromVoice: Boolean): List<Act> {
        var s = raw.lowercase().trim()
        for (p in listOf("type ", "write ", "dictate ", "text ", "tape ", "écris ", "اكتب ")) {
            if (s.startsWith(p)) return listOf(Act(emptyList(), null, 1, raw.trim().substring(p.length)))
        }
        if (fromVoice) s = translate(s)
        s = " $s "
        for ((rx, v) in macroRegex) s = rx.replace(s) { v }
        s = s.replace("+", " ").replace(",", " ").replace(".", " ").replace("-", " ")
        for ((a, b) in norm) s = s.replace(a, b)
        s = Regex("numpad\\s+(\\w+)").replace(s) { "numpad" + it.groupValues[1] }
        s = Regex("\\bplus\\b").replace(s, " ")
        s = s.replace("arrow", " ")
        val skip = setOf("press", "hit", "key", "the", "times", "time", "then", "letter", "number")
        val toks = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it !in skip }

        val acts = mutableListOf<Act>()
        val mods = mutableListOf<Pair<Int, Boolean>>()
        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            val m = modWords[t]
            if (m != null) { mods.add(m); i++; continue }
            var k: Pair<Int, Boolean>? = null
            var consumed = 1
            val fm = Regex("^f(\\d{1,2})$").find(t)
            val fnum = if (t == "f" && i + 1 < toks.size) numOf(toks[i + 1], false) else null
            if (fm != null && fm.groupValues[1].toInt() in 1..12) {
                k = Pair(0x70 + fm.groupValues[1].toInt() - 1, false)
            } else if (fnum != null && fnum in 1..12) {
                k = Pair(0x70 + fnum - 1, false); consumed = 2
            } else {
                k = keyWords[t]
            }
            if (k == null && t.length == 1 && t[0] in 'a'..'z') k = Pair(t[0].uppercaseChar().code, false)
            if (k == null) { val c = nato[t]; if (c != null) k = Pair(c.uppercaseChar().code, false) }
            if (k == null) { val n = numOf(t, false); if (n != null && n in 0..9) k = Pair(0x30 + n, false) }
            if (k == null && mods.isNotEmpty()) { val c = letterAlias[t]; if (c != null) k = Pair(c.uppercaseChar().code, false) }
            if (k == null) { i++; continue }
            val kk: Pair<Int, Boolean> = k
            i += consumed
            var count = 1
            if (mods.isEmpty() && i < toks.size) {
                val n = numOf(toks[i], true)
                if (n != null && n in 1..50) { count = n; i++ }
            }
            acts.add(Act(mods.toList(), kk, count))
            mods.clear()
        }
        if (mods.isNotEmpty()) acts.add(Act(mods.toList(), null, 1))
        return acts
    }

    fun execute(acts: List<Act>) {
        for (a in acts) {
            if (a.text != null) { Remote.sendText(a.text); continue }
            a.mods.forEach { Remote.sendKey(it.first, it.second, true) }
            val k = a.key
            if (k != null) repeat(a.count) { Remote.tap(k.first, k.second) }
            a.mods.reversed().forEach { Remote.sendKey(it.first, it.second, false) }
        }
    }

    /** Runs a phrase: custom macro first, then built-in commands. Returns false when nothing was understood. */
    fun run(raw: String, fromVoice: Boolean): Boolean {
        val low = raw.trim().lowercase()
        if (low.isEmpty()) return false
        for (m in macrosCache) {
            if (m.matches(low)) { runMacro(m); return true }
        }
        when (low) {
            "push clipboard", "send clipboard" -> { val c = app ?: return false; Remote.pushClipboard(c); return true }
            "get clipboard", "get pc clipboard", "pull clipboard" -> { Remote.fetchPcClipboard(false); return true }
            "get selection", "copy selection" -> { Remote.fetchPcClipboard(true); return true }
            "last clipboard", "copy last clipboard" -> { Remote.recopyLastPcClip(); return true }
            "toggle mute", "mute speech", "unmute speech" -> { Remote.toggleMute(); return true }
            "send sas", "control alt delete", "ctrl alt delete" -> { Remote.sendJson(JSONObject().put("type", "send_SAS")); return true }
        }
        val acts = parse(raw, fromVoice)
        if (acts.isEmpty()) return false
        execute(acts)
        return true
    }

    private val waitRx = Regex("^(?:wait|pause)\\s+(\\d+(?:\\.\\d+)?)\\s*(ms|milliseconds?|s|secs?|seconds?)?$", RegexOption.IGNORE_CASE)

    private fun waitMs(m: MatchResult): Long {
        val n = m.groupValues[1].toDoubleOrNull() ?: 0.0
        val unit = m.groupValues[2].lowercase()
        return (if (unit.startsWith("m")) n else n * 1000.0).toLong().coerceIn(0L, 60_000L)
    }

    /**
     * Runs one line of commands. Steps separated by ";" run one after another with [gap] milliseconds between them.
     * A step like "wait 2" (seconds) or "wait 500 ms" adds an extra pause.
     */
    fun runLine(raw: String, fromVoice: Boolean = false, gap: Int = 400): Boolean {
        val steps = raw.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        if (steps.isEmpty()) return false
        if (steps.size == 1) return if (waitRx.matches(steps[0])) true else run(steps[0], fromVoice)
        var delay = 0L
        for (st in steps) {
            val w = waitRx.matchEntire(st)
            if (w != null) { delay += waitMs(w); continue }
            ui.postDelayed({ run(st, fromVoice) }, delay)
            delay += gap
        }
        return true
    }

    /** Every phrase that already means something, so the macro editor can warn about clashes. */
    fun builtinPhrases(): Set<String> {
        val s = HashSet<String>()
        macroList.forEach { s.add(it.first) }
        s.addAll(listOf("push clipboard", "send clipboard", "get clipboard", "get pc clipboard", "pull clipboard",
            "get selection", "copy selection", "last clipboard", "copy last clipboard", "toggle mute", "mute speech",
            "unmute speech", "send sas", "control alt delete", "ctrl alt delete", "start dictation", "stop dictation",
            "stop listening"))
        return s
    }

    // ---------------- recorded macros -> readable steps ----------------
    private val preferredKeyNames = listOf("enter", "tab", "escape", "space", "backspace", "delete", "up", "down", "left", "right",
        "home", "end", "pageup", "pagedown", "ins", "capslock", "menu", "printscreen", "scrolllock", "pause", "numlock",
        "minus", "equals", "openbracket", "closebracket", "backslash", "semicolon", "quote", "comma", "period", "slash",
        "backtick", "numpad0", "numpad1", "numpad2", "numpad3", "numpad4", "numpad5", "numpad6", "numpad7", "numpad8",
        "numpad9", "numpadplus", "numpadminus", "numpadmultiply", "numpaddivide", "numpaddot", "numpadenter")
    private val keyNameOf: Map<Pair<Int, Boolean>, String> by lazy {
        val m = HashMap<Pair<Int, Boolean>, String>()
        for (n in preferredKeyNames) keyWords[n]?.let { if (!m.containsKey(it)) m[it] = n }
        m
    }

    private fun modName(vk: Int, ext: Boolean): String? = when {
        vk == 0x11 -> "control"; vk == 0x12 -> "alt"; vk == 0x10 -> "shift"
        vk == 0x5B && ext -> "windows"; vk == 0x2D && ext -> "nvda"
        else -> null
    }

    private fun keyName(vk: Int, ext: Boolean): String? = when (vk) {
        in 0x41..0x5A -> ('a' + (vk - 0x41)).toString()
        in 0x30..0x39 -> (vk - 0x30).toString()
        in 0x70..0x7B -> "f" + (vk - 0x70 + 1)
        else -> keyNameOf[Pair(vk, ext)]
    }

    /** Best-effort: turns recorded key events ("vk,ext,pressed;...") into steps. The timing between keys is lost. */
    fun rawToSteps(data: String): List<String> {
        val steps = ArrayList<String>()
        val typed = StringBuilder()
        fun flush() { if (typed.isNotEmpty()) { steps.add("type $typed"); typed.setLength(0) } }
        val held = ArrayList<String>()      // modifier names currently down, in order
        var usedWithKey = false
        for (e in data.split(";")) {
            val p = e.split(",")
            if (p.size != 3) continue
            val vk = p[0].toIntOrNull() ?: continue
            val ext = p[1] == "1"
            val down = p[2] == "1"
            val mod = modName(vk, ext)
            if (mod != null) {
                if (down) { if (held.isEmpty()) usedWithKey = false; if (mod !in held) held.add(mod) }
                else if (mod in held) {
                    val snapshot = held.toList()
                    held.remove(mod)
                    if (held.isEmpty() && !usedWithKey) { flush(); steps.add(snapshot.joinToString(" ")) }
                }
                continue
            }
            if (!down) continue
            val name = keyName(vk, ext) ?: continue
            usedWithKey = true
            val plainChar = name.length == 1 || name == "space"
            if (held.isEmpty() && plainChar) { typed.append(if (name == "space") ' ' else name[0]); continue }
            if (held == listOf("shift") && name.length == 1 && name[0].isLetter()) { typed.append(name[0].uppercaseChar()); continue }
            flush()
            steps.add((held + name).joinToString(" "))
        }
        flush()
        return steps
    }

    fun runMacro(m: Store.Macro) {
        if (m.kind == "raw") {
            var delay = 0L
            for (e in m.data.split(";")) {
                val p = e.split(",")
                if (p.size != 3) continue
                val vk = p[0].toIntOrNull() ?: continue
                val ext = p[1] == "1"
                val pressed = p[2] == "1"
                ui.postDelayed({ Remote.sendKey(vk, ext, pressed) }, delay)
                delay += 30
            }
        } else {
            runLine(m.data, false, m.gap)
        }
    }
}
