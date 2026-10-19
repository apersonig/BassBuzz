package com.example.bassbuzz

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.vibrator.VibratorFrequencyProfile
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

const val TEST_MIN_HZ = 1
const val TEST_MAX_HZ = 500

enum class TestMode { ENVELOPE, PULSE, NONE }

/** What to do when the chosen frequency is higher than the motor says it can play. */
enum class AboveRange { SILENT, CLAMP, FORCE }

/**
 * What will happen at a given frequency. [sendHz] is the frequency actually requested from the
 * motor, [gain] the roll-off multiplier (1 = none), [text] a line to show the user.
 */
data class TestPlan(val mode: TestMode, val text: String, val sendHz: Float = 0f, val gain: Float = 1f)

/**
 * Android 16 (API 36) lets an app ask the motor for a specific frequency through
 * [VibrationEffect.WaveformEnvelopeBuilder]. Everything that touches those classes lives here
 * so that older Android versions never load them.
 */
@RequiresApi(36)
private object Envelope {
    fun profile(vibrator: Vibrator): VibratorFrequencyProfile? = vibrator.frequencyProfile

    fun effect(freqHz: Float, strength: Float): VibrationEffect =
        VibrationEffect.WaveformEnvelopeBuilder()
            .addControlPoint(strength, freqHz, 50) // short ramp up from silence
            .addControlPoint(strength, freqHz, 500)
            .addControlPoint(strength, freqHz, 500)
            .build()
}

/**
 * Plays a steady vibration at a chosen frequency (1-500 Hz) so you can feel what the motor does.
 *
 *  - Inside the motor's real frequency range: asks the motor for exactly that frequency (Android 16+).
 *  - Below that range: the motor can't make such a slow tone, so its own buzz is switched on and
 *    off at that rate instead, which feels like a pulse train.
 *  - Above the range: nothing can be played, and Android itself produces no vibration there.
 */
class FrequencyTester(context: Context) {

    private val vibrator: Vibrator =
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator

    private val thread = HandlerThread("bassbuzz-freq").also { it.start() }
    private val handler = Handler(thread.looper)

    @Volatile
    var frequencyHz = 170f

    /** 0..1 of the motor's maximum output. */
    @Volatile
    var strength = 1f

    @Volatile
    var compat = false

    /** Subwoofer-style roll-off: output falls 12 dB per octave below [rolloffCornerHz]. */
    @Volatile
    var rolloffEnabled = true

    @Volatile
    var rolloffCornerHz = 60f

    @Volatile
    var aboveRange = AboveRange.FORCE

    @Volatile
    var active = false
        private set

    /** Called on the haptic thread when Android rejects an effect. */
    @Volatile
    var onError: ((String) -> Unit)? = null

    private val profile: VibratorFrequencyProfile? = try {
        if (Build.VERSION.SDK_INT >= 36) Envelope.profile(vibrator) else null
    } catch (e: Throwable) {
        null
    }

    private val minHz: Float = profile?.minFrequencyHz ?: 0f
    private val maxHz: Float = profile?.maxFrequencyHz ?: 0f

    private val alarmAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val mediaAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** One line describing the motor, shown once under the slider. */
    fun motorInfo(): String {
        val p = profile
            ?: return "This phone/Android version doesn't offer frequency control (needs Android 16 and a " +
                "supporting motor), so only slow pulsing is available."
        var peakHz = 0
        var peakG = 0f
        val map = p.frequenciesOutputAcceleration
        for (i in 0 until map.size()) {
            val g = map.valueAt(i)
            if (g > peakG) {
                peakG = g
                peakHz = map.keyAt(i)
            }
        }
        return "Motor range: %d-%d Hz, strongest near %d Hz (%.1f G).".format(
            minHz.toInt(), maxHz.toInt(), peakHz, p.maxOutputAccelerationGs,
        )
    }

    /** Roll-off multiplier at [freq]: 1 well above the corner, 0.707 at it, then -12 dB per octave. */
    fun gainFor(freq: Float): Float {
        if (!rolloffEnabled) return 1f
        val r = rolloffCornerHz / freq
        return (1.0 / sqrt(1.0 + r * r * r * r)).toFloat()
    }

