package com.simplepdf.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ReaderSessionTest {
    private val first = PdfDocument("/storage/first.pdf", "first.pdf", 1, 10)
    private val second = PdfDocument("/storage/second.pdf", "second.pdf", 2, 20)
    private val third = PdfDocument("/storage/third.pdf", "third.pdf", 3, 30)

    @Test
    fun emptySessionStartsAtHome() {
        assertEquals(emptyList<OpenPdfTab>(), ReaderSession().tabs)
        assertNull(ReaderSession().selectedPath)
    }

    @Test
    fun openingSelectsAndReopeningPreservesPageAndOrder() {
        val original = ReaderSession().open(first).updatePage(first.path, 12).open(second)
        val refreshed = first.copy(title = "Renamed", modifiedAt = 10)
        val reopened = original.open(refreshed)

        assertEquals(listOf(refreshed, second), reopened.tabs.map { it.document })
        assertEquals(12, reopened.tabs.first().pageIndex)
        assertEquals(first.path, reopened.selectedPath)
        assertEquals(second.path, original.selectedPath)
        assertEquals(first, original.tabs.first().document)
    }

    @Test
    fun selectingHomePreservesTabsAndPages() {
        val original = ReaderSession().open(first).updatePage(first.path, 8)
        val home = original.select(null)

        assertEquals(original.tabs, home.tabs)
        assertNull(home.selectedPath)
        assertEquals(first.path, home.select(first.path).selectedPath)
    }

    @Test
    fun closingActiveTabSelectsRightNeighborOrLeftNeighborAtEnd() {
        val all = ReaderSession().open(first).open(second).open(third)

        assertEquals(second.path, all.select(first.path).close(first.path).selectedPath)
        assertEquals(third.path, all.select(second.path).close(second.path).selectedPath)
        assertEquals(second.path, all.close(third.path).selectedPath)
    }

    @Test
    fun closingLastTabReturnsHome() {
        assertEquals(ReaderSession(), ReaderSession().open(first).close(first.path))
    }

    @Test
    fun closingInactiveTabDoesNotChangeSelectionOrLeaveHome() {
        val all = ReaderSession().open(first).open(second)

        assertEquals(second.path, all.close(first.path).selectedPath)
        assertNull(all.select(null).close(first.path).selectedPath)
        assertEquals(listOf(second), all.close(first.path).tabs.map { it.document })
    }

    @Test
    fun updatingAnInactivePageDoesNotSelectTheTab() {
        val all = ReaderSession().open(first).open(second)
        val updated = all.updatePage(first.path, 42)

        assertEquals(42, updated.tabs.first().pageIndex)
        assertEquals(0, all.tabs.first().pageIndex)
        assertEquals(second.path, updated.selectedPath)
        assertNull(updated.select(null).updatePage(second.path, 1).selectedPath)
    }

    @Test
    fun invalidPathsPagesAndSessionInvariantsAreRejected() {
        val session = ReaderSession().open(first)
        for (path in listOf("", " ", "/missing.pdf")) {
            assertThrows(IllegalArgumentException::class.java) { session.select(path) }
            assertThrows(IllegalArgumentException::class.java) { session.close(path) }
            assertThrows(IllegalArgumentException::class.java) { session.updatePage(path, 0) }
        }
        assertThrows(IllegalArgumentException::class.java) { session.updatePage(first.path, -1) }
        assertThrows(IllegalArgumentException::class.java) { OpenPdfTab(first, -1) }
        assertThrows(IllegalArgumentException::class.java) { first.copy(path = " ") }
        assertThrows(IllegalArgumentException::class.java) { first.copy(sizeBytes = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            ReaderSession(listOf(OpenPdfTab(first), OpenPdfTab(first)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReaderSession(listOf(OpenPdfTab(first)), second.path)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReaderSession(selectedPath = first.path)
        }
    }
}
