package com.example.ldafscope

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.ui.Fonts
import com.example.ui.Kit
import com.example.ui.Palettes
import com.example.ui.Theme

class MainActivity : ComponentActivity(), SensorEventListener, RangeFinder.Callback {

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val SMOOTHING = 5
    }

    private lateinit var rangeFinder: RangeFinder
    private lateinit var sensorManager: SensorManager
    private lateinit var scan: ScanView
    private lateinit var distanceLabel: TextView
    private lateinit var stateLabel: TextView
    private lateinit var focusInfo: TextView
    private lateinit var kit: Kit
    private lateinit var statRows: Map<String, TextView>
    private lateinit var grantButton: Button
    private lateinit var reportView: TextView

    private val recent = ArrayDeque<Float>()
    private var scanning = true
    private var lastUiMs = 0L
    private val rotation = FloatArray(9)

    /** Where the back of the phone points, written by the sensor callback, read by the camera thread. */
    @Volatile
    private var direction: FloatArray? = null

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showNoPermission()
        }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rangeFinder = RangeFinder(applicationContext).also { it.callback = this }
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        kit = Kit(this, Theme(Palettes.instrument, Fonts.condensed(this)))
        setContentView(buildUi())
        kit.matchSystemBars(window)
    }

    override fun onStart() {
        super.onStart()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val rv = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rv != null) {
            sensorManager.registerListener(this, rv, SensorManager.SENSOR_DELAY_GAME)
        } else {
            stateLabel.text = "This phone has no rotation sensor, so the map can't be drawn."
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStop() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensorManager.unregisterListener(this)
        rangeFinder.stop()
        super.onStop()
    }

    override fun onDestroy() {
        rangeFinder.release()
        super.onDestroy()
    }

    private fun startCamera() {
        grantButton.visibility = View.GONE
        focusInfo.text = try {
            rangeFinder.describeFocus()
        } catch (e: Exception) {
            "Couldn't read camera details: ${e.message}"
        }
        stateLabel.text = "Starting the camera..."
        rangeFinder.start()
    }

    private fun showNoPermission() {
        stateLabel.text = "Camera access is needed: Android only lets apps read the autofocus distance through the camera."
        grantButton.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- sensors and camera

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        direction = Geometry.backDirection(rotation)
        scan.heading = direction
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onReading(reading: Reading) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUiMs < 100) return // about 10 updates per second is plenty
        lastUiMs = now

        val meters = Geometry.metersFromDiopters(reading.diopters)
        val ok = Geometry.acceptable(reading.afState, meters)
        val dir = direction
        runOnUiThread { handleReading(reading, meters, ok, dir) }
    }

    override fun onError(message: String) {
        runOnUiThread { stateLabel.text = message }
    }

    private fun handleReading(r: Reading, meters: Float, ok: Boolean, dir: FloatArray?) {
        if (ok) {
            recent.addLast(meters)
            while (recent.size > SMOOTHING) recent.removeFirst()
        } else if (meters.isInfinite() && r.afState != null && (r.afState == 2 || r.afState == 4)) {
            recent.clear() // focused on something beyond the useful range
        }

        val shown = when {
            recent.isNotEmpty() -> Geometry.median(recent)
            meters.isInfinite() -> Float.POSITIVE_INFINITY
            else -> Float.NaN
        }
        distanceLabel.text = Geometry.format(shown)
        distanceLabel.setTextColor(if (ok) kit.c.ink else kit.c.inkSoft)
        statRows["Autofocus"]?.text = Geometry.afStateName(r.afState)
        statRows["Lens"]?.text = "%.2f diopters".format(r.diopters)
        statRows["Points"]?.text = scan.count.toString()

        if (scanning && ok && dir != null) {
            scan.add(dir[0] * meters, dir[1] * meters, dir[2] * meters)
        }
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val col = kit.column()

        col.addView(kit.title("LDAF Scope"))
        kit.gap(col, 4)
        col.addView(kit.caption("Point the back of the phone at something to read how far away it is."))
        kit.gap(col, 32)

        // The reading is the whole point of the app, so it gets the most space and the largest type.
        distanceLabel = kit.readout("--", 72f, kit.c.inkSoft)
        col.addView(distanceLabel)
        kit.gap(col, 8)
        stateLabel = kit.caption("")
        col.addView(stateLabel)
        kit.gap(col, 16)

        val rows = LinkedHashMap<String, TextView>()
        for (name in listOf("Autofocus", "Lens", "Points")) {
            val value = kit.readout("--", 14f)
            rows[name] = value
            col.addView(statRow(name, value), LinearLayout.LayoutParams(MATCH, WRAP))
        }
        statRows = rows
        kit.gap(col, 8)
        focusInfo = kit.text("", 12f, kit.c.inkSoft, kit.f.mono)
        col.addView(focusInfo)
        kit.gap(col, 12)
        grantButton = kit.button("Allow camera", Kit.Weight.PRIMARY) { cameraPermission.launch(Manifest.permission.CAMERA) }
            .apply { visibility = View.GONE }
        col.addView(grantButton, LinearLayout.LayoutParams(MATCH, WRAP))

        kit.gap(col, 8)
        col.addView(
            kit.caption(
                "Android does not give apps the raw laser sensor. This reads where the camera's autofocus has settled, " +
                    "which the phone steers with the laser. It works best from about 10 cm to a few metres on " +
                    "textured, well-lit surfaces, and it lags when you move fast.",
            ),
        )
        kit.gap(col, 40)

        col.addView(kit.heading("Scan map"))
        kit.gap(col, 4)
        col.addView(kit.caption("Sweep the phone slowly around you. Brighter points are higher up."))
        kit.gap(col, 12)
        scan = ScanView(this).also { it.style(kit.c.surface, kit.c.line, kit.c.inkSoft, kit.c.accent, kit.f.mono) }
        col.addView(scan, LinearLayout.LayoutParams(MATCH, kit.dp(340)))
        kit.gap(col, 8)
        col.addView(
            kit.toggle("Record points", "Adds a point each time a distance is read.", true) { scanning = it }.view,
            LinearLayout.LayoutParams(MATCH, WRAP),
        )
        kit.gap(col, 8)
        col.addView(kit.button("Clear map") { scan.clear() }, LinearLayout.LayoutParams(MATCH, WRAP))
        kit.gap(col, 40)

        col.addView(kit.heading("Device explorer"))
        kit.gap(col, 4)
        col.addView(
            kit.caption(
                "Lists every sensor and camera key this phone shows to apps, to find out whether the laser sensor " +
                    "is exposed anywhere else.",
            ),
        )
        kit.gap(col, 12)
        col.addView(
            kit.rowOf(
                kit.button("Build report") { buildReport() },
                kit.button("Share report") { shareReport() },
            ),
            LinearLayout.LayoutParams(MATCH, WRAP),
        )
        kit.gap(col, 12)
        reportView = kit.text("", 11f, kit.c.ink, kit.f.mono).apply { setTextIsSelectable(true) }
        col.addView(reportView, LinearLayout.LayoutParams(MATCH, WRAP))

        return kit.screen(col)
    }

    /** Name on the left, value on the right, a hairline under each: reads like a spec sheet. */
    private fun statRow(name: String, value: TextView): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        minimumHeight = kit.dp(40)
        addView(kit.body(name), LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(value)
        background = android.graphics.drawable.LayerDrawable(
            arrayOf(android.graphics.drawable.ColorDrawable(kit.c.line)),
        ).apply { setLayerInset(0, 0, kit.dp(40) - 1, 0, 0) }
    }

    // ---------------------------------------------------------------- explorer

    private fun buildReport() {
        reportView.text = try {
            DeviceReport.build(this, rangeFinder.lastResult)
        } catch (e: Exception) {
            "Couldn't build the report: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun shareReport() {
        if (reportView.text.isNullOrEmpty()) buildReport()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "LDAF Scope device report")
            putExtra(Intent.EXTRA_TEXT, reportView.text.toString())
        }
        startActivity(Intent.createChooser(send, "Share report"))
    }
}