    fun planFor(freq: Float): TestPlan {
        val gain = gainFor(freq)
        val roll = if (rolloffEnabled) " Roll-off: %.1f dB.".format(20f * log10(gain)) else ""
        val p = profile
        if (p == null) {
            return if (freq <= 100f) {
                TestPlan(
                    TestMode.PULSE,
                    "Pulse mode: the motor's buzz is switched on/off ${fmtHz(freq)} times per second.$roll",
                    freq, gain,
                )
            } else {
                TestPlan(TestMode.NONE, "Not playable: this phone can't produce frequencies this high.")
            }
        }
        return when {
            freq < minHz -> TestPlan(
                TestMode.PULSE,
                "Below the motor's range: pulsing its buzz on/off ${fmtHz(freq)} times per second.$roll",
                freq, gain,
            )
            freq > maxHz -> when (aboveRange) {
                AboveRange.SILENT -> TestPlan(
                    TestMode.NONE,
                    "Above the motor's range (${maxHz.toInt()} Hz): nothing is played.",
                )
                AboveRange.CLAMP -> TestPlan(
                    TestMode.ENVELOPE,
                    "Above the motor's range: playing its limit of ${maxHz.toInt()} Hz instead.$roll",
                    maxHz, gain,
                )
                AboveRange.FORCE -> TestPlan(
                    TestMode.ENVELOPE,
                    "Forcing ${fmtHz(freq)} Hz, past the motor's ${maxHz.toInt()} Hz limit. " +
                        "Android may play nothing here; if so, switch to Clamp.$roll",
                    freq, gain,
                )
            }
            else -> TestPlan(
                TestMode.ENVELOPE,
                "Playing true %s Hz, about %.2f G at full strength.%s".format(
                    fmtHz(freq), p.getOutputAccelerationGs(freq), roll,
                ),
                freq, gain,
            )
        }
    }

    private fun fmtHz(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else "%.1f".format(f)

    // ---------------------------------------------------------------- control

    private val dirty = AtomicBoolean(false)

    private val refresh = Runnable {
        dirty.set(false)
        if (!active) return@Runnable
        handler.removeCallbacks(renew)
        send()
    }

    private val renew: Runnable = Runnable {
        if (!active) return@Runnable
        send()
    }

    fun start() {
        if (active) return
        active = true
        update()
    }

    fun stop() {
        active = false
        dirty.set(false)
        handler.removeCallbacksAndMessages(null)
        handler.post { vibrator.cancel() }
    }

    /** Call whenever frequency or strength changed. Sends at most one update every 40 ms. */
    fun update() {
        if (!active) return
        if (dirty.compareAndSet(false, true)) handler.postDelayed(refresh, 40)
    }

    fun release() {
        stop()
        handler.post { thread.quitSafely() }
    }

    private fun send() {
        val f = frequencyHz.coerceIn(TEST_MIN_HZ.toFloat(), TEST_MAX_HZ.toFloat())
        val plan = planFor(f)
        val level = (strength.coerceIn(0.05f, 1f) * plan.gain).coerceIn(0f, 1f)
        try {
            when (plan.mode) {
                TestMode.ENVELOPE -> {
                    try {
                        vibrate(Envelope.effect(plan.sendHz, level))
                    } catch (e: Exception) {
                        // Android refused a forced out-of-range frequency: fall back to the motor's limit.
                        if (plan.sendHz > maxHz && maxHz > 0f) {
                            vibrate(Envelope.effect(maxHz, level))
                            onError?.invoke(
                                "Android refused ${fmtHz(plan.sendHz)} Hz (${e.message ?: "out of range"}); " +
                                    "playing ${maxHz.toInt()} Hz instead.",
                            )
                        } else {
                            throw e
                        }
                    }
                    // Each effect lasts ~1 s; queue the next one just before it ends.
                    handler.postDelayed(renew, 1000)
                }
                TestMode.PULSE -> vibrate(pulseEffect(plan.sendHz, level))
                TestMode.NONE -> vibrator.cancel()
            }
        } catch (e: Exception) {
            active = false
            onError?.invoke("Motor rejected the effect: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Repeating on/off waveform at [freq] Hz; it keeps going by itself until cancelled. */
    private fun pulseEffect(freq: Float, level: Float): VibrationEffect {
        val period = 1000f / freq
        val on = max(period / 2f, 5f).toLong()
        val off = max(period - on, 5f).toLong()
        val amp = if (vibrator.hasAmplitudeControl()) (level * 255).toInt().coerceIn(1, 255) else 255
        return VibrationEffect.createWaveform(longArrayOf(on, off), intArrayOf(amp, 0), 0)
    }

    @Suppress("DEPRECATION")
    private fun vibrate(effect: VibrationEffect) {
        vibrator.vibrate(effect, if (compat) alarmAttrs else mediaAttrs)
    }
}
