package com.example.nvdapocket

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.TextView
import com.example.nvdapocket.ui.Ui
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/** Settings shared by the pad and the gesture editor. map = gesture id -> action text (empty = nothing). */
class GestureConfig {
    @Volatile var distDp = 100
    @Volatile var tapsOn = true
    var map: MutableMap<String, String> = LinkedHashMap()
}

/**
 * The touch pad. It recognises swipes, taps, double/triple taps, holds, pinch, rotate, edge swipes and corner taps
 * with 1 to 5 fingers. A gesture only does something when the user has given it an action, and the less common
 * ones (double tap, pinch, edge swipe...) are only looked for when assigned, so they never get in the way.
 * In learn mode the next gesture is not run: its id is passed to [learn] instead.
 */
class GesturePad(ctx: Context, private val ui: Ui, private val cfg: GestureConfig) : TextView(ctx) {
    var learn: ((String) -> Unit)? = null

    private var ax = 0f; private var ay = 0f       // anchor: where the last 1-finger step / the multi-finger touch started
    private var sx = 0f; private var sy = 0f       // where the current finger count started (for drift)
    private var lx = 0f; private var ly = 0f       // latest centre of the fingers
    private var x0 = 0f; private var y0 = 0f       // where the very first finger went down
    private var maxP = 0
    private var moved = false
    private var consumed = false                   // an edge swipe already fired for this touch
    private var drift = 0f
    private var downT = 0L
    private var lastFire = 0L
    private var d0 = 0f; private var lastSpread = 0f
    private var a0 = 0f; private var lastRot = 0f
    private var edgeId: String? = null
    private var tapN = 0
    private var tapCount = 0
    private var tapJob: Runnable? = null
    private val repeatIds = setOf("1U", "1D", "1L", "1R")

    private fun thr() = ui.dp(cfg.distDp).toFloat()
    private fun hasAction(id: String) = learn != null || !cfg.map[id].isNullOrEmpty()

    init {
        text = "GESTURE PAD\n\n" +
            "1 finger: swipe = arrows (keep dragging to repeat)\ntap = Enter, hold = Escape\n\n" +
            "2 fingers: swipe right/left = Tab / Shift+Tab\nswipe up/down = Page Up / Page Down\ntap = Space\n\n" +
            "3 fingers: swipe up/down = Home / End\nswipe left/right = Backspace / Delete\ntap = Alt+Tab\n\n" +
            "Many more gestures (double and triple taps, holds, pinch, rotate, edge swipes, corner taps, 4 and 5 fingers) " +
            "do nothing until you choose what they do with Edit gestures."
        textSize = 14f
        gravity = Gravity.CENTER
        setBackgroundColor(0xFFCFE8FF.toInt())
        setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
    }

