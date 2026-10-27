package com.example.watchlauncher

import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.widget.EditText
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextClock
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.ui.Fonts
import com.example.ui.Kit
import com.example.ui.Palettes
import com.example.ui.SheetItem
import com.example.ui.Theme

class LauncherActivity : ComponentActivity(), HoneycombView.Listener {

    private lateinit var prefs: Prefs
    private lateinit var kit: Kit
    private lateinit var repo: AppRepository
    private lateinit var grid: HoneycombView
    private lateinit var list: ListView
    private lateinit var clock: TextClock
    private lateinit var root: FrameLayout
    private var apps: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private var searching = false
    private lateinit var searchPanel: LinearLayout
    private lateinit var searchInput: EditText
    private var topInset = 0
    private var bottomInset = 0

    private val homeRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        kit = Kit(this, Theme(Palettes.night, Fonts.system()))
        repo = AppRepository(this, prefs)
        val d = resources.displayMetrics.density

        grid = HoneycombView(this).also { it.listener = this }
        list = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            dividerHeight = (8 * d).toInt()
            clipToPadding = false
            visibility = View.GONE
            overScrollMode = View.OVER_SCROLL_NEVER
            overScrollMode = View.OVER_SCROLL_ALWAYS
            setOnItemClickListener { _, v, pos, _ -> launch(shown[pos], boundsOf(v)) }
            setOnItemLongClickListener { _, v, pos, _ -> onAppMenu(shown[pos], boundsOf(v)); true }
            // Pull down twice, as in iOS Settings: only a pull that starts with the list already at the top opens search.
            var startedAtTop = false
            var startY = 0f
            var done = false
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startedAtTop = !canScrollVertically(-1); startY = e.rawY; done = false }
                    MotionEvent.ACTION_MOVE -> if (startedAtTop && !done && !searching && e.rawY - startY > 72 * d) {
                        done = true
                        openSearch()
                    }
                }
                false
            }
        }
        searchInput = EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(kit.c.inkSoft)
            setTextColor(Color.WHITE)
            textSize = 17f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = GradientDrawable().apply { cornerRadius = 24 * d; setColor(kit.c.surface) }
            setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0)
            compoundDrawablePadding = (8 * d).toInt()
            val glass = resources.getDrawable(android.R.drawable.ic_menu_search, theme).mutate()
            glass.setTint(kit.c.inkSoft)
            setCompoundDrawablesRelativeWithIntrinsicBounds(glass, null, null, null)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = applyFilter()
            })
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) {
                    shown.firstOrNull()?.let { launch(it, Rect(0, 0, 1, 1)) }
                    true
                } else false
            }
        }
        searchPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding((12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            visibility = View.GONE
            addView(searchInput, LinearLayout.LayoutParams(0, (46 * d).toInt(), 1f))
            addView(TextView(context).apply {
                text = "Cancel"
                textSize = 17f
                setTextColor(kit.c.accent)
                setPadding((14 * d).toInt(), (10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt())
                setOnClickListener { closeSearch() }
            })
        }
        clock = TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            setTextColor(Color.WHITE)
            textSize = 19f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(grid, FrameLayout.LayoutParams(-1, -1))
            addView(list, FrameLayout.LayoutParams(-1, -1))
            addView(searchPanel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { rightMargin = (20 * d).toInt() })
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                topInset = bars.top
                bottomInset = bars.bottom
                (clock.layoutParams as FrameLayout.LayoutParams).topMargin = topInset + (8 * d).toInt()
                clock.requestLayout()
                (searchPanel.layoutParams as FrameLayout.LayoutParams).topMargin = topInset
                searchPanel.requestLayout()
                updateListPadding()
                grid.bottomInset = bottomInset
                insets
            }
        }
        setContentView(root)

        list.adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@LauncherActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((10 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), (8 * d).toInt())
                    background = GradientDrawable().apply { cornerRadius = 40 * d; setColor(kit.c.surface) }
                    layoutParams = android.widget.AbsListView.LayoutParams(-1, -2)
                    addView(ImageView(context), LinearLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt()))
                    addView(TextView(context).apply { setTextColor(Color.WHITE); textSize = 18f; maxLines = 1 },
                        LinearLayout.LayoutParams(-1, -2).apply { leftMargin = (14 * d).toInt() })
                }
                val app = shown[position]
                (row.getChildAt(0) as ImageView).setImageBitmap(app.full)
                (row.getChildAt(1) as TextView).text = app.label
                return row
            }
        }

        applySettings()
        reload()
    }

    private fun reload() {
        val px = HoneycombView.maxIconPx(resources.displayMetrics.widthPixels).coerceIn(96, 400)
        repo.load(px, onList = {
            apps = it
            grid.setApps(it)
            applyFilter()
            (list.adapter as BaseAdapter).notifyDataSetChanged()
        }, onProgress = {
            grid.invalidate()
            (list.adapter as BaseAdapter).notifyDataSetChanged()
        })
    }

    override fun onStart() {
        super.onStart()
        repo.register { reload() }
    }

    override fun onStop() {
        repo.unregister()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) goHome()
    }

    private fun goHome() {
        if (searching) closeSearch()
        else if (list.visibility == View.VISIBLE) setListMode(false)
        else if (!grid.isCentred()) grid.recentre()
    }

    @Deprecated("Back is handled here so a launcher never closes itself")
    override fun onBackPressed() {
        if (searching) closeSearch() else if (prefs.listView) setListMode(false) else if (!grid.isCentred()) grid.recentre()
    }

    private fun setListMode(on: Boolean) {
        prefs.listView = on
        updateVisibility()
        if (on) list.setSelection(0)
    }

    private fun updateVisibility() {
        val showList = prefs.listView || searching
        list.visibility = if (showList) View.VISIBLE else View.GONE
        grid.visibility = if (showList) View.GONE else View.VISIBLE
        clock.visibility = if (prefs.clock && !searching) View.VISIBLE else View.GONE
        updateListPadding()
    }

    private fun updateListPadding() {
        val d = resources.displayMetrics.density
        val top = topInset + ((if (searching) 62 else 44) * d).toInt()
        list.setPadding((12 * d).toInt(), top, (12 * d).toInt(), bottomInset + (12 * d).toInt())
    }

    // ---- search ----

    override fun onPullToSearch() = openSearch()

    private fun openSearch() {
        if (searching) return
        searching = true
        searchInput.setText("")
        updateVisibility()
        applyFilter()
        searchPanel.visibility = View.VISIBLE
        searchPanel.alpha = 0f
        searchPanel.translationY = -60 * resources.displayMetrics.density
        searchPanel.animate().alpha(1f).translationY(0f).setDuration(220).start()
        searchInput.requestFocus()
        searchInput.post { getSystemService(InputMethodManager::class.java).showSoftInput(searchInput, 0) }
    }

    private fun closeSearch() {
        if (!searching) return
        searching = false
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()
        searchPanel.animate().alpha(0f).translationY(-60 * resources.displayMetrics.density).setDuration(180)
            .withEndAction { if (!searching) searchPanel.visibility = View.GONE }.start()
        updateVisibility()
        applyFilter()
    }

    private fun applyFilter() {
        val q = if (searching) searchInput.text.toString().trim().lowercase() else ""
        shown = if (q.isEmpty()) apps else {
            val (starts, contains) = apps.filter { it.label.lowercase().contains(q) }.partition { it.label.lowercase().startsWith(q) }
            starts + contains
        }
        (list.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun applySettings() {
        grid.setSpacingMode(prefs.spacing)
        grid.snap = prefs.snap
        grid.ticks = prefs.ticks
        updateVisibility()
        window.insetsController?.let {
            if (prefs.hideStatusBar) {
                it.hide(WindowInsets.Type.statusBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                it.show(WindowInsets.Type.statusBars())
            }
        }
    }

    private fun boundsOf(v: View): Rect {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    private fun launch(app: AppEntry, bounds: Rect) {
        val options = ActivityOptions.makeScaleUpAnimation(root, bounds.left, bounds.top, bounds.width(), bounds.height())
        try {
            repo.launch(app, bounds, options.toBundle())
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Couldn't open ${app.label}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onLaunch(app: AppEntry, bounds: Rect) = launch(app, bounds)

    override fun onAppMenu(app: AppEntry, bounds: Rect) {
        kit.sheet(app.label, listOf(
            SheetItem("App info") { try { repo.showAppInfo(app, bounds) } catch (_: Exception) {} },
            SheetItem("Move to centre") { prefs.promote(app.key); reload() },
            SheetItem("Uninstall", destructive = true) {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
            },
        )).show()
    }

    override fun onBackgroundMenu() {
        val onOff = { b: Boolean -> if (b) "On" else "Off" }
        kit.sheet("Watch Launcher", listOf(
            SheetItem("View", if (prefs.listView) "List" else "Honeycomb") { setListMode(!prefs.listView) },
            SheetItem("Order", if (prefs.order == 0) "Most used" else "A to Z") { prefs.order = 1 - prefs.order; reload() },
            SheetItem("Icon spacing", listOf("Compact", "Normal", "Roomy")[prefs.spacing]) {
                prefs.spacing = (prefs.spacing + 1) % 3
                applySettings()
            },
            SheetItem("Snap to nearest icon", onOff(prefs.snap)) { prefs.snap = !prefs.snap; applySettings() },
            SheetItem("Haptic ticks", onOff(prefs.ticks)) { prefs.ticks = !prefs.ticks; applySettings() },
            SheetItem("Clock", onOff(prefs.clock)) { prefs.clock = !prefs.clock; applySettings() },
            SheetItem("Hide status bar", onOff(prefs.hideStatusBar)) { prefs.hideStatusBar = !prefs.hideStatusBar; applySettings() },
            SheetItem("Search apps") { openSearch() },
            SheetItem("Centre the grid") { grid.recentre() },
            SheetItem("Make this my home app") { requestHomeRole() },
        )).show()
    }

    private fun requestHomeRole() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm.isRoleHeld(RoleManager.ROLE_HOME)) {
            android.widget.Toast.makeText(this, "Already your home app", android.widget.Toast.LENGTH_SHORT).show()
        } else if (rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
            homeRole.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
        } else {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }
    }
}
