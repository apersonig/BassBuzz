package com.example.ultrasonic

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * Streams a pure sine to the speaker. The phase is carried from sample to sample, so changing the
 * frequency or sweeping never makes a click, and the volume is eased in and out for the same reason.
 *
 * The highest frequency possible is half the sample rate. This uses the phone's own output rate
 * (normally 48 kHz, so 24 kHz); asking for more would only be resampled away by Android.
 */
class ToneEngine {

    val sampleRate: Int = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
        .let { if (it in 8000..384000) it else 48000 }

    @Volatile
    var frequencyHz = 21000f

    /** 0..1 of full scale, before the route cap. */
    @Volatile
    var amplitude = 0.1f

    @Volatile
    var sweeping = false

    @Volatile
    var sweepFromHz = 18000f

    @Volatile
    var sweepToHz = 24000f

    @Volatile
    var sweepSeconds = 10f

    /** On/off period in ms (half on, half off); 0 means a steady tone. */
    @Volatile
    var pulsePeriodMs = 0

    /** Lowered automatically when the sound isn't coming out of the phone's loudspeaker. */
    @Volatile
    var routeCap = 1f
        private set

    /** The frequency being produced right now (moves during a sweep). */
    @Volatile
    var currentHz = 0f
        private set

    @Volatile
    var isRunning = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** Called from the audio thread when playback has fully stopped. */
    @Volatile
    var onStopped: (() -> Unit)? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    fun start(): Boolean {
        if (isRunning) return true
        lastError = null
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBuf <= 0) {
            lastError = "This phone can't open a float audio output at $sampleRate Hz"
            return false
        }
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(max(minBuf, 4096 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            lastError = "Couldn't open audio output: ${e.message ?: e.javaClass.simpleName}"
            return false
        }

        track = t
        stopRequested = false
        isRunning = true
        thread = Thread({ run(t) }, "ultrasonic-tone").also { it.start() }
        return true
    }

    /** Fades out and stops. Returns immediately; [onStopped] fires when the sound has ended. */
    fun stop() {
        stopRequested = true
    }

    /** Stops without waiting for a fade. Used when the app is being torn down. */
    fun release() {
        stopRequested = true
        try {
            thread?.join(500)
        } catch (e: InterruptedException) {
        }
    }

    /**
     * Looks at where Android is sending the sound and lowers the volume cap if it isn't the loudspeaker:
     * a tone near 20 kHz right inside an ear canal can be painful. Returns a line for the screen.
     */
    fun updateRoute(): String {
        val d: AudioDeviceInfo? = try {
            track?.routedDevice
        } catch (e: Exception) {
            null
        }
        if (d == null) return "Output: starting..."
        val onSpeaker = d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE
        routeCap = if (onSpeaker) 1f else 0.1f
        return if (onSpeaker) {
            "Output: phone speaker"
        } else {
            "Output: ${typeName(d.type)}. Volume is capped at 10% here; keep it away from your ears."
        }
    }

    private fun typeName(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "phone earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth audio"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB audio"
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> "Bluetooth LE audio"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        else -> "external output (type $type)"
    }

    private fun run(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            t.play()
            val sr = sampleRate.toDouble()
            val twoPi = 2.0 * PI
            val slew = 1f - exp(-1f / (sampleRate * 0.005f)) // ~5 ms smoothing for volume changes
            val buf = FloatArray(1024)
            var phase = 0.0
            var gain = 0f
            var n = 0L

            while (true) {
                val stopping = stopRequested
                val sweep = sweeping
                val from = sweepFromHz
                val to = sweepToHz
                val secs = max(sweepSeconds, 0.1f)
                val fixed = frequencyHz
                val period = pulsePeriodMs
                val target = amplitude * routeCap

                var f = fixed
                for (i in buf.indices) {
                    f = if (sweep) Dsp.sweepFrequency(n / sr, from, to, secs) else fixed
                    phase += twoPi * f / sr
                    if (phase >= twoPi) phase -= twoPi

                    val gate = if (period > 0 && (n * 1000.0 / sr) % period >= period / 2.0) 0f else 1f
                    val desired = if (stopping) 0f else target * gate
                    gain += (desired - gain) * slew

                    buf[i] = (sin(phase) * gain).toFloat()
                    n++
                }
                currentHz = f

                if (t.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING) < 0) {
                    lastError = "Audio output stopped unexpectedly"
                    break
                }
                if (stopping && gain < 0.0005f) break
            }
        } catch (e: Exception) {
            lastError = "Audio error: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            try {
                t.stop()
            } catch (e: Exception) {
            }
            t.release()
            track = null
            isRunning = false
            currentHz = 0f
            onStopped?.invoke()
        }
    }
}
