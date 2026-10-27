package com.example.bassbuzz.player

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.bassbuzz.EnvelopeView
import com.example.bassbuzz.HapticSettings
import com.example.ui.Fonts
import com.example.ui.Kit
import com.example.ui.Palettes
import com.example.ui.Theme

class PlayerActivity : ComponentActivity(), PlaybackService.Listener {

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var kit: Kit
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
        kit = Kit(this, Theme(Palettes.paper, Fonts.plex(this)))
        setContentView(buildUi())
        kit.matchSystemBars(window)
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

    private fun dp(v: Int) = kit.dp(v)

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun buildUi(): View {
        val col = kit.column()

        col.addView(kit.title("BassBuzz Player"))
        kit.gap(col, 4)
        col.addView(kit.caption("Plays .bbz files: the music, and the bass in your vibration motor."))
        kit.gap(col, 32)

        col.addView(kit.heading("Song"))
        kit.gap(col, 12)
        fileLabel = kit.text("No file open", 17f, kit.c.ink, kit.f.medium)
        col.addView(fileLabel)
        kit.gap(col, 12)
        col.addView(kit.button("Open haptic file (.bbz)") { picker.launch(arrayOf("*/*")) }, LinearLayout.LayoutParams(MATCH, WRAP))
        kit.gap(col, 16)

        envelope = EnvelopeView(this).apply {
            hint = "Open a .bbz file to begin"
            setColors(kit.c.accent, kit.c.line, kit.c.inkSoft, kit.f.regular)
            onSeek = { f ->
                service?.let { s -> s.seek((f * s.player.durationMs).toInt()) }
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
        kit.gap(col, 12)
        statusLabel = kit.caption("")
        col.addView(statusLabel)
        kit.gap(col, 40)

        col.addView(kit.heading("Haptic tuning"))
        kit.gap(col, 12)
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
            col, "Timing", -200f, 300f, settings.offsetMs.toFloat(), { "${it.toInt()} ms" },
            hint = "Raise it if the buzz comes after the music. Bluetooth earbuds usually need a negative value.",
        ) {
            settings = settings.copy(offsetMs = it.toInt())
            settingsChanged()
        }

        col.addView(
            kit.toggle("Background-proof mode", "Turn this on if the buzz stops when the screen is off.", compat) { on ->
                compat = on
                settingsChanged()
            }.view,
            LinearLayout.LayoutParams(MATCH, WRAP),
        )
        kit.gap(col, 12)
        col.addView(kit.button("Test motor") { service?.player?.testBuzz() }, LinearLayout.LayoutParams(MATCH, WRAP))
        kit.gap(col, 12)
        col.addView(
            kit.caption(
                "Playback and haptics continue in the background and with the screen off. " +
                    "Control them from the notification. Stop in the notification ends the session.",
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
