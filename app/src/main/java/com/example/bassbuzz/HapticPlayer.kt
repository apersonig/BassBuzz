package com.example.bassbuzz

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.pow

data class HapticSettings(
    /** Overall strength multiplier. */
    val intensity: Float = 1.0f,
    /** Bass level (0..1 of loud) below which the motor stays off. */
    val gate: Float = 0.15f,
    /** How much extra kick the motor gets on sharp bass hits. */
    val punch: Float = 0.8f,
    /** Positive = haptics fire earlier relative to the audio. */
    val offsetMs: Int = 0,
)

private const val MIN_AMPLITUDE = 35 // LRAs are barely perceptible below this
private const val CHUNK_FRAMES = 12 // ~240 ms of haptics sent per tick
private const val TICK_MS = 200L // re-sync to the audio clock this often

internal fun amplitudeFor(track: HapticTrack, frame: Int, s: HapticSettings): Int {
    if (frame < 0 || frame >= track.level.size) return 0
    val lvl = track.level[frame]
    if (lvl <= s.gate) return 0
    val body = ((lvl - s.gate) / (1f - s.gate)).coerceIn(0f, 1.5f)
    var v = body.pow(0.7f)
    v += track.flux[frame] * s.punch
    v = (v * s.intensity).coerceIn(0f, 1f)
    if (v < 0.03f) return 0
    return (MIN_AMPLITUDE + v * (255 - MIN_AMPLITUDE)).toInt().coerceIn(1, 255)
}

/**
 * Plays audio with MediaPlayer and, on a background thread, feeds the vibration motor short
 * waveforms taken from the analysed bass envelope. Every tick re-reads the audio position,
 * so the haptics can never drift away from the music.
 */
class HapticPlayer(private val context: Context) {

    private val vibrator: Vibrator =
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator

    private val thread = HandlerThread("bassbuzz-haptics").also { it.start() }
    private val handler = Handler(thread.looper)

    @Volatile
    var track: HapticTrack? = null

    @Volatile
    var settings = HapticSettings()

    /** Use the alarm vibration channel, for phones where media vibration is switched off. */
    @Volatile
    var compat = false

    @Volatile
    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    private var prepared = false

    @Volatile
    private var running = false

    val hasVibrator: Boolean get() = vibrator.hasVibrator()
    val hasAmplitudeControl: Boolean get() = vibrator.hasAmplitudeControl()

    val isPlaying: Boolean
        get() = try {
            mediaPlayer?.isPlaying == true
        } catch (e: IllegalStateException) {
            false
        }

    val positionMs: Int
        get() = try {
            if (prepared) mediaPlayer?.currentPosition ?: 0 else 0
        } catch (e: IllegalStateException) {
            0
        }

    val durationMs: Int
        get() = try {
            if (prepared) mediaPlayer?.duration ?: 0 else 0
        } catch (e: IllegalStateException) {
            0
        }

    private val mediaAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val alarmAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val mp = mediaPlayer ?: return
            val t = track
            if (t != null) {
                val pos = try {
                    mp.currentPosition
                } catch (e: IllegalStateException) {
                    return
                }
                emit(t, pos)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun load(uri: Uri, onPrepared: () -> Unit, onError: (String) -> Unit, onCompleted: () -> Unit) {
        stopInternal()
        val mp = MediaPlayer()
        mp.setAudioAttributes(mediaAttrs)
        try {
            mp.setDataSource(context, uri)
        } catch (e: Exception) {
            mp.release()
            onError(e.message ?: "Couldn't open this file")
            return
        }
        mp.setOnPreparedListener {
            prepared = true
            onPrepared()
        }
        mp.setOnCompletionListener {
            running = false
            handler.removeCallbacks(tick)
            handler.post { vibrator.cancel() }
            onCompleted()
        }
        mp.setOnErrorListener { _, what, extra ->
            onError("Playback error ($what/$extra)")
            true
        }
        mediaPlayer = mp
        mp.prepareAsync()
    }

    fun play() {
        val mp = mediaPlayer ?: return
        if (!prepared) return
        mp.start()
        running = true
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun pause() {
        running = false
        handler.removeCallbacks(tick)
        // Cancel on the haptic thread so it can't race with a tick that is already running.
        handler.post { vibrator.cancel() }
        try {
            mediaPlayer?.pause()
        } catch (e: IllegalStateException) {
        }
    }

    fun seekTo(ms: Int) {
        if (!prepared) return
        try {
            mediaPlayer?.seekTo(ms)
        } catch (e: IllegalStateException) {
        }
    }

    /** A rising buzz followed by three hard hits, to check the motor and the settings. */
    fun testBuzz() {
        val timings = ArrayList<Long>()
        val amps = ArrayList<Int>()
        repeat(10) {
            timings += 20L
            amps += 25 + it * 25
        }
        timings += 120L
        amps += 0
        repeat(3) {
            timings += 60L
            amps += 255
            timings += 100L
            amps += 0
        }
        vibrate(VibrationEffect.createWaveform(timings.toLongArray(), amps.toIntArray(), -1))
    }

    fun release() {
        running = false
        handler.removeCallbacksAndMessages(null)
        handler.post {
            vibrator.cancel()
            thread.quitSafely()
        }
        try {
            mediaPlayer?.release()
        } catch (e: Exception) {
        }
        mediaPlayer = null
        prepared = false
    }

    private fun stopInternal() {
        running = false
        handler.removeCallbacks(tick)
        handler.post { vibrator.cancel() }
        try {
            mediaPlayer?.release()
        } catch (e: Exception) {
        }
        mediaPlayer = null
        prepared = false
    }

    private fun emit(t: HapticTrack, posMs: Int) {
        val s = settings
        val target = posMs + s.offsetMs
        val firstFrame = Math.floorDiv(target, HOP_MS)
        val firstDur = (HOP_MS - Math.floorMod(target, HOP_MS)).toLong().coerceAtLeast(5L)

        val timings = LongArray(CHUNK_FRAMES) { if (it == 0) firstDur else HOP_MS.toLong() }
        val amps = IntArray(CHUNK_FRAMES) { amplitudeFor(t, firstFrame + it, s) }

        if (!hasAmplitudeControl) {
            for (i in amps.indices) amps[i] = if (amps[i] >= 90) 255 else 0
        }
        if (amps.all { it == 0 }) {
            vibrator.cancel()
            return
        }
        vibrate(VibrationEffect.createWaveform(timings, amps, -1))
    }

    @Suppress("DEPRECATION")
    private fun vibrate(effect: VibrationEffect) {
        vibrator.vibrate(effect, if (compat) alarmAttrs else mediaAttrs)
    }
}
