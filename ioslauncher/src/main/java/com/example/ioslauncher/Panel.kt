package com.example.ioslauncher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/** A flat, matte surface for the dock, search pill and library cards. Only its opacity can be changed. */
class Panel {

    /** 0 = see-through, 1 = solid. */
    var level = 0.7f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(c: Canvas, r: RectF, radius: Float, alpha: Float = 1f) {
        if (alpha <= 0f) return
        val a = (0.40f + 0.60f * level) * alpha
        fill.color = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), 0x1C, 0x1C, 0x1E)
        c.drawRoundRect(r, radius, radius, fill)
    }
}
