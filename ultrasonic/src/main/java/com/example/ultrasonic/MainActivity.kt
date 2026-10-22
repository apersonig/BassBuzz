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
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private companion object {
        const val TEXT = 0xFFE8E6F0.toInt()
        const val MUTED = 0xFF9893A8.toInt()
        const val ACCENT = 0xFFB794F6.toInt()
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
    private lateinit var toneModeButton: Button
    private lateinit var sweepModeButton: Button
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

        setContentView(buildUi())
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
        AlertDialog.Builder(this)
            .setTitle("Before you start")
            .setMessage(
                "Ultrasonic sound is silent to most adults, but not to everyone or everything.\n\n" +
                    "- Dogs, cats, rodents and other animals hear it clearly and can be distressed by it. " +
                    "Keep it away from pets.\n" +
                    "- Younger people may hear tones from about 17 kHz up, and loud tones can be " +
                    "uncomfortable or cause ringing in the ears.\n" +
                    "- Never use headphones or earbuds with this, and start with the volume low.\n" +
                    "- Please don't use it to bother or harm anyone.",
            )
            .setCancelable(false)
            .setPositiveButton("I understand") { _, _ -> prefs.edit().putBoolean("ack", true).apply() }
            .show()
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

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun LinearLayout.buttonRow(vararg buttons: Button) {
        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        for (b in buttons) row.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun buildUi(): View {
        val pad = dp(20)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(label("Ultrasonic Tone", 30f, bold = true))
        col.addView(
            label(
                "Plays a pure tone through the speaker. Output rate is ${engine.sampleRate / 1000.0} kHz, so " +
                    "the highest possible tone is ${maxHz / 1000.0} kHz. Many phone speakers fade out above about 20 kHz.",
                13f, MUTED,
            ),
        )
        col.gap(16)

        freqLabel = label("", 40f, bold = true)
        col.addView(freqLabel)
        zoneLabel = label("", 14f, ACCENT)
        col.addView(zoneLabel)
        routeLabel = label("", 12f, MUTED)
        col.addView(routeLabel)
        col.gap(12)

        playButton = button("Play") { togglePlay() }.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f) }
        col.addView(playButton, LinearLayout.LayoutParams(MATCH, dp(64)))
        statusLabel = label("", 13f, MUTED)
        col.addView(statusLabel)
        col.gap(16)

        toneModeButton = button("Steady tone") { setMode(sweep = false) }
        sweepModeButton = button("Sweep") { setMode(sweep = true) }
        col.buttonRow(toneModeButton, sweepModeButton)
        col.gap(12)

        // ---- steady tone controls
        toneBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        toneBar = SeekBar(this).apply {
            max = (maxHz - Dsp.MIN_HZ) / 10
            progress = (toneHz - Dsp.MIN_HZ) / 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) setToneHz(Dsp.MIN_HZ + p * 10)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        toneBox.addView(toneBar, LinearLayout.LayoutParams(MATCH, WRAP))
        val ends = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ends.addView(label("${Dsp.MIN_HZ / 1000} kHz", 12f, MUTED), LinearLayout.LayoutParams(0, WRAP, 1f))
        ends.addView(label("${maxHz / 1000.0} kHz", 12f, MUTED))
        toneBox.addView(ends, LinearLayout.LayoutParams(MATCH, WRAP))
        toneBox.gap(8)
        toneBox.buttonRow(
            button("-1k") { setToneHz(toneHz - 1000) },
            button("-100") { setToneHz(toneHz - 100) },
            button("-10") { setToneHz(toneHz - 10) },
            button("+10") { setToneHz(toneHz + 10) },
            button("+100") { setToneHz(toneHz + 100) },
            button("+1k") { setToneHz(toneHz + 1000) },
        )
        val presets = listOf(18000, 20000, 22000, 23000).filter { it <= maxHz }
        toneBox.buttonRow(*presets.map { hz -> button("${hz / 1000} kHz") { setToneHz(hz) } }.toTypedArray())
        col.addView(toneBox, LinearLayout.LayoutParams(MATCH, WRAP))

        // ---- sweep controls
        sweepBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
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
        sweepBox.addView(label("The sweep repeats until you stop it. End lower than start sweeps downwards.", 12f, MUTED))
        col.addView(sweepBox, LinearLayout.LayoutParams(MATCH, WRAP))

        col.gap(16)
        addSlider(col, "Volume", 0f, 1f, volume, { "${(it * 100).roundToInt()}%" }) {
            volume = it
            sync()
        }
        col.addView(label("This is the app's level. The phone's media volume also applies.", 12f, MUTED))
        col.gap(16)

        col.addView(
            Switch(this).apply {
                text = "Pulsed (on/off) instead of continuous"
                setTextColor(TEXT)
                setOnCheckedChangeListener { _, on ->
                    pulseOn = on
                    sync()
                }
            },
        )
        col.gap(4)
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

        col.gap(8)
        col.addView(label("Mic check", 18f, bold = true))
        col.addView(
            label(
                "You can't hear this, so how do you know it's working? Turn this on and the microphone measures " +
                    "the tone against the background noise. Phone mics also fade out near 20 kHz, so a weak " +
                    "reading doesn't prove the speaker is silent.",
                12f, MUTED,
            ),
        )
        micSwitch = Switch(this).apply {
            text = "Measure the tone with the microphone"
            setTextColor(TEXT)
            setOnCheckedChangeListener { _, on -> onMicSwitch(on) }
        }
        col.addView(micSwitch)
        micLabel = label("", 14f, ACCENT)
        col.addView(micLabel)

        col.gap(24)
        col.addView(
            label(
                "Safety: keep the phone away from ears, babies and pets, don't use headphones, and keep the " +
                    "volume low. Playback stops when you leave the app.",
                12f, MUTED,
            ),
        )

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

    private fun addSlider(
        parent: LinearLayout,
        title: String,
        lo: Float,
        hi: Float,
        initial: Float,
        fmt: (Float) -> String,
        onChange: (Float) -> Unit,
    ) {
        val text = label("", 14f)
        fun refresh(v: Float) {
            text.text = "$title: ${fmt(v)}"
        }
        refresh(initial)

        val bar = SeekBar(this).apply {
            max = 1000
            progress = ((initial - lo) / (hi - lo) * 1000).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val v = lo + (hi - lo) * p / 1000f
                    refresh(v)
                    if (fromUser) onChange(v)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        parent.addView(text)
        parent.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        parent.gap(8)
    }

    private fun roundTo100(v: Float): Int = ((v / 100f).roundToInt() * 100).coerceIn(Dsp.MIN_HZ, maxHz)

    private fun hzText(hz: Int) = "%,d Hz".format(hz)

    // ---------------------------------------------------------------- state

    private fun setMode(sweep: Boolean) {
        sweepMode = sweep
        toneBox.visibility = if (sweep) View.GONE else View.VISIBLE
        sweepBox.visibility = if (sweep) View.VISIBLE else View.GONE
        toneModeButton.alpha = if (sweep) 0.5f else 1f
        sweepModeButton.alpha = if (sweep) 1f else 0.5f
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
