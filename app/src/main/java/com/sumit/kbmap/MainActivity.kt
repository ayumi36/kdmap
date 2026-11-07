package com.sumit.kbmap

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val prefs = getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "KB Mapper"
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = "1) Enable the service in Accessibility settings.\n" +
                "2) Open your game, press F8 to toggle mapping.\n" +
                "3) Edit the layout below (x, y are screen fractions), Save. It reloads on every toggle."
            setPadding(0, pad / 2, 0, pad / 2)
        })
        root.addView(Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        val et = EditText(this).apply {
            setText(prefs.getString(Config.KEY, Config.DEFAULT))
            typeface = Typeface.MONOSPACE
            textSize = 12f
            gravity = Gravity.TOP
            minLines = 14
        }
        root.addView(et)

        root.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                try {
                    Config.parse(et.text.toString())
                    prefs.edit().putString(Config.KEY, et.text.toString()).apply()
                    Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Bad JSON: " + e.message, Toast.LENGTH_LONG).show()
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Reset to default"
            setOnClickListener {
                et.setText(Config.DEFAULT)
                prefs.edit().remove(Config.KEY).apply()
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }
}
