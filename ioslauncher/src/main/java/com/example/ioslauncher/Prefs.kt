package com.example.ioslauncher

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val usage = context.getSharedPreferences("usage", Context.MODE_PRIVATE)

    var layout: String?
        get() = sp.getString("layout", null)
        set(v) = sp.edit().putString("layout", v).apply()

    /** How solid the dock, search pill and library cards are, 0 to 100. */
    var opacity: Int
        get() = sp.getInt("opacity", 70)
        set(v) = sp.edit().putInt("opacity", v).apply()

    fun launches(key: String): Int = usage.getInt(key, 0)
    fun countLaunch(key: String) = usage.edit().putInt(key, launches(key) + 1).apply()
}
