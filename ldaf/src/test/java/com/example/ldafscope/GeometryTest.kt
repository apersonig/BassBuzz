package com.example.ldafscope

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {

    @Test
    fun dioptersConvertToMeters() {
        assertEquals(0.5f, Geometry.metersFromDiopters(2f), 1e-6f)
        assertEquals(0.1f, Geometry.metersFromDiopters(10f), 1e-6f)
        assertEquals(4f, Geometry.metersFromDiopters(0.25f), 1e-6f)
    }

    @Test
    fun zeroAndTinyDioptersMeanInfinity() {
        assertTrue(Geometry.metersFromDiopters(0f).isInfinite())
        assertTrue(Geometry.metersFromDiopters(0.04f).isInfinite())
    }

    @Test
    fun phoneLyingFlatFaceUpPointsStraightDown() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertArrayEquals(floatArrayOf(0f, 0f, -1f), Geometry.backDirection(identity), 1e-6f)
    }

    @Test
    fun uprightPhoneWithBackToTheNorthPointsNorth() {
        // Device x = east, device y = up, device z (out of the screen, towards the user) = south.
        val r = floatArrayOf(
            1f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f,
        )
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), Geometry.backDirection(r), 1e-6f)
    }

    @Test
    fun uprightPhoneTurnedToTheEastPointsEast() {
        // Rotate the previous pose a quarter turn clockwise seen from above: back now faces east.
        // Device x = south, device y = up, device z = west.
        val r = floatArrayOf(
            0f, 0f, -1f,
            -1f, 0f, 0f,
            0f, 1f, 0f,
        )
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), Geometry.backDirection(r), 1e-6f)
    }

    @Test
    fun onlySettledFocusInRangeIsAccepted() {
        assertTrue(Geometry.acceptable(2, 1f))
        assertTrue(Geometry.acceptable(4, 0.3f))
        assertFalse(Geometry.acceptable(1, 1f)) // still searching
        assertFalse(Geometry.acceptable(6, 1f)) // failed to focus
        assertFalse(Geometry.acceptable(null, 1f))
        assertFalse(Geometry.acceptable(2, Float.POSITIVE_INFINITY))
        assertFalse(Geometry.acceptable(2, 30f))
    }

    @Test
    fun medianHandlesOddEvenAndEmpty() {
        assertEquals(2f, Geometry.median(listOf(3f, 1f, 2f)), 0f)
        assertEquals(2.5f, Geometry.median(listOf(4f, 1f, 2f, 3f)), 0f)
        assertTrue(Geometry.median(emptyList()).isNaN())
    }

    @Test
    fun formatsDistances() {
        assertEquals("45 cm", Geometry.format(0.45f))
        assertEquals("1.50 m", Geometry.format(1.5f))
        assertEquals("far", Geometry.format(Float.POSITIVE_INFINITY))
        assertEquals("-", Geometry.format(Float.NaN))
    }
}
