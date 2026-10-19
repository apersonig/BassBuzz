package com.example.bassbuzz

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Length of one haptic frame. The motor amplitude is updated once per frame. */
const val HOP_MS = 20

/** One low-pass biquad section (RBJ cookbook), transposed direct form II. */
internal class Biquad(sampleRate: Int, cutoffHz: Double, q: Double) {
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var z1 = 0.0
    private var z2 = 0.0

    init {
        val w0 = 2.0 * PI * cutoffHz / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val c = cos(w0)
        val a0 = 1.0 + alpha
        b0 = (1.0 - c) / 2.0 / a0
        b1 = (1.0 - c) / a0
        b2 = b0
        a1 = -2.0 * c / a0
        a2 = (1.0 - alpha) / a0
    }

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}

/**
 * Streams mono PCM through a 4th-order Butterworth low-pass (two cascaded biquads) and
 * measures the RMS of what is left every [HOP_MS] milliseconds, using a window of two hops
 * so that low bass notes don't make the envelope ripple.
 */
class BassAnalyzer(sampleRate: Int, cutoffHz: Double) {
    private val stages: Array<Biquad>
    private val hopSamples = sampleRate * HOP_MS / 1000
    private var energy = 0.0
    private var prevEnergy = 0.0
    private var count = 0
    private var frames = FloatArray(16384)
    private var n = 0

    init {
        val fc = cutoffHz.coerceIn(20.0, sampleRate * 0.45)
        // Q values of a 4th-order Butterworth response.
        stages = arrayOf(Biquad(sampleRate, fc, 0.5411961), Biquad(sampleRate, fc, 1.3065630))
    }

    fun process(samples: FloatArray, length: Int) {
        for (i in 0 until length) {
            var y = samples[i].toDouble()
            for (s in stages) y = s.process(y)
            energy += y * y
            if (++count == hopSamples) {
                push(sqrt((energy + prevEnergy) / (2.0 * hopSamples)))
                prevEnergy = energy
                energy = 0.0
                count = 0
            }
        }
    }

    private fun push(v: Double) {
        if (n == frames.size) frames = frames.copyOf(n * 2)
        frames[n++] = v.toFloat()
    }

    /** Raw RMS per frame. */
    fun finish(): FloatArray = frames.copyOf(n)
}

/**
 * The analysed song: [level] is the bass loudness per frame, normalised so that the loud parts
 * sit around 1.0; [flux] is how sharply the level just rose (kick / bass-hit onsets).
 */
class HapticTrack(val level: FloatArray, val flux: FloatArray) {
    companion object {
        fun from(rms: FloatArray): HapticTrack {
            val sorted = rms.copyOf()
            sorted.sort()
            val idx = ((sorted.size - 1) * 0.98).toInt()
            // Floor keeps tracks with no real bass from being blown up into noise.
            val ref = maxOf(sorted[idx], 0.003f)
            val level = FloatArray(rms.size) { minOf(rms[it] / ref, 1.5f) }
            val flux = FloatArray(rms.size) { if (it == 0) 0f else maxOf(0f, level[it] - level[it - 1]) }
            return HapticTrack(level, flux)
        }
    }
}
