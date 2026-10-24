package com.example.watchlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** The Apple-Watch-style honeycomb: round icons packed in hexagons, big in the middle and shrinking towards the edges. */
class HoneycombView(context: Context) : View(context) {

    interface Listener {
        fun onLaunch(app: AppEntry, bounds: Rect)
        fun onAppMenu(app: AppEntry, bounds: Rect)
        fun onBackgroundMenu()
    }

    var listener: Listener? = null

    var snap: Boolean
        get() = scroller.snapEnabled
        set(v) { scroller.snapEnabled = v }

    var ticks = true

    /** Space kept free at the bottom for the gesture bar; the caption sits above it. */
    var bottomInset = 0

    private val density = resources.displayMetrics.density
    /** Gap between icon centres: a share of the screen width, so it suits any screen size. */
    var spacingPx = resources.displayMetrics.widthPixels / 4.2f
        private set
    val iconPx: Float get() = spacingPx * ICON_SHARE

    private var spacingMode = 1

    private val lens = Lens(0.45f, 1.2f, 0.32f)
    private val scroller = Scroller2D()

    private var apps: List<AppEntry> = emptyList()
    private var cells: List<IntArray> = emptyList()
    private var cellX = FloatArray(0)
    private var cellY = FloatArray(0)

    private var rx = 1f
    private var ry = 1f
    private var focus = -1
    private var touching = false
    private var flung = false
    private var lastFrame = 0L
    private var animating = false

    companion object {
        const val ICON_SHARE = 0.80f // icon diameter as a share of the spacing: the rest is the gap
        val SPACING_MULTIPLIER = floatArrayOf(0.88f, 1.0f, 1.25f)

        /** Icon bitmap size that is big enough for the roomiest setting. */
        fun maxIconPx(screenWidthPx: Int): Int = (screenWidthPx / 4.2f * SPACING_MULTIPLIER.max() * ICON_SHARE).toInt()
    }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2C2C2E.toInt() }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 17f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val dst = RectF()
    private val tmp = FloatArray(4)

    init {
        scroller.snapTarget = { _, _ -> nearestCellCentre() }
    }

    fun setApps(list: List<AppEntry>) {
        apps = list
        cells = Hex.spiral(list.size)
        layoutCells()
        scroller.jumpTo(0f, 0f)
        focus = if (list.isEmpty()) -1 else 0
        invalidate()
    }

    /** 0 = compact, 1 = normal, 2 = roomy. */
    fun setSpacingMode(mode: Int) {
        spacingMode = mode.coerceIn(0, 2)
        applySpacing()
    }

    private fun applySpacing() {
        val w = if (width > 0) width else resources.displayMetrics.widthPixels
        spacingPx = w / 4.2f * SPACING_MULTIPLIER[spacingMode]
        // Keep the same icon in the middle while the spacing changes.
        val oldX = scroller.x
        val oldY = scroller.y
        val before = spacingPxForLayout
        layoutCells()
        if (before > 0f) scroller.jumpTo(oldX * spacingPx / before, oldY * spacingPx / before)
        invalidate()
    }

    private var spacingPxForLayout = 0f

    private fun layoutCells() {
        cellX = FloatArray(cells.size) { Hex.x(cells[it][0], cells[it][1], spacingPx) }
        cellY = FloatArray(cells.size) { Hex.y(cells[it][1], spacingPx) }
        spacingPxForLayout = spacingPx
        if (cells.isEmpty()) scroller.setBounds(0f, 0f, 0f, 0f)
        else scroller.setBounds(cellX.min(), cellX.max(), cellY.min(), cellY.max())
    }

    fun isCentred(): Boolean = abs(scroller.x) < 1f && abs(scroller.y) < 1f

    fun recentre() {
        scroller.animateTo(0f, 0f)
        startAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rx = w * 0.55f
        ry = h * 0.42f
        applySpacing()
    }

    /** Fills [tmp] with screen x, screen y, icon scale and lens distance for cell [i]. */
    private fun place(i: Int) {
        val dx = cellX[i] - scroller.x
        val dy = cellY[i] - scroller.y
        val d = hypot(dx / rx, dy / ry)
        val f = lens.factor(d)
        tmp[0] = width / 2f + dx * f
        tmp[1] = height / 2f + dy * f
        tmp[2] = lens.scale(d)
        tmp[3] = d
    }

    private fun nearestCellCentre(): FloatArray? {
        if (apps.isEmpty()) return null
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in apps.indices) {
            val d = hypot((cellX[i] - scroller.x) / rx, (cellY[i] - scroller.y) / ry)
            if (d < bestD) { bestD = d; best = i }
        }
        return floatArrayOf(cellX[best], cellY[best])
    }

    override fun onDraw(canvas: Canvas) {
        var nearest = -1
        var nearestD = Float.MAX_VALUE
        // Outer icons first so the big ones in the middle are on top.
        for (i in apps.indices.reversed()) {
            place(i)
            val sx = tmp[0]; val sy = tmp[1]; val s = tmp[2]; val d = tmp[3]
            if (d < nearestD) { nearestD = d; nearest = i }
            val r = iconPx * s / 2f
            if (sx + r < 0 || sx - r > width || sy + r < 0 || sy - r > height) continue
            val app = apps[i]
            val bmp = if (s < 0.5f) app.small ?: app.full else app.full
            if (bmp == null) {
                canvas.drawCircle(sx, sy, r, placeholder)
            } else {
                dst.set(sx - r, sy - r, sx + r, sy + r)
                canvas.drawBitmap(bmp, null, dst, bitmapPaint)
            }
        }
        if (nearest != focus && nearest >= 0) {
            focus = nearest
            if (ticks && (touching || animating)) tick()
        }
        if (focus in apps.indices) {
            canvas.drawText(apps[focus].label, width / 2f, height - bottomInset - 28f * density, caption)
        }
    }

    private fun tick() {
        val kind = if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        performHapticFeedback(kind)
    }

    // ---- animation ----

    private fun startAnimation() {
        if (animating) return
        animating = true
        lastFrame = 0L
        postOnAnimation(frame)
    }

    private val frame = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = if (lastFrame == 0L) 1f / 60f else (now - lastFrame) / 1e9f
            lastFrame = now
            val more = scroller.step(dt)
            invalidate()
            if (more) postOnAnimation(this) else animating = false
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        animating = false
        super.onDetachedFromWindow()
    }

    // ---- touch ----

    private fun hit(x: Float, y: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in apps.indices) {
            place(i)
            val r = max(iconPx * tmp[2] / 2f, 14f * density)
            val d = hypot(tmp[0] - x, tmp[1] - y)
            if (d <= r && d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun boundsOf(i: Int): Rect {
        place(i)
        val r = iconPx * tmp[2] / 2f
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return Rect((loc[0] + tmp[0] - r).toInt(), (loc[1] + tmp[1] - r).toInt(), (loc[0] + tmp[0] + r).toInt(), (loc[1] + tmp[1] + r).toInt())
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.stop()
            flung = false
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scroller.drag(dx, dy)
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            flung = true
            scroller.fling(-vx, -vy)
            startAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val i = hit(e.x, e.y)
            if (i >= 0) {
                playSoundEffect(android.view.SoundEffectConstants.CLICK)
                listener?.onLaunch(apps[i], boundsOf(i))
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val i = hit(e.x, e.y)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (i >= 0) listener?.onAppMenu(apps[i], boundsOf(i)) else listener?.onBackgroundMenu()
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> touching = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
        }
        val handled = gestures.onTouchEvent(e)
        if ((e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) && !flung) {
            scroller.release()
            startAnimation()
        }
        return handled || true
    }
}
