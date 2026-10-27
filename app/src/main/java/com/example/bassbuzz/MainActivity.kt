package com.example.bassbuzz

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.ui.Fonts
import com.example.ui.Kit
import com.example.ui.Palettes
import com.example.ui.Theme
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var kit: Kit
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
        kit = Kit(this, Theme(Palettes.paper, Fonts.plex(this)))
        setContentView(buildUi())
        kit.matchSystemBars(window)
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

    private fun dp(v: Int) = kit.dp(v)

    private fun buildUi(): View {
        val col = kit.column()

        col.addView(kit.title("BassBuzz"))
        kit.gap(col, 4)
        col.addView(kit.caption("Plays music and sends the bass to the vibration motor."))
        kit.gap(col, 32)

        col.addView(kit.heading("Song"))
        kit.gap(col, 12)
        fileLabel = kit.text("No song open", 17f, kit.c.ink, kit.f.medium)
        col.addView(fileLabel)
        kit.gap(col, 12)
        col.addView(kit.button("Open audio file") { picker.launch(arrayOf("audio/*")) }, LinearLayout.LayoutParams(MATCH, WRAP))
        kit.gap(col, 16)

        envelope = EnvelopeView(this).apply {
            setColors(kit.c.accent, kit.c.line, kit.c.inkSoft, kit.f.regular)
            onSeek = { f ->
                player.seekTo((f * player.durationMs).toInt())
                updateProgress()
            }
        }
        col.addView(envelope, LinearLayout.LayoutParams(MATCH, dp(120)))
        kit.gap(col, 8)
        timeLabel = kit.readout("0:00 / 0:00", 14f, kit.c.inkSoft).apply { gravity = Gravity.END }
        col.addView(timeLabel, LinearLayout.LayoutParams(MATCH, WRAP))
        kit.gap(col, 16)

        playButton = kit.button("Play", Kit.Weight.PRIMARY) { togglePlay() }.apply { isEnabled = false }
        col.addView(playButton, LinearLayout.LayoutParams(MATCH, dp(56)))
        kit.gap(col, 8)
        exportButton = kit.button("Export haptic file (.bbz)") { startExport() }.apply { isEnabled = false }
        col.addView(exportButton, LinearLayout.LayoutParams(MATCH, WRAP))
        kit.gap(col, 12)
        statusLabel = kit.caption("")
        col.addView(statusLabel)
        kit.gap(col, 40)

        col.addView(kit.heading("Haptic tuning"))
        kit.gap(col, 12)
        addSlider(col, "Intensity", 0.2f, 2.0f, settings.intensity, { "${(it * 100).toInt()}%" }) {
            settings = settings.copy(intensity = it)
            player.settings = settings
        }
        addSlider(col, "Noise gate", 0f, 0.6f, settings.gate, { "${(it * 100).toInt()}%" }) {
            settings = settings.copy(gate = it)
            player.settings = settings
        }
        addSlider(col, "Punch on hits", 0f, 2f, settings.punch, { "${(it * 100).toInt()}%" }) {
            settings = settings.copy(punch = it)
            player.settings = settings
        }
        addSlider(
            col, "Timing", -200f, 300f, settings.offsetMs.toFloat(), { "${it.toInt()} ms" },
            hint = "Raise it if the buzz comes after the music.",
        ) {
            settings = settings.copy(offsetMs = it.toInt())
            player.settings = settings
        }
        addSlider(
            col, "Bass range", 60f, 250f, cutoffHz.toFloat(), { "up to ${it.toInt()} Hz" },
            hint = "The song is analysed again when you let go.",
            onCommit = { currentUri?.let { u -> analyze(u) } },
        ) { cutoffHz = it.toInt() }

        col.addView(
            kit.toggle("Compatibility mode", "Turn this on if you feel nothing.") { on ->
                player.compat = on
                tester.compat = on
            }.view,
            LinearLayout.LayoutParams(MATCH, WRAP),
        )
        kit.gap(col, 12)
        col.addView(kit.button("Test motor") { player.testBuzz() }, LinearLayout.LayoutParams(MATCH, WRAP))
        kit.gap(col, 12)
        col.addView(kit.caption("Keep the app in front while playing. The screen stays on, and the buzz pauses if you leave."))

        addFrequencyTester(col)

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
        onCommit: (() -> Unit)? = null,
        onChange: (Float) -> Unit,
    ) {
        parent.addView(kit.slider(title, lo, hi, initial, fmt, hint, onChange, onCommit).view, LinearLayout.LayoutParams(MATCH, WRAP))
        kit.gap(parent, 16)
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
        kit.gap(col, 40)
        col.addView(kit.heading("Frequency tester"))
        kit.gap(col, 4)
        col.addView(kit.caption(tester.motorInfo()))
        kit.gap(col, 16)

        testFreqLabel = kit.readout("", 44f)
        col.addView(testFreqLabel)
        kit.gap(col, 4)
        testPlanLabel = kit.caption("")
        col.addView(testPlanLabel)
        kit.gap(col, 8)

        testBar = kit.seekBar(TEST_MAX_HZ - TEST_MIN_HZ, testHz - TEST_MIN_HZ, { p, fromUser ->
            if (fromUser) setTestHz(p + TEST_MIN_HZ)
        })
        col.addView(testBar, LinearLayout.LayoutParams(MATCH, dp(44)))

        val range = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        range.addView(kit.readout("$TEST_MIN_HZ Hz", 12f, kit.c.inkSoft), LinearLayout.LayoutParams(0, WRAP, 1f))
        range.addView(kit.readout("$TEST_MAX_HZ Hz", 12f, kit.c.inkSoft))
        col.addView(range, LinearLayout.LayoutParams(MATCH, WRAP))
        kit.gap(col, 12)

        col.addView(
            kit.rowOf(
                kit.button("\u221210") { setTestHz(testHz - 10) },
                kit.button("\u22121") { setTestHz(testHz - 1) },
                kit.button("+1") { setTestHz(testHz + 1) },
                kit.button("+10") { setTestHz(testHz + 10) },
            ),
            LinearLayout.LayoutParams(MATCH, WRAP),
        )
        kit.gap(col, 12)

        testToggle = kit.button("Start vibration", Kit.Weight.PRIMARY) { toggleTester() }
        col.addView(testToggle, LinearLayout.LayoutParams(MATCH, dp(56)))
        kit.gap(col, 20)

        addSlider(col, "Test strength", 0.05f, 1f, tester.strength, { "${(it * 100).toInt()}%" }) {
            tester.strength = it
            tester.update()
        }

        col.addView(
            kit.toggle("Subwoofer roll-off", "Quieter at low frequencies, like a real subwoofer.", tester.rolloffEnabled) { on ->
                tester.rolloffEnabled = on
                tester.update()
                refreshTestUi()
            }.view,
            LinearLayout.LayoutParams(MATCH, WRAP),
        )
        kit.gap(col, 8)
        addSlider(
            col, "Roll-off corner", 20f, 300f, tester.rolloffCornerHz, { "${it.toInt()} Hz" },
            hint = "Below this the level falls by 12 dB per octave.",
        ) {
            tester.rolloffCornerHz = it
            tester.update()
            refreshTestUi()
        }

        col.addView(kit.body("Above the motor's range"))
        kit.gap(col, 8)
        val choices = listOf(
            "Silent" to AboveRange.SILENT,
            "Clamp to max" to AboveRange.CLAMP,
            "Force anyway" to AboveRange.FORCE,
        )
        col.addView(
            kit.segmented(choices.map { it.first }, choices.indexOfFirst { it.second == tester.aboveRange }.coerceAtLeast(0)) { i ->
                tester.aboveRange = choices[i].second
                tester.update()
                refreshTestUi()
            }.view,
            LinearLayout.LayoutParams(MATCH, WRAP),
        )

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
