package com.sumit.kbmap

import android.content.Context
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/** Pseudo key codes for mouse buttons / wheel so they can be bound like keyboard keys. */
object Keys {
    const val MOUSE_L = 10001
    const val MOUSE_R = 10002
    const val MOUSE_M = 10003
    const val MOUSE_BACK = 10004
    const val MOUSE_FWD = 10005
    const val WHEEL_UP = 10006
    const val WHEEL_DOWN = 10007

    private val names = linkedMapOf(
        MOUSE_L to "MOUSE_LEFT", MOUSE_R to "MOUSE_RIGHT", MOUSE_M to "MOUSE_MIDDLE",
        MOUSE_BACK to "MOUSE_BACK", MOUSE_FWD to "MOUSE_FORWARD",
        WHEEL_UP to "WHEEL_UP", WHEEL_DOWN to "WHEEL_DOWN"
    )
    private val shorts = mapOf(
        MOUSE_L to "LMB", MOUSE_R to "RMB", MOUSE_M to "MMB", MOUSE_BACK to "M4", MOUSE_FWD to "M5",
        WHEEL_UP to "Wh\u2191", WHEEL_DOWN to "Wh\u2193"
    )

    fun isMouse(k: Int) = names.containsKey(k)
    fun short(k: Int): String? = shorts[k]
    fun name(k: Int): String = names[k] ?: KeyEvent.keyCodeToString(k)
    fun parse(s: String): Int {
        for ((k, v) in names) if (v == s) return k
        return KeyEvent.keyCodeFromString(s)
    }
}

class Bind(
    var type: String,
    var key: Int,
    var x: Float,
    var y: Float,
    var r: Float,
    var kUp: Int,
    var kDown: Int,
    var kLeft: Int,
    var kRight: Int,
    var sz: Int = 36,
    var a: Float = 0.35f,
    var c: Int = -1
)

class Config(var toggle: Int, val binds: MutableList<Bind>) {
    var mouseOn = true
    var sens = 1.5f
    var hide = false
    // camera zone as screen fractions: left, top, right, bottom
    var zone = floatArrayOf(0.45f, 0.05f, 0.98f, 0.95f)

    val keys: Set<Int>
        get() = binds
            .flatMap { listOf(it.key, it.kUp, it.kDown, it.kLeft, it.kRight) }
            .filter { it != 0 }
            .toSet()

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, toJson()).apply()
    }

    private fun rnd(v: Float) = Math.round(v * 1000) / 1000.0

    fun toJson(): String {
        val o = JSONObject()
        o.put("toggle", Keys.name(toggle))
        o.put("hide", hide)
        val m = JSONObject()
        m.put("on", mouseOn)
        m.put("sens", rnd(sens))
        m.put("zone", JSONArray(zone.map { rnd(it) }))
        o.put("mouse", m)
        val arr = JSONArray()
        binds.forEach { b ->
            val j = JSONObject()
            j.put("type", b.type)
            j.put("x", rnd(b.x))
            j.put("y", rnd(b.y))
            j.put("sz", b.sz)
            j.put("a", rnd(b.a))
            j.put("c", b.c)
            if (b.type == "joystick") {
                j.put("r", rnd(b.r))
                j.put("up", Keys.name(b.kUp))
                j.put("down", Keys.name(b.kDown))
                j.put("left", Keys.name(b.kLeft))
                j.put("right", Keys.name(b.kRight))
            } else {
                j.put("key", Keys.name(b.key))
            }
            arr.put(j)
        }
        o.put("binds", arr)
        return o.toString(2)
    }

    companion object {
        const val PREFS = "kb"
        const val KEY = "cfg"

        // x, y = screen fractions (0..1). sz = button size in dp. a = idle opacity (0..1).
        // c = colour index (-1 = default for type).
        // types: hold (touch held while key held) | tap (one tap per press)
        //        fire (tap on press + tap on release: for games where one click toggles continuous fire)
        // key can also be MOUSE_LEFT / MOUSE_RIGHT / MOUSE_MIDDLE / MOUSE_BACK / MOUSE_FORWARD / WHEEL_UP / WHEEL_DOWN
        // mouse: on = mouse look enabled, sens = camera speed, zone = [left, top, right, bottom] screen fractions
        const val DEFAULT = """{
  "toggle": "KEYCODE_GRAVE",
  "hide": false,
  "mouse": {"on": true, "sens": 1.5, "zone": [0.45, 0.05, 0.98, 0.95]},
  "binds": [
    {"type":"hold","key":"KEYCODE_W","x":0.15,"y":0.62},
    {"type":"hold","key":"KEYCODE_A","x":0.09,"y":0.72},
    {"type":"hold","key":"KEYCODE_S","x":0.15,"y":0.82},
    {"type":"hold","key":"KEYCODE_D","x":0.21,"y":0.72},
    {"type":"fire","key":"KEYCODE_SPACE","x":0.80,"y":0.70},
    {"type":"tap","key":"KEYCODE_R","x":0.80,"y":0.50},
    {"type":"tap","key":"KEYCODE_E","x":0.72,"y":0.40}
  ]
}"""

        private fun kc(o: JSONObject, n: String): Int {
            val s = o.optString(n, "")
            return if (s.isEmpty()) 0 else Keys.parse(s)
        }

        fun parse(s: String): Config {
            val o = JSONObject(s)
            val arr = o.getJSONArray("binds")
            val list = ArrayList<Bind>()
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                if (b.getString("type") == "joystick") {
                    // old format: split into 4 separate hold buttons
                    val jx = b.getDouble("x").toFloat()
                    val jy = b.getDouble("y").toFloat()
                    val jr = b.optDouble("r", 0.08).toFloat()
                    list.add(Bind("hold", kc(b, "up"), jx, jy - jr, 0.08f, 0, 0, 0, 0))
                    list.add(Bind("hold", kc(b, "down"), jx, jy + jr, 0.08f, 0, 0, 0, 0))
                    list.add(Bind("hold", kc(b, "left"), jx - jr * 0.5f, jy, 0.08f, 0, 0, 0, 0))
                    list.add(Bind("hold", kc(b, "right"), jx + jr * 0.5f, jy, 0.08f, 0, 0, 0, 0))
                    continue
                }
                list.add(
                    Bind(
                        b.getString("type"), kc(b, "key"),
                        b.getDouble("x").toFloat(), b.getDouble("y").toFloat(),
                        b.optDouble("r", 0.08).toFloat(),
                        kc(b, "up"), kc(b, "down"), kc(b, "left"), kc(b, "right"),
                        b.optInt("sz", 36), b.optDouble("a", 0.35).toFloat(), b.optInt("c", -1)
                    )
                )
            }
            val t = kc(o, "toggle")
            val cfg = Config(if (t == 0) KeyEvent.KEYCODE_GRAVE else t, list)
            cfg.hide = o.optBoolean("hide", false)
            o.optJSONObject("mouse")?.let { m ->
                cfg.mouseOn = m.optBoolean("on", true)
                cfg.sens = m.optDouble("sens", 1.5).toFloat().coerceIn(0.2f, 6f)
                val z = m.optJSONArray("zone")
                if (z != null && z.length() == 4) {
                    val f = FloatArray(4) { z.getDouble(it).toFloat().coerceIn(0f, 1f) }
                    if (f[2] - f[0] > 0.05f && f[3] - f[1] > 0.05f) cfg.zone = f
                }
            }
            return cfg
        }

        fun load(ctx: Context): Config {
            val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, DEFAULT) ?: DEFAULT
            return try { parse(s) } catch (e: Exception) { parse(DEFAULT) }
        }
    }
}
