package com.example.watchlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensTest {

    private val lens = Lens(inner = 0.4f, outer = 1.15f, minScale = 0.3f)

    @Test
    fun centreKeepsFullSize() {
        assertEquals(1f, lens.scale(0f), 0f)
        assertEquals(1f, lens.scale(0.4f), 0f)
    }

    @Test
    fun farIconsAreAtTheMinimumSize() {
        assertEquals(0.3f, lens.scale(1.15f), 1e-6f)
        assertEquals(0.3f, lens.scale(5f), 1e-6f)
    }

    @Test
    fun scaleShrinksSmoothlyAndNeverGrows() {
        var previous = lens.scale(0f)
        var d = 0f
        while (d <= 1.4f) {
            val s = lens.scale(d)
            assertTrue("scale grew at $d", s <= previous + 1e-6f)
            assertTrue(s in (0.3f - 1e-5f)..(1f + 1e-5f)) // tolerance for float rounding at the ends
            previous = s
            d += 0.01f
        }
    }

    @Test
    fun noJumpAtTheEdgesOfTheTransition() {
        assertEquals(lens.scale(0.4f), lens.scale(0.4001f), 1e-3f)
        assertEquals(lens.scale(1.15f), lens.scale(1.1499f), 1e-3f)
    }

    @Test
    fun mappingStartsAtZeroAndIsIdenticalInTheFullSizeZone() {
        assertEquals(0f, lens.map(0f), 0f)
        assertEquals(0.2f, lens.map(0.2f), 1e-3f)
        assertEquals(0.4f, lens.map(0.4f), 1e-3f)
    }

    @Test
    fun mappingOnlyMovesOutwardsAndSqueezesDistantIcons() {
        var previous = 0f
        var d = 0f
        while (d <= 3f) {
            val m = lens.map(d)
            assertTrue("map went backwards at $d", m >= previous)
            assertTrue(m <= d + 1e-4f) // never pushes anything further out
            previous = m
            d += 0.01f
        }
        assertTrue(lens.map(2f) < 1.5f)
    }

    @Test
    fun mappingIsContinuousAcrossTheTableEnd() {
        assertEquals(lens.map(1.1499f), lens.map(1.1501f), 2e-3f)
    }

    @Test
    fun localSpacingMatchesIconSize() {
        // The gap between neighbouring icons shrinks at the same rate as the icons themselves.
        for (d in listOf(0.3f, 0.6f, 0.9f, 1.3f, 2f)) {
            val h = 0.001f
            val slope = (lens.map(d + h) - lens.map(d - h)) / (2 * h)
            assertEquals(lens.scale(d), slope, 0.01f)
        }
    }

    @Test
    fun factorIsOneAtTheCentre() {
        assertEquals(1f, lens.factor(0f), 0f)
        assertEquals(1f, lens.factor(0.2f), 1e-3f)
    }
}
