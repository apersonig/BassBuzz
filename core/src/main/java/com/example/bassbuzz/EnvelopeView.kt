package com.example.bassbuzz

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Draws the bass envelope of the song as bars, highlights what has played, and lets you scrub. */
class EnvelopeView(context: Context) : View(context) {

    var levels: FloatArray? = null
        set(value) {
            field = value
            invalidate()
        }

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var hint: String = "Open an audio file to begin"
        set(value) {
            field = value
            invalidate()
        }

    var onSeek: ((Float) -> Unit)? = null

    var dragging = false
        private set

    private val played = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC23B22.toInt() }
    private val unplayed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8F8672.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5E584B.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 14f * resources.displayMetrics.scaledDensity
    }

    /** Colour of the part that has played, of the part still to come, and of the hint text. */
    fun setColors(playedColor: Int, unplayedColor: Int, hintColor: Int, hintFont: android.graphics.Typeface? = null) {
        played.color = playedColor
        unplayed.color = unplayedColor
        textPaint.color = hintColor
        if (hintFont != null) textPaint.typeface = hintFont
        invalidate()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val lv = levels
        if (lv == null || lv.isEmpty()) {
            canvas.drawText(hint, w / 2f, h / 2f, textPaint)
            return
        }

        val barW = dp(2f)
        val step = barW + dp(2f)
        val bars = max(1, (w / step).toInt())
        val mid = h / 2f
        val playedBars = (progress * bars).toInt()

        for (b in 0 until bars) {
            val from = (b.toLong() * lv.size / bars).toInt()
            val to = min(lv.size, max(from + 1, ((b + 1).toLong() * lv.size / bars).toInt()))
            var m = 0f
            for (i in from until to) if (lv[i] > m) m = lv[i]
            val bh = max(dp(2f), min(1f, m) * h * 0.92f)
            val x = b * step
            canvas.drawRect(x, mid - bh / 2f, x + barW, mid + bh / 2f, if (b < playedBars) played else unplayed)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val lv = levels
        if (lv == null || lv.isEmpty() || width == 0) return false
        val f = e.x / width
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                progress = f
            }
            MotionEvent.ACTION_MOVE -> progress = f
            MotionEvent.ACTION_UP -> {
                progress = f
                dragging = false
                onSeek?.invoke(progress)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
