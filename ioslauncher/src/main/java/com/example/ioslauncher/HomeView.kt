package com.example.ioslauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The whole paged home screen, drawn on one canvas: pages of icons, the dock, the search pill and page dots,
 * the App Library as the last page, and the edit mode with wiggling icons and drag to rearrange.
 */
class HomeView(context: Context) : View(context) {

    interface Listener {
        fun onLaunch(app: AppEntry, bounds: Rect)
        fun onAppMenu(app: AppEntry, bounds: Rect, inLibrary: Boolean)
        fun onBackgroundMenu()
        fun onSearch()
        fun onCategory(name: String, apps: List<AppEntry>)
        fun onEditChanged(editing: Boolean)
        fun onLayoutChanged(layout: HomeLayout)
    }

    private class Card(val name: String, val apps: List<AppEntry>)
    private class Hit(val key: String?, val zone: Int, val cx: Float, val cy: Float, val card: Int = -1)

    private companion object {
        const val Z_PAGE = 0
        const val Z_DOCK = 1
        const val Z_LIB_ICON = 2
        const val Z_LIB_CARD = 3
        const val Z_PILL = 4
        const val Z_LIB_SEARCH = 5

        const val M_NONE = 0
        const val M_PAGING = 1
        const val M_VSCROLL = 2
        const val M_PULL = 3
        const val M_DRAG = 4
        const val M_DONE = 5 // long press handled; ignore the rest of the gesture
    }

    var listener: Listener? = null
    var launchCount: (String) -> Int = { 0 }

    private val d = resources.displayMetrics.density
    private val panel = Panel()

    fun setPanelOpacity(level: Float) {
        panel.level = level
        invalidate()
    }

    // ---- data ----
    private var appList: List<AppEntry> = emptyList()
    private var byKey: Map<String, AppEntry> = emptyMap()
    private var savedLayout: HomeLayout? = null
    private var defaultDock: List<String> = emptyList()
    private var layout: HomeLayout = HomeLayout.EMPTY
    private var preview: HomeLayout? = null
    private val shown: HomeLayout get() = preview ?: layout
    private val history = History<HomeLayout>()
    private var cards: List<Card> = emptyList()

    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo
    var editing = false
        private set

    // ---- geometry ----
    private var topInset = 0f
    private var bottomInset = 0f
    private var cellW = 0f
    private var iconPx = 0f
    private var gridTop = 0f
    private var gridBottom = 0f
    private var rowH = 0f
    private var rows = 6
    private var perPage = 24
    private var dockTop = 0f
    private var dockH = 0f
    private var dockMargin = 0f
    private var pillCy = 0f
    private var dotsCy = 0f
    private val pillRect = RectF()
    private var cardW = 0f
    private var cardsTop = 0f
    private var libMax = 0f
    private var topScrim: Paint? = null
    private var bottomScrim: Paint? = null

    // ---- motion state ----
    private var pagePos = 0f
    private var pageAnim: ValueAnimator? = null
    private var libScroll = 0f
    private var libVel = 0f
    private val offsets = HashMap<String, FloatArray>()
    private var clock = 0L
    private var frameScheduled = false
    private var lastFrame = 0L

    // ---- drag state ----
    private var dragKey: String? = null
    private var dragX = 0f
    private var dragY = 0f
    private var grabX = 0f
    private var grabY = 0f
    private var pendingLayout: HomeLayout? = null
    private var pendingSince = 0L
    private var edgeSince = 0L

