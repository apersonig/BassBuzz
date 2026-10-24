package com.example.ioslauncher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/** Draws the frosted-glass panels (dock, search pill, library cards). */
class Glass(private val density: Float) {

    /** 0 = clear, 1 = frosted. */
    var level = 0.55f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val inset = RectF()

    private fun white(alpha: Float): Int = Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), 255, 255, 255)

    fun draw(c: Canvas, r: RectF, radius: Float, alpha: Float = 1f) {
        if (alpha <= 0f) return
        fill.color = white((0.10f + 0.30f * level) * alpha)
        c.drawRoundRect(r, radius, radius, fill)

        // Light falling from the top: a soft sheen, then a bright edge at the top that fades down the sides.
        sheen.shader = LinearGradient(0f, r.top, 0f, r.bottom,
            intArrayOf(white(0.20f * alpha), white(0f), white(0.07f * alpha)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, sheen)

        edge.shader = LinearGradient(0f, r.top, 0f, r.bottom,
            intArrayOf(white(0.60f * alpha), white(0.10f * alpha), white(0.32f * alpha)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        val h = edge.strokeWidth / 2f
        inset.set(r.left + h, r.top + h, r.right - h, r.bottom - h)
        c.drawRoundRect(inset, radius - h, radius - h, edge)
    }
}
