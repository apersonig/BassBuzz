package com.example.ldafscope

/** Pure maths for turning camera focus distance and phone orientation into points. No Android needed. */
object Geometry {

    /** Below this many diopters the lens is effectively at infinity (more than 20 m away). */
    const val FAR_DIOPTERS = 0.05f

    /** Points further than this are not plotted: autofocus isn't trustworthy out there. */
    const val MAX_PLOT_METERS = 10f

    const val MIN_PLOT_METERS = 0.05f

    // CaptureResult.CONTROL_AF_STATE values
    private const val AF_PASSIVE_FOCUSED = 2
    private const val AF_FOCUSED_LOCKED = 4

    /** Focus distance is reported in diopters (1 / metres); 0 means infinity. */
    fun metersFromDiopters(diopters: Float): Float =
        if (diopters < FAR_DIOPTERS) Float.POSITIVE_INFINITY else 1f / diopters

    /**
     * Unit vector (east, north, up) of the direction the back of the phone points, from the row-major 3x3
     * device-to-world matrix produced by SensorManager.getRotationMatrixFromVector.
     */
    fun backDirection(r: FloatArray): FloatArray = floatArrayOf(-r[2], -r[5], -r[8])

    /** True when autofocus reports a settled focus and the distance is in a believable range. */
    fun acceptable(afState: Int?, meters: Float): Boolean =
        (afState == AF_PASSIVE_FOCUSED || afState == AF_FOCUSED_LOCKED) &&
            meters.isFinite() && meters in MIN_PLOT_METERS..MAX_PLOT_METERS

    fun median(values: Collection<Float>): Float {
        if (values.isEmpty()) return Float.NaN
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }

    fun format(meters: Float): String = when {
        meters.isNaN() -> "-"
        meters.isInfinite() -> "far"
        meters < 1f -> "%.0f cm".format(meters * 100f)
        else -> "%.2f m".format(meters)
    }

    fun afStateName(state: Int?): String = when (state) {
        0 -> "idle"
        1 -> "searching"
        2 -> "focused"
        3 -> "searching (active)"
        4 -> "focus locked"
        5 -> "couldn't focus (locked)"
        6 -> "couldn't focus"
        else -> "unknown"
    }
}
