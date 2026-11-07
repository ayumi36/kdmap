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
import android.os.SystemClock
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
    private var sel: Bind? = null
    private var pending: Bind? = null
    private var pendingToggle = false
    private var joyStep = 0
    private val swallowUp = HashSet<Int>()
    private val fireDownAt = HashMap<Int, Long>()
    private val pressed = HashSet<Int>()
    private val tapOn = HashSet<Int>()
    private val slots = HashMap<Int, Slot>()
    private val views = HashMap<Bind, View>()
    private var busy = false
    private var dirty = false
    private var okCount = 0
    private val dur = 80L

    private lateinit var layer: FrameLayout
    private lateinit var layerLp: WLP
    private lateinit var hud: LinearLayout
    private lateinit var hudLp: WLP
    private lateinit var tvOn: TextView
    private lateinit var tvEdit: TextView
    private lateinit var tvKey: TextView
    private lateinit var tvRes: TextView
    private lateinit var tvToggle: TextView
    private lateinit var tvSel: TextView
    private lateinit var editBox: LinearLayout
    private lateinit var selBox: LinearLayout

    private val baseFlags = WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_LAYOUT_IN_SCREEN or WLP.FLAG_LAYOUT_NO_LIMITS

    private val palette = intArrayOf(
        0xCCA67DF3.toInt(), 0xCCF37D7D.toInt(), 0xCC7DC3F3.toInt(),
        0xCC7DF3A6.toInt(), 0xCCF3D67D.toInt(), 0xCCFFFFFF.toInt()
    )
    private val joyNames = listOf("UP", "LEFT", "DOWN", "RIGHT")

    private val names = mapOf(
        KeyEvent.KEYCODE_SPACE to "Spc",
        KeyEvent.KEYCODE_SHIFT_LEFT to "LSh", KeyEvent.KEYCODE_SHIFT_RIGHT to "RSh",
        KeyEvent.KEYCODE_CTRL_LEFT to "LCt", KeyEvent.KEYCODE_CTRL_RIGHT to "RCt",
        KeyEvent.KEYCODE_ALT_LEFT to "LAl", KeyEvent.KEYCODE_ALT_RIGHT to "RAl",
        KeyEvent.KEYCODE_META_LEFT to "Win", KeyEvent.KEYCODE_META_RIGHT to "Win",
        KeyEvent.KEYCODE_TAB to "Tab", KeyEvent.KEYCODE_CAPS_LOCK to "Cap",
        KeyEvent.KEYCODE_ESCAPE to "Esc", KeyEvent.KEYCODE_ENTER to "Ent",
        KeyEvent.KEYCODE_DEL to "Bks", KeyEvent.KEYCODE_FORWARD_DEL to "Del",
        KeyEvent.KEYCODE_GRAVE to "`", KeyEvent.KEYCODE_MINUS to "-", KeyEvent.KEYCODE_EQUALS to "=",
        KeyEvent.KEYCODE_LEFT_BRACKET to "[", KeyEvent.KEYCODE_RIGHT_BRACKET to "]",
        KeyEvent.KEYCODE_BACKSLASH to "\\", KeyEvent.KEYCODE_SEMICOLON to ";",
        KeyEvent.KEYCODE_APOSTROPHE to "'", KeyEvent.KEYCODE_COMMA to ",",
        KeyEvent.KEYCODE_PERIOD to ".", KeyEvent.KEYCODE_SLASH to "/",
        KeyEvent.KEYCODE_DPAD_UP to "\u2191", KeyEvent.KEYCODE_DPAD_DOWN to "\u2193",
        KeyEvent.KEYCODE_DPAD_LEFT to "\u2190", KeyEvent.KEYCODE_DPAD_RIGHT to "\u2192",
        KeyEvent.KEYCODE_PAGE_UP to "PgU", KeyEvent.KEYCODE_PAGE_DOWN to "PgD",
        KeyEvent.KEYCODE_MOVE_HOME to "Hom", KeyEvent.KEYCODE_MOVE_END to "End",
        KeyEvent.KEYCODE_INSERT to "Ins", KeyEvent.KEYCODE_MENU to "Mnu"
    )

    private val cb = object : GestureResultCallback() {
        override fun onCompleted(g: GestureDescription?) {
            busy = false
            okCount++
            if (::tvRes.isInitialized) tvRes.text = "gesture ok x" + okCount
            if (slots.isNotEmpty() || dirty) { dirty = false; refresh() }
        }
        override fun onCancelled(g: GestureDescription?) {
            busy = false
            slots.clear()
            if (::tvRes.isInitialized) tvRes.text = "gesture CANCELLED"
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

    private fun full(k: Int) = KeyEvent.keyCodeToString(k).removePrefix("KEYCODE_")

    private fun short(k: Int): String {
        if (k == 0) return "?"
        names[k]?.let { return it }
        val s = full(k)
        return when {
            s.startsWith("NUMPAD_") -> "N" + s.removePrefix("NUMPAD_")
            s.length > 4 -> s.substring(0, 4)
            else -> s
        }
    }

    private fun label(b: Bind): String {
        if (b.type != "joystick") return short(b.key)
        val l = listOf(b.kUp, b.kLeft, b.kDown, b.kRight).map { short(it) }
        return if (l.all { it.length == 1 }) l.joinToString("") else "JOY"
    }

    private fun typeName(b: Bind) = when (b.type) {
        "tap" -> "Tap"
        "hold" -> "Hold"
        "fire" -> "Fire (tap on press+release)"
        else -> "Joystick"
    }

    private fun defColor(b: Bind) = when (b.type) {
        "joystick" -> 2
        "hold", "fire" -> 1
        else -> 0
    }

    private fun chip(t: String, px: Int = 10, click: () -> Unit) = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 12f
        setPadding(dp(px), dp(7), dp(px), dp(7))
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

        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(chip("+ Add Btn", 8) { addBtn("tap") })
            addView(chip("Remove", 8) { selDel() })
        }
        tvToggle = chip("Toggle key: " + short(cfg.toggle), 7) {
            pendingToggle = true
            tvKey.text = "Press the new toggle key..."
        }
        tvSel = TextView(this).apply {
            setTextColor(0xFFFFE066.toInt())
            textSize = 11f
            setPadding(dp(7), dp(4), dp(7), dp(2))
        }
        val r1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(chip("Key", 7) { sel?.let { startBind(it) } })
            addView(chip("Type", 7) { selType() })
            addView(chip("Color", 7) { selColor() })
        }
        val r2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(chip("Sz-", 7) { selSize(-1) })
            addView(chip("Sz+", 7) { selSize(1) })
            addView(chip("Op-", 7) { selAlpha(-1) })
            addView(chip("Op+", 7) { selAlpha(1) })
        }
        selBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(tvSel)
            addView(r1)
            addView(r2)
        }
        editBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(addRow)
            addView(tvToggle)
            addView(selBox)
        }

        tvKey = TextView(this).apply {
            text = "last key: -"
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 11f
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        tvRes = TextView(this).apply {
            text = "gesture: -"
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 11f
            setPadding(dp(10), dp(2), dp(10), dp(4))
        }

        hud.addView(handle)
        hud.addView(tvOn)
        hud.addView(tvEdit)
        hud.addView(editBox)
        hud.addView(tvKey)
        hud.addView(tvRes)
        hud.addView(chip("Test tap") { testTap() })
        wm.addView(hud, hudLp)
    }

    private fun redrawButtons() {
        layer.removeAllViews()
        views.clear()
        val (w, h) = screen()
        cfg.binds.forEach { b ->
            val joy = b.type == "joystick"
            val size = if (joy) (b.r * minOf(w, h) * 2).toInt() else dp(b.sz)
            val col = palette[(if (b.c in palette.indices) b.c else defColor(b))]
            val selected = editing && b === sel
            val tv = TextView(this).apply {
                text = label(b)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = if (joy) 12f else (b.sz / 3f).coerceIn(7f, 14f)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(col)
                    setStroke(dp(if (selected) 3 else 1), if (selected) 0xFFFFE066.toInt() else Color.WHITE)
                }
                alpha = if (editing) 1f else b.a
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
        v.setOnTouchListener { _, e ->
            val lp = v.layoutParams as FrameLayout.LayoutParams
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    offX = e.rawX - lp.leftMargin
                    offY = e.rawY - lp.topMargin
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
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
                    }
                    sel = b
                    redrawButtons()
                    updateSel()
                }
            }
            true
        }
    }

    // ---------- selected-button customisation ----------

    private fun updateSel() {
        val b = sel
        selBox.visibility = if (editing && b != null) View.VISIBLE else View.GONE
        if (b != null) {
            val s = if (b.type == "joystick") Math.round(b.r * 100) else b.sz
            tvSel.text = typeName(b) + "  size " + s + "  opacity " + Math.round(b.a * 10)
        }
    }

    private fun changed() { cfg.save(this); redrawButtons(); updateSel() }

    private fun startBind(b: Bind) {
        pending = b
        joyStep = 0
        tvKey.text = if (b.type == "joystick") "Joystick: press UP key" else "Press a key..."
    }

    private fun selType() {
        val b = sel ?: return
        if (b.type == "joystick") { tvKey.text = "Joystick has no type"; return }
        b.type = when (b.type) { "tap" -> "hold"; "hold" -> "fire"; else -> "tap" }
        changed()
    }

    private fun selColor() {
        val b = sel ?: return
        val cur = if (b.c in palette.indices) b.c else defColor(b)
        b.c = (cur + 1) % palette.size
        changed()
    }

    private fun selSize(d: Int) {
        val b = sel ?: return
        if (b.type == "joystick") b.r = (b.r + d * 0.01f).coerceIn(0.04f, 0.25f)
        else b.sz = (b.sz + d * 4).coerceIn(20, 90)
        changed()
    }

    private fun selAlpha(d: Int) {
        val b = sel ?: return
        b.a = (Math.round(b.a * 10) + d).coerceIn(1, 10) / 10f
        changed()
    }

    private fun selDel() {
        val b = sel
        if (b == null) { tvKey.text = "Select a button first"; return }
        cfg.binds.remove(b)
        sel = null
        changed()
    }

    // ---------- modes ----------

    private fun setOn(v: Boolean) {
        if (v && editing) setEditing(false)
        on = v
        ui.removeCallbacksAndMessages(null)
        pressed.clear()
        tapOn.clear()
        fireDownAt.clear()
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
        pendingToggle = false
        sel = null
        if (v) cfg = Config.load(this)
        layerLp.flags = if (v) baseFlags else baseFlags or WLP.FLAG_NOT_TOUCHABLE
        layer.setBackgroundColor(if (v) 0x66000000 else 0)
        wm.updateViewLayout(layer, layerLp)
        tvEdit.text = if (v) "Done" else "Edit layout"
        editBox.visibility = if (v) View.VISIBLE else View.GONE
        tvToggle.text = "Toggle key: " + short(cfg.toggle)
        tvKey.text = if (v) "tap a button to select | drag to move" else "last key: -"
        redrawButtons()
        updateSel()
    }

    private fun addBtn(type: String) {
        val o = (cfg.binds.size % 6) * 0.04f
        val b = Bind(type, 0, 0.45f + o, 0.40f + o, 0.08f, 0, 0, 0, 0)
        cfg.binds.add(b)
        sel = b
        changed()
        startBind(b)
    }

    // ---------- keyboard ----------

    private fun pulse(i: Int, delay: Long) {
        ui.postDelayed({
            tapOn.add(i)
            refresh()
            ui.postDelayed({ tapOn.remove(i); refresh() }, 120)
        }, delay)
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        val k = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN

        if (k in swallowUp) {
            if (!down) swallowUp.remove(k)
            return true
        }

        if (down && pendingToggle) {
            if (e.repeatCount > 0) return true
            pendingToggle = false
            cfg.toggle = k
            cfg.save(this)
            swallowUp.add(k)
            tvToggle.text = "Toggle key: " + short(k)
            tvKey.text = "toggle key set: " + full(k)
            return true
        }

        val p = pending
        if (p != null && down) {
            if (e.repeatCount > 0) return true
            swallowUp.add(k)
            if (p.type == "joystick") {
                when (joyStep) { 0 -> p.kUp = k; 1 -> p.kLeft = k; 2 -> p.kDown = k; else -> p.kRight = k }
                joyStep++
                if (joyStep > 3) { pending = null; tvKey.text = "joystick bound" }
                else tvKey.text = "Joystick: press " + joyNames[joyStep] + " key"
            } else {
                p.key = k
                pending = null
                tvKey.text = "bound: " + full(k)
            }
            cfg.save(this)
            redrawButtons()
            return true
        }

        if (down && e.repeatCount == 0) {
            tvKey.text = "last key: " + full(k) + (if (!on && !editing && k in cfg.keys) " (mapping OFF!)" else "")
        }

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
                if (b.key == k && (b.type == "tap" || b.type == "fire")) {
                    if (b.type == "fire") fireDownAt[i] = SystemClock.uptimeMillis()
                    pulse(i, 0)
                }
            }
        } else if (e.action == KeyEvent.ACTION_UP) {
            pressed.remove(k)
            cfg.binds.forEachIndexed { i, b ->
                if (b.type == "fire" && b.key == k) {
                    val since = SystemClock.uptimeMillis() - (fireDownAt.remove(i) ?: 0L)
                    pulse(i, maxOf(0L, 280L - since))
                }
            }
        }
        refresh()
        return true
    }

    // ---------- touch injection ----------

    private fun testTap() {
        if (editing) { tvRes.text = "tap Done first"; return }
        if (busy) { tvRes.text = "busy, try again"; return }
        val (w, h) = screen()
        val b = cfg.binds.firstOrNull { it.type != "joystick" } ?: cfg.binds.firstOrNull()
        if (b == null) { tvRes.text = "no buttons"; return }
        val p = Path()
        p.moveTo(b.x * w, b.y * h)
        val g = GestureDescription.Builder().addStroke(StrokeDescription(p, 0, 100)).build()
        tvRes.text = "test tap sent"
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(x: GestureDescription?) { tvRes.text = "test tap: done" }
            override fun onCancelled(x: GestureDescription?) { tvRes.text = "test tap: CANCELLED" }
        }, null)
        if (!ok) tvRes.text = "test tap: REJECTED"
    }

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
                "tap", "fire" -> want = i in tapOn
            }
            views[b]?.alpha = if (want && on) 1f else b.a

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
        if (!dispatchGesture(gb.build(), cb, null)) { busy = false; slots.clear(); tvRes.text = "gesture REJECTED" }
    }
}
