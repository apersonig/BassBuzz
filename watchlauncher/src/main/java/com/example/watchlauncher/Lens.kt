package com.example.watchlauncher

/**
 * The fisheye of the Apple Watch grid. Distances are measured from the middle of the screen in units where
 * 1.0 is roughly the screen edge. Icons inside [inner] keep full size; between [inner] and [outer] they
 * shrink smoothly down to [minScale] and stay there.
 *
 * Positions are squeezed the same way: where icons are drawn at scale s, the gap between neighbours is also
 * s times as big, so shrinking icons stay evenly packed instead of overlapping.
 */
class Lens(
    private val inner: Float = 0.40f,
    private val outer: Float = 1.15f,
    private val minScale: Float = 0.30f,
) {
    private companion object {
        const val STEPS = 512
    }

    /** table[i] = integral of scale() from 0 to i * outer / STEPS. */
    private val table = FloatArray(STEPS + 1)

    init {
        val h = outer / STEPS
        var acc = 0f
        for (i in 1..STEPS) {
            acc += (scale((i - 1) * h) + scale(i * h)) / 2f * h
            table[i] = acc
        }
    }

    /** Icon size multiplier at distance [d] from the centre. */
    fun scale(d: Float): Float {
        if (d <= inner) return 1f
        if (d >= outer) return minScale
        val t = (d - inner) / (outer - inner)
        val smooth = t * t * (3f - 2f * t)
        return 1f + (minScale - 1f) * smooth
    }

    /** Where an item at true distance [d] ends up on screen (same units). */
    fun map(d: Float): Float {
        if (d <= 0f) return 0f
        if (d >= outer) return table[STEPS] + (d - outer) * minScale
        val x = d / outer * STEPS
        val i = x.toInt().coerceAtMost(STEPS - 1)
        val f = x - i
        return table[i] * (1f - f) + table[i + 1] * f
    }

    /** Ratio by which an offset vector is shortened on screen: map(d) / d. */
    fun factor(d: Float): Float = if (d < 1e-6f) 1f else map(d) / d
}
