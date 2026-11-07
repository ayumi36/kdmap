package com.sumit.kbmap

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val bg = 0xFF121116.toInt()
    private val card = 0xFF1C1B22.toInt()
    private val chipBg = 0xFF2A2933.toInt()
    private val accent = 0xFFA67DF3.toInt()
    private val txt = 0xFFEDEAF5.toInt()
    private val dim = 0xFF9E9AAE.toInt()
    private val ok = 0xFF7DF3A6.toInt()
    private val bad = 0xFFF37D7D.toInt()

    private lateinit var tvSvc: TextView
    private lateinit var tvNotif: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }

    private fun text(t: String, sp: Float, c: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = sp
        setTextColor(c)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(t: String, filled: Boolean, click: () -> Unit) = TextView(this).apply {
        text = t
        gravity = Gravity.CENTER
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (filled) 0xFF1C1B22.toInt() else txt)
        background = rounded(if (filled) accent else chipBg, 14)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        setOnClickListener { click() }
    }

    private fun LinearLayout.add(v: View, top: Int = 10) {
        addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
        })
    }

    private fun cardBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(card, 18)
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val prefs = getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }

        root.add(text("KB Mapper", 28f, txt, true), 0)
        root.add(text("Play touch games with a Bluetooth keyboard and mouse.", 13f, dim), 2)

        // status
        val status = cardBox()
        tvSvc = text("", 14f, txt, true)
        tvNotif = text("", 14f, txt, true)
        status.add(tvSvc, 0)
        status.add(tvNotif, 8)
        status.add(button("Enable service in Accessibility", true) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, 14)
        status.add(button("Notification settings", false) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
            } else {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }, 8)
        status.add(button("Open customize menu", false) {
            val s = MapperService.instance
            if (s == null) Toast.makeText(this, "Enable the service first", Toast.LENGTH_SHORT).show()
            else s.showFromApp()
        }, 8)
        root.add(status, 16)

        // how to
        val how = cardBox()
        how.add(text("How it works", 15f, txt, true), 0)
        how.add(text(
            "1. Enable the service and allow notifications.\n" +
                "2. Open your game. Pull down the shade and tap the KB Mapper notification to open Customize.\n" +
                "3. Edit layout: drag the circles onto the game's buttons, set their keys. Mouse buttons and the wheel can be bound too: tap Key, then click.\n" +
                "4. Drag the dashed camera zone over the area where the game rotates the view.\n" +
                "5. Turn Mapping on (or press the ` key). \u2715 in the notification switches everything off.",
            12.5f, dim
        ), 8)
        root.add(how, 12)

        // advanced JSON
        val adv = cardBox()
        val et = EditText(this).apply {
            setText(prefs.getString(Config.KEY, Config.DEFAULT))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(txt)
            setBackgroundColor(chipBg)
            gravity = Gravity.TOP
            minLines = 12
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        body.add(et, 10)
        body.add(button("Save", true) {
            try {
                Config.parse(et.text.toString())
                prefs.edit().putString(Config.KEY, et.text.toString()).apply()
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Bad JSON: " + e.message, Toast.LENGTH_LONG).show()
            }
        }, 10)
        body.add(button("Reset to default", false) {
            et.setText(Config.DEFAULT)
            prefs.edit().remove(Config.KEY).apply()
        }, 8)
        val toggle = button("Advanced: edit JSON  \u25BE", false) {}
        toggle.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            if (open) et.setText(prefs.getString(Config.KEY, Config.DEFAULT))
            body.visibility = if (open) View.VISIBLE else View.GONE
        }
        adv.add(toggle, 0)
        adv.add(body, 0)
        root.add(adv, 12)

        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(root) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        refreshStatus()
    }

    private fun serviceEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val c = ComponentName(this, MapperService::class.java)
        return s.contains(c.flattenToString()) || s.contains(c.flattenToShortString())
    }

    private fun refreshStatus() {
        val svc = serviceEnabled()
        tvSvc.text = if (svc) "\u25CF  Service enabled" else "\u25CF  Service not enabled"
        tvSvc.setTextColor(if (svc) ok else bad)
        val nOk = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        tvNotif.text = if (nOk) "\u25CF  Notifications allowed" else "\u25CF  Notifications blocked (needed for the menu)"
        tvNotif.setTextColor(if (nOk) ok else bad)
    }
}