    // ---- paints ----
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3A3C.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(11.5f)
        textAlign = Paint.Align.CENTER
        setShadowLayer(3f * d, 0f, 1f * d, 0x99000000.toInt())
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(13f)
        textAlign = Paint.Align.CENTER
        setShadowLayer(3f * d, 0f, 1f * d, 0x99000000.toInt())
    }
    private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD9FFFFFF.toInt()
        textSize = sp(14.5f)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xD9FFFFFF.toInt()
        strokeWidth = 1.8f * d
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE62C2C2E.toInt() }
    private val tmp = RectF()
    private val labels = HashMap<String, String>()

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    // ------------------------------------------------------------------ setup

    fun setApps(list: List<AppEntry>, saved: HomeLayout?, dockKeys: List<String>) {
        appList = list
        byKey = list.associateBy { it.key }
        savedLayout = saved
        defaultDock = dockKeys
        labels.clear()
        rebuild()
    }

    fun setInsets(top: Int, bottom: Int) {
        topInset = top.toFloat()
        bottomInset = bottom.toFloat()
        rebuild()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuild()

    private fun computeGeometry() {
        cellW = width / 4f
        iconPx = min(cellW * 0.62f, 74f * d)
        dockMargin = 12f * d
        dockH = iconPx + 24f * d
        val dockBottom = height - bottomInset - 8f * d
        dockTop = dockBottom - dockH
        pillCy = dockTop - 28f * d
        dotsCy = pillCy - 31f * d
        pillRect.set(width / 2f - 72f * d, pillCy - 17f * d, width / 2f + 72f * d, pillCy + 17f * d)
        gridTop = topInset + 18f * d
        gridBottom = dotsCy - 18f * d
        rows = ((gridBottom - gridTop) / (iconPx + 36f * d)).toInt().coerceIn(3, 6)
        rowH = min((gridBottom - gridTop) / rows, iconPx + 56f * d)
        perPage = 4 * rows

        cardW = (width - 48f * d) / 2f
        cardsTop = topInset + 14f * d + 44f * d + 20f * d

        topScrim = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, topInset + 90f * d, 0x66000000, 0x00000000, Shader.TileMode.CLAMP)
        }
        bottomScrim = Paint().apply {
            shader = LinearGradient(0f, height - 260f * d, 0f, height.toFloat(), 0x00000000, 0x59000000, Shader.TileMode.CLAMP)
        }
    }

    private fun rebuild() {
        if (width == 0 || height == 0 || appList.isEmpty()) return
        computeGeometry()
        val before = layout
        val base = if (layout === HomeLayout.EMPTY) savedLayout else layout
        layout = HomeLayout.reconcile(base, appList.map { it.key }, defaultDock, perPage)
        preview = null
        buildLibrary()
        pagePos = pagePos.coerceIn(0f, maxPage())
        offsets.clear()
        if (layout != before) listener?.onLayoutChanged(layout)
        invalidate()
    }

    private fun buildLibrary() {
        val groups = appList.groupBy { it.categoryName }
        cards = Categories.ORDER.mapNotNull { name ->
            groups[name]?.let { apps ->
                Card(name, apps.sortedWith(compareByDescending<AppEntry> { launchCount(it.key) }.thenBy { it.label.lowercase() }))
            }
        }
        val rowCount = (cards.size + 1) / 2
        val content = cardsTop + rowCount * (cardW + 38f * d)
        libMax = max(0f, content - (height - bottomInset - 12f * d))
        libScroll = libScroll.coerceIn(0f, libMax)
    }

    private fun maxPage(): Float = if (editing) (shown.pages.size - 1).toFloat() else shown.pages.size.toFloat()

    fun currentPage(): Int = pagePos.roundToInt()

    fun goToPage(p: Int) = animatePage(p.toFloat().coerceIn(0f, maxPage()))

    private fun animatePage(target: Float, ms: Long = 280) {
        pageAnim?.cancel()
        val a = ValueAnimator.ofFloat(pagePos, target)
        a.duration = ms
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener {
            pagePos = it.animatedValue as Float
            invalidate()
        }
        pageAnim = a
        a.start()
    }

    // ------------------------------------------------------------------ layout editing

    fun isHidden(key: String) = key in layout.hidden

    fun addToHome(key: String) = commit(layout.addToHome(key, perPage))

    fun undo() {
        history.undo(layout)?.let { applyHistory(it) }
    }

    fun redo() {
        history.redo(layout)?.let { applyHistory(it) }
    }

    fun resetLayout() {
        commit(HomeLayout.reconcile(null, appList.map { it.key }, defaultDock, perPage))
    }

    private fun applyHistory(l: HomeLayout) {
        transition(l)
        layout = l
        preview = null
        if (pagePos > maxPage()) animatePage(maxPage())
        listener?.onLayoutChanged(layout)
        invalidate()
    }

    private fun commit(l: HomeLayout) {
        if (l == layout) return
        history.record(layout)
        transition(l)
        layout = l
        preview = null
        if (pagePos > maxPage()) animatePage(maxPage())
        listener?.onLayoutChanged(layout)
        invalidate()
    }

    fun enterEdit() {
        if (editing) return
        editing = true
        if (pagePos > maxPage()) animatePage(maxPage())
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener?.onEditChanged(true)
        schedule()
    }

    fun exitEdit() {
        if (!editing) return
        cancelDrag()
        editing = false
        listener?.onEditChanged(false)
        invalidate()
    }

    // ------------------------------------------------------------------ positions

    private fun slotX(slot: Int) = (slot % 4 + 0.5f) * cellW
    private fun slotY(slot: Int) = gridTop + (slot / 4) * rowH + iconPx / 2f
    private fun dockX(i: Int, count: Int): Float {
        val sw = (width - 2 * dockMargin) / 4f
        return width / 2f + (i - (count - 1) / 2f) * sw
    }
    private fun dockY() = dockTop + dockH / 2f

    /** Screen position of every icon in [l] at the current page position. */
    private fun targets(l: HomeLayout): HashMap<String, FloatArray> {
        val m = HashMap<String, FloatArray>()
        for ((p, page) in l.pages.withIndex()) for ((i, k) in page.withIndex()) m[k] = floatArrayOf((p - pagePos) * width + slotX(i), slotY(i))
        for ((i, k) in l.dock.withIndex()) m[k] = floatArrayOf(dockX(i, l.dock.size), dockY())
        return m
    }

    /** Icons glide from where they were to where [newLayout] puts them: the old position becomes a fading offset. */
    private fun transition(newLayout: HomeLayout) {
        val before = targets(shown)
        val after = targets(newLayout)
        for ((k, a) in after) {
            val b = before[k] ?: continue
            val o = offsets.getOrPut(k) { FloatArray(2) }
            o[0] += b[0] - a[0]
            o[1] += b[1] - a[1]
        }
        schedule()
    }

    // ------------------------------------------------------------------ drawing

    private fun wiggle(key: String): Float = sin(clock * 0.0105 + (key.hashCode() % 9)).toFloat() * 1.8f

    private fun labelFor(app: AppEntry): String = labels.getOrPut(app.key) {
        val maxW = cellW - 10f * d
        val n = textPaint.breakText(app.label, true, maxW, null)
        if (n >= app.label.length) app.label else app.label.substring(0, max(1, n - 1)).trimEnd() + "…"
    }

    private fun drawApp(c: Canvas, app: AppEntry, cx: Float, cy: Float, size: Float, alpha: Float, label: Boolean, wiggleOn: Boolean, badge: Boolean = false) {
        if (cx + size < 0 || cx - size > width) return
        c.save()
        if (wiggleOn) c.rotate(wiggle(app.key), cx, cy)
        val h = size / 2f
        tmp.set(cx - h, cy - h, cx + h, cy + h)
        val bmp = app.icon
        val a = (alpha * 255).toInt()
        if (bmp != null) {
            iconPaint.alpha = a
            c.drawBitmap(bmp, null, tmp, iconPaint)
        } else {
            placeholder.alpha = a
            c.drawRoundRect(tmp, size * 0.225f, size * 0.225f, placeholder)
        }
        if (label) {
            textPaint.alpha = a
            c.drawText(labelFor(app), cx, cy + h + 15f * d, textPaint)
        }
        if (badge) {
            val bx = cx - h + 3f * d
            val by = cy - h + 3f * d
            c.drawCircle(bx, by, 10.5f * d, badgePaint)
            c.drawLine(bx - 4.5f * d, by, bx + 4.5f * d, by, strokePaint)
        }
        c.restore()
    }

    override fun onDraw(c: Canvas) {
        clock = SystemClock.uptimeMillis()
        topScrim?.let { c.drawRect(0f, 0f, width.toFloat(), topInset + 90f * d, it) }
        bottomScrim?.let { c.drawRect(0f, height - 260f * d, width.toFloat(), height.toFloat(), it) }
        if (appList.isEmpty()) return

        val l = shown
        val n = l.pages.size
        val lo = floor(pagePos).toInt().coerceIn(0, n - 1)
        val hi = ceil(pagePos).toInt().coerceIn(0, n - 1)
        for (p in lo..hi) {
            val a = 1f - 0.4f * min(1f, abs(p - pagePos))
            for ((i, k) in l.pages[p].withIndex()) {
                if (k == dragKey) continue
                val app = byKey[k] ?: continue
                val o = offsets[k]
                drawApp(c, app, (p - pagePos) * width + slotX(i) + (o?.get(0) ?: 0f), slotY(i) + (o?.get(1) ?: 0f), iconPx, a, true, editing, editing)
            }
        }

        if (pagePos > n - 1 + 0.001f) drawLibrary(c, n)

        val fade = 1f - (pagePos - (n - 1)).coerceIn(0f, 1f)
        if (fade > 0f) {
            drawDock(c, l, fade)
            drawDotsAndPill(c, n, fade)
        }

        val dk = dragKey
        if (dk != null) byKey[dk]?.let { drawApp(c, it, dragX + grabX, dragY + grabY, iconPx * 1.14f, 0.96f, false, false) }
    }

    private fun drawDock(c: Canvas, l: HomeLayout, fade: Float) {
        tmp.set(dockMargin, dockTop, width - dockMargin, dockTop + dockH)
        panel.draw(c, tmp, 30f * d, fade)
        for ((i, k) in l.dock.withIndex()) {
            if (k == dragKey) continue
            val app = byKey[k] ?: continue
            val o = offsets[k]
            drawApp(c, app, dockX(i, l.dock.size) + (o?.get(0) ?: 0f), dockY() + (o?.get(1) ?: 0f), iconPx, fade, false, editing, editing)
        }
    }

    private fun drawDotsAndPill(c: Canvas, n: Int, fade: Float) {
        if (n > 1 || editing) {
            val gap = 14f * d
            val x0 = width / 2f - (n - 1) * gap / 2f
            for (i in 0 until n) {
                val near = max(0f, 1f - abs(i - pagePos))
                dotPaint.alpha = ((0.38f + 0.62f * near) * fade * 255).toInt()
                c.drawCircle(x0 + i * gap, dotsCy, 3.2f * d, dotPaint)
            }
        }
        if (!editing) {
            panel.draw(c, pillRect, 17f * d, fade)
            strokePaint.alpha = (fade * 217).toInt()
            val gx = pillRect.left + 34f * d
            c.drawCircle(gx, pillCy - 1.5f * d, 5f * d, strokePaint)
            c.drawLine(gx + 3.6f * d, pillCy + 2f * d, gx + 8f * d, pillCy + 6.4f * d, strokePaint)
            pillText.alpha = (fade * 217).toInt()
            c.drawText("Search", pillRect.left + 50f * d, pillCy + 5f * d, pillText)
        }
    }

    private fun cardOrigin(i: Int, x0: Float, out: FloatArray) {
        out[0] = x0 + 16f * d + (i % 2) * (cardW + 16f * d)
        out[1] = cardsTop - libScroll + (i / 2) * (cardW + 38f * d)
    }

    private fun drawLibrary(c: Canvas, n: Int) {
        val x0 = (n - pagePos) * width
        val pill = RectF(x0 + 16f * d, topInset + 14f * d, x0 + width - 16f * d, topInset + 58f * d)
        panel.draw(c, pill, 22f * d)
        val gx = pill.left + 24f * d
        strokePaint.alpha = 217
        c.drawCircle(gx, pill.centerY() - 1.5f * d, 5.5f * d, strokePaint)
        c.drawLine(gx + 4f * d, pill.centerY() + 2.5f * d, gx + 9f * d, pill.centerY() + 7.5f * d, strokePaint)
        pillText.alpha = 217
        c.drawText("App Library", pill.left + 44f * d, pill.centerY() + 5.2f * d, pillText)

        c.save()
        c.clipRect(x0, pill.bottom + 8f * d, x0 + width, height.toFloat())
        val o = FloatArray(2)
        val pad = 14f * d
        val mini = (cardW - 3 * pad) / 2f
        for ((i, card) in cards.withIndex()) {
            cardOrigin(i, x0, o)
            if (o[1] + cardW + 38f * d < pill.bottom || o[1] > height) continue
            tmp.set(o[0], o[1], o[0] + cardW, o[1] + cardW)
            panel.draw(c, tmp, 26f * d)
            for ((j, app) in card.apps.take(4).withIndex()) {
                drawApp(c, app, o[0] + pad + (j % 2) * (mini + pad) + mini / 2f, o[1] + pad + (j / 2) * (mini + pad) + mini / 2f, mini, 1f, false, false)
            }
            c.drawText(card.name, o[0] + cardW / 2f, o[1] + cardW + 20f * d, labelPaint)
        }
        c.restore()
    }

    // ------------------------------------------------------------------ frames

    private fun schedule() {
        if (frameScheduled) return
        frameScheduled = true
        lastFrame = SystemClock.uptimeMillis()
        postOnAnimation(frame)
    }

    private val frame = object : Runnable {
        override fun run() {
            frameScheduled = false
            val now = SystemClock.uptimeMillis()
            val dt = ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
            lastFrame = now
            step(now, dt)
            invalidate()
            if (editing || offsets.isNotEmpty() || abs(libVel) > 20f || dragKey != null) schedule()
        }
    }

    private fun step(now: Long, dt: Float) {
        val k = exp(-16f * dt)
        val it = offsets.entries.iterator()
        while (it.hasNext()) {
            val o = it.next().value
            o[0] *= k
            o[1] *= k
            if (abs(o[0]) < 0.5f && abs(o[1]) < 0.5f) it.remove()
        }
        if (abs(libVel) > 20f) {
            libScroll = (libScroll + libVel * dt).coerceIn(0f, libMax)
            libVel *= exp(-3.2f * dt)
            if (libScroll <= 0f || libScroll >= libMax) libVel = 0f
        } else {
            libVel = 0f
        }
        if (dragKey != null) tickDrag(now)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        removeCallbacks(longPress)
        frameScheduled = false
        pageAnim?.cancel()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ hit testing

    private fun hit(x: Float, y: Float): Hit? {
        val l = shown
        val n = l.pages.size
        if (pagePos > n - 0.5f) return hitLibrary(x, y, n)
        if (!editing && pillRect.contains(x, y)) return Hit(null, Z_PILL, x, y)
        if (y >= dockTop - 6f * d) {
            for ((i, k) in l.dock.withIndex()) {
                val cx = dockX(i, l.dock.size)
                if (abs(x - cx) <= iconPx * 0.6f && abs(y - dockY()) <= iconPx * 0.7f) return Hit(k, Z_DOCK, cx, dockY())
            }
            return null
        }
        val p = pagePos.roundToInt().coerceIn(0, n - 1)
        for ((i, k) in l.pages[p].withIndex()) {
            val cx = (p - pagePos) * width + slotX(i)
            val cy = slotY(i)
            if (abs(x - cx) <= iconPx * 0.6f && y >= cy - iconPx / 2f - 6f * d && y <= cy + iconPx / 2f + 22f * d) return Hit(k, Z_PAGE, cx, cy)
        }
        return null
    }

    private fun hitLibrary(x: Float, y: Float, n: Int): Hit? {
        val x0 = (n - pagePos) * width
        val pill = RectF(x0 + 16f * d, topInset + 14f * d, x0 + width - 16f * d, topInset + 58f * d)
        if (pill.contains(x, y)) return Hit(null, Z_LIB_SEARCH, x, y)
        if (y < pill.bottom + 8f * d) return null
        val o = FloatArray(2)
        val pad = 14f * d
        val mini = (cardW - 3 * pad) / 2f
        for ((i, card) in cards.withIndex()) {
            cardOrigin(i, x0, o)
            if (x < o[0] || x > o[0] + cardW || y < o[1] || y > o[1] + cardW + 30f * d) continue
            for ((j, app) in card.apps.take(4).withIndex()) {
                val cx = o[0] + pad + (j % 2) * (mini + pad) + mini / 2f
                val cy = o[1] + pad + (j / 2) * (mini + pad) + mini / 2f
                if (abs(x - cx) <= mini / 2f + 4f * d && abs(y - cy) <= mini / 2f + 4f * d) return Hit(app.key, Z_LIB_ICON, cx, cy, i)
            }
            return Hit(null, Z_LIB_CARD, x, y, i)
        }
        return null
    }

    private fun boundsOf(cx: Float, cy: Float, size: Float): Rect {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val h = size / 2f
        return Rect((loc[0] + cx - h).toInt(), (loc[1] + cy - h).toInt(), (loc[0] + cx + h).toInt(), (loc[1] + cy + h).toInt())
    }

    private fun sizeOf(h: Hit): Float {
        if (h.zone != Z_LIB_ICON) return iconPx
        return (cardW - 3 * 14f * d) / 2f
    }

    // ------------------------------------------------------------------ touch

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var mode = M_NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downHit: Hit? = null
    private var startedLibTop = false
    private var pulled = false
    private var tracker: VelocityTracker? = null

    private val longPress = Runnable {
        if (mode != M_NONE) return@Runnable
        mode = M_DONE
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val h = downHit
        val app = h?.key?.let { byKey[it] }
        when {
            app != null && h != null -> listener?.onAppMenu(app, boundsOf(h.cx, h.cy, sizeOf(h)), h.zone == Z_LIB_ICON)
            h != null && h.zone != Z_PAGE && h.zone != Z_DOCK -> {}
            pagePos > shown.pages.size - 0.5f -> listener?.onBackgroundMenu()
            else -> enterEdit()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (appList.isEmpty()) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pageAnim?.cancel()
                libVel = 0f
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                mode = M_NONE
                pulled = false
                startedLibTop = libScroll <= 0f
                downHit = hit(e.x, e.y)
                if (!editing) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                val dx = e.x - lastX
                val dy = e.y - lastY
                when (mode) {
                    M_DRAG -> {
                        dragX = e.x
                        dragY = e.y
                        updateDragTarget()
                    }
                    M_NONE -> if (hypot(e.x - downX, e.y - downY) > slop) {
                        removeCallbacks(longPress)
                        val h = downHit
                        val tdx = e.x - downX
                        val tdy = e.y - downY
                        mode = when {
                            editing && h != null && h.key != null && (h.zone == Z_PAGE || h.zone == Z_DOCK) -> M_DRAG
                            abs(tdx) > abs(tdy) -> M_PAGING
                            pagePos > shown.pages.size - 0.5f -> M_VSCROLL
                            else -> M_PULL
                        }
                        if (mode == M_DRAG && h != null) startDrag(h, e)
                    }
                    M_PAGING -> {
                        pagePos = (pagePos - dx / width).coerceIn(-0.12f, maxPage() + 0.12f)
                        invalidate()
                    }
                    M_VSCROLL -> {
                        libScroll = (libScroll - dy).coerceIn(0f, libMax)
                        if (startedLibTop && libScroll <= 0f && e.y - downY > 72f * d && !pulled) {
                            pulled = true
                            listener?.onSearch()
                        }
                        invalidate()
                    }
                    M_PULL -> if (!pulled && !editing && e.y - downY > 72f * d) {
                        pulled = true
                        listener?.onSearch()
                    }
                }
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                tracker?.addMovement(e)
                when (mode) {
                    M_NONE -> tap()
                    M_PAGING -> finishPaging()
                    M_VSCROLL -> {
                        tracker?.computeCurrentVelocity(1000)
                        libVel = -(tracker?.yVelocity ?: 0f)
                        schedule()
                    }
                    M_DRAG -> endDrag()
                }
                mode = M_NONE
                tracker?.recycle()
                tracker = null
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (mode == M_DRAG) endDrag()
                if (mode == M_PAGING) animatePage(pagePos.roundToInt().toFloat().coerceIn(0f, maxPage()))
                mode = M_NONE
                tracker?.recycle()
                tracker = null
            }
        }
        return true
    }

    private fun finishPaging() {
        tracker?.computeCurrentVelocity(1000)
        val vx = tracker?.xVelocity ?: 0f
        val base = pagePos.roundToInt()
        val target = when {
            vx < -700f * d / 2.6f -> max(base, floor(pagePos).toInt() + 1)
            vx > 700f * d / 2.6f -> min(base, ceil(pagePos).toInt() - 1)
            else -> base
        }
        animatePage(target.toFloat().coerceIn(0f, maxPage()))
    }

    private fun tap() {
        val h = downHit ?: return
        val app = h.key?.let { byKey[it] }
        when (h.zone) {
            Z_PAGE, Z_DOCK -> {
                if (app == null) return
                if (editing) {
                    val bx = h.cx - iconPx / 2f + 3f * d
                    val by = h.cy - iconPx / 2f + 3f * d
                    if (hypot(downX - bx, downY - by) < 22f * d) commit(layout.removeFromHome(app.key, perPage))
                } else {
                    listener?.onLaunch(app, boundsOf(h.cx, h.cy, iconPx))
                }
            }
            Z_LIB_ICON -> if (app != null) listener?.onLaunch(app, boundsOf(h.cx, h.cy, sizeOf(h)))
            Z_LIB_CARD -> cards.getOrNull(h.card)?.let { listener?.onCategory(it.name, it.apps) }
            Z_PILL, Z_LIB_SEARCH -> listener?.onSearch()
        }
    }

    // ------------------------------------------------------------------ dragging icons

    private fun startDrag(h: Hit, e: MotionEvent) {
        dragKey = h.key
        dragX = e.x
        dragY = e.y
        grabX = h.cx - downX
        grabY = h.cy - downY
        preview = layout
        pendingLayout = null
        edgeSince = 0L
        performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
        schedule()
    }

    private fun updateDragTarget() {
        val key = dragKey ?: return
        val fx = dragX
        val fy = dragY
        val cand: HomeLayout? = when {
            fy > dockTop - 14f * d -> {
                val others = layout.dock.count { it != key }
                val count = min(others + 1, HomeLayout.DOCK_SIZE)
                val sw = (width - 2 * dockMargin) / 4f
                val first = width / 2f - (count - 1) / 2f * sw
                layout.moveToDock(key, ((fx - first) / sw + 0.5f).toInt().coerceIn(0, others), perPage, false)
            }
            fy >= gridTop - 10f * d && fy <= gridBottom + 24f * d -> {
                val col = (fx / cellW).toInt().coerceIn(0, 3)
                val row = ((fy - gridTop) / rowH).toInt().coerceIn(0, rows - 1)
                layout.move(key, pagePos.roundToInt().coerceAtLeast(0), row * 4 + col, perPage, false)
            }
            else -> null
        }
        if (cand == null || cand == shown) {
            pendingLayout = null
        } else if (cand != pendingLayout) {
            pendingLayout = cand
            pendingSince = SystemClock.uptimeMillis()
        }
    }

    private fun tickDrag(now: Long) {
        val key = dragKey ?: return
        pendingLayout?.let {
            if (now - pendingSince >= 90) {
                transition(it)
                preview = it
                pendingLayout = null
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        val dir = when {
            dragX < 28f * d -> -1
            dragX > width - 28f * d -> 1
            else -> 0
        }
        if (dir == 0) {
            edgeSince = 0L
        } else if (edgeSince == 0L) {
            edgeSince = now
        } else if (now - edgeSince > 550) {
            edgeSince = now
            val next = pagePos.roundToInt() + dir
            val s = shown
            if (next >= 0 && next < s.pages.size) {
                animatePage(next.toFloat())
            } else if (next == s.pages.size && s.pages.last().any { it != key }) {
                val np = s.move(key, s.pages.size, 0, perPage, false)
                transition(np)
                preview = np
                animatePage(next.toFloat())
            }
        }
    }

    private fun endDrag() {
        val key = dragKey ?: return
        val dropX = dragX + grabX
        val dropY = dragY + grabY
        val result = (pendingLayout ?: shown).normalize(perPage, true)
        if (result != layout) history.record(layout)
        transition(result)
        dragKey = null
        pendingLayout = null
        layout = result
        preview = null
        targets(result)[key]?.let { offsets[key] = floatArrayOf(dropX - it[0], dropY - it[1]) }
        if (pagePos > maxPage()) animatePage(maxPage())
        listener?.onLayoutChanged(layout)
        schedule()
        invalidate()
    }

    private fun cancelDrag() {
        if (dragKey == null) return
        dragKey = null
        pendingLayout = null
        preview = null
        invalidate()
    }
}
