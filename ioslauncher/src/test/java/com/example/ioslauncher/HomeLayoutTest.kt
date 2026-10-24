package com.example.ioslauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {

    private fun keys(n: Int) = (1..n).map { "app$it" }
    private val per = 4

    private fun HomeLayout.all() = pages.flatten() + dock + hidden

    @Test
    fun freshLayoutPutsTheDefaultDockFirstAndFillsPages() {
        val l = HomeLayout.reconcile(null, keys(10), listOf("app3", "app1", "nope"), per)
        assertEquals(listOf("app3", "app1"), l.dock)
        assertEquals(listOf("app2", "app4", "app5", "app6"), l.pages[0])
        assertEquals(2, l.pages.size)
        assertEquals(10, l.all().size)
    }

    @Test
    fun dockIsCappedAtFour() {
        val l = HomeLayout.reconcile(null, keys(10), keys(8), per)
        assertEquals(4, l.dock.size)
    }

    @Test
    fun moveWithinAPageReorders() {
        val l = HomeLayout(listOf(listOf("a", "b", "c", "d")), emptyList(), emptySet())
        assertEquals(listOf("b", "c", "a", "d"), l.move("a", 0, 2, per).pages[0])
        assertEquals(listOf("d", "a", "b", "c"), l.move("d", 0, 0, per).pages[0])
    }

    @Test
    fun movingIntoAFullPageSpillsTheLastIconToTheNextPage() {
        val l = HomeLayout(listOf(listOf("a", "b", "c", "d"), listOf("e", "f")), emptyList(), emptySet())
        val m = l.move("f", 0, 1, per)
        assertEquals(listOf("a", "f", "b", "c"), m.pages[0])
        assertEquals(listOf("d", "e"), m.pages[1])
    }

    @Test
    fun movingPastTheLastPageCreatesANewPage() {
        val l = HomeLayout(listOf(listOf("a", "b")), emptyList(), emptySet())
        val m = l.move("a", 1, 0, per)
        assertEquals(listOf(listOf("b"), listOf("a")), m.pages)
    }

    @Test
    fun emptyPagesAreDroppedUnlessADragIsInProgress() {
        val l = HomeLayout(listOf(listOf("a"), listOf("b")), emptyList(), emptySet())
        assertEquals(listOf(listOf("b", "a")), l.move("a", 1, 1, per).pages)
        assertEquals(listOf(emptyList(), listOf("b", "a")), l.move("a", 1, 1, per, dropEmpty = false).pages)
    }

    @Test
    fun thereIsAlwaysOnePage() {
        val l = HomeLayout(listOf(listOf("a")), emptyList(), emptySet()).removeFromHome("a", per)
        assertEquals(1, l.pages.size)
        assertTrue(l.pages[0].isEmpty())
        assertEquals(setOf("a"), l.hidden)
    }

    @Test
    fun dockAcceptsFourAndRefusesAFifth() {
        var l = HomeLayout(listOf(listOf("a", "b", "c", "d", "e", "f")), emptyList(), emptySet())
        for ((i, k) in listOf("a", "b", "c", "d").withIndex()) l = l.moveToDock(k, i, per)
        assertEquals(listOf("a", "b", "c", "d"), l.dock)
        val refused = l.moveToDock("e", 0, per)
        assertEquals(l, refused)
        // but reordering inside a full dock is fine
        assertEquals(listOf("d", "a", "b", "c"), l.moveToDock("d", 0, per).dock)
    }

    @Test
    fun iconsMoveOutOfTheDockOntoAPage() {
        val l = HomeLayout(listOf(listOf("a")), listOf("x", "y"), emptySet())
        val m = l.move("x", 0, 0, per)
        assertEquals(listOf("y"), m.dock)
        assertEquals(listOf("x", "a"), m.pages[0])
    }

    @Test
    fun removeAndAddBackToHome() {
        val l = HomeLayout(listOf(listOf("a", "b")), listOf("x"), emptySet())
        val removed = l.removeFromHome("a", per).removeFromHome("x", per)
        assertEquals(setOf("a", "x"), removed.hidden)
        assertEquals(listOf("b"), removed.pages[0])
        assertTrue(removed.dock.isEmpty())
        val back = removed.addToHome("a", per)
        assertEquals(listOf("b", "a"), back.pages[0])
        assertEquals(setOf("x"), back.hidden)
        assertEquals(back, back.addToHome("a", per)) // not hidden any more: nothing to do
    }

    @Test
    fun anAppIsNeverInTwoPlaces() {
        var l = HomeLayout.reconcile(null, keys(9), listOf("app1", "app2"), per)
        l = l.move("app1", 1, 0, per).moveToDock("app5", 0, per).removeFromHome("app7", per).move("app2", 0, 0, per)
        val all = l.all()
        assertEquals(all.size, all.toSet().size)
        assertEquals(9, all.size)
    }

    @Test
    fun reconcileDropsUninstalledAndAppendsNewApps() {
        val saved = HomeLayout(listOf(listOf("a", "gone"), listOf("b")), listOf("x", "gone2"), setOf("h", "gone3"))
        val l = HomeLayout.reconcile(saved, listOf("a", "b", "x", "h", "new1", "new2"), emptyList(), per)
        assertEquals(listOf("x"), l.dock)
        assertEquals(setOf("h"), l.hidden)
        assertEquals(listOf("a"), l.pages[0])
        assertEquals(listOf("b", "new1", "new2"), l.pages[1])
    }

    @Test
    fun hiddenAppsStayHiddenAcrossReconcile() {
        val saved = HomeLayout(listOf(listOf("a")), emptyList(), setOf("h"))
        val l = HomeLayout.reconcile(saved, listOf("a", "h"), emptyList(), per)
        assertEquals(setOf("h"), l.hidden)
        assertFalse(l.pages.flatten().contains("h"))
    }

    @Test
    fun serializeRoundTrips() {
        val l = HomeLayout(listOf(listOf("com.a/.Main@0", "b"), listOf("c")), listOf("x", "y"), setOf("h"))
        assertEquals(l, HomeLayout.parse(l.serialize()))
        val empty = HomeLayout.EMPTY
        assertEquals(empty, HomeLayout.parse(empty.serialize()))
    }

    @Test
    fun parseRejectsGarbage() {
        assertNull(HomeLayout.parse(null))
        assertNull(HomeLayout.parse(""))
        assertNull(HomeLayout.parse("hello"))
    }
}
