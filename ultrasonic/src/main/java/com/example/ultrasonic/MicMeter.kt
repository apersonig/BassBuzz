package com.example.ultrasonic

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.max

/**
 * Listens through the microphone and measures how loud the tone is compared with the background, so you can
 * tell whether the speaker is really producing a sound you can't hear. Phone microphones also roll off
 * towards 20 kHz and above, so a weak reading can mean the mic, not just the speaker.
 */
class MicMeter(private val sampleRate: Int, private val onLevel: (toneDbfs: Float, floorDbfs: Float) -> Unit) {

    /** The tone to look for; may be changed while running. */
    @Volatile
    var frequencyHz = 21000f

    @Volatile
    private var running = false
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start(unprocessed: Boolean): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
        val rec = try {
            AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 8192) * 2)
        } catch (e: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }

        running = true
        thread = Thread({
            val n = sampleRate / 10 // 100 ms blocks
            val shorts = ShortArray(n)
            val floats = FloatArray(n)
            try {
                rec.startRecording()
                while (running) {
                    var got = 0
                    while (got < n && running) {
                        val r = rec.read(shorts, got, n - got)
                        if (r <= 0) {
                            running = false
                            break
                        }
                        got += r
                    }
                    if (got < n) break
                    for (i in 0 until n) floats[i] = shorts[i] / 32768f

                    val f = frequencyHz
                    val tone = Dsp.goertzelAmplitude(floats, n, f, sampleRate)
                    val refs = Dsp.referenceFreqs(f)
                    var floor = 0f
                    for (r in refs) floor += Dsp.goertzelAmplitude(floats, n, r, sampleRate)
                    onLevel(Dsp.toDbfs(tone), Dsp.toDbfs(floor / refs.size))
                }
            } catch (e: Exception) {
                running = false
            } finally {
                try {
                    rec.stop()
                } catch (e: Exception) {
                }
                rec.release()
            }
        }, "ultrasonic-mic").also { it.start() }
        return true
    }

    fun stop() {
        running = false
    }
}
