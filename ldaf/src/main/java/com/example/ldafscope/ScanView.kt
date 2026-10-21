package com.example.ldafscope

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Top-down map. You are in the middle, north is up. Every accepted distance reading is placed along the
 * direction the phone was pointing; colour shows height (blue low, red high).
 */
class ScanView(context: Context) : View(context) {

    private companion object {
        const val MAX_POINTS = 4000
        val RANGE_STEPS = floatArrayOf(1f, 2f, 3f, 5f, 8f, 10f)
    }

    private val points = ArrayList<FloatArray>() // east, north, up in metres

    /** Unit vector (east, north, up) the phone is pointing along right now. */
    var heading: FloatArray? = null
        set(value) {
            field = value
            invalidate()
        }

    val count: Int get() = points.size

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF2A3340.toInt()
        strokeWidth = dp(1f)
    }
    private val axis = Paint(ring).apply { color = 0xFF3A4556.toInt() }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val aim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FD1C5.toInt()
        strokeWidth = dp(2f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8B95A5.toInt()
        textSize = dp(11f)
        textAlign = Paint.Align.CENTER
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    fun add(east: Float, north: Float, up: Float) {
        if (points.size >= MAX_POINTS) points.removeAt(0)
        points.add(floatArrayOf(east, north, up))
        invalidate()
    }

    fun clear() {
        points.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = min(w, h) / 2f - dp(18f)

        var farthest = 1f
        for (p in points) farthest = max(farthest, hypot(p[0], p[1]))
        val range = RANGE_STEPS.firstOrNull { it >= farthest } ?: ceil(farthest)

        for (i in 1..4) {
            val r = radius * i / 4f
            canvas.drawCircle(cx, cy, r, ring)
            canvas.drawText("%.1f m".format(range * i / 4f), cx + r * 0.71f, cy - r * 0.71f, text)
        }
        canvas.drawLine(cx - radius, cy, cx + radius, cy, axis)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, axis)
        canvas.drawText("N", cx, cy - radius - dp(4f), text)

        val hsv = FloatArray(3)
        hsv[1] = 0.8f
        hsv[2] = 1f
        dot.alpha = 255
        for (p in points) {
            val t = ((p[2] + 1f) / 2.5f).coerceIn(0f, 1f)
            hsv[0] = 220f * (1f - t)
            dot.color = Color.HSVToColor(hsv)
            canvas.drawCircle(cx + p[0] / range * radius, cy - p[1] / range * radius, dp(2.5f), dot)
        }

        heading?.let { d ->
            val len = hypot(d[0], d[1]) // pointing straight up or down has no sideways direction to draw
            if (len > 0.05f) {
                canvas.drawLine(cx, cy, cx + d[0] / len * radius, cy - d[1] / len * radius, aim)
            }
        }
        canvas.drawCircle(cx, cy, dp(4f), aim)
    }
}
