package com.example.ioslauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {

    @Test
    fun undoAndRedoWalkBackAndForth() {
        val h = History<Int>()
        // state went 1 -> 2 -> 3
        h.record(1)
        h.record(2)
        assertEquals(2, h.undo(3))
        assertEquals(1, h.undo(2))
        assertNull(h.undo(1))
        assertEquals(2, h.redo(1))
        assertEquals(3, h.redo(2))
        assertNull(h.redo(3))
    }

    @Test
    fun aNewChangeEndsTheRedoChain() {
        val h = History<Int>()
        h.record(1)
        h.undo(2)
        assertTrue(h.canRedo)
        h.record(1)
        assertFalse(h.canRedo)
    }

    @Test
    fun theOldestStatesFallOffTheEnd() {
        val h = History<Int>(limit = 3)
        for (i in 1..5) h.record(i)
        assertEquals(5, h.undo(6))
        assertEquals(4, h.undo(5))
        assertEquals(3, h.undo(4))
        assertNull(h.undo(3))
    }

    @Test
    fun clearEmptiesBoth() {
        val h = History<Int>()
        h.record(1)
        h.undo(2)
        h.clear()
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
    }
}
