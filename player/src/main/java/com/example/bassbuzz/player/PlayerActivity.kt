package com.example.bassbuzz.player

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Space
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.bassbuzz.EnvelopeView
import com.example.bassbuzz.HapticSettings

class PlayerActivity : ComponentActivity(), PlaybackService.Listener {

    private companion object {
        const val TEXT = 0xFFE8EAF0.toInt()
        const val MUTED = 0xFF9AA0AE.toInt()
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var envelope: EnvelopeView
    private lateinit var fileLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var timeLabel: TextView
    private lateinit var playButton: Button

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("player", Context.MODE_PRIVATE) }

    private var service: PlaybackService? = null
    private var pendingUri: Uri? = null
    private var settings = HapticSettings()
    private var compat = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openFile(uri)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as PlaybackService.LocalBinder).service
            service = s
            s.listener = this@PlayerActivity
            applySettings(s)
            pendingUri?.let {
                pendingUri = null
                s.open(it)
            }
            refreshUi()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            ui.postDelayed(this, 50)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadPrefs()
        setContentView(buildUi())
        handleIntent(intent)
        refreshUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
        ui.post(ticker)
    }

    override fun onStop() {
        ui.removeCallbacks(ticker)
        service?.listener = null
        try {
            unbindService(connection)
        } catch (e: IllegalArgumentException) {
        }
        service = null
        super.onStop()
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW) i.data?.let { openFile(it) }
    }

    // ---------------------------------------------------------------- actions

    private fun openFile(uri: Uri) {
        val s = service
        if (s != null) s.open(uri) else pendingUri = uri
    }

    private fun togglePlay() {
        val s = service ?: return
        if (s.player.isPlaying) {
            s.pause()
        } else {
            askNotificationPermission()
            // Goes through startForegroundService so the song keeps playing after the app is closed.
            startForegroundService(Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_PLAY))
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onPlayerChanged() = refreshUi()

    // ---------------------------------------------------------------- settings

    private fun loadPrefs() {
        settings = HapticSettings(
            intensity = prefs.getFloat("intensity", 1.0f),
            gate = prefs.getFloat("gate", 0.15f),
            punch = prefs.getFloat("punch", 0.8f),
            offsetMs = prefs.getInt("offset", 0),
        )
        compat = prefs.getBoolean("compat", false)
    }

    private fun savePrefs() {
        prefs.edit()
            .putFloat("intensity", settings.intensity)
            .putFloat("gate", settings.gate)
            .putFloat("punch", settings.punch)
            .putInt("offset", settings.offsetMs)
            .putBoolean("compat", compat)
            .apply()
    }

    private fun applySettings(s: PlaybackService) {
        s.player.settings = settings
        s.player.compat = compat
    }

    private fun settingsChanged() {
        service?.let { applySettings(it) }
        savePrefs()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(s: String, sp: Float, color: Int = TEXT, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun LinearLayout.gap(dpv: Int) = addView(Space(this@PlayerActivity), LinearLayout.LayoutParams(1, dp(dpv)))

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun buildUi(): View {
        val pad = dp(20)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(label("BassBuzz Player", 30f, bold = true))
        col.addView(label("Plays .bbz haptic files: music plus the bass in your motor", 14f, MUTED))
        col.gap(20)

        fileLabel = label("No file loaded", 16f)
        col.addView(fileLabel)
        col.gap(8)
        col.addView(
            Button(this).apply {
                text = "Open haptic file (.bbz)"
                setOnClickListener { picker.launch(arrayOf("*/*")) }
            },
            LinearLayout.LayoutParams(MATCH, WRAP),
        )
        col.gap(12)

        envelope = EnvelopeView(this).apply {
            hint = "Open a .bbz file to begin"
            onSeek = { f ->
                service?.let { s -> s.seek((f * s.player.durationMs).toInt()) }
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
        col.gap(8)
        statusLabel = label("", 13f, MUTED)
        col.addView(statusLabel)
        col.gap(24)

        col.addView(label("Haptic tuning", 18f, bold = true))
        col.gap(8)
        addSlider(col, "Intensity", 0.2f, 2.0f, settings.intensity, { "${(it * 100).toInt()}%" }) {
            settings = settings.copy(intensity = it)
            settingsChanged()
        }
        addSlider(col, "Noise gate", 0f, 0.6f, settings.gate, { "${(it * 100).toInt()}%" }) {
            settings = settings.copy(gate = it)
            settingsChanged()
        }
        addSlider(col, "Punch on hits", 0f, 2f, settings.punch, { "${(it * 100).toInt()}%" }) {
            settings = settings.copy(punch = it)
            settingsChanged()
        }
        addSlider(
            col, "Timing (raise it if the buzz comes after the music; Bluetooth earbuds need negative)",
            -200f, 300f, settings.offsetMs.toFloat(), { "${it.toInt()} ms" },
        ) {
            settings = settings.copy(offsetMs = it.toInt())
            settingsChanged()
        }

        col.gap(8)
        col.addView(
            Switch(this).apply {
                text = "Background-proof mode (use if the buzz stops when the screen is off)"
                setTextColor(TEXT)
                isChecked = compat
                setOnCheckedChangeListener { _, on ->
                    compat = on
                    settingsChanged()
                }
            },
        )
        col.gap(12)
        col.addView(
            Button(this).apply {
                text = "Test motor"
                setOnClickListener { service?.player?.testBuzz() }
            },
            LinearLayout.LayoutParams(MATCH, WRAP),
        )
        col.gap(12)
        col.addView(
            label(
                "Playback and haptics continue in the background and with the screen off. " +
                    "Control them from the notification. Stop in the notification ends the session.",
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
        parent.gap(10)
    }

    private fun refreshUi() {
        val s = service
        if (s == null) {
            statusLabel.text = if (pendingUri != null) "Opening…" else "Connecting…"
            return
        }
        fileLabel.text = s.title ?: "No file loaded"

        val levels = s.player.track?.level
        if (envelope.levels !== levels) envelope.levels = levels
        envelope.hint = when {
            s.loading -> "Loading…"
            s.error != null && !s.ready -> "Couldn't open this file"
            else -> "Open a .bbz file to begin"
        }

        playButton.isEnabled = s.ready
        playButton.text = if (s.player.isPlaying) "Pause" else "Play"
        statusLabel.text = when {
            s.error != null -> s.error
            s.loading -> "Loading…"
            s.ready -> "Ready. Playback continues in the background."
            else -> "Open a .bbz file made with BassBuzz."
        }
        updateProgress()
    }

    private fun updateProgress() {
        val s = service ?: return
        val dur = s.player.durationMs
        val pos = s.player.positionMs
        if (dur > 0 && !envelope.dragging) envelope.progress = pos.toFloat() / dur
        timeLabel.text = "${fmt(pos)} / ${fmt(dur)}"
    }
}
