package com.example.watchlauncher

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Two-axis scrolling with the feel of a watch face: drag follows the finger, a fling coasts to a stop,
 * going past the edge is rubber-banded and springs back, and (optionally) the motion settles with the
 * nearest icon exactly in the middle.
 *
 * (x, y) is the point of the content that sits at the centre of the screen.
 */
class Scroller2D {

    private enum class State { IDLE, FLING, SETTLE }

    private companion object {
        const val FRICTION = 3.2f // fling slows by this factor of e per second
        const val SPRING_K = 140f // pull back from over-scroll, and the settle spring
        val SPRING_C = 2f * Math.sqrt(SPRING_K.toDouble()).toFloat() // critically damped
        const val SETTLE_SPEED = 45f // px/s below which a fling hands over to the settle
        const val OVERSCROLL_DRAG = 0.4f // finger movement that counts while beyond the edge
        const val MAX_DT = 0.032f
    }

    private val pos = FloatArray(2)
    private val vel = FloatArray(2)
    private val lo = FloatArray(2)
    private val hi = FloatArray(2)
    private val target = FloatArray(2)
    private var state = State.IDLE

    val x: Float get() = pos[0]
    val y: Float get() = pos[1]

    val isAnimating: Boolean get() = state != State.IDLE

    var snapEnabled = true

    /** Returns the content point to settle on, given where the scroll came to rest, or null for none. */
    var snapTarget: ((Float, Float) -> FloatArray?)? = null

    fun setBounds(minX: Float, maxX: Float, minY: Float, maxY: Float) {
        lo[0] = minX
        hi[0] = maxX
        lo[1] = minY
        hi[1] = maxY
    }

    fun jumpTo(x: Float, y: Float) {
        pos[0] = x
        pos[1] = y
        vel[0] = 0f
        vel[1] = 0f
        state = State.IDLE
    }

    /** Glides to [x], [y] with the settle spring. */
    fun animateTo(x: Float, y: Float) {
        target[0] = x
        target[1] = y
        state = State.SETTLE
    }

    fun stop() {
        vel[0] = 0f
        vel[1] = 0f
        state = State.IDLE
    }

    /** Moves the content by a finger drag of ([dx], [dy]); beyond the edge the content resists. */
    fun drag(dx: Float, dy: Float) {
        state = State.IDLE
        vel[0] = 0f
        vel[1] = 0f
        pos[0] += resist(0, dx)
        pos[1] += resist(1, dy)
    }

    private fun resist(axis: Int, delta: Float): Float {
        val p = pos[axis]
        val outwards = (p < lo[axis] && delta < 0f) || (p > hi[axis] && delta > 0f)
        return if (outwards) delta * OVERSCROLL_DRAG else delta
    }

    /** Finger lifted while moving at ([vx], [vy]) content px/s. */
    fun fling(vx: Float, vy: Float) {
        vel[0] = vx
        vel[1] = vy
        state = State.FLING
    }

    /** Finger lifted without a fling: spring back if over the edge, otherwise settle. */
    fun release() {
        vel[0] = 0f
        vel[1] = 0f
        state = State.FLING // the first step decides between spring, settle and stop
    }

    /** Advances the motion by [dtSeconds]. Returns true while it still needs more frames. */
    fun step(dtSeconds: Float): Boolean {
        if (state == State.IDLE) return false
        val dt = dtSeconds.coerceIn(0f, MAX_DT)

        if (state == State.SETTLE) {
            for (a in 0..1) {
                val accel = SPRING_K * (target[a] - pos[a]) - SPRING_C * vel[a]
                vel[a] += accel * dt
                pos[a] += vel[a] * dt
            }
            if (abs(pos[0] - target[0]) < 0.25f && abs(pos[1] - target[1]) < 0.25f &&
                abs(vel[0]) < 8f && abs(vel[1]) < 8f
            ) {
                pos[0] = target[0]
                pos[1] = target[1]
                stop()
                return false
            }
            return true
        }

        val decay = exp(-FRICTION * dt)
        var outside = false
        for (a in 0..1) {
            val edge = when {
                pos[a] < lo[a] -> lo[a]
                pos[a] > hi[a] -> hi[a]
                else -> Float.NaN
            }
            if (edge.isNaN()) {
                vel[a] *= decay
                pos[a] += vel[a] * dt
            } else {
                outside = true
                val accel = SPRING_K * (edge - pos[a]) - SPRING_C * vel[a]
                vel[a] += accel * dt
                pos[a] += vel[a] * dt
                // The spring only approaches the edge, and can stall one float step outside it. Close enough is on it.
                if (abs(edge - pos[a]) < 0.25f && abs(vel[a]) < 8f) {
                    pos[a] = edge
                    vel[a] = 0f
                }
            }
        }

        if (!outside && hypot(vel[0], vel[1]) < SETTLE_SPEED) {
            val snap = if (snapEnabled) snapTarget?.invoke(pos[0], pos[1]) else null
            if (snap != null) {
                target[0] = snap[0].coerceIn(lo[0], hi[0])
                target[1] = snap[1].coerceIn(lo[1], hi[1])
                state = State.SETTLE
                return true
            }
            stop()
            return false
        }
        return true
    }
}
