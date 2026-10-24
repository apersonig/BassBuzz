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
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextClock
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class LauncherActivity : ComponentActivity(), HoneycombView.Listener {

    private lateinit var prefs: Prefs
    private lateinit var repo: AppRepository
    private lateinit var grid: HoneycombView
    private lateinit var list: ListView
    private lateinit var clock: TextClock
    private lateinit var root: FrameLayout
    private var apps: List<AppEntry> = emptyList()
    private var topInset = 0
    private var bottomInset = 0

    private val homeRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        repo = AppRepository(this, prefs)
        val d = resources.displayMetrics.density

        grid = HoneycombView(this).also { it.listener = this }
        list = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            dividerHeight = (8 * d).toInt()
            clipToPadding = false
            visibility = View.GONE
            overScrollMode = View.OVER_SCROLL_NEVER
            setOnItemClickListener { _, v, pos, _ -> launch(apps[pos], boundsOf(v)) }
            setOnItemLongClickListener { _, v, pos, _ -> onAppMenu(apps[pos], boundsOf(v)); true }
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
            addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { rightMargin = (20 * d).toInt() })
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                topInset = bars.top
                bottomInset = bars.bottom
                (clock.layoutParams as FrameLayout.LayoutParams).topMargin = topInset + (8 * d).toInt()
                clock.requestLayout()
                list.setPadding((12 * d).toInt(), topInset + (44 * d).toInt(), (12 * d).toInt(), bottomInset + (12 * d).toInt())
                grid.bottomInset = bottomInset
                insets
            }
        }
        setContentView(root)

        list.adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(position: Int) = apps[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@LauncherActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((10 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), (8 * d).toInt())
                    background = GradientDrawable().apply { cornerRadius = 40 * d; setColor(0xFF1C1C1E.toInt()) }
                    layoutParams = android.widget.AbsListView.LayoutParams(-1, -2)
                    addView(ImageView(context), LinearLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt()))
                    addView(TextView(context).apply { setTextColor(Color.WHITE); textSize = 18f; maxLines = 1 },
                        LinearLayout.LayoutParams(-1, -2).apply { leftMargin = (14 * d).toInt() })
                }
                val app = apps[position]
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
        if (list.visibility == View.VISIBLE) setListMode(false)
        else if (!grid.isCentred()) grid.recentre()
    }

    @Deprecated("Back is handled here so a launcher never closes itself")
    override fun onBackPressed() {
        if (prefs.listView) setListMode(false) else if (!grid.isCentred()) grid.recentre()
    }

    private fun setListMode(on: Boolean) {
        prefs.listView = on
        list.visibility = if (on) View.VISIBLE else View.GONE
        grid.visibility = if (on) View.GONE else View.VISIBLE
        if (on) list.setSelection(0)
    }

    private fun applySettings() {
        grid.setSpacingMode(prefs.spacing)
        grid.snap = prefs.snap
        grid.ticks = prefs.ticks
        clock.visibility = if (prefs.clock) View.VISIBLE else View.GONE
        list.visibility = if (prefs.listView) View.VISIBLE else View.GONE
        grid.visibility = if (prefs.listView) View.GONE else View.VISIBLE
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
        val items = arrayOf("App info", "Uninstall", "Move to centre")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(app.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> try { repo.showAppInfo(app, bounds) } catch (_: Exception) {}
                    1 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
                    2 -> { prefs.promote(app.key); reload() }
                }
            }.show()
    }

    override fun onBackgroundMenu() {
        val onOff = { b: Boolean -> if (b) "on" else "off" }
        val items = arrayOf(
            if (prefs.listView) "Show honeycomb" else "Show list",
            "Order: " + if (prefs.order == 0) "most used (tap for A to Z)" else "A to Z (tap for most used)",
            "Icon spacing: " + listOf("compact", "normal", "roomy")[prefs.spacing] + " (tap to change)",
            "Snap to nearest icon: ${onOff(prefs.snap)}",
            "Haptic ticks: ${onOff(prefs.ticks)}",
            "Clock: ${onOff(prefs.clock)}",
            "Hide status bar: ${onOff(prefs.hideStatusBar)}",
            "Make this my home app",
            "Centre the grid",
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Watch Launcher")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> setListMode(!prefs.listView)
                    1 -> { prefs.order = 1 - prefs.order; reload() }
                    2 -> { prefs.spacing = (prefs.spacing + 1) % 3; applySettings() }
                    3 -> { prefs.snap = !prefs.snap; applySettings() }
                    4 -> { prefs.ticks = !prefs.ticks; applySettings() }
                    5 -> { prefs.clock = !prefs.clock; applySettings() }
                    6 -> { prefs.hideStatusBar = !prefs.hideStatusBar; applySettings() }
                    7 -> requestHomeRole()
                    8 -> grid.recentre()
                }
            }.show()
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
