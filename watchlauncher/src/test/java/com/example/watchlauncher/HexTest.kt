package com.example.watchlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HexTest {

    @Test
    fun ringsHaveSixTimesTheirRadiusCells() {
        assertEquals(1, Hex.ring(0).size)
        for (k in 1..8) assertEquals(6 * k, Hex.ring(k).size)
    }

    @Test
    fun everyCellOfARingIsExactlyThatFarFromTheCentre() {
        for (k in 0..8) {
            for (c in Hex.ring(k)) assertEquals(k, Hex.distance(0, 0, c[0], c[1]))
        }
    }

    @Test
    fun spiralHasNoDuplicatesAndTheRequestedLength() {
        for (n in listOf(0, 1, 2, 7, 8, 19, 20, 100, 237)) {
            val cells = Hex.spiral(n)
            assertEquals(n, cells.size)
            assertEquals(n, cells.map { it[0] to it[1] }.toSet().size)
        }
    }

    @Test
    fun spiralStartsAtTheCentreAndFillsRingsInOrder() {
        val cells = Hex.spiral(19) // centre + ring 1 + ring 2
        assertEquals(0, Hex.distance(0, 0, cells[0][0], cells[0][1]))
        for (i in 1..6) assertEquals(1, Hex.distance(0, 0, cells[i][0], cells[i][1]))
        for (i in 7..18) assertEquals(2, Hex.distance(0, 0, cells[i][0], cells[i][1]))
    }

    @Test
    fun consecutiveCellsAroundARingTouch() {
        for (k in 1..6) {
            val ring = Hex.ring(k)
            for (i in ring.indices) {
                val a = ring[i]
                val b = ring[(i + 1) % ring.size]
                assertEquals(1, Hex.distance(a[0], a[1], b[0], b[1]))
            }
        }
    }

    @Test
    fun neighbouringCellsAreExactlyOneSpacingApart() {
        val spacing = 80f
        val cells = Hex.spiral(61)
        for (a in cells) {
            for (b in cells) {
                if (Hex.distance(a[0], a[1], b[0], b[1]) == 1) {
                    val dx = Hex.x(a[0], a[1], spacing) - Hex.x(b[0], b[1], spacing)
                    val dy = Hex.y(a[1], spacing) - Hex.y(b[1], spacing)
                    assertEquals(spacing, Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat(), 0.01f)
                }
            }
        }
    }

    @Test
    fun centreCellSitsAtTheOrigin() {
        assertEquals(0f, Hex.x(0, 0, 80f), 0f)
        assertEquals(0f, Hex.y(0, 80f), 0f)
        assertTrue(Hex.y(1, 80f) > 0f)
    }
}
