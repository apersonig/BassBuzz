package com.example.watchlauncher

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val usage: SharedPreferences = context.getSharedPreferences("usage", Context.MODE_PRIVATE)

    var listView: Boolean
        get() = sp.getBoolean("list", false)
        set(v) = sp.edit().putBoolean("list", v).apply()

    /** 0 = most used first (the most used app is in the middle), 1 = A to Z. */
    var order: Int
        get() = sp.getInt("order", 0)
        set(v) = sp.edit().putInt("order", v).apply()

    var snap: Boolean
        get() = sp.getBoolean("snap", true)
        set(v) = sp.edit().putBoolean("snap", v).apply()

    var ticks: Boolean
        get() = sp.getBoolean("ticks", true)
        set(v) = sp.edit().putBoolean("ticks", v).apply()

    var clock: Boolean
        get() = sp.getBoolean("clock", true)
        set(v) = sp.edit().putBoolean("clock", v).apply()

    var hideStatusBar: Boolean
        get() = sp.getBoolean("hide_status", false)
        set(v) = sp.edit().putBoolean("hide_status", v).apply()

    fun launches(key: String): Int = usage.getInt(key, 0)

    fun countLaunch(key: String) {
        usage.edit().putInt(key, launches(key) + 1).apply()
    }

    /** Makes [key] the most used app, so it takes the middle spot next time the grid is built. */
    fun promote(key: String) {
        val top = usage.all.values.maxOfOrNull { (it as? Int) ?: 0 } ?: 0
        usage.edit().putInt(key, top + 1).apply()
    }
}
