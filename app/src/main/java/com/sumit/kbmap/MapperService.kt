package com.sumit.kbmap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as WLP
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MapperService : AccessibilityService() {

    companion object {
        var instance: MapperService? = null
        private const val CAM = -1
        private const val CH = "kbmap"
        private const val NID = 1
        private const val MP = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WC = ViewGroup.LayoutParams.WRAP_CONTENT
        const val ACT_OPEN = "com.sumit.kbmap.OPEN"
        const val ACT_TOGGLE = "com.sumit.kbmap.TOGGLE"
        const val ACT_CLOSE = "com.sumit.kbmap.CLOSE"

        private val BG = 0xF21C1B22.toInt()
        private val CARD = 0xFF2A2933.toInt()
        private val ACCENT = 0xFFA67DF3.toInt()
        private val OKC = 0xFF7DF3A6.toInt()
        private val TXT = 0xFFEDEAF5.toInt()
        private val DIM = 0xFF9E9AAE.toInt()
        private val OFFTRACK = 0xFF55536A.toInt()
        private val YEL = 0xFFFFE066.toInt()
    }

    private class Slot(var x: Float, var y: Float, var stroke: StrokeDescription)

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var cfg = Config.parse(Config.DEFAULT)
    private var on = false
    private var editing = false
    private var shown = true
    private var built = false
    private var sel: Bind? = null
    private var pending: Bind? = null
    private var pendingToggle = false
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

    // mouse / camera state
    private var camX = 0f
    private var camY = 0f
    private var camLast = 0L
    private var camRecenter = false
    private var lastButtons = 0
    private var captured = false
    private var cap: CapView? = null

    private var keyMsg = "-"
    private var resMsg = "-"

    private lateinit var layer: FrameLayout
    private lateinit var layerLp: WLP
    private var panelRoot: FrameLayout? = null
    private var panelCard: LinearLayout? = null
    private var tvDiag: TextView? = null
    private var bar: LinearLayout? = null
    private var tvHint: TextView? = null
    private var tvSel: TextView? = null

    private val baseFlags = WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_LAYOUT_IN_SCREEN or WLP.FLAG_LAYOUT_NO_LIMITS

    private val palette = intArrayOf(
        0xCCA67DF3.toInt(), 0xCCF37D7D.toInt(), 0xCC7DC3F3.toInt(),
        0xCC7DF3A6.toInt(), 0xCCF3D67D.toInt(), 0xCCFFFFFF.toInt()
    )

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
            setRes("gesture ok x" + okCount)
            if (slots.isNotEmpty() || dirty) { dirty = false; refresh() }
        }
        override fun onCancelled(g: GestureDescription?) {
            busy = false
            slots.clear()
            setRes("gesture CANCELLED")
            if (on && (pressed.isNotEmpty() || tapOn.isNotEmpty() || camX != 0f || camY != 0f)) ui.postDelayed({ refresh() }, 100)
        }
    }

    private val rcv = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACT_OPEN -> openPanel()
                ACT_TOGGLE -> setOn(!on)
                ACT_CLOSE -> closeAll()
            }
        }
    }

    // pointer-capture window: a 1x1 untouchable window that holds focus so we get relative mouse motion
    private inner class CapView(c: Context) : View(c) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            requestPointerCapture()
        }
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (hasWindowFocus) requestPointerCapture()
        }
        override fun onPointerCaptureChange(hasCapture: Boolean) {
            super.onPointerCaptureChange(hasCapture)
            captured = hasCapture
            updateDiag()
        }
        override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
            mouseEvent(event)
            return true
        }
    }

    // ---------- lifecycle ----------

    override fun onServiceConnected() {
        instance = this
        cfg = Config.load(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (!built) {
            built = true
            buildLayer()
            val f = IntentFilter().apply {
                addAction(ACT_OPEN); addAction(ACT_TOGGLE); addAction(ACT_CLOSE)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(rcv, f, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(rcv, f)
        }
        shown = true
        layer.visibility = View.VISIBLE
        redrawButtons()
        postNotif()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() { pressed.clear(); tapOn.clear(); refresh() }

    override fun onUnbind(i: Intent?): Boolean {
        instance = null
        try { unregisterReceiver(rcv) } catch (_: Exception) {}
        closePanel()
        hideEditBar()
        cap?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        cap = null
        try { wm.removeView(layer) } catch (_: Exception) {}
        try { getSystemService(NotificationManager::class.java).cancel(NID) } catch (_: Exception) {}
        built = false
        return super.onUnbind(i)
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        if (!built) return
        val (w, h) = screen()
        layerLp.width = w
        layerLp.height = h
        wm.updateViewLayout(layer, layerLp)
        redrawButtons()
        if (editing) { hideEditBar(); showEditBar() }
    }

    // ---------- helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun screen(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    private fun full(k: Int): String =
        if (Keys.isMouse(k)) Keys.name(k) else KeyEvent.keyCodeToString(k).removePrefix("KEYCODE_")

    private fun short(k: Int): String {
        if (k == 0) return "?"
        Keys.short(k)?.let { return it }
        names[k]?.let { return it }
        val s = full(k)
        return when {
            s.startsWith("NUMPAD_") -> "N" + s.removePrefix("NUMPAD_")
            s.length > 4 -> s.substring(0, 4)
            else -> s
        }
    }

    private fun typeName(b: Bind) = when (b.type) {
        "tap" -> "Tap"
        "hold" -> "Hold"
        "fire" -> "Fire"
        else -> b.type
    }

    private fun defColor(b: Bind) = when (b.type) {
        "hold", "fire" -> 1
        else -> 0
    }

    private fun btnAlpha(b: Bind, active: Boolean): Float =
        if (editing) 1f else if (cfg.hide) 0f else if (active) 1f else b.a

    private fun lp(w: Int, h: Int, flags: Int): WLP =
        WLP(w, h, WLP.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WLP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

    private fun setKeyMsg(s: String) {
        keyMsg = s
        tvHint?.text = s
        updateDiag()
    }

    private fun setRes(s: String) {
        resMsg = s
        updateDiag()
    }

    private fun diagText(): String =
        "last key: " + keyMsg + "\n" + resMsg + "  \u00B7  mouse " + (if (captured) "captured" else "not captured")

    private fun updateDiag() { tvDiag?.text = diagText() }

    // ---------- small UI kit ----------

    private fun rounded(c: Int, r: Int): GradientDrawable =
        GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }

    private fun tv(t: String, sp: Float, c: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = sp
        setTextColor(c)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun pill(t: String, bg: Int = CARD, fg: Int = TXT, small: Boolean = false, click: () -> Unit) =
        TextView(this).apply {
            text = t
            setTextColor(fg)
            textSize = if (small) 12f else 13f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(bg, 12)
            val ph = if (small) 12 else 14
            val pv = if (small) 8 else 11
            setPadding(dp(ph), dp(pv), dp(ph), dp(pv))
            setOnClickListener { click() }
        }

    private fun LinearLayout.add(v: View, top: Int = 8) {
        addView(v, LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(top) })
    }

    private fun switchRow(title: String, sub: String, value: Boolean, set: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv(title, 14f, TXT, true))
            addView(tv(sub, 11f, DIM))
        }
        row.addView(col, LinearLayout.LayoutParams(0, WC, 1f))
        val track = FrameLayout(this).apply { background = rounded(if (value) ACCENT else OFFTRACK, 14) }
        val thumb = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        }
        val tlp = FrameLayout.LayoutParams(dp(22), dp(22), (if (value) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL)
        tlp.setMargins(dp(3), 0, dp(3), 0)
        track.addView(thumb, tlp)
        row.addView(track, LinearLayout.LayoutParams(dp(48), dp(28)))
        row.setOnClickListener { set(!value) }
        return row
    }

    private fun stepRow(title: String, value: String, minus: () -> Unit, plus: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD, 14)
            setPadding(dp(14), dp(8), dp(8), dp(8))
        }
        row.addView(tv(title, 14f, TXT, true), LinearLayout.LayoutParams(0, WC, 1f))
        row.addView(pill("\u2212", 0xFF3A3947.toInt(), TXT, true) { minus() })
        row.addView(tv(value, 14f, ACCENT, true).apply { gravity = Gravity.CENTER; minWidth = dp(44) })
        row.addView(pill("+", 0xFF3A3947.toInt(), TXT, true) { plus() })
        return row
    }

    // ---------- overlay windows ----------

    private fun buildLayer() {
        val (w, h) = screen()
        layer = FrameLayout(this)
        layerLp = lp(w, h, baseFlags or WLP.FLAG_NOT_TOUCHABLE)
        wm.addView(layer, layerLp)
    }

    private fun openPanel() {
        if (!built) return
        if (!shown) show(false)
        if (editing) setEditing(false, reopen = false)
        if (panelRoot != null) { rebuildPanel(); return }
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { closePanel() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(BG, 22)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true
        }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(card) }
        val (w, _) = screen()
        val cw = minOf(dp(380), w - dp(24))
        val slp = FrameLayout.LayoutParams(cw, WC, Gravity.CENTER)
        slp.topMargin = dp(16); slp.bottomMargin = dp(16)
        root.addView(scroll, slp)
        panelRoot = root
        panelCard = card
        try {
            wm.addView(root, lp(MP, MP, baseFlags))
        } catch (e: Exception) {
            panelRoot = null; panelCard = null
            return
        }
        rebuildPanel()
    }

    private fun closePanel() {
        panelRoot?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panelRoot = null
        panelCard = null
        tvDiag = null
    }

    private fun rebuildPanel() {
        val card = panelCard ?: return
        card.removeAllViews()

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv("KB Mapper", 20f, TXT, true))
            addView(tv(if (on) "Mapping is ON" else "Mapping is OFF", 12f, if (on) OKC else DIM))
        }
        head.addView(titles, LinearLayout.LayoutParams(0, WC, 1f))
        head.addView(pill("\u2715") { closePanel() })
        card.addView(head)

        card.add(switchRow("Mapping", "Keyboard & mouse control the game", on) { setOn(it) }, 14)
        card.add(switchRow("Mouse look", "Mouse moves the camera, buttons & wheel can be bound", cfg.mouseOn) {
            cfg.mouseOn = it
            cfg.save(this)
            camX = 0f; camY = 0f
            syncCapture()
            rebuildPanel()
        })
        if (cfg.mouseOn) {
            card.add(stepRow("Mouse sensitivity", (Math.round(cfg.sens * 10) / 10.0).toString(),
                { setSens(-1) }, { setSens(1) }))
        }
        card.add(switchRow("Hide buttons", "Makes every overlay button invisible (opacity 0)", cfg.hide) {
            cfg.hide = it
            cfg.save(this)
            applyAlpha()
            rebuildPanel()
        })

        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r.addView(pill("Edit layout", ACCENT, 0xFF1C1B22.toInt()) { setEditing(true) },
            LinearLayout.LayoutParams(0, WC, 1f).apply { rightMargin = dp(4) })
        r.addView(pill("Test tap") { testTap() },
            LinearLayout.LayoutParams(0, WC, 1f).apply { leftMargin = dp(4) })
        card.add(r, 10)

        val tk = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener {
                pendingToggle = true
                setKeyMsg("press the new toggle key...")
            }
        }
        tk.addView(tv("Toggle key", 14f, TXT, true), LinearLayout.LayoutParams(0, WC, 1f))
        tk.addView(tv(short(cfg.toggle) + "  \u00B7 tap to change", 12f, ACCENT, true))
        card.add(tk)

        val d = tv(diagText(), 11f, DIM)
        tvDiag = d
        card.add(d, 12)
    }

    private fun setSens(d: Int) {
        cfg.sens = ((Math.round(cfg.sens * 10) + d).coerceIn(2, 60)) / 10f
        cfg.save(this)
        rebuildPanel()
    }

    private fun applyAlpha() {
        cfg.binds.forEach { b ->
            val active = on && (b.key in pressed)
            views[b]?.alpha = btnAlpha(b, active)
        }
    }

    // ---------- edit bar ----------

    private fun showEditBar() {
        if (bar != null) return
        val (w, _) = screen()
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(BG, 20)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val hint = tv(keyMsg, 11f, DIM)
        val s = tv("", 12f, YEL, true)
        tvHint = hint
        tvSel = s
        b.addView(s)
        b.addView(hint)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun chip(t: String, bg: Int = CARD, fg: Int = TXT, click: () -> Unit) {
            row.addView(pill(t, bg, fg, true, click), LinearLayout.LayoutParams(WC, WC).apply { rightMargin = dp(6) })
        }
        chip("+ Add") { addBtn("tap") }
        chip("Remove") { selDel() }
        chip("Key") { sel?.let { startBind(it) } ?: setKeyMsg("select a button first") }
        chip("Type") { selType() }
        chip("Color") { selColor() }
        chip("Size \u2212") { selSize(-1) }
        chip("Size +") { selSize(1) }
        chip("Op \u2212") { selAlpha(-1) }
        chip("Op +") { selAlpha(1) }
        chip("Done", ACCENT, 0xFF1C1B22.toInt()) { setEditing(false) }
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        b.add(hs, 8)

        bar = b
        val barLp = lp(minOf(w - dp(16), dp(560)), WC, baseFlags).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(10)
        }
        try { wm.addView(b, barLp) } catch (e: Exception) { bar = null; return }
        updateSel()
    }

    private fun hideEditBar() {
        bar?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bar = null
        tvHint = null
        tvSel = null
    }

    // ---------- buttons layer ----------

    private fun redrawButtons() {
        if (!built) return
        layer.removeAllViews()
        views.clear()
        val (w, h) = screen()
        if (editing && cfg.mouseOn) addZone(w, h)
        cfg.binds.forEach { b ->
            val size = dp(b.sz)
            val col = palette[(if (b.c in palette.indices) b.c else defColor(b))]
            val selected = editing && b === sel
            val v = TextView(this).apply {
                text = short(b.key)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = (b.sz / 3f).coerceIn(7f, 14f)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(col)
                    setStroke(dp(if (selected) 3 else 1), if (selected) YEL else Color.WHITE)
                }
                alpha = btnAlpha(b, false)
            }
            val l = FrameLayout.LayoutParams(size, size)
            l.leftMargin = (b.x * w - size / 2f).toInt()
            l.topMargin = (b.y * h - size / 2f).toInt()
            layer.addView(v, l)
            views[b] = v
            if (editing) attachEdit(v, b, size)
        }
    }

    private fun addZone(w: Int, h: Int) {
        val z = cfg.zone
        val l = (z[0] * w).toInt()
        val t = (z[1] * h).toInt()
        val zw = ((z[2] - z[0]) * w).toInt()
        val zh = ((z[3] - z[1]) * h).toInt()
        val hs = dp(34)
        val zv = TextView(this).apply {
            text = "CAMERA ZONE\nmouse look swipes here"
            setTextColor(YEL)
            textSize = 11f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0x22FFE066)
                setStroke(dp(2), YEL, dp(8).toFloat(), dp(6).toFloat())
                cornerRadius = dp(12).toFloat()
            }
        }
        val hd = TextView(this).apply {
            text = "\u2198"
            setTextColor(0xFF1C1B22.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(YEL) }
        }
        val zlp = FrameLayout.LayoutParams(zw, zh).apply { leftMargin = l; topMargin = t }
        val hlp = FrameLayout.LayoutParams(hs, hs).apply { leftMargin = l + zw - hs; topMargin = t + zh - hs }
        layer.addView(zv, zlp)
        layer.addView(hd, hlp)

        fun saveZone() {
            cfg.zone = floatArrayOf(
                zlp.leftMargin.toFloat() / w, zlp.topMargin.toFloat() / h,
                (zlp.leftMargin + zlp.width).toFloat() / w, (zlp.topMargin + zlp.height).toFloat() / h
            )
            cfg.save(this)
        }

        var ox = 0f
        var oy = 0f
        zv.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { ox = e.rawX - zlp.leftMargin; oy = e.rawY - zlp.topMargin }
                MotionEvent.ACTION_MOVE -> {
                    zlp.leftMargin = (e.rawX - ox).toInt().coerceIn(0, maxOf(0, w - zlp.width))
                    zlp.topMargin = (e.rawY - oy).toInt().coerceIn(0, maxOf(0, h - zlp.height))
                    hlp.leftMargin = zlp.leftMargin + zlp.width - hs
                    hlp.topMargin = zlp.topMargin + zlp.height - hs
                    zv.layoutParams = zlp
                    hd.layoutParams = hlp
                }
                MotionEvent.ACTION_UP -> saveZone()
            }
            true
        }
        hd.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val nr = e.rawX.toInt().coerceIn(minOf(zlp.leftMargin + dp(80), w), w)
                    val nb = e.rawY.toInt().coerceIn(minOf(zlp.topMargin + dp(80), h), h)
                    zlp.width = nr - zlp.leftMargin
                    zlp.height = nb - zlp.topMargin
                    hlp.leftMargin = nr - hs
                    hlp.topMargin = nb - hs
                    zv.layoutParams = zlp
                    hd.layoutParams = hlp
                }
                MotionEvent.ACTION_UP -> saveZone()
            }
            true
        }
    }

    private fun attachEdit(v: View, b: Bind, size: Int) {
        var offX = 0f
        var offY = 0f
        var downX = 0f
        var downY = 0f
        var moved = false
        v.setOnTouchListener { _, e ->
            val l = v.layoutParams as FrameLayout.LayoutParams
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    offX = e.rawX - l.leftMargin
                    offY = e.rawY - l.topMargin
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && Math.hypot((e.rawX - downX).toDouble(), (e.rawY - downY).toDouble()) > dp(8)) moved = true
                    if (moved) {
                        l.leftMargin = (e.rawX - offX).toInt()
                        l.topMargin = (e.rawY - offY).toInt()
                        v.layoutParams = l
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val (w, h) = screen()
                    if (moved) {
                        b.x = ((l.leftMargin + size / 2f) / w).coerceIn(0f, 1f)
                        b.y = ((l.topMargin + size / 2f) / h).coerceIn(0f, 1f)
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
        tvSel?.text = if (b == null) "Tap a button to select, drag to move"
        else typeName(b) + "  \u00B7  key " + short(b.key) + "  \u00B7  size " + b.sz + "  \u00B7  opacity " + Math.round(b.a * 10)
    }

    private fun changed() { cfg.save(this); redrawButtons(); updateSel() }

    private fun startBind(b: Bind) {
        pending = b
        setKeyMsg(if (cfg.mouseOn) "press a key or mouse button..." else "press a key...")
        syncCapture()
    }

    private fun bindKey(p: Bind, k: Int) {
        p.key = k
        pending = null
        cfg.save(this)
        setKeyMsg("bound: " + full(k))
        redrawButtons()
        updateSel()
        ui.post { syncCapture() }
    }

    private fun selType() {
        val b = sel ?: run { setKeyMsg("select a button first"); return }
        b.type = when (b.type) { "tap" -> "hold"; "hold" -> "fire"; else -> "tap" }
        changed()
    }

    private fun selColor() {
        val b = sel ?: run { setKeyMsg("select a button first"); return }
        val cur = if (b.c in palette.indices) b.c else defColor(b)
        b.c = (cur + 1) % palette.size
        changed()
    }

    private fun selSize(d: Int) {
        val b = sel ?: run { setKeyMsg("select a button first"); return }
        b.sz = (b.sz + d * 4).coerceIn(20, 90)
        changed()
    }

    private fun selAlpha(d: Int) {
        val b = sel ?: run { setKeyMsg("select a button first"); return }
        b.a = (Math.round(b.a * 10) + d).coerceIn(1, 10) / 10f
        changed()
    }

    private fun selDel() {
        val b = sel
        if (b == null) { setKeyMsg("select a button first"); return }
        cfg.binds.remove(b)
        sel = null
        changed()
    }

    private fun addBtn(type: String) {
        val o = (cfg.binds.size % 6) * 0.04f
        val b = Bind(type, 0, 0.45f + o, 0.40f + o, 0.08f, 0, 0, 0, 0)
        cfg.binds.add(b)
        sel = b
        changed()
        startBind(b)
    }

    // ---------- modes ----------

    private fun setOn(v: Boolean) {
        if (v && editing) setEditing(false, reopen = false)
        on = v
        ui.removeCallbacksAndMessages(null)
        pressed.clear()
        tapOn.clear()
        fireDownAt.clear()
        camX = 0f; camY = 0f; camRecenter = false; lastButtons = 0
        if (v) { cfg = Config.load(this); sel = null }
        redrawButtons()
        refresh()
        syncCapture()
        postNotif()
        if (panelRoot != null) rebuildPanel()
    }

    private fun setEditing(v: Boolean, reopen: Boolean = true) {
        if (v == editing) return
        if (v) {
            if (on) setOn(false)
            closePanel()
        }
        editing = v
        pending = null
        pendingToggle = false
        sel = null
        if (v) cfg = Config.load(this)
        layerLp.flags = if (v) baseFlags else baseFlags or WLP.FLAG_NOT_TOUCHABLE
        layer.setBackgroundColor(if (v) 0x66000000 else 0)
        wm.updateViewLayout(layer, layerLp)
        if (v) {
            keyMsg = "drag buttons \u00B7 pick one, then use Key / Type / Color / Size / Op"
            showEditBar()
        } else {
            keyMsg = "-"
            hideEditBar()
        }
        redrawButtons()
        syncCapture()
        if (!v && reopen) openPanel()
    }

    private fun show(open: Boolean) {
        if (!built) return
        shown = true
        layer.visibility = View.VISIBLE
        postNotif()
        if (open) openPanel()
    }

    /** Called from MainActivity */
    fun showFromApp() { ui.post { show(true) } }

    private fun closeAll() {
        setOn(false)
        setEditing(false, reopen = false)
        closePanel()
        shown = false
        syncCapture()
        layer.visibility = View.GONE
        try { getSystemService(NotificationManager::class.java).cancel(NID) } catch (_: Exception) {}
    }

    // ---------- notification ----------

    private fun pi(action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this, code, Intent(action).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun postNotif() {
        if (!shown) return
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH, "KB Mapper", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
            )
            val icon = Icon.createWithResource(this, R.drawable.ic_stat)
            val n = Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(ACCENT)
                .setContentTitle("KB Mapper \u2014 " + (if (on) "ON" else "OFF"))
                .setContentText("Tap to open Customize")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi(ACT_OPEN, 1))
                .addAction(Notification.Action.Builder(icon, if (on) "Turn off" else "Turn on", pi(ACT_TOGGLE, 2)).build())
                .addAction(Notification.Action.Builder(icon, "\u2715 Close", pi(ACT_CLOSE, 3)).build())
                .build()
            nm.notify(NID, n)
        } catch (_: Exception) {}
    }

    // ---------- mouse ----------

    private fun syncCapture() {
        val need = shown && cfg.mouseOn && ((on && !editing) || pending != null)
        if (need && cap == null) {
            val v = CapView(this)
            cap = v
            try {
                wm.addView(v, lp(1, 1, WLP.FLAG_NOT_TOUCHABLE or WLP.FLAG_LAYOUT_IN_SCREEN or WLP.FLAG_ALT_FOCUSABLE_IM))
            } catch (e: Exception) {
                cap = null
                setRes("mouse window failed")
            }
        } else if (!need && cap != null) {
            cap?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            cap = null
            captured = false
            lastButtons = 0
            updateDiag()
        }
    }

    private fun mouseEvent(e: MotionEvent) {
        val bs = e.buttonState
        val ch = bs xor lastButtons
        lastButtons = bs
        if (ch != 0) {
            mouseBtn(ch, bs, MotionEvent.BUTTON_PRIMARY, Keys.MOUSE_L)
            mouseBtn(ch, bs, MotionEvent.BUTTON_SECONDARY, Keys.MOUSE_R)
            mouseBtn(ch, bs, MotionEvent.BUTTON_TERTIARY, Keys.MOUSE_M)
            mouseBtn(ch, bs, MotionEvent.BUTTON_BACK, Keys.MOUSE_BACK)
            mouseBtn(ch, bs, MotionEvent.BUTTON_FORWARD, Keys.MOUSE_FWD)
        }
        val act = e.actionMasked
        if (act == MotionEvent.ACTION_SCROLL) {
            val s = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (s > 0f) wheel(Keys.WHEEL_UP) else if (s < 0f) wheel(Keys.WHEEL_DOWN)
        }
        if (on && cfg.mouseOn && !editing && (act == MotionEvent.ACTION_MOVE || act == MotionEvent.ACTION_HOVER_MOVE)) {
            var dx = e.x
            var dy = e.y
            for (i in 0 until e.historySize) {
                dx += e.getHistoricalX(i)
                dy += e.getHistoricalY(i)
            }
            if (dx != 0f || dy != 0f) {
                camX += dx * cfg.sens
                camY += dy * cfg.sens
                camLast = SystemClock.uptimeMillis()
                refresh()
            }
        }
    }

    private fun mouseBtn(ch: Int, bs: Int, mask: Int, code: Int) {
        if ((ch and mask) == 0) return
        val down = (bs and mask) != 0
        if (down) {
            val p = pending
            if (p != null) { bindKey(p, code); return }
            if (on && code in cfg.keys) feed(code, true)
        } else if (code in pressed) {
            feed(code, false)
        }
    }

    private fun wheel(code: Int) {
        val p = pending
        if (p != null) { bindKey(p, code); return }
        if (on && code in cfg.keys) {
            feed(code, true)
            ui.postDelayed({ feed(code, false) }, 40)
        }
    }

    // ---------- keyboard ----------

    private fun pulse(i: Int, delay: Long) {
        ui.postDelayed({
            tapOn.add(i)
            refresh()
            ui.postDelayed({ tapOn.remove(i); refresh() }, 120)
        }, delay)
    }

    /** Mapped input (keyboard key or mouse button) went down/up. */
    private fun feed(k: Int, down: Boolean) {
        if (down) {
            pressed.add(k)
            cfg.binds.forEachIndexed { i, b ->
                if (b.key == k && (b.type == "tap" || b.type == "fire")) {
                    if (b.type == "fire") fireDownAt[i] = SystemClock.uptimeMillis()
                    pulse(i, 0)
                }
            }
        } else {
            pressed.remove(k)
            cfg.binds.forEachIndexed { i, b ->
                if (b.type == "fire" && b.key == k) {
                    val since = SystemClock.uptimeMillis() - (fireDownAt.remove(i) ?: 0L)
                    pulse(i, maxOf(0L, 280L - since))
                }
            }
        }
        refresh()
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
            setKeyMsg("toggle key set: " + full(k))
            if (panelRoot != null) rebuildPanel()
            return true
        }

        val p = pending
        if (p != null && down) {
            if (e.repeatCount > 0) return true
            swallowUp.add(k)
            bindKey(p, k)
            return true
        }

        if (down && e.repeatCount == 0) {
            setKeyMsg(full(k) + (if (!on && !editing && k in cfg.keys) " (mapping OFF!)" else ""))
        }

        if (k == cfg.toggle) {
            if (down && e.repeatCount == 0) {
                if (!shown) show(false)
                setOn(!on)
                Toast.makeText(this, if (on) "Mapping ON" else "Mapping OFF", Toast.LENGTH_SHORT).show()
            }
            return true
        }
        if (!on || k !in cfg.keys) return false

        if (down) {
            if (e.repeatCount > 0) return true
            feed(k, true)
        } else if (e.action == KeyEvent.ACTION_UP) {
            feed(k, false)
        }
        return true
    }

    // ---------- touch injection ----------

    private fun testTap() {
        if (editing) { setRes("tap Done first"); return }
        if (busy) { setRes("busy, try again"); return }
        val (w, h) = screen()
        val b = cfg.binds.firstOrNull()
        if (b == null) { setRes("no buttons"); return }
        val p = Path()
        p.moveTo(b.x * w, b.y * h)
        val g = GestureDescription.Builder().addStroke(StrokeDescription(p, 0, 100)).build()
        setRes("test tap sent")
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(x: GestureDescription?) { setRes("test tap: done") }
            override fun onCancelled(x: GestureDescription?) { setRes("test tap: CANCELLED") }
        }, null)
        if (!ok) setRes("test tap: REJECTED")
    }

    private fun refresh() {
        if (busy) { dirty = true; return }
        val (w, h) = screen()
        val gb = GestureDescription.Builder()
        var n = 0
        val finished = ArrayList<Int>()
        val now = SystemClock.uptimeMillis()

        cfg.binds.forEachIndexed { i, b ->
            val want = on && when (b.type) {
                "hold" -> b.key in pressed
                "tap", "fire" -> i in tapOn
                else -> false
            }
            views[b]?.alpha = btnAlpha(b, want)

            val sx = b.x * w
            val sy = b.y * h
            val s = slots[i]
            if (want) {
                val p = Path()
                val st: StrokeDescription
                if (s == null) {
                    p.moveTo(sx, sy); p.lineTo(sx, sy)
                    st = StrokeDescription(p, 0, dur, true)
                    slots[i] = Slot(sx, sy, st)
                } else {
                    p.moveTo(s.x, s.y); p.lineTo(sx, sy)
                    st = s.stroke.continueStroke(p, 0, dur, true)
                    s.stroke = st; s.x = sx; s.y = sy
                }
                gb.addStroke(st); n++
            } else if (s != null) {
                val p = Path()
                p.moveTo(s.x, s.y); p.lineTo(s.x, s.y)
                gb.addStroke(s.stroke.continueStroke(p, 0, 1, false)); n++
                finished.add(i)
            }
        }

        // camera (mouse look): one long drag inside the camera zone, re-centred when it nears the edge
        val z = cfg.zone
        val zl = z[0] * w
        val zt = z[1] * h
        val zr = z[2] * w
        val zb = z[3] * h
        val cs = slots[CAM]
        val live = on && cfg.mouseOn && (camX != 0f || camY != 0f || (cs != null && now - camLast < 150L))
        if (live) {
            if (cs != null && camRecenter) {
                val p = Path()
                p.moveTo(cs.x, cs.y); p.lineTo(cs.x, cs.y)
                gb.addStroke(cs.stroke.continueStroke(p, 0, 1, false)); n++
                finished.add(CAM)
                camRecenter = false
                dirty = true
            } else {
                val maxStep = minOf(zr - zl, zb - zt) * 0.35f
                val dx = camX.coerceIn(-maxStep, maxStep)
                val dy = camY.coerceIn(-maxStep, maxStep)
                camX -= dx
                camY -= dy
                val p = Path()
                val st: StrokeDescription
                val nx: Float
                val ny: Float
                if (cs == null) {
                    val cx = (zl + zr) / 2f
                    val cy = (zt + zb) / 2f
                    nx = (cx + dx).coerceIn(zl, zr)
                    ny = (cy + dy).coerceIn(zt, zb)
                    p.moveTo(cx, cy); p.lineTo(nx, ny)
                    st = StrokeDescription(p, 0, dur, true)
                    slots[CAM] = Slot(nx, ny, st)
                } else {
                    nx = (cs.x + dx).coerceIn(zl, zr)
                    ny = (cs.y + dy).coerceIn(zt, zb)
                    p.moveTo(cs.x, cs.y); p.lineTo(nx, ny)
                    st = cs.stroke.continueStroke(p, 0, dur, true)
                    cs.stroke = st; cs.x = nx; cs.y = ny
                }
                gb.addStroke(st); n++
                val mx = (zr - zl) * 0.12f
                val my = (zb - zt) * 0.12f
                if (nx < zl + mx || nx > zr - mx || ny < zt + my || ny > zb - my) camRecenter = true
            }
        } else if (cs != null) {
            val p = Path()
            p.moveTo(cs.x, cs.y); p.lineTo(cs.x, cs.y)
            gb.addStroke(cs.stroke.continueStroke(p, 0, 1, false)); n++
            finished.add(CAM)
            camRecenter = false
        }

        finished.forEach { slots.remove(it) }
        if (n == 0) return
        busy = true
        if (!dispatchGesture(gb.build(), cb, null)) { busy = false; slots.clear(); setRes("gesture REJECTED") }
    }
}
