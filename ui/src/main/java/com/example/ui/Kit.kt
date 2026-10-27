package com.example.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Space
import android.widget.Switch
import android.widget.TextView

/** Colours of one app. Every colour has a job: text, quiet surface, track, one accent, and a warning. */
class Palette(
    val bg: Int,
    val surface: Int,
    val ink: Int,
    val inkSoft: Int,
    val line: Int,
    val accent: Int,
    val onAccent: Int,
    val danger: Int,
)

/** Typefaces of one app. [mono] is for numbers, so digits line up and don't jitter as they change. */
class Fonts(
    val regular: Typeface,
    val medium: Typeface,
    val semibold: Typeface,
    val mono: Typeface,
    val monoMedium: Typeface,
) {
    companion object {
        fun plex(ctx: Context) = Fonts(
            ctx.resources.getFont(R.font.plex_sans_regular),
            ctx.resources.getFont(R.font.plex_sans_medium),
            ctx.resources.getFont(R.font.plex_sans_semibold),
            ctx.resources.getFont(R.font.plex_mono_regular),
            ctx.resources.getFont(R.font.plex_mono_medium),
        )

        fun condensed(ctx: Context) = Fonts(
            ctx.resources.getFont(R.font.plex_cond_regular),
            ctx.resources.getFont(R.font.plex_cond_medium),
            ctx.resources.getFont(R.font.plex_cond_semibold),
            ctx.resources.getFont(R.font.plex_mono_regular),
            ctx.resources.getFont(R.font.plex_mono_medium),
        )

        /** The phone's own font, for the launchers, which should look like part of Android. */
        fun system(): Fonts {
            val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            return Fonts(Typeface.DEFAULT, medium, medium, Typeface.MONOSPACE, Typeface.MONOSPACE)
        }
    }
}

class Theme(val c: Palette, val f: Fonts)

class Slider(val view: View, val bar: SeekBar, private val valueView: TextView) {
    fun setValueText(s: String) {
        valueView.text = s
    }
}

class Toggle(val view: View, val switch: Switch)

class Segmented(val view: LinearLayout, private val paint: (Int) -> Unit) {
    fun select(i: Int) = paint(i)
}

class SheetItem(val label: String, val value: String? = null, val destructive: Boolean = false, val onClick: () -> Unit)

/**
 * The building blocks every app is made from. One spacing scale (4, 8, 12, 16, 24, 32), one corner radius,
 * and controls that are drawn here instead of left as stock Android widgets.
 */
class Kit(val ctx: Context, val theme: Theme) {

    enum class Weight { PRIMARY, SECONDARY, QUIET }

    val c: Palette get() = theme.c
    val f: Fonts get() = theme.f
    private val density = ctx.resources.displayMetrics.density

    fun dp(v: Int): Int = (v * density).toInt()
    private val radius get() = dp(8).toFloat()

    // ---------------------------------------------------------------- text

