package com.example.ioslauncher

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val usage = context.getSharedPreferences("usage", Context.MODE_PRIVATE)

    var layout: String?
        get() = sp.getString("layout", null)
        set(v) = sp.edit().putString("layout", v).apply()

    /** 0 = clear glass, 100 = frosted: the iOS 27 transparency slider. */
    var glass: Int
        get() = sp.getInt("glass", 55)
        set(v) = sp.edit().putInt("glass", v).apply()

    fun launches(key: String): Int = usage.getInt(key, 0)
    fun countLaunch(key: String) = usage.edit().putInt(key, launches(key) + 1).apply()
}
