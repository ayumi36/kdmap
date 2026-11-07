package com.sumit.kbmap

import android.content.Context
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

class Bind(
    val type: String,
    var key: Int,
    var x: Float,
    var y: Float,
    val r: Float,
    val kUp: Int,
    val kDown: Int,
    val kLeft: Int,
    val kRight: Int
)

class Config(var toggle: Int, val binds: MutableList<Bind>) {
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
        o.put("toggle", KeyEvent.keyCodeToString(toggle))
        val arr = JSONArray()
        binds.forEach { b ->
            val j = JSONObject()
            j.put("type", b.type)
            j.put("x", rnd(b.x))
            j.put("y", rnd(b.y))
            if (b.type == "joystick") {
                j.put("r", rnd(b.r))
                j.put("up", KeyEvent.keyCodeToString(b.kUp))
                j.put("down", KeyEvent.keyCodeToString(b.kDown))
                j.put("left", KeyEvent.keyCodeToString(b.kLeft))
                j.put("right", KeyEvent.keyCodeToString(b.kRight))
            } else {
                j.put("key", KeyEvent.keyCodeToString(b.key))
            }
            arr.put(j)
        }
        o.put("binds", arr)
        return o.toString(2)
    }

    companion object {
        const val PREFS = "kb"
        const val KEY = "cfg"

        // x, y = screen fractions (0..1). r = joystick radius as fraction of the shorter screen side.
        // types: joystick (4 keys), hold (touch while key held), tap (short tap per key press)
        const val DEFAULT = """{
  "toggle": "KEYCODE_GRAVE",
  "binds": [
    {"type":"joystick","x":0.15,"y":0.72,"r":0.10,"up":"KEYCODE_W","down":"KEYCODE_S","left":"KEYCODE_A","right":"KEYCODE_D"},
    {"type":"hold","key":"KEYCODE_SPACE","x":0.88,"y":0.70},
    {"type":"tap","key":"KEYCODE_R","x":0.80,"y":0.50},
    {"type":"tap","key":"KEYCODE_E","x":0.72,"y":0.40}
  ]
}"""

        private fun kc(o: JSONObject, n: String): Int {
            val s = o.optString(n, "")
            return if (s.isEmpty()) 0 else KeyEvent.keyCodeFromString(s)
        }

        fun parse(s: String): Config {
            val o = JSONObject(s)
            val arr = o.getJSONArray("binds")
            val list = ArrayList<Bind>()
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                list.add(
                    Bind(
                        b.getString("type"), kc(b, "key"),
                        b.getDouble("x").toFloat(), b.getDouble("y").toFloat(),
                        b.optDouble("r", 0.1).toFloat(),
                        kc(b, "up"), kc(b, "down"), kc(b, "left"), kc(b, "right")
                    )
                )
            }
            val t = kc(o, "toggle")
            return Config(if (t == 0) KeyEvent.KEYCODE_GRAVE else t, list)
        }

        fun load(ctx: Context): Config {
            val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, DEFAULT) ?: DEFAULT
            return try { parse(s) } catch (e: Exception) { parse(DEFAULT) }
        }
    }
}
