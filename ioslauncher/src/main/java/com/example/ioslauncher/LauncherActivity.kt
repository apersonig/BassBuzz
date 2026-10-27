package com.example.ioslauncher

import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.ui.Fonts
import com.example.ui.Kit
import com.example.ui.Palettes
import com.example.ui.SheetItem
import com.example.ui.Theme

class LauncherActivity : ComponentActivity(), HomeView.Listener {

    private lateinit var prefs: Prefs
    private lateinit var kit: Kit
    private lateinit var repo: AppRepository
    private lateinit var home: HomeView
    private lateinit var root: FrameLayout
    private lateinit var editBar: LinearLayout
    private lateinit var undoButton: TextView
    private lateinit var redoButton: TextView

    private lateinit var searchPanel: LinearLayout
    private lateinit var searchRow: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var categoryTitle: TextView
    private lateinit var results: ListView
    private lateinit var holdLayer: FrameLayout
    private var holdOpen = false

    private var apps: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private var categoryApps: List<AppEntry>? = null
    private var overlayOpen = false
    private var topInset = 0
    private var bottomInset = 0

    private val homeRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        kit = Kit(this, Theme(Palettes.night, Fonts.system()))
        repo = AppRepository(this, prefs)
        val d = resources.displayMetrics.density

        home = HomeView(this).also {
            it.listener = this
            it.launchCount = { key -> prefs.launches(key) }
            it.setPanelOpacity(prefs.opacity / 100f)
        }