    // Keep Android's own back gesture from stealing swipes that start at the left or right edge of the pad.
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (Build.VERSION.SDK_INT >= 29) {
            val h = b - t
            val half = ui.dp(100)
            systemGestureExclusionRects = listOf(Rect(0, max(0, h / 2 - half), r - l, minOf(h, h / 2 + half)))
        }
    }

    private fun cx(e: MotionEvent): Float { var t = 0f; for (i in 0 until e.pointerCount) t += e.getX(i); return t / e.pointerCount }
    private fun cy(e: MotionEvent): Float { var t = 0f; for (i in 0 until e.pointerCount) t += e.getY(i); return t / e.pointerCount }
    private fun dir(dx: Float, dy: Float): Char =
        if (abs(dx) > abs(dy)) (if (dx > 0) 'R' else 'L') else (if (dy > 0) 'D' else 'U')

    /** Average distance of the fingers from their centre: grows when spreading, shrinks when pinching. */
    private fun spread(e: MotionEvent): Float {
        val x = cx(e); val y = cy(e); var t = 0f
        for (i in 0 until e.pointerCount) t += hypot(e.getX(i) - x, e.getY(i) - y)
        return t / e.pointerCount
    }

    private fun angle(e: MotionEvent): Float =
        Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()

    private fun normAngle(a: Float): Float {
        var d = a
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return d
    }

    private fun edgeZone(x: Float, y: Float): String? {
        val b = ui.dp(36).toFloat()
        val id = when {
            x < b -> "1EL"
            x > width - b -> "1ER"
            y < b -> "1ET"
            y > height - b -> "1EB"
            else -> null
        }
        return if (id != null && hasAction(id)) id else null
    }

    private fun cornerId(x: Float, y: Float): String? {
        val w = width.toFloat(); val h = height.toFloat()
        val cw = w * 0.22f; val ch = h * 0.22f
        val id = when {
            x < cw && y < ch -> "1CTL"
            x > w - cw && y < ch -> "1CTR"
            x < cw && y > h - ch -> "1CBL"
            x > w - cw && y > h - ch -> "1CBR"
            else -> null
        }
        return if (id != null && hasAction(id)) id else null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                maxP = 1; moved = false; consumed = false; drift = 0f; downT = e.eventTime
                ax = cx(e); ay = cy(e); sx = ax; sy = ay; lx = ax; ly = ay
                x0 = ax; y0 = ay
                edgeId = edgeZone(x0, y0)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount > maxP) {
                    maxP = e.pointerCount; moved = false; drift = 0f; downT = e.eventTime
                    ax = cx(e); ay = cy(e); sx = ax; sy = ay; lx = ax; ly = ay
                    d0 = spread(e); lastSpread = d0
                    a0 = if (maxP == 2) angle(e) else 0f
                    lastRot = 0f
                    edgeId = null
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == maxP) {
                    val x = cx(e); val y = cy(e)
                    drift = max(drift, max(abs(x - sx), abs(y - sy)))
                    if (maxP == 1) {
                        if (consumed) return true
                        val eid = edgeId
                        if (eid != null) {
                            val tx = x - x0; val ty = y - y0
                            val inward = when (eid) { "1EL" -> tx; "1ER" -> -tx; "1ET" -> ty; else -> -ty }
                            val side = if (eid == "1EL" || eid == "1ER") abs(ty) else abs(tx)
                            if (inward >= thr() * 0.8f && inward > side) {
                                consumed = true; moved = true
                                fire(eid)
                                return true
                            }
                        }
                        val dx = x - ax; val dy = y - ay
                        if (abs(dx) > thr() || abs(dy) > thr()) { fire("1" + dir(dx, dy)); ax = x; ay = y; moved = true }
                    } else {
                        lx = x; ly = y
                        lastSpread = spread(e)
                        if (maxP == 2) lastRot = normAngle(angle(e) - a0)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val dur = e.eventTime - downT
                if (maxP >= 2) endMulti(dur)
                else if (maxP == 1 && !moved && cfg.tapsOn && drift < ui.dp(24)) endSingle(dur)
                maxP = 0
            }
            MotionEvent.ACTION_CANCEL -> maxP = 0
        }
        return true
    }

    private fun endSingle(dur: Long) {
        if (dur >= 700) fire("1H")
        else if (dur < 350) {
            val corner = cornerId(x0, y0)
            if (corner != null) fire(corner) else registerTap(1)
        }
    }

    private fun endMulti(dur: Long) {
        val t = thr()
        val dx = lx - ax; val dy = ly - ay
        // Each possible gesture gets a score (1.0 = just reached its threshold); the clearest one wins.
        val cands = ArrayList<Pair<Float, String>>()
        val sw = max(abs(dx), abs(dy)) / (t * 1.3f)
        if (sw >= 1f) cands.add(Pair(sw, "$maxP${dir(dx, dy)}"))
        val ds = lastSpread - d0
        val pm = abs(ds) / (t * 0.35f)
        if (pm >= 1f) {
            val id = "$maxP" + (if (ds < 0) "PI" else "PO")
            if (hasAction(id)) cands.add(Pair(pm, id))
        }
        if (maxP == 2) {
            val rm = abs(lastRot) / 35f
            if (rm >= 1f) {
                val id = if (lastRot > 0) "2RC" else "2RA"
                if (hasAction(id)) cands.add(Pair(rm, id))
            }
        }
        val best = cands.maxByOrNull { it.first }
        if (best != null) { fire(best.second); return }
        if (cfg.tapsOn && drift < ui.dp(24)) {
            if (dur < 350) registerTap(maxP) else if (dur >= 700) fire("${maxP}H")
        }
    }

    /**
     * Counts quick taps with the same number of fingers. A single tap fires at once unless a double or triple tap
     * is assigned for that finger count; then it waits a moment to see whether more taps follow.
     */
    private fun registerTap(n: Int) {
        tapJob?.let { removeCallbacks(it) }
        tapJob = null
        if (n != tapN) { tapN = n; tapCount = 0 }
        tapCount++
        val c = tapCount
        val more = (c == 1 && (hasAction("${n}DT") || hasAction("${n}TT"))) || (c == 2 && hasAction("${n}TT"))
        if (!more || c >= 3) { finishTaps(c); return }
        val r = Runnable { finishTaps(c) }
        tapJob = r
        postDelayed(r, 300)
    }

    private fun finishTaps(c: Int) {
        val n = tapN
        tapCount = 0
        tapJob = null
        fire(when { c >= 3 -> "${n}TT"; c == 2 -> "${n}DT"; else -> "${n}T" })
    }

    private fun fire(id: String) {
        val cb = learn
        if (cb != null) {
            learn = null
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            cb(id)
            return
        }
        if (id !in repeatIds) {
            val now = SystemClock.uptimeMillis()
            if (now - lastFire < 300) return
            lastFire = now
        }
        val action = cfg.map[id] ?: return
        if (action.isEmpty()) return
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        Voice.runLine(action, false)
    }
}
