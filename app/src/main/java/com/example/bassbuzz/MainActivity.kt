package com.example.bassbuzz

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Space
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private companion object {
        const val TEXT = 0xFFE8EAF0.toInt()
        const val MUTED = 0xFF9AA0AE.toInt()
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var player: HapticPlayer
    private lateinit var tester: FrequencyTester
    private lateinit var testToggle: Button
    private lateinit var testFreqLabel: TextView
    private lateinit var testPlanLabel: TextView
    private lateinit var testBar: SeekBar
    private var testHz = 170
    private lateinit var envelope: EnvelopeView
    private lateinit var fileLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var timeLabel: TextView
    private lateinit var playButton: Button
    private lateinit var exportButton: Button
    private var analyzedCutoff = 120

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    @Volatile
    private var analysisGen = 0
    private var currentUri: Uri? = null
    private var cutoffHz = 120
    private var settings = HapticSettings()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadFile(uri)
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) exportTo(uri)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (player.isPlaying && !envelope.dragging) updateProgress()
            ui.postDelayed(this, 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = HapticPlayer(applicationContext)
        tester = FrequencyTester(applicationContext).also { t ->
            t.onError = { msg ->
                runOnUiThread {
                    setStatus(msg)
                    refreshTestUi()
                }
            }
        }
        setContentView(buildUi())
        ui.post(ticker)
        setStatus(
            when {
                !player.hasVibrator -> "This device reports no vibration motor."
                !player.hasAmplitudeControl -> "Motor has no amplitude control: haptics will be on/off only."
                else -> "Motor with amplitude control detected. Open a song to start."
            },
        )
    }

    override fun onStop() {
        super.onStop()
        // Haptics are only reliable while the app is in front, so stop everything when leaving.
        if (player.isPlaying) togglePlay()
        stopTester()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        analysisGen += 1
        io.shutdownNow()
        tester.release()
        player.release()
        super.onDestroy()
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

        col.addView(label("BassBuzz", 30f, bold = true))
        col.addView(label("Feel the bass of your music in the vibration motor", 14f, MUTED))
        col.gap(20)

        fileLabel = label("No file loaded", 16f)
        col.addView(fileLabel)
        col.gap(8)
        col.addView(
            Button(this).apply {
                text = "Open audio file"
                setOnClickListener { picker.launch(arrayOf("audio/*")) }
            },
            LinearLayout.LayoutParams(MATCH, WRAP),
        )
        col.gap(12)

        envelope = EnvelopeView(this).apply {
            onSeek = { f ->
                player.seekTo((f * player.durationMs).toInt())
                updateProgress()
            }
        }
        col.addView(envelope, LinearLayout.LayoutParams(MATCH, dp(140)))
        timeLabel = label("0:00 / 0:00", 14f, MUTED)
        col.addView(timeLabel)
        col.gap(8)

        playButton = Button(this).apply {
            text = "Play"
            isEnabled = false
            setOnClickListener { togglePlay() }
        }
        col.addView(playButton, LinearLayout.LayoutParams(MATCH, WRAP))
        exportButton = Button(this).apply {
            text = "Export haptic file (.bbz)"
            isEnabled = false
            setOnClickListener { startExport() }
        }
        col.addView(exportButton, LinearLayout.LayoutParams(MATCH, WRAP))
        col.gap(8)
        statusLabel = label("", 13f, MUTED)
        col.addView(statusLabel)
        col.gap(24)

        col.addView(label("Haptic tuning", 18f, bold = true))
        col.gap(8)
        addSlider(col, "Intensity", 0.2f, 2.0f, settings.intensity, { "${(it * 100).toInt()}%" }, {
            settings = settings.copy(intensity = it)
            player.settings = settings
        })
        addSlider(col, "Noise gate", 0f, 0.6f, settings.gate, { "${(it * 100).toInt()}%" }, {
            settings = settings.copy(gate = it)
            player.settings = settings
        })
        addSlider(col, "Punch on hits", 0f, 2f, settings.punch, { "${(it * 100).toInt()}%" }, {
            settings = settings.copy(punch = it)
            player.settings = settings
        })
        addSlider(
            col, "Timing (raise it if the buzz lags the music)", -200f, 300f, settings.offsetMs.toFloat(),
            { "${it.toInt()} ms" },
            {
                settings = settings.copy(offsetMs = it.toInt())
                player.settings = settings
            },
        )
        addSlider(
            col, "Bass range (re-analyses when released)", 60f, 250f, cutoffHz.toFloat(),
            { "up to ${it.toInt()} Hz" },
            { cutoffHz = it.toInt() },
            onCommit = { currentUri?.let { u -> analyze(u) } },
        )

        col.gap(8)
        col.addView(
            Switch(this).apply {
                text = "Compatibility mode (use if you feel nothing)"
                setTextColor(TEXT)
                setOnCheckedChangeListener { _, on ->
                    player.compat = on
                    tester.compat = on
                }
            },
        )
        col.gap(12)
        col.addView(
            Button(this).apply {
                text = "Test motor"
                setOnClickListener { player.testBuzz() }
            },
            LinearLayout.LayoutParams(MATCH, WRAP),
        )
        col.gap(12)
        col.addView(label("Keep the app in front while playing: the screen stays on and haptics pause if you leave.", 12f, MUTED))

        addFrequencyTester(col)

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
        onCommit: (() -> Unit)? = null,
    ) {
        val text = label("", 14f)
        fun refresh(v: Float) {
            text.text = "$title: ${fmt(v)}"
        }
        refresh(initial)

        val bar = SeekBar(this).apply {
            this.max = 1000
            progress = ((initial - lo) / (hi - lo) * 1000).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val v = lo + (hi - lo) * p / 1000f
                    refresh(v)
                    onChange(v)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}

                override fun onStopTrackingTouch(sb: SeekBar) {
                    onCommit?.invoke()
                }
            })
        }
        parent.addView(text)
        parent.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        parent.gap(10)
    }

    private fun setStatus(s: String) {
        statusLabel.text = s
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun updateProgress() {
        val dur = player.durationMs
        val pos = player.positionMs
        if (dur > 0) envelope.progress = pos.toFloat() / dur
        timeLabel.text = "${fmt(pos)} / ${fmt(dur)}"
    }

    // ---------------------------------------------------------------- actions

    private fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
            playButton.text = "Play"
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            stopTester() // the tester and the music would fight over the motor
            player.play()
            playButton.text = "Pause"
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---------------------------------------------------------------- export

    private fun startExport() {
        val src = currentUri ?: return
        if (player.track == null) return
        exportLauncher.launch("${displayName(src).substringBeforeLast('.')}.${HapticFile.EXTENSION}")
    }

    /** Writes the song and its bass envelope into one .bbz file at [dest]. */
    private fun exportTo(dest: Uri) {
        val src = currentUri ?: return
        val track = player.track ?: return
        val name = displayName(src)
        val title = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "mp3")
        val cutoff = analyzedCutoff

        exportButton.isEnabled = false
        setStatus("Exporting…")
        io.execute {
            try {
                val audio = contentResolver.openInputStream(src)?.use { it.readBytes() }
                    ?: throw DecodeException("Couldn't read the original file")
                if (audio.size > 200 * 1024 * 1024) throw DecodeException("File is too large to export (over 200 MB)")
                val out = contentResolver.openOutputStream(dest, "w")
                    ?: throw DecodeException("Couldn't write to the chosen location")
                out.use { HapticFile.write(it.buffered(), title, ext, cutoff, track.level, audio) }
                runOnUiThread {
                    exportButton.isEnabled = true
                    setStatus("Saved haptic file (%.1f MB). Open it in BassBuzz Player.".format(audio.size / 1048576f))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    exportButton.isEnabled = true
                    setStatus("Export failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    // ---------------------------------------------------------------- frequency tester

    private fun addFrequencyTester(col: LinearLayout) {
        col.gap(28)
        col.addView(label("Frequency tester", 18f, bold = true))
        col.gap(4)
        col.addView(label(tester.motorInfo(), 12f, MUTED))
        col.gap(12)

        testFreqLabel = label("", 34f, bold = true)
        col.addView(testFreqLabel)
        testPlanLabel = label("", 13f, MUTED)
        col.addView(testPlanLabel)
        col.gap(8)

        testBar = SeekBar(this).apply {
            max = TEST_MAX_HZ - TEST_MIN_HZ
            progress = testHz - TEST_MIN_HZ
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) setTestHz(p + TEST_MIN_HZ)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        col.addView(testBar, LinearLayout.LayoutParams(MATCH, WRAP))

        val range = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        range.addView(label("$TEST_MIN_HZ Hz", 12f, MUTED), LinearLayout.LayoutParams(0, WRAP, 1f))
        range.addView(label("$TEST_MAX_HZ Hz", 12f, MUTED))
        col.addView(range, LinearLayout.LayoutParams(MATCH, WRAP))
        col.gap(8)

        val fine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun step(text: String, delta: Int) = Button(this).apply {
            this.text = text
            setOnClickListener { setTestHz(testHz + delta) }
        }
        fine.addView(step("-10", -10), LinearLayout.LayoutParams(0, WRAP, 1f))
        fine.addView(step("-1", -1), LinearLayout.LayoutParams(0, WRAP, 1f))
        fine.addView(step("+1", 1), LinearLayout.LayoutParams(0, WRAP, 1f))
        fine.addView(step("+10", 10), LinearLayout.LayoutParams(0, WRAP, 1f))
        col.addView(fine, LinearLayout.LayoutParams(MATCH, WRAP))
        col.gap(8)

        col.addView(
            Button(this).apply {
                text = "Start vibration"
                testToggle = this
                setOnClickListener { toggleTester() }
            },
            LinearLayout.LayoutParams(MATCH, WRAP),
        )
        col.gap(10)
        addSlider(col, "Test strength", 0.05f, 1f, tester.strength, { "${(it * 100).toInt()}%" }, {
            tester.strength = it
            tester.update()
        })

        col.addView(
            Switch(this).apply {
                text = "Subwoofer roll-off (quieter at low frequencies)"
                setTextColor(TEXT)
                isChecked = tester.rolloffEnabled
                setOnCheckedChangeListener { _, on ->
                    tester.rolloffEnabled = on
                    tester.update()
                    refreshTestUi()
                }
            },
        )
        col.gap(8)
        addSlider(col, "Roll-off corner (-12 dB per octave below it)", 20f, 300f, tester.rolloffCornerHz, { "${it.toInt()} Hz" }, {
            tester.rolloffCornerHz = it
            tester.update()
            refreshTestUi()
        })

        col.addView(label("Above the motor's range", 14f))
        col.gap(4)
        val choices = listOf(
            "Silent" to AboveRange.SILENT,
            "Clamp to max" to AboveRange.CLAMP,
            "Force anyway" to AboveRange.FORCE,
        )
        col.addView(
            RadioGroup(this).apply {
                orientation = RadioGroup.HORIZONTAL
                choices.forEachIndexed { i, (name, mode) ->
                    addView(
                        RadioButton(this@MainActivity).apply {
                            id = 1000 + i
                            text = name
                            setTextColor(TEXT)
                            isChecked = tester.aboveRange == mode
                        },
                    )
                }
                setOnCheckedChangeListener { _, id ->
                    val picked = choices.getOrNull(id - 1000) ?: return@setOnCheckedChangeListener
                    tester.aboveRange = picked.second
                    tester.update()
                    refreshTestUi()
                }
            },
        )
        col.gap(16)

        tester.frequencyHz = testHz.toFloat()
        refreshTestUi()
    }

    private fun setTestHz(hz: Int) {
        testHz = hz.coerceIn(TEST_MIN_HZ, TEST_MAX_HZ)
        if (testBar.progress != testHz - TEST_MIN_HZ) testBar.progress = testHz - TEST_MIN_HZ
        tester.frequencyHz = testHz.toFloat()
        tester.update()
        refreshTestUi()
    }

    private fun refreshTestUi() {
        testFreqLabel.text = "$testHz Hz"
        testPlanLabel.text = tester.planFor(testHz.toFloat()).text
        testToggle.text = if (tester.active) "Stop vibration" else "Start vibration"
    }

    private fun toggleTester() {
        if (tester.active) {
            stopTester()
        } else {
            if (player.isPlaying) togglePlay()
            tester.frequencyHz = testHz.toFloat()
            tester.start()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            refreshTestUi()
        }
    }

    private fun stopTester() {
        if (!tester.active) return
        tester.stop()
        if (!player.isPlaying) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshTestUi()
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "audio file"
        }
        return uri.lastPathSegment ?: "audio file"
    }

    private fun loadFile(uri: Uri) {
        currentUri = uri
        player.track = null
        envelope.levels = null
        envelope.progress = 0f
        envelope.hint = "Analyzing…"
        fileLabel.text = displayName(uri)
        timeLabel.text = "0:00 / 0:00"
        playButton.isEnabled = false
        exportButton.isEnabled = false
        playButton.text = "Play"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        player.load(
            uri,
            onPrepared = {
                playButton.isEnabled = true
                updateProgress()
            },
            onError = { msg -> setStatus(msg) },
            onCompleted = {
                playButton.text = "Play"
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                envelope.progress = 0f
                timeLabel.text = "0:00 / ${fmt(player.durationMs)}"
            },
        )
        analyze(uri)
    }

    /** Decodes the song and builds the bass envelope on a background thread. */
    private fun analyze(uri: Uri) {
        analysisGen += 1
        val gen = analysisGen
        val cutoff = cutoffHz
        exportButton.isEnabled = false
        setStatus("Analyzing bass…")

        io.execute {
            try {
                var analyzer: BassAnalyzer? = null
                var lastPct = -1
                AudioDecoder.decode(
                    context = this@MainActivity,
                    uri = uri,
                    isCancelled = { gen != analysisGen },
                    onFormat = { sr -> analyzer = BassAnalyzer(sr, cutoff.toDouble()) },
                    onSamples = { buf, n -> analyzer?.process(buf, n) },
                    onProgress = { f ->
                        val pct = (f * 100).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            runOnUiThread { if (gen == analysisGen) setStatus("Analyzing bass… $pct%") }
                        }
                    },
                )
                if (gen != analysisGen) return@execute

                val raw = analyzer?.finish()
                if (raw == null || raw.size < 5) throw DecodeException("No audio could be decoded from this file")
                val result = HapticTrack.from(raw)

                runOnUiThread {
                    if (gen == analysisGen) {
                        player.track = result
                        envelope.levels = result.level
                        analyzedCutoff = cutoff
                        exportButton.isEnabled = true
                        setStatus("Ready. The motor follows the bass up to $cutoff Hz.")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (gen == analysisGen) {
                        envelope.hint = "Couldn't analyze this file"
                        setStatus("Couldn't analyze: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
        }
    }
}
