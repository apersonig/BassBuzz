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

class LauncherActivity : ComponentActivity(), HomeView.Listener {

    private lateinit var prefs: Prefs
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
        repo = AppRepository(this, prefs)
        val d = resources.displayMetrics.density

        home = HomeView(this).also {
            it.listener = this
            it.launchCount = { key -> prefs.launches(key) }
            it.setGlass(prefs.glass / 100f)
        }

        // ---- edit bar: Undo / Redo (new in iOS 27), Customize, Done ----
        fun pill(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 40 * d; setColor(0x55FFFFFF) }
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
            setHintTextColor(0xFFAEAEB2.toInt())
            setTextColor(Color.WHITE)
            textSize = 17f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = GradientDrawable().apply { cornerRadius = 24 * d; setColor(0x44FFFFFF) }
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
            setBackgroundColor(0xAA000000.toInt())
            addView(searchRow)
            addView(results, LinearLayout.LayoutParams(-1, 0, 1f))
            isClickable = true
        }

        root = FrameLayout(this).apply {
            addView(home, FrameLayout.LayoutParams(-1, -1))
            addView(editBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(searchPanel, FrameLayout.LayoutParams(-1, -1))
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
                overlayOpen -> closeOverlay()
                home.editing -> home.exitEdit()
                else -> home.goToPage(0)
            }
        }
    }

    @Deprecated("Back is handled here so a launcher never closes itself")
    override fun onBackPressed() {
        when {
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
        try { window.setBackgroundBlurRadius(60) } catch (_: Exception) {}
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
        try { window.setBackgroundBlurRadius(0) } catch (_: Exception) {}
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

    override fun onAppMenu(app: AppEntry, bounds: Rect, inLibrary: Boolean) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (inLibrary && home.isHidden(app.key)) {
            labels.add("Add to Home Screen")
            actions.add { home.addToHome(app.key); closeOverlay() }
        }
        labels.add("App info")
        actions.add { try { repo.showAppInfo(app, bounds) } catch (_: Exception) {} }
        labels.add("Delete App")
        actions.add { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))) }
        if (!inLibrary) {
            labels.add("Edit Home Screen")
            actions.add { home.enterEdit() }
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(app.label)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    override fun onBackgroundMenu() = showSettings()

    private fun showSettings() {
        val items = arrayOf("Glass transparency…", "Make this my home app", "Reset home screen layout")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Glass Launcher")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showGlassSlider()
                    1 -> requestHomeRole()
                    2 -> home.resetLayout()
                }
            }.show()
    }

    private fun showGlassSlider() {
        val d = resources.displayMetrics.density
        val bar = SeekBar(this).apply {
            max = 100
            progress = prefs.glass
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    prefs.glass = p
                    home.setGlass(p / 100f)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (16 * d).toInt(), (24 * d).toInt(), (8 * d).toInt())
            addView(TextView(context).apply { text = "Clear  ←→  Frosted"; setTextColor(0xFFAEAEB2.toInt()) })
            addView(bar)
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Glass transparency")
            .setView(box)
            .setPositiveButton("Done", null)
            .show()
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
