package com.example.watchlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Scroller2DTest {

    private val dt = 1f / 60f

    private fun scroller(): Scroller2D = Scroller2D().apply {
        setBounds(-500f, 500f, -800f, 800f)
        snapEnabled = false
    }

    /** Runs the animation for up to [seconds]; returns how long it took, or -1 if it never finished. */
    private fun run(s: Scroller2D, seconds: Float = 10f): Float {
        var t = 0f
        while (t < seconds) {
            if (!s.step(dt)) return t
            t += dt
        }
        return -1f
    }

    @Test
    fun dragMovesTheContentByTheFingerDistance() {
        val s = scroller()
        s.drag(30f, -20f)
        assertEquals(30f, s.x, 1e-4f)
        assertEquals(-20f, s.y, 1e-4f)
        assertFalse(s.isAnimating)
    }

    @Test
    fun dragBeyondTheEdgeIsResisted() {
        val s = scroller()
        s.jumpTo(520f, 0f) // already past the edge at 500
        s.drag(100f, 0f)
        assertEquals(560f, s.x, 1e-3f) // only 40% of the finger movement counts
    }

    @Test
    fun dragBackInwardsFromBeyondTheEdgeIsNotResisted() {
        val s = scroller()
        s.jumpTo(550f, 0f)
        s.drag(-50f, 0f)
        assertEquals(500f, s.x, 1e-3f)
    }

    @Test
    fun flingCoastsInTheDirectionOfTheFlingAndStops() {
        val s = scroller()
        s.fling(600f, 0f)
        val t = run(s)
        assertTrue("never stopped", t > 0f)
        assertTrue(s.x > 100f)
        assertTrue(s.x <= 500f)
        assertEquals(0f, s.y, 1e-3f)
        assertFalse(s.isAnimating)
    }

    @Test
    fun hardFlingPastTheEdgeSpringsBackInsideTheBounds() {
        val s = scroller()
        s.jumpTo(400f, 0f)
        s.fling(4000f, 0f)
        val t = run(s)
        assertTrue("never stopped", t > 0f)
        assertTrue(s.x in 499.5f..500.5f)
    }

    @Test
    fun releaseOutsideTheBoundsReturnsToTheEdge() {
        val s = scroller()
        s.jumpTo(-700f, 0f)
        s.release()
        val t = run(s)
        assertTrue("never stopped", t > 0f)
        assertTrue(s.x in -500.5f..-499.5f)
    }

    @Test
    fun settleLandsExactlyOnTheSnapTarget() {
        val s = Scroller2D().apply {
            setBounds(-500f, 500f, -800f, 800f)
            snapEnabled = true
            snapTarget = { _, _ -> floatArrayOf(120f, 90f) }
        }
        s.jumpTo(100f, 80f)
        s.release()
        val t = run(s)
        assertTrue("never stopped", t > 0f)
        assertEquals(120f, s.x, 0f)
        assertEquals(90f, s.y, 0f)
    }

    @Test
    fun snapTargetOutsideTheBoundsIsClamped() {
        val s = Scroller2D().apply {
            setBounds(0f, 100f, 0f, 100f)
            snapEnabled = true
            snapTarget = { _, _ -> floatArrayOf(250f, -40f) }
        }
        s.jumpTo(50f, 50f)
        s.release()
        run(s)
        assertEquals(100f, s.x, 0f)
        assertEquals(0f, s.y, 0f)
    }

    @Test
    fun snapDisabledJustStopsWhereItIs() {
        val s = Scroller2D().apply {
            setBounds(-500f, 500f, -800f, 800f)
            snapEnabled = false
            snapTarget = { _, _ -> floatArrayOf(300f, 300f) }
        }
        s.jumpTo(10f, 20f)
        s.release()
        run(s)
        assertEquals(10f, s.x, 1e-3f)
        assertEquals(20f, s.y, 1e-3f)
    }

    @Test
    fun animateToGlidesToThePoint() {
        val s = scroller()
        s.animateTo(200f, -300f)
        val t = run(s)
        assertTrue("never stopped", t > 0f)
        assertEquals(200f, s.x, 0f)
        assertEquals(-300f, s.y, 0f)
    }

    @Test
    fun touchingDuringAnimationStopsIt() {
        val s = scroller()
        s.fling(2000f, 0f)
        s.step(dt)
        assertTrue(s.isAnimating)
        s.drag(1f, 0f)
        assertFalse(s.isAnimating)
    }

    @Test
    fun hugeFrameTimesDoNotBlowUp() {
        val s = scroller()
        s.fling(3000f, 3000f)
        repeat(200) { s.step(5f) } // a stalled frame of 5 seconds, repeatedly
        assertTrue(s.x in -600f..600f)
        assertTrue(s.y in -900f..900f)
    }
}