    fun text(s: CharSequence, size: Float, color: Int = c.ink, font: Typeface = f.regular): TextView = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = font
        includeFontPadding = false
        setLineSpacing(0f, 1.18f)
    }

    fun title(s: String) = text(s, 26f, c.ink, f.semibold)
    fun heading(s: String) = text(s, 17f, c.ink, f.semibold)
    fun body(s: CharSequence) = text(s, 15f)
    fun caption(s: CharSequence) = text(s, 13f, c.inkSoft)
    fun readout(s: String, size: Float, color: Int = c.ink) = text(s, size, color, f.monoMedium)

    fun gap(parent: LinearLayout, dpv: Int) {
        parent.addView(Space(ctx), LinearLayout.LayoutParams(1, dp(dpv)))
    }

    fun column(): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun rowOf(vararg views: View, gapDp: Int = 8): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = dp(gapDp)
            })
        }
    }

    // ---------------------------------------------------------------- buttons

    private fun shape(fill: Int, r: Float = radius) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = r
    }

    private fun states(enabled: Int, disabled: Int) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(disabled, enabled),
    )

    private fun withAlpha(color: Int, alpha: Float) = Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    fun button(label: String, weight: Weight = Weight.SECONDARY, onClick: () -> Unit): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        typeface = f.medium
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        includeFontPadding = false
        stateListAnimator = null
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(14), 0, dp(14), 0)
        val fill: Int
        val fg: Int
        when (weight) {
            Weight.PRIMARY -> { fill = c.accent; fg = c.onAccent }
            Weight.SECONDARY -> { fill = c.surface; fg = c.ink }
            Weight.QUIET -> { fill = Color.TRANSPARENT; fg = c.accent }
        }
        val off = if (weight == Weight.QUIET) Color.TRANSPARENT else withAlpha(c.inkSoft, 0.18f)
        val bg = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(off))
            addState(intArrayOf(), shape(fill))
        }
        background = RippleDrawable(ColorStateList.valueOf(withAlpha(c.ink, 0.14f)), bg, shape(Color.WHITE))
        setTextColor(states(fg, withAlpha(c.inkSoft, 0.75f)))
        setOnClickListener { onClick() }
    }

    /** One choice out of a few, shown as a row of labels with the chosen one filled. */
    fun segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit): Segmented {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(c.surface, dp(10).toFloat())
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val cells = options.map { name ->
            TextView(ctx).apply {
                text = name
                gravity = Gravity.CENTER
                typeface = f.medium
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                includeFontPadding = false
                minHeight = dp(42)
            }
        }
        fun paint(sel: Int) {
            cells.forEachIndexed { i, cell ->
                val on = i == sel
                cell.background = if (on) shape(c.ink, dp(7).toFloat()) else null
                cell.setTextColor(if (on) c.bg else c.ink)
                cell.isSelected = on
            }
        }
        cells.forEachIndexed { i, cell ->
            row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            cell.setOnClickListener {
                paint(i)
                onSelect(i)
            }
        }
        paint(selected)
        return Segmented(row, ::paint)
    }

    // ---------------------------------------------------------------- sliders and switches

    fun seekBar(max: Int, progress: Int, onChange: (Int, Boolean) -> Unit, onStop: () -> Unit = {}): SeekBar = SeekBar(ctx).apply {
        this.max = max
        this.progress = progress
        val trackH = dp(4)
        val track = shape(c.line, trackH / 2f).apply { setSize(1, trackH) }
        val done = ClipDrawable(shape(c.accent, trackH / 2f).apply { setSize(1, trackH) }, Gravity.START, ClipDrawable.HORIZONTAL)
        val inset = (dp(44) - trackH) / 2
        progressDrawable = LayerDrawable(arrayOf(track, done)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
            setLayerInset(0, 0, inset, 0, inset)
            setLayerInset(1, 0, inset, 0, inset)
        }
        thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(c.accent)
            setSize(dp(26), dp(26))
            setStroke(dp(3), c.bg) // a ring in the page colour keeps the thumb clear of the track
        }
        splitTrack = false
        background = null
        setPadding(dp(13), 0, dp(13), 0)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = onChange(p, fromUser)
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) = onStop()
        })
    }

    /** A labelled slider: name on the left, current value in mono on the right, optional hint under them. */
    fun slider(
        title: String,
        lo: Float,
        hi: Float,
        initial: Float,
        fmt: (Float) -> String,
        hint: String? = null,
        onChange: (Float) -> Unit,
        onCommit: (() -> Unit)? = null,
    ): Slider {
        val valueView = text(fmt(initial), 15f, c.ink, f.monoMedium)
        val bar = seekBar(
            1000, ((initial - lo) / (hi - lo) * 1000).toInt(),
            { p, fromUser ->
                val v = lo + (hi - lo) * p / 1000f
                valueView.text = fmt(v)
                if (fromUser) onChange(v)
            },
            { onCommit?.invoke() },
        )
        val box = column()
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(body(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueView)
        }
        box.addView(head, LinearLayout.LayoutParams(-1, -2))
        if (hint != null) {
            gap(box, 2)
            box.addView(caption(hint))
        }
        box.addView(bar, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(2) })
        return Slider(box, bar, valueView)
    }

    fun toggle(title: String, hint: String? = null, checked: Boolean = false, onChange: (Boolean) -> Unit): Toggle {
        val sw = Switch(ctx).apply {
            isChecked = checked
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(c.onAccent, c.inkSoft),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(c.accent, c.line),
            )
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        val labels = column().apply {
            addView(body(title))
            if (hint != null) {
                addView(caption(hint), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
            }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            addView(sw, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(16) })
            setOnClickListener { sw.toggle() }
        }
        return Toggle(row, sw)
    }

    // ---------------------------------------------------------------- screens and dialogs

    /** A scrolling page on the app's background, with the system bars' space and a 20dp gutter. */
    fun screen(content: LinearLayout): ScrollView = ScrollView(ctx).apply {
        setBackgroundColor(c.bg)
        clipToPadding = false
        isVerticalScrollBarEnabled = false
        addView(content, ViewGroup.LayoutParams(-1, -2))
        setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(bars.left + dp(20), bars.top + dp(24), bars.right + dp(20), bars.bottom + dp(32))
            WindowInsets.CONSUMED
        }
    }

    /** Dark or light system bar icons, to match the page colour. */
    fun matchSystemBars(window: Window) {
        val light = (0.299f * Color.red(c.bg) + 0.587f * Color.green(c.bg) + 0.114f * Color.blue(c.bg)) / 255f > 0.6f
        val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (light) flags else 0, flags)
    }

    private fun baseDialog(): Dialog = Dialog(ctx).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    fun dialog(title: String, message: String, confirm: String, cancelable: Boolean = true, onConfirm: () -> Unit = {}): Dialog {
        val d = baseDialog()
        val card = column().apply {
            background = shape(c.surface, dp(14).toFloat())
            setPadding(dp(24), dp(24), dp(24), dp(12))
        }
        card.addView(heading(title))
        gap(card, 12)
        card.addView(body(message))
        gap(card, 16)
        val actions = LinearLayout(ctx).apply { gravity = Gravity.END }
        actions.addView(button(confirm, Weight.QUIET) {
            d.dismiss()
            onConfirm()
        })
        card.addView(actions, LinearLayout.LayoutParams(-1, -2))
        d.setContentView(card)
        d.setCancelable(cancelable)
        d.setOnShowListener {
            val w = ctx.resources.displayMetrics.widthPixels
            d.window?.setLayout(minOf(dp(380), (w * 0.9f).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return d
    }

    /** Same card, but holding any view (a slider, say) instead of a sentence. */
    fun dialog(title: String, content: View, confirm: String, onConfirm: () -> Unit = {}): Dialog {
        val d = baseDialog()
        val card = column().apply {
            background = shape(c.surface, dp(14).toFloat())
            setPadding(dp(24), dp(24), dp(24), dp(12))
        }
        card.addView(heading(title))
        gap(card, 16)
        card.addView(content, LinearLayout.LayoutParams(-1, -2))
        gap(card, 16)
        val actions = LinearLayout(ctx).apply { gravity = Gravity.END }
        actions.addView(button(confirm, Weight.QUIET) {
            d.dismiss()
            onConfirm()
        })
        card.addView(actions, LinearLayout.LayoutParams(-1, -2))
        d.setContentView(card)
        d.setOnShowListener {
            val w = ctx.resources.displayMetrics.widthPixels
            d.window?.setLayout(minOf(dp(380), (w * 0.9f).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return d
    }

    /** A list of choices that slides up from the bottom, used by the launchers. */
    fun sheet(title: String?, items: List<SheetItem>): Dialog {
        val d = baseDialog()
        val card = column().apply {
            background = GradientDrawable().apply {
                setColor(c.surface)
                val r = dp(16).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(dp(8), dp(12), dp(8), dp(24))
        }
        if (title != null) {
            card.addView(text(title, 13f, c.inkSoft, f.medium), LinearLayout.LayoutParams(-2, -2).apply {
                leftMargin = dp(16); topMargin = dp(4); bottomMargin = dp(8)
            })
        }
        for (item in items) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(16), 0, dp(16), 0)
                background = RippleDrawable(ColorStateList.valueOf(withAlpha(c.ink, 0.12f)), null, shape(Color.WHITE))
                addView(text(item.label, 16f, if (item.destructive) c.danger else c.ink, f.regular), LinearLayout.LayoutParams(0, -2, 1f))
                if (item.value != null) addView(text(item.value, 15f, c.inkSoft, f.regular))
                setOnClickListener {
                    d.dismiss()
                    item.onClick()
                }
            }
            card.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        d.setContentView(card)
        d.setOnShowListener {
            d.window?.apply {
                setGravity(Gravity.BOTTOM)
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setWindowAnimations(android.R.style.Animation_InputMethod)
            }
        }
        return d
    }
}
