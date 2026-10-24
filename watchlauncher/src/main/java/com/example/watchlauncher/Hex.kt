package com.example.watchlauncher

/**
 * Hexagonal packing of circles, as in the Apple Watch app grid. Cells are addressed in axial coordinates
 * (q, r); rows run horizontally and every other row is shifted by half a cell.
 */
object Hex {

    private val DIRECTIONS = arrayOf(
        intArrayOf(1, 0), intArrayOf(1, -1), intArrayOf(0, -1),
        intArrayOf(-1, 0), intArrayOf(-1, 1), intArrayOf(0, 1),
    )

    private const val ROW_HEIGHT = 0.8660254f // sqrt(3) / 2

    /** The cells exactly [radius] steps from the centre: 1 for radius 0, otherwise 6 * radius. */
    fun ring(radius: Int): List<IntArray> {
        if (radius == 0) return listOf(intArrayOf(0, 0))
        val cells = ArrayList<IntArray>(6 * radius)
        var q = DIRECTIONS[4][0] * radius
        var r = DIRECTIONS[4][1] * radius
        for (side in 0 until 6) {
            for (step in 0 until radius) {
                cells.add(intArrayOf(q, r))
                q += DIRECTIONS[side][0]
                r += DIRECTIONS[side][1]
            }
        }
        return cells
    }

    /** The first [count] cells of a spiral that starts in the middle and winds outwards ring by ring. */
    fun spiral(count: Int): List<IntArray> {
        val cells = ArrayList<IntArray>(count)
        var radius = 0
        while (cells.size < count) {
            for (c in ring(radius)) {
                if (cells.size == count) break
                cells.add(c)
            }
            radius++
        }
        return cells
    }

    /** Distance in cell steps between two axial cells. */
    fun distance(q1: Int, r1: Int, q2: Int, r2: Int): Int {
        val dq = q1 - q2
        val dr = r1 - r2
        return (Math.abs(dq) + Math.abs(dr) + Math.abs(dq + dr)) / 2
    }

    /** Horizontal position of a cell centre when neighbouring centres are [spacing] apart. */
    fun x(q: Int, r: Int, spacing: Float): Float = spacing * (q + r / 2f)

    fun y(r: Int, spacing: Float): Float = spacing * ROW_HEIGHT * r
}
