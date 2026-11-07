package com.sumit.kbmap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as WLP
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MapperService : AccessibilityService() {

    private class Slot(var x: Float, var y: Float, var stroke: StrokeDescription)

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var cfg = Config.parse(Config.DEFAULT)
    private var on = false
    private var editing = false
    private var pending: Bind? = null
    private var lastBound = -1
    private val pressed = HashSet<Int>()
    private val tapOn = HashSet<Int>()
    private val slots = HashMap<Int, Slot>()
    private val views = HashMap<Bind, View>()
    private var busy = false
    private var dirty = false
    private val dur = 80L

    private lateinit var layer: FrameLayout
    private lateinit var layerLp: WLP
    private lateinit var hud: LinearLayout
    private lateinit var hudLp: WLP
    private lateinit var tvOn: TextView
    private lateinit var tvEdit: TextView
    private lateinit var tvKey: TextView
    private lateinit var addRow: LinearLayout

    private val baseFlags = WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_LAYOUT_IN_SCREEN or WLP.FLAG_LAYOUT_NO_LIMITS

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

    // ---------- lifecycle ----------

    override fun onServiceConnected() {
        cfg = Config.load(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildLayer()
        buildHud()
        redrawButtons()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() { pressed.clear(); tapOn.clear(); refresh() }

    override fun onUnbind(i: Intent?): Boolean {
        try { wm.removeView(layer) } catch (_: Exception) {}
        try { wm.removeView(hud) } catch (_: Exception) {}
        return super.onUnbind(i)
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        if (!::layer.isInitialized) return
        val (w, h) = screen()
        layerLp.width = w
        layerLp.height = h
        wm.updateViewLayout(layer, layerLp)
        redrawButtons()
    }

    // ---------- helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun screen(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    private fun short(k: Int): String {
        if (k == 0) return "?"
        val s = KeyEvent.keyCodeToString(k).removePrefix("KEYCODE_")
        return if (s.length > 5) s.substring(0, 5) else s
    }

    private fun label(b: Bind): String {
        if (b.type != "joystick") return short(b.key)
        val l = listOf(b.kUp, b.kLeft, b.kDown, b.kRight).map { short(it) }
        return if (l.all { it.length == 1 }) l.joinToString("") else "JOY"
    }

    private fun chip(t: String, click: () -> Unit) = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 12f
        setPadding(dp(10), dp(7), dp(10), dp(7))
        setOnClickListener { click() }
    }

    // ---------- overlay windows ----------

    private fun buildLayer() {
        val (w, h) = screen()
        layer = FrameLayout(this)
        layerLp = WLP(w, h, WLP.TYPE_ACCESSIBILITY_OVERLAY, baseFlags or WLP.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WLP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(layer, layerLp)
    }

    private fun buildHud() {
        hudLp = WLP(WLP.WRAP_CONTENT, WLP.WRAP_CONTENT, WLP.TYPE_ACCESSIBILITY_OVERLAY, baseFlags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(48)
            layoutInDisplayCutoutMode = WLP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC15121F.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val handle = TextView(this).apply {
            text = "\u283F KB Mapper"
            setTextColor(0xFFA67DF3.toInt())
            textSize = 12f
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        var ox = 0f
        var oy = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { ox = e.rawX - hudLp.x; oy = e.rawY - hudLp.y }
                MotionEvent.ACTION_MOVE -> {
                    hudLp.x = (e.rawX - ox).toInt()
                    hudLp.y = (e.rawY - oy).toInt()
                    wm.updateViewLayout(hud, hudLp)
                }
            }
            true
        }

        tvOn = chip("Mapping: OFF") { setOn(!on) }
        tvEdit = chip("Edit layout") { setEditing(!editing) }
        addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            addView(chip("+Tap") { addBtn("tap") })
            addView(chip("+Hold") { addBtn("hold") })
            addView(chip("+Joy") { addBtn("joystick") })
        }
        tvKey = TextView(this).apply {
            text = "last key: -"
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 11f
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }

        hud.addView(handle)
        hud.addView(tvOn)
        hud.addView(tvEdit)
        hud.addView(addRow)
        hud.addView(tvKey)
        wm.addView(hud, hudLp)
    }

    private fun redrawButtons() {
        layer.removeAllViews()
        views.clear()
        val (w, h) = screen()
        cfg.binds.forEach { b ->
            val size = if (b.type == "joystick") (b.r * minOf(w, h) * 2).toInt() else dp(52)
            val col = when (b.type) {
                "joystick" -> 0xCC7DC3F3.toInt()
                "hold" -> 0xCCF37D7D.toInt()
                else -> 0xCCA67DF3.toInt()
            }
            val tv = TextView(this).apply {
                text = label(b)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 12f
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(col)
                    setStroke(dp(2), Color.WHITE)
                }
                alpha = if (editing) 1f else 0.35f
            }
            val lp = FrameLayout.LayoutParams(size, size)
            lp.leftMargin = (b.x * w - size / 2f).toInt()
            lp.topMargin = (b.y * h - size / 2f).toInt()
            layer.addView(tv, lp)
            views[b] = tv
            if (editing) attachEdit(tv, b, size)
        }
    }

    private fun attachEdit(v: View, b: Bind, size: Int) {
        var offX = 0f
        var offY = 0f
        var downX = 0f
        var downY = 0f
        var moved = false
        var downT = 0L
        v.setOnTouchListener { _, e ->
            val lp = v.layoutParams as FrameLayout.LayoutParams
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    offX = e.rawX - lp.leftMargin
                    offY = e.rawY - lp.topMargin
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                    downT = e.eventTime
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && Math.hypot((e.rawX - downX).toDouble(), (e.rawY - downY).toDouble()) > dp(8)) moved = true
                    if (moved) {
                        lp.leftMargin = (e.rawX - offX).toInt()
                        lp.topMargin = (e.rawY - offY).toInt()
                        v.layoutParams = lp
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val (w, h) = screen()
                    if (moved) {
                        b.x = ((lp.leftMargin + size / 2f) / w).coerceIn(0f, 1f)
                        b.y = ((lp.topMargin + size / 2f) / h).coerceIn(0f, 1f)
                        cfg.save(this)
                    } else if (e.eventTime - downT > 600) {
                        cfg.binds.remove(b)
                        cfg.save(this)
                        redrawButtons()
                    } else if (b.type == "joystick") {
                        tvKey.text = "Joystick keys: edit JSON in app"
                    } else {
                        pending = b
                        tvKey.text = "Press a key for this button..."
                    }
                }
            }
            true
        }
    }

    // ---------- modes ----------

    private fun setOn(v: Boolean) {
        if (v && editing) setEditing(false)
        on = v
        pressed.clear()
        tapOn.clear()
        if (v) cfg = Config.load(this)
        tvOn.text = if (on) "Mapping: ON" else "Mapping: OFF"
        tvOn.setTextColor(if (on) 0xFF7DF3A6.toInt() else Color.WHITE)
        redrawButtons()
        refresh()
    }

    private fun setEditing(v: Boolean) {
        if (v && on) setOn(false)
        editing = v
        pending = null
        if (v) cfg = Config.load(this)
        layerLp.flags = if (v) baseFlags else baseFlags or WLP.FLAG_NOT_TOUCHABLE
        layer.setBackgroundColor(if (v) 0x66000000 else 0)
        wm.updateViewLayout(layer, layerLp)
        tvEdit.text = if (v) "Done" else "Edit layout"
        addRow.visibility = if (v) View.VISIBLE else View.GONE
        tvKey.text = if (v) "tap: bind key | drag: move | hold: delete" else "last key: -"
        redrawButtons()
    }

    private fun addBtn(type: String) {
        val j = type == "joystick"
        val b = Bind(
            type, 0, 0.5f, 0.5f, 0.1f,
            if (j) KeyEvent.KEYCODE_W else 0, if (j) KeyEvent.KEYCODE_S else 0,
            if (j) KeyEvent.KEYCODE_A else 0, if (j) KeyEvent.KEYCODE_D else 0
        )
        cfg.binds.add(b)
        cfg.save(this)
        redrawButtons()
        if (!j) {
            pending = b
            tvKey.text = "Press a key for the new button..."
        }
    }

    // ---------- keyboard ----------

    override fun onKeyEvent(e: KeyEvent): Boolean {
        val k = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN

        val p = pending
        if (p != null && down) {
            p.key = k
            cfg.save(this)
            pending = null
            lastBound = k
            tvKey.text = "bound: " + short(k)
            redrawButtons()
            return true
        }
        if (!down && k == lastBound) { lastBound = -1; return true }

        if (down && e.repeatCount == 0 && p == null) tvKey.text = "last key: " + short(k)

        if (k == cfg.toggle) {
            if (down && e.repeatCount == 0) {
                setOn(!on)
                Toast.makeText(this, if (on) "Mapping ON" else "Mapping OFF", Toast.LENGTH_SHORT).show()
            }
            return true
        }
        if (!on || k !in cfg.keys) return false
        if (down) {
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

    // ---------- touch injection ----------

    private fun refresh() {
        if (busy) { dirty = true; return }
        val (w, h) = screen()
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
            views[b]?.alpha = if (want && on) 1f else 0.35f

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
