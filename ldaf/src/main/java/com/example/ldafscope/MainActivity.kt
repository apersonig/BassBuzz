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

class MainActivity : ComponentActivity(), SensorEventListener, RangeFinder.Callback {

    private companion object {
        const val TEXT = 0xFFE8EAF0.toInt()
        const val MUTED = 0xFF8B95A5.toInt()
        const val ACCENT = 0xFF4FD1C5.toInt()
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
    private lateinit var scanToggle: Button
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
        setContentView(buildUi())
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
        distanceLabel.setTextColor(if (ok) TEXT else MUTED)
        stateLabel.text = "Autofocus: ${Geometry.afStateName(r.afState)}  |  lens at %.2f diopters  |  %d points".format(r.diopters, scan.count)

        if (scanning && ok && dir != null) {
            scan.add(dir[0] * meters, dir[1] * meters, dir[2] * meters)
        }
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(s: String, sp: Float, color: Int = TEXT, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun LinearLayout.gap(dpv: Int) = addView(Space(this@MainActivity), LinearLayout.LayoutParams(1, dp(dpv)))

    private fun buildUi(): View {
        val pad = dp(20)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(label("LDAF Scope", 30f, bold = true))
        col.addView(label("Point the back of the phone at something and read the distance", 14f, MUTED))
        col.gap(16)

        distanceLabel = label("-", 56f, bold = true)
        col.addView(distanceLabel)
        stateLabel = label("", 13f, MUTED)
        col.addView(stateLabel)
        focusInfo = label("", 12f, MUTED)
        col.addView(focusInfo)
        col.gap(8)
        grantButton = Button(this).apply {
            text = "Grant camera access"
            visibility = View.GONE
            setOnClickListener { cameraPermission.launch(Manifest.permission.CAMERA) }
        }
        col.addView(grantButton, LinearLayout.LayoutParams(MATCH, WRAP))

        col.addView(
            label(
                "Android doesn't give apps the raw laser sensor. This reads where the camera's autofocus has " +
                    "settled, which the phone steers using the laser, so it follows the distance to what you " +
                    "aim at. Expect it to work best from about 10 cm to a few metres, on textured, " +
                    "well-lit surfaces. It lags if you move fast.",
                12f, MUTED,
            ),
        )
        col.gap(16)

        col.addView(label("Scan map", 18f, bold = true))
        col.addView(label("Sweep the phone slowly around you. Colour = height: blue low, red high.", 12f, MUTED))
        col.gap(8)
        scan = ScanView(this)
        col.addView(scan, LinearLayout.LayoutParams(MATCH, dp(340)))
        col.gap(8)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scanToggle = Button(this).apply {
            text = "Scanning: on"
            setOnClickListener {
                scanning = !scanning
                text = if (scanning) "Scanning: on" else "Scanning: paused"
            }
        }
        row.addView(scanToggle, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(
            Button(this).apply {
                text = "Clear map"
                setOnClickListener { scan.clear() }
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        col.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        col.gap(24)

        col.addView(label("Device explorer", 18f, bold = true))
        col.addView(
            label(
                "Lists every sensor and camera key this phone shows to apps, to find out whether the laser " +
                    "sensor is exposed anywhere else. Build it once the live readout is running.",
                12f, MUTED,
            ),
        )
        col.gap(8)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(
            Button(this).apply {
                text = "Build report"
                setOnClickListener { buildReport() }
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        row2.addView(
            Button(this).apply {
                text = "Share report"
                setOnClickListener { shareReport() }
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        col.addView(row2, LinearLayout.LayoutParams(MATCH, WRAP))
        col.gap(8)
        reportView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(ACCENT)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        col.addView(reportView, LinearLayout.LayoutParams(MATCH, WRAP))

        return ScrollView(this).apply {
            clipToPadding = false
            addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad)
                WindowInsets.CONSUMED
            }
        }
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
