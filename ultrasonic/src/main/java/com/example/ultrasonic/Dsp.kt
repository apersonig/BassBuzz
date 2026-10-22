package com.example.ultrasonic

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Pure signal maths, kept free of Android so it can be unit tested. */
object Dsp {

    const val MIN_HZ = 1000

    /** Highest frequency offered: a little under Nyquist, where a sine is still a sine. */
    fun maxHz(sampleRate: Int): Int = sampleRate / 2 - 50

    /**
     * Amplitude (0..1, same scale as the samples) of the sinusoid at exactly [freq] in the first [n] samples
     * of [x], found with the Goertzel algorithm. A pure tone of amplitude A at [freq] gives about A.
     */
    fun goertzelAmplitude(x: FloatArray, n: Int, freq: Float, sampleRate: Int): Float {
        val w = 2.0 * PI * freq / sampleRate
        val coeff = 2.0 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until n) {
            val s0 = x[i] + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        return (2.0 * sqrt(max(power, 0.0)) / n).toFloat()
    }

    fun toDbfs(amplitude: Float): Float = 20f * log10(max(amplitude, 1e-9f))

    /** Frequencies near [freq] that should be free of the tone, used to estimate the background level. */
    fun referenceFreqs(freq: Float): FloatArray =
        if (freq > 6000f) floatArrayOf(freq - 1500f, freq - 2500f, freq - 3500f)
        else floatArrayOf(freq + 1500f, freq + 2500f, freq + 3500f)

    /** Linear sweep from [from] to [to] Hz taking [seconds], starting over when it reaches the end. */
    fun sweepFrequency(t: Double, from: Float, to: Float, seconds: Float): Float {
        val p = ((t % seconds) / seconds).toFloat()
        return from + (to - from) * p
    }

    /** Slider position (0..1) to amplitude: squared so the low end has fine control. */
    fun volumeCurve(slider: Float): Float {
        val s = slider.coerceIn(0f, 1f)
        return s * s
    }

    fun zone(freq: Float): String = when {
        freq < 16000f -> "Audible"
        freq < 20000f -> "Near-ultrasonic: younger people may still hear this"
        else -> "Ultrasonic: above what most adults can hear"
    }
}
