package com.sumit.kbmap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class MapperService : AccessibilityService() {

    private class Slot(var x: Float, var y: Float, var stroke: StrokeDescription)

    private val ui = Handler(Looper.getMainLooper())
    private var cfg = Config.parse(Config.DEFAULT)
    private var on = false
    private val pressed = HashSet<Int>()
    private val tapOn = HashSet<Int>()
    private val slots = HashMap<Int, Slot>()
    private var busy = false
    private var dirty = false
    private val dur = 80L

    private val cb = object : GestureResultCallback() {
        override fun onCompleted(g: GestureDescription?) {
            busy = false
            if (slots.isNotEmpty() || dirty) { dirty = false; refresh() }
        }
        override fun onCancelled(g: GestureDescription?) {
            busy = false
            slots.clear()
            if (on && (pressed.isNotEmpty() || tapOn.isNotEmpty())) ui.postDelayed({ refresh() }, 100)
        }
    }

    override fun onServiceConnected() { cfg = Config.load(this) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() { pressed.clear(); tapOn.clear(); refresh() }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        val k = e.keyCode
        if (k == cfg.toggle) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                on = !on
                pressed.clear(); tapOn.clear()
                if (on) cfg = Config.load(this)
                Toast.makeText(this, if (on) "Mapping ON" else "Mapping OFF", Toast.LENGTH_SHORT).show()
                refresh()
            }
            return true
        }
        if (!on || k !in cfg.keys) return false
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (e.repeatCount > 0) return true
            pressed.add(k)
            cfg.binds.forEachIndexed { i, b ->
                if (b.type == "tap" && b.key == k) {
                    tapOn.add(i)
                    ui.postDelayed({ tapOn.remove(i); refresh() }, 120)
                }
            }
        } else if (e.action == KeyEvent.ACTION_UP) {
            pressed.remove(k)
        }
        refresh()
        return true
    }

    private fun refresh() {
        if (busy) { dirty = true; return }
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val gb = GestureDescription.Builder()
        var n = 0
        val finished = ArrayList<Int>()

        cfg.binds.forEachIndexed { i, b ->
            var want = false
            val sx = b.x * w
            val sy = b.y * h
            var tx = sx
            var ty = sy
            when (b.type) {
                "joystick" -> {
                    val dx = (if (b.kRight in pressed) 1f else 0f) - (if (b.kLeft in pressed) 1f else 0f)
                    val dy = (if (b.kDown in pressed) 1f else 0f) - (if (b.kUp in pressed) 1f else 0f)
                    if (dx != 0f || dy != 0f) {
                        want = true
                        val l = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                        val rr = b.r * minOf(w, h)
                        tx = sx + dx / l * rr
                        ty = sy + dy / l * rr
                    }
                }
                "hold" -> want = b.key in pressed
                "tap" -> want = i in tapOn
            }

            val s = slots[i]
            if (want && on) {
                val p = Path()
                val st: StrokeDescription
                if (s == null) {
                    p.moveTo(sx, sy); p.lineTo(tx, ty)
                    st = StrokeDescription(p, 0, dur, true)
                    slots[i] = Slot(tx, ty, st)
                } else {
                    p.moveTo(s.x, s.y); p.lineTo(tx, ty)
                    st = s.stroke.continueStroke(p, 0, dur, true)
                    s.stroke = st; s.x = tx; s.y = ty
                }
                gb.addStroke(st); n++
            } else if (s != null) {
                val p = Path()
                p.moveTo(s.x, s.y); p.lineTo(s.x, s.y)
                gb.addStroke(s.stroke.continueStroke(p, 0, 1, false)); n++
                finished.add(i)
            }
        }
        finished.forEach { slots.remove(it) }
        if (n == 0) return
        busy = true
        if (!dispatchGesture(gb.build(), cb, null)) { busy = false; slots.clear() }
    }
}
