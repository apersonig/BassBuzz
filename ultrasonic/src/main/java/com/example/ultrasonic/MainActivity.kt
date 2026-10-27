package com.example.ultrasonic

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Space
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.ui.Fonts
import com.example.ui.Kit
import com.example.ui.Palettes
import com.example.ui.Segmented
import com.example.ui.Theme
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val PREFS = "ultrasonic"
    }

    private lateinit var engine: ToneEngine
    private var mic: MicMeter? = null

    private lateinit var freqLabel: TextView
    private lateinit var zoneLabel: TextView
    private lateinit var routeLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var micLabel: TextView
    private lateinit var playButton: Button
    private lateinit var toneBar: SeekBar
    private lateinit var toneBox: LinearLayout
    private lateinit var sweepBox: LinearLayout
    private lateinit var kit: Kit
    private lateinit var modeSelector: Segmented
    private lateinit var micSwitch: Switch

    private var maxHz = 23950
    private var toneHz = 21000
    private var sweepFrom = 18000
    private var sweepTo = 23950
    private var sweepSeconds = 10
    private var volume = 0.3f // slider position; the amplitude is this squared
    private var sweepMode = false
    private var pulseOn = false
    private var pulseMs = 1000
    private var autoStopMin = 10
    private var micWanted = false
    private var micTone = Float.NaN
    private var micFloor = Float.NaN

    private val ui = Handler(Looper.getMainLooper())

    private val autoStop = Runnable {
        stopTone()
        statusLabel.text = "Stopped by the timer."
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (engine.isRunning) {
                routeLabel.text = engine.updateRoute()
                val f = if (sweepMode) engine.currentHz else toneHz.toFloat()
                if (f > 0f) showFrequency(f)
            }
            ui.postDelayed(this, 100)
        }
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            micWanted = true
            startMicIfNeeded()
        } else {
            micWanted = false
            micSwitch.isChecked = false
            micLabel.text = "Microphone access is needed for the mic check."
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = ToneEngine()
        maxHz = Dsp.maxHz(engine.sampleRate)
        toneHz = toneHz.coerceAtMost(maxHz)
        sweepTo = maxHz
        engine.onStopped = { runOnUiThread { onToneStopped() } }

        kit = Kit(this, Theme(Palettes.bench, Fonts.plex(this)))
        setContentView(buildUi())
        kit.matchSystemBars(window)
        setMode(sweep = false)
        ui.post(ticker)
        showSafetyNotice()
    }

    override fun onStop() {
        // A tone nobody is looking at is a tone nobody is monitoring, so stop it when the app leaves the screen.
        if (engine.isRunning) stopTone()
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        mic?.stop()
        engine.onStopped = null
        engine.release()
        super.onDestroy()
    }

    private fun showSafetyNotice() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("ack", false)) return
        kit.dialog(
            "Before you start",
            "Ultrasonic sound is silent to most adults, but not to everyone or everything.\n\n" +
                "Dogs, cats, rodents and other animals hear it clearly and can be distressed by it, so keep it away from pets.\n\n" +
                "Younger people may hear tones from about 17 kHz up, and loud tones can be uncomfortable or cause ringing in the ears.\n\n" +
                "Never use headphones or earbuds with this, and start with the volume low. Please don't use it to bother or harm anyone.",
            "I understand",
            cancelable = false,
        ) { prefs.edit().putBoolean("ack", true).apply() }.show()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val col = kit.column()

        col.addView(kit.title("Ultrasonic Tone"))
        kit.gap(col, 4)
        col.addView(
            kit.caption(
                "Output rate is ${engine.sampleRate / 1000.0} kHz, so the highest tone is ${maxHz / 1000.0} kHz. " +
                    "Many phone speakers fade out above about 20 kHz.",
            ),
        )
        kit.gap(col, 32)

        freqLabel = kit.readout("", 48f)
        col.addView(freqLabel)
        kit.gap(col, 4)
        zoneLabel = kit.text("", 14f, kit.c.accent, kit.f.medium)
        col.addView(zoneLabel)
        kit.gap(col, 2)
        routeLabel = kit.caption("")
        col.addView(routeLabel)
        kit.gap(col, 16)

        playButton = kit.button("Play", Kit.Weight.PRIMARY) { togglePlay() }
        col.addView(playButton, LinearLayout.LayoutParams(MATCH, kit.dp(56)))
        kit.gap(col, 8)
        statusLabel = kit.caption("")
        col.addView(statusLabel)
        kit.gap(col, 32)

        modeSelector = kit.segmented(listOf("Steady tone", "Sweep"), 0) { setMode(sweep = it == 1) }
        col.addView(modeSelector.view, LinearLayout.LayoutParams(MATCH, WRAP))
        kit.gap(col, 16)

        // ---- steady tone controls
        toneBox = kit.column()
        toneBar = kit.seekBar((maxHz - Dsp.MIN_HZ) / 10, (toneHz - Dsp.MIN_HZ) / 10, { p, fromUser ->
            if (fromUser) setToneHz(Dsp.MIN_HZ + p * 10)
        })
        toneBox.addView(toneBar, LinearLayout.LayoutParams(MATCH, kit.dp(44)))
        val ends = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ends.addView(kit.readout("${Dsp.MIN_HZ / 1000} kHz", 12f, kit.c.inkSoft), LinearLayout.LayoutParams(0, WRAP, 1f))
        ends.addView(kit.readout("${maxHz / 1000.0} kHz", 12f, kit.c.inkSoft))
        toneBox.addView(ends, LinearLayout.LayoutParams(MATCH, WRAP))
        kit.gap(toneBox, 12)
        toneBox.addView(
            kit.rowOf(
                kit.button("\u2212100") { setToneHz(toneHz - 100) },
                kit.button("\u221210") { setToneHz(toneHz - 10) },
                kit.button("+10") { setToneHz(toneHz + 10) },
                kit.button("+100") { setToneHz(toneHz + 100) },
            ),
            LinearLayout.LayoutParams(MATCH, WRAP),
        )
        kit.gap(toneBox, 16)
        toneBox.addView(kit.caption("Presets"))
        kit.gap(toneBox, 8)
        val presets = listOf(18000, 20000, 22000, 23000).filter { it <= maxHz }
        toneBox.addView(
            kit.rowOf(*presets.map { hz -> kit.button("${hz / 1000}k") { setToneHz(hz) } }.toTypedArray()),
            LinearLayout.LayoutParams(MATCH, WRAP),
        )
        col.addView(toneBox, LinearLayout.LayoutParams(MATCH, WRAP))

        // ---- sweep controls
        sweepBox = kit.column()
        addSlider(sweepBox, "Start", Dsp.MIN_HZ.toFloat(), maxHz.toFloat(), sweepFrom.toFloat(), { hzText(roundTo100(it)) }) {
            sweepFrom = roundTo100(it)
            sync()
        }
        addSlider(sweepBox, "End", Dsp.MIN_HZ.toFloat(), maxHz.toFloat(), sweepTo.toFloat(), { hzText(roundTo100(it)) }) {
            sweepTo = roundTo100(it)
            sync()
        }
        addSlider(sweepBox, "Time for one sweep", 1f, 60f, sweepSeconds.toFloat(), { "${it.roundToInt()} s" }) {
            sweepSeconds = it.roundToInt().coerceAtLeast(1)
            sync()
        }
        sweepBox.addView(kit.caption("The sweep repeats until you stop it. An end below the start sweeps downwards."))
        col.addView(sweepBox, LinearLayout.LayoutParams(MATCH, WRAP))

        kit.gap(col, 32)
        col.addView(kit.heading("Output"))
        kit.gap(col, 12)
        addSlider(col, "Volume", 0f, 1f, volume, { "${(it * 100).roundToInt()}%" }, "The phone's media volume also applies.") {
            volume = it
            sync()
        }
        col.addView(
            kit.toggle("Pulsed", "Switch on and off instead of playing continuously.") { on ->
                pulseOn = on
                sync()
            }.view,
            LinearLayout.LayoutParams(MATCH, WRAP),
        )
        kit.gap(col, 8)
        addSlider(col, "Pulse period", 100f, 2000f, pulseMs.toFloat(), { "${it.roundToInt()} ms" }) {
            pulseMs = it.roundToInt()
            sync()
        }
        addSlider(col, "Auto-stop after", 1f, 60f, autoStopMin.toFloat(), { "${it.roundToInt()} min" }) {
            autoStopMin = it.roundToInt().coerceAtLeast(1)
            if (engine.isRunning) {
                ui.removeCallbacks(autoStop)
                ui.postDelayed(autoStop, autoStopMin * 60_000L)
            }
        }

        kit.gap(col, 32)
        col.addView(kit.heading("Mic check"))
        kit.gap(col, 4)
        col.addView(
            kit.caption(
                "You can't hear this, so how do you know it works? With this on, the microphone measures the tone " +
                    "against the background noise. Phone mics also fade out near 20 kHz, so a weak reading does " +
                    "not prove the speaker is silent.",
            ),
        )
        kit.gap(col, 8)
        val micToggle = kit.toggle("Measure with the microphone") { on -> onMicSwitch(on) }
        micSwitch = micToggle.switch
        col.addView(micToggle.view, LinearLayout.LayoutParams(MATCH, WRAP))
        kit.gap(col, 4)
        micLabel = kit.text("", 14f, kit.c.accent, kit.f.medium)
        col.addView(micLabel)

        kit.gap(col, 32)
        col.addView(
            kit.caption(
                "Safety: keep the phone away from ears, babies and pets, don't use headphones, and keep the " +
                    "volume low. Playback stops when you leave the app.",
            ),
        )

        return kit.screen(col)
    }

    private fun addSlider(
        parent: LinearLayout,
        title: String,
        lo: Float,
        hi: Float,
        initial: Float,
        fmt: (Float) -> String,
        hint: String? = null,
        onChange: (Float) -> Unit,
    ) {
        parent.addView(kit.slider(title, lo, hi, initial, fmt, hint, onChange).view, LinearLayout.LayoutParams(MATCH, WRAP))
        kit.gap(parent, 16)
    }

    private fun roundTo100(v: Float): Int = ((v / 100f).roundToInt() * 100).coerceIn(Dsp.MIN_HZ, maxHz)

    private fun hzText(hz: Int) = "%,d Hz".format(hz)

    // ---------------------------------------------------------------- state

    private fun setMode(sweep: Boolean) {
        sweepMode = sweep
        toneBox.visibility = if (sweep) View.GONE else View.VISIBLE
        sweepBox.visibility = if (sweep) View.VISIBLE else View.GONE
        modeSelector.select(if (sweep) 1 else 0)
        sync()
    }

    private fun setToneHz(hz: Int) {
        toneHz = hz.coerceIn(Dsp.MIN_HZ, maxHz)
        val p = (toneHz - Dsp.MIN_HZ) / 10
        if (toneBar.progress != p) toneBar.progress = p
        sync()
    }

    /** Pushes every setting to the engine and the mic meter, and refreshes the big readout. */
    private fun sync() {
        engine.frequencyHz = toneHz.toFloat()
        engine.sweeping = sweepMode
        engine.sweepFromHz = sweepFrom.toFloat()
        engine.sweepToHz = sweepTo.toFloat()
        engine.sweepSeconds = sweepSeconds.toFloat()
        engine.amplitude = Dsp.volumeCurve(volume)
        engine.pulsePeriodMs = if (pulseOn) pulseMs else 0
        mic?.frequencyHz = toneHz.toFloat()
        if (!engine.isRunning) refreshIdleReadout()
    }

    private fun refreshIdleReadout() {
        if (sweepMode) {
            freqLabel.text = "${sweepFrom / 1000.0} to ${sweepTo / 1000.0} kHz"
            zoneLabel.text = "Sweep over ${sweepSeconds} s"
        } else {
            showFrequency(toneHz.toFloat())
        }
    }

    private fun showFrequency(f: Float) {
        freqLabel.text = hzText(f.roundToInt())
        zoneLabel.text = Dsp.zone(f)
    }

    // ---------------------------------------------------------------- playback

    private fun togglePlay() {
        if (engine.isRunning) stopTone() else startTone()
    }

    private fun startTone() {
        sync()
        if (!engine.start()) {
            statusLabel.text = engine.lastError ?: "Couldn't start audio output."
            return
        }
        statusLabel.text = ""
        playButton.text = "Stop"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui.removeCallbacks(autoStop)
        ui.postDelayed(autoStop, autoStopMin * 60_000L)
        startMicIfNeeded()
    }

    private fun stopTone() {
        ui.removeCallbacks(autoStop)
        engine.stop() // fades out; onToneStopped() runs when the sound has ended
    }

    private fun onToneStopped() {
        playButton.text = "Play"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mic?.stop()
        mic = null
        micTone = Float.NaN
        micFloor = Float.NaN
        routeLabel.text = ""
        engine.lastError?.let { statusLabel.text = it }
        refreshIdleReadout()
    }

    // ---------------------------------------------------------------- mic check

    private fun onMicSwitch(on: Boolean) {
        if (!on) {
            micWanted = false
            mic?.stop()
            mic = null
            micLabel.text = ""
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            micWanted = true
            startMicIfNeeded()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startMicIfNeeded() {
        if (!micWanted || !engine.isRunning) {
            if (micWanted) micLabel.text = "Press Play to start measuring."
            return
        }
        if (mic?.isRunning == true) return
        val unprocessed = getSystemService(AudioManager::class.java)
            .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val m = MicMeter(engine.sampleRate) { tone, floor -> runOnUiThread { showMic(tone, floor) } }
        m.frequencyHz = toneHz.toFloat()
        if (m.start(unprocessed)) {
            mic = m
            micLabel.text = "Listening..."
        } else {
            micLabel.text = "Couldn't start the microphone."
        }
    }

    private fun showMic(tone: Float, floor: Float) {
        if (sweepMode || pulseOn) {
            micLabel.text = "The mic check needs a steady tone: turn off the sweep and pulsing."
            return
        }
        micTone = if (micTone.isNaN()) tone else micTone * 0.6f + tone * 0.4f
        micFloor = if (micFloor.isNaN()) floor else micFloor * 0.6f + floor * 0.4f
        val margin = micTone - micFloor
        val verdict = when {
            margin >= 10f -> "Detected"
            margin >= 4f -> "Weak"
            else -> "Not detected"
        }
        micLabel.text = "%s: tone %.0f dBFS, background %.0f dBFS (%+.0f dB)".format(verdict, micTone, micFloor, margin)
    }
}
