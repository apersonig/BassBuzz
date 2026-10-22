package com.example.ultrasonic

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DspTest {

    private fun sine(freq: Float, sampleRate: Int, n: Int, amp: Float) =
        FloatArray(n) { (amp * sin(2.0 * PI * freq * it / sampleRate)).toFloat() }

    @Test
    fun goertzelRecoversAmplitudeOfAnUltrasonicTone() {
        val x = sine(21000f, 48000, 4800, 0.5f)
        assertEquals(0.5f, Dsp.goertzelAmplitude(x, x.size, 21000f, 48000), 0.01f)
    }

    @Test
    fun goertzelWorksNearNyquist() {
        val x = sine(23500f, 48000, 4800, 0.4f)
        assertEquals(0.4f, Dsp.goertzelAmplitude(x, x.size, 23500f, 48000), 0.02f)
    }

    @Test
    fun goertzelIgnoresOtherFrequencies() {
        val x = sine(21000f, 48000, 4800, 0.5f)
        for (f in Dsp.referenceFreqs(21000f)) {
            assertTrue("leak at $f", Dsp.goertzelAmplitude(x, x.size, f, 48000) < 0.01f)
        }
    }

    @Test
    fun goertzelOnSilenceIsZero() {
        val x = FloatArray(4800)
        assertEquals(0f, Dsp.goertzelAmplitude(x, x.size, 20000f, 48000), 0f)
    }

    @Test
    fun dbfsOfKnownLevels() {
        assertEquals(0f, Dsp.toDbfs(1f), 1e-4f)
        assertEquals(-20f, Dsp.toDbfs(0.1f), 1e-3f)
        assertTrue(Dsp.toDbfs(0f) < -150f) // silence doesn't blow up
    }

    @Test
    fun referenceFrequenciesStayClearOfTheTone() {
        assertArrayEquals(floatArrayOf(19500f, 18500f, 17500f), Dsp.referenceFreqs(21000f), 0f)
        assertArrayEquals(floatArrayOf(3500f, 4500f, 5500f), Dsp.referenceFreqs(2000f), 0f)
    }

    @Test
    fun sweepRunsFromStartToEndThenRepeats() {
        assertEquals(18000f, Dsp.sweepFrequency(0.0, 18000f, 24000f, 10f), 0.01f)
        assertEquals(21000f, Dsp.sweepFrequency(5.0, 18000f, 24000f, 10f), 0.01f)
        assertEquals(18000f, Dsp.sweepFrequency(10.0, 18000f, 24000f, 10f), 0.01f)
        assertEquals(21000f, Dsp.sweepFrequency(15.0, 18000f, 24000f, 10f), 0.01f)
    }

    @Test
    fun sweepCanRunDownwards() {
        assertEquals(22000f, Dsp.sweepFrequency(2.5, 24000f, 20000f, 5f), 0.01f)
    }

    @Test
    fun volumeCurveIsQuietAtTheBottomAndFullAtTheTop() {
        assertEquals(0f, Dsp.volumeCurve(0f), 0f)
        assertEquals(0.25f, Dsp.volumeCurve(0.5f), 1e-6f)
        assertEquals(1f, Dsp.volumeCurve(1f), 0f)
        assertEquals(1f, Dsp.volumeCurve(3f), 0f) // out-of-range input is clamped
    }

    @Test
    fun maxFrequencyStaysBelowNyquist() {
        assertEquals(23950, Dsp.maxHz(48000))
        assertEquals(22000, Dsp.maxHz(44100)) // 44100 / 2 - 50
    }

    @Test
    fun zonesAreNamedByFrequency() {
        assertTrue(Dsp.zone(8000f).startsWith("Audible"))
        assertTrue(Dsp.zone(18000f).startsWith("Near-ultrasonic"))
        assertTrue(Dsp.zone(22000f).startsWith("Ultrasonic"))
    }
}