        // ---- edit bar: Undo / Redo (new in iOS 27), Customize, Done ----
        fun pill(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 40 * d; setColor(kit.c.surface) }
            setOnClickListener { onClick() }
        }
        undoButton = pill("↶ Undo") { home.undo(); refreshEditBar() }
        redoButton = pill("↷ Redo") { home.redo(); refreshEditBar() }
        val spacer = View(this)
        editBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            addView(undoButton)
            addView(redoButton, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = (8 * d).toInt() })
            addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
            addView(pill("Customize") { showSettings() })
            addView(pill("Done") { home.exitEdit() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = (8 * d).toInt() })
        }

        // ---- search / category searchPanel ----
        searchInput = EditText(this).apply {
            hint = "Search"
            setHintTextColor(kit.c.inkSoft)
            setTextColor(Color.WHITE)
            textSize = 17f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = GradientDrawable().apply { cornerRadius = 24 * d; setColor(kit.c.surface) }
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
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
        val cancel = TextView(this).apply {
            text = "Cancel"
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding((14 * d).toInt(), (10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt())
            setOnClickListener { closeOverlay() }
        }
        categoryTitle = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            visibility = View.GONE
        }
        searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            addView(searchInput, LinearLayout.LayoutParams(0, (46 * d).toInt(), 1f))
            addView(categoryTitle, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = (6 * d).toInt() })
            addView(cancel)
        }
        results = ListView(this).apply {
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = (4 * d).toInt()
            clipToPadding = false
            setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
            setOnItemClickListener { _, v, pos, _ -> launch(shown[pos], boundsOf(v)) }
            setOnItemLongClickListener { _, v, pos, _ -> onAppMenu(shown[pos], boundsOf(v), true); true }
            adapter = object : BaseAdapter() {
                override fun getCount() = shown.size
                override fun getItem(position: Int) = shown[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = (convertView as? LinearLayout) ?: LinearLayout(this@LauncherActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
                        layoutParams = android.widget.AbsListView.LayoutParams(-1, -2)
                        addView(ImageView(context), LinearLayout.LayoutParams((50 * d).toInt(), (50 * d).toInt()))
                        addView(TextView(context).apply { setTextColor(Color.WHITE); textSize = 18f; maxLines = 1 },
                            LinearLayout.LayoutParams(-1, -2).apply { leftMargin = (16 * d).toInt() })
                    }
                    val app = shown[position]
                    (row.getChildAt(0) as ImageView).setImageBitmap(app.icon)
                    (row.getChildAt(1) as TextView).text = app.label
                    return row
                }
            }
        }
        searchPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(0xEB000000.toInt())
            addView(searchRow)
            addView(results, LinearLayout.LayoutParams(-1, 0, 1f))
            isClickable = true
        }

        holdLayer = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(0xB3000000.toInt())
            setOnClickListener { closeHoldMenu() }
        }

        root = FrameLayout(this).apply {
            addView(home, FrameLayout.LayoutParams(-1, -1))
            addView(editBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(searchPanel, FrameLayout.LayoutParams(-1, -1))
            addView(holdLayer, FrameLayout.LayoutParams(-1, -1))
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                topInset = bars.top
                bottomInset = bars.bottom
                home.setInsets(bars.top, bars.bottom)
                (editBar.layoutParams as FrameLayout.LayoutParams).topMargin = topInset
                editBar.requestLayout()
                searchPanel.setPadding(0, topInset, 0, maxOf(bars.bottom, ime.bottom))
                insets
            }
        }
        setContentView(root)
        reload()
    }

    // ------------------------------------------------------------------ loading

    private fun reload() {
        val px = (resources.displayMetrics.widthPixels / 4f * 0.62f).toInt().coerceIn(96, 320)
        repo.load(px, onList = { list ->
            apps = list
            val dockKeys = repo.defaultDockPackages().mapNotNull { pkg -> list.firstOrNull { it.packageName == pkg }?.key }
            home.setApps(list, HomeLayout.parse(prefs.layout), dockKeys)
            applyFilter()
        }, onProgress = {
            home.invalidate()
            (results.adapter as BaseAdapter).notifyDataSetChanged()
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

    // ------------------------------------------------------------------ navigation

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            when {
                holdOpen -> closeHoldMenu()
                overlayOpen -> closeOverlay()
                home.editing -> home.exitEdit()
                else -> home.goToPage(0)
            }
        }
    }

    @Deprecated("Back is handled here so a launcher never closes itself")
    override fun onBackPressed() {
        when {
            holdOpen -> closeHoldMenu()
            overlayOpen -> closeOverlay()
            home.editing -> home.exitEdit()
            else -> home.goToPage(0)
        }
    }

    // ------------------------------------------------------------------ HomeView.Listener

    override fun onLaunch(app: AppEntry, bounds: Rect) = launch(app, bounds)

    override fun onLayoutChanged(layout: HomeLayout) {
        prefs.layout = layout.serialize()
        refreshEditBar()
    }

    override fun onEditChanged(editing: Boolean) {
        editBar.visibility = if (editing) View.VISIBLE else View.GONE
        refreshEditBar()
    }

    private fun refreshEditBar() {
        undoButton.alpha = if (home.canUndo) 1f else 0.4f
        redoButton.alpha = if (home.canRedo) 1f else 0.4f
    }

    override fun onSearch() = openOverlay(null, "")

    override fun onCategory(name: String, apps: List<AppEntry>) = openOverlay(name, "", apps)

    private fun openOverlay(category: String?, query: String, only: List<AppEntry>? = null) {
        if (overlayOpen) return
        overlayOpen = true
        categoryApps = only
        searchInput.setText(query)
        val isCategory = category != null
        searchInput.visibility = if (isCategory) View.GONE else View.VISIBLE
        categoryTitle.visibility = if (isCategory) View.VISIBLE else View.GONE
        categoryTitle.text = category ?: ""
        applyFilter()
        searchPanel.visibility = View.VISIBLE
        searchPanel.alpha = 0f
        searchPanel.animate().alpha(1f).setDuration(200).start()
        results.setSelection(0)
        if (!isCategory) {
            searchInput.requestFocus()
            searchInput.post { getSystemService(InputMethodManager::class.java).showSoftInput(searchInput, 0) }
        }
    }

    private fun closeOverlay() {
        if (!overlayOpen) return
        overlayOpen = false
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()
        searchPanel.animate().alpha(0f).setDuration(160).withEndAction { if (!overlayOpen) searchPanel.visibility = View.GONE }.start()
    }

    private fun applyFilter() {
        val pool = categoryApps ?: apps
        val q = if (overlayOpen && categoryApps == null) searchInput.text.toString().trim().lowercase() else ""
        shown = if (q.isEmpty()) pool else {
            val (starts, contains) = pool.filter { it.label.lowercase().contains(q) }.partition { it.label.lowercase().startsWith(q) }
            starts + contains
        }
        (results.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }

    // ------------------------------------------------------------------ menus

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
            Toast.makeText(this, "Couldn't open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private class MenuRow(
        val label: String,
        val glyph: String? = null,
        val icon: android.graphics.drawable.Drawable? = null,
        val destructive: Boolean = false,
        val action: () -> Unit,
    )

    /** The press-and-hold menu: the screen dims, the app lifts up, and a menu opens beside it. */
    override fun onAppMenu(app: AppEntry, bounds: Rect, inLibrary: Boolean) {
        val shortcuts = repo.shortcuts(app).map { s ->
            MenuRow(s.shortLabel?.toString() ?: s.id, icon = repo.shortcutIcon(s)) { try { repo.startShortcut(s) } catch (_: Exception) {} }
        }
        val actions = ArrayList<MenuRow>()
        if (inLibrary && home.isHidden(app.key)) {
            actions.add(MenuRow("Add to Home Screen", "+") { home.addToHome(app.key); closeOverlay() })
        }
        if (!inLibrary) actions.add(MenuRow("Edit Home Screen", "✎") { home.enterEdit() })
        actions.add(MenuRow("App Info", "ⓘ") { try { repo.showAppInfo(app, bounds) } catch (_: Exception) {} })
        actions.add(MenuRow("Delete App", "−", destructive = true) {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
        })
        showHoldMenu(app, bounds, shortcuts, actions)
    }

    private fun showHoldMenu(app: AppEntry, bounds: Rect, shortcuts: List<MenuRow>, actions: List<MenuRow>) {
        val d = resources.displayMetrics.density
        holdLayer.removeAllViews()
        holdOpen = true

        val rootLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)
        val ix = bounds.left - rootLoc[0]
        val iy = bounds.top - rootLoc[1]

        // The lifted icon, exactly over the one that was pressed.
        val lifted = ImageView(this).apply {
            setImageBitmap(app.icon)
            scaleType = ImageView.ScaleType.FIT_XY
        }
        holdLayer.addView(lifted, FrameLayout.LayoutParams(bounds.width(), bounds.height()).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = ix
            topMargin = iy
        })

        // The menu card.
        fun row(r: MenuRow): View = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (50 * d).toInt()
            setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
            addView(TextView(context).apply {
                text = r.label
                textSize = 17f
                maxLines = 1
                setTextColor(if (r.destructive) 0xFFFF453A.toInt() else Color.WHITE)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (r.icon != null) {
                addView(ImageView(context).apply { setImageDrawable(r.icon) }, LinearLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt()))
            } else if (r.glyph != null) {
                addView(TextView(context).apply {
                    text = r.glyph
                    textSize = 20f
                    setTextColor(if (r.destructive) 0xFFFF453A.toInt() else Color.WHITE)
                })
            }
            setOnClickListener {
                closeHoldMenu()
                r.action()
            }
        }
        fun line(thick: Boolean) = View(this).apply {
            setBackgroundColor(if (thick) 0x55000000 else 0x33FFFFFF)
        }.also { }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 22 * d
                setColor(0xFF2C2C2E.toInt())
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            elevation = 12 * d
        }
        shortcuts.forEachIndexed { i, r ->
            if (i > 0) card.addView(line(false), LinearLayout.LayoutParams(-1, 1))
            card.addView(row(r), LinearLayout.LayoutParams(-1, -2))
        }
        if (shortcuts.isNotEmpty()) card.addView(line(true), LinearLayout.LayoutParams(-1, (6 * d).toInt()))
        actions.forEachIndexed { i, r ->
            if (i > 0) card.addView(line(false), LinearLayout.LayoutParams(-1, 1))
            card.addView(row(r), LinearLayout.LayoutParams(-1, -2))
        }

        val w = (250 * d).toInt()
        card.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val h = card.measuredHeight
        val screenW = root.width
        val screenH = root.height
        val margin = (16 * d).toInt()
        val gap = (14 * d).toInt() + (if (bounds.width() >= 40 * d) (14 * d).toInt() else 0) // room for the app's name under big icons
        val x = (ix + bounds.width() / 2 - w / 2).coerceIn(margin, maxOf(margin, screenW - w - margin))
        val below = iy + bounds.height() + gap + h <= screenH - bottomInset - margin
        val y = if (below) iy + bounds.height() + gap else maxOf(topInset + margin, iy - gap - h)
        holdLayer.addView(card, FrameLayout.LayoutParams(w, h).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = x
            topMargin = y
        })

        holdLayer.visibility = View.VISIBLE
        holdLayer.alpha = 0f
        holdLayer.animate().alpha(1f).setDuration(180).start()
        lifted.animate().scaleX(1.14f).scaleY(1.14f).setDuration(220).setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
        card.alpha = 0f
        card.scaleX = 0.7f
        card.scaleY = 0.7f
        card.pivotX = (ix + bounds.width() / 2 - x).toFloat()
        card.pivotY = if (below) 0f else h.toFloat()
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
    }

    private fun closeHoldMenu() {
        if (!holdOpen) return
        holdOpen = false
        try { window.setBackgroundBlurRadius(if (overlayOpen) 60 else 0) } catch (_: Exception) {}
        holdLayer.animate().alpha(0f).setDuration(140).withEndAction {
            if (!holdOpen) {
                holdLayer.visibility = View.GONE
                holdLayer.removeAllViews()
            }
        }.start()
    }

    override fun onBackgroundMenu() = showSettings()

    private fun showSettings() {
        kit.sheet("Home screen", listOf(
            SheetItem("Panel opacity", "${prefs.opacity}%") { showOpacitySlider() },
            SheetItem("Make this my home app") { requestHomeRole() },
            SheetItem("Reset home screen layout", destructive = true) { home.resetLayout() },
        )).show()
    }

    private fun showOpacitySlider() {
        val slider = kit.slider(
            "Opacity", 0f, 100f, prefs.opacity.toFloat(), { "${it.toInt()}%" },
            "How solid the dock, search pill and library cards are.",
            { p ->
                prefs.opacity = p.toInt()
                home.setPanelOpacity(p / 100f)
            },
        )
        kit.dialog("Panel opacity", slider.view, "Done").show()
    }

    private fun requestHomeRole() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm.isRoleHeld(RoleManager.ROLE_HOME)) {
            Toast.makeText(this, "Already your home app", Toast.LENGTH_SHORT).show()
        } else if (rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
            homeRole.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
        } else {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }
    }
}
