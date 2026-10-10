package com.simplepdf.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteLruCacheTest {
    @Test
    fun byteBudgetIsSharedAcrossDocumentAndPageKeys() {
        val cache = ByteLruCache<PdfPageKey, String>(10) { it.length }
        val firstPage = PdfPageKey("first.pdf", 0)
        val otherDocumentPage = PdfPageKey("second.pdf", 0)
        val nextPage = PdfPageKey("first.pdf", 1)

        cache.put(firstPage, "aaaa")
        assertEquals(4, cache.sizeBytes)
        cache.put(otherDocumentPage, "bbbb")
        assertEquals(8, cache.sizeBytes)
        cache.put(nextPage, "cccc")

        assertEquals(10, cache.maxBytes)
        assertTrue(cache.sizeBytes <= cache.maxBytes)
        assertEquals(8, cache.sizeBytes)
        assertNull(cache[firstPage])
        assertEquals("bbbb", cache[otherDocumentPage])
        assertEquals("cccc", cache[nextPage])
    }

    @Test
    fun readingAnEntryMakesItMostRecentlyUsed() {
        val cache = ByteLruCache<String, Int>(8) { it }
        cache.put("first", 4)
        cache.put("second", 4)

        assertEquals(4, cache["first"])
        cache.put("third", 4)

        assertNull(cache["second"])
        assertEquals(4, cache["first"])
        assertEquals(4, cache["third"])
        assertEquals(8, cache.sizeBytes)
    }

    @Test
    fun insertionEvictsAsManyOldEntriesAsNeeded() {
        val cache = ByteLruCache<String, Int>(10) { it }
        cache.put("first", 3)
        cache.put("second", 3)
        cache.put("third", 3)

        cache.put("large", 8)

        assertNull(cache["first"])
        assertNull(cache["second"])
        assertNull(cache["third"])
        assertEquals(8, cache["large"])
        assertEquals(8, cache.sizeBytes)
    }

    @Test
    fun overwritingSubtractsOldSizeBeforeAccountingForNewValue() {
        val cache = ByteLruCache<String, Int>(10) { it }
        cache.put("first", 6)
        cache.put("second", 3)

        cache.put("first", 4)

        assertEquals(7, cache.sizeBytes)
        assertEquals(4, cache["first"])
        assertEquals(3, cache["second"])

        cache.put("first", 8)

        assertEquals(8, cache.sizeBytes)
        assertEquals(8, cache["first"])
        assertNull(cache["second"])
    }

    @Test
    fun overwritingMakesTheEntryMostRecentlyUsed() {
        val cache = ByteLruCache<String, Int>(8) { it }
        cache.put("first", 4)
        cache.put("second", 4)

        cache.put("first", 4)
        cache.put("third", 4)

        assertNull(cache["second"])
        assertEquals(4, cache["first"])
        assertEquals(4, cache["third"])
        assertEquals(8, cache.sizeBytes)
    }

    @Test
    fun oversizedNewEntryDoesNotEvictExistingEntries() {
        val cache = ByteLruCache<String, Int>(8) { it }
        cache.put("first", 4)
        cache.put("second", 4)

        cache.put("oversized", 9)

        assertNull(cache["oversized"])
        assertEquals(4, cache["first"])
        assertEquals(4, cache["second"])
        assertEquals(8, cache.sizeBytes)
    }

    @Test
    fun oversizedReplacementPreservesOldValueAndAccessOrder() {
        val cache = ByteLruCache<String, Int>(8) { it }
        cache.put("first", 4)
        cache.put("second", 4)

        cache.put("first", 9)
        assertEquals(8, cache.sizeBytes)
        assertEquals(4, cache["first"])
        assertEquals(4, cache["second"])
        cache.put("first", 9)
        cache.put("third", 4)

        assertNull(cache["first"])
        assertEquals(4, cache["second"])
        assertEquals(4, cache["third"])
        assertEquals(8, cache.sizeBytes)
    }

    @Test
    fun entryExactlyAtTheBudgetIsAccepted() {
        val cache = ByteLruCache<String, Int>(8) { it }
        cache.put("first", 4)

        cache.put("full", 8)

        assertNull(cache["first"])
        assertEquals(8, cache["full"])
        assertEquals(8, cache.sizeBytes)
    }

    @Test
    fun removingEntriesUpdatesAccountingAndMissingKeysAreHarmless() {
        val cache = ByteLruCache<String, Int>(10) { it }
        cache.put("first", 4)
        cache.put("second", 3)

        cache.remove("first")
        cache.remove("first")
        cache.remove("missing")

        assertNull(cache["first"])
        assertEquals(3, cache["second"])
        assertEquals(3, cache.sizeBytes)
        cache.remove("second")
        assertEquals(0, cache.sizeBytes)
    }

    @Test
    fun removingAllPagesOfOneDocumentPreservesOtherDocuments() {
        val cache = ByteLruCache<PdfPageKey, Int>(20) { it }
        val firstPage = PdfPageKey("first.pdf", 0)
        val secondPage = PdfPageKey("first.pdf", 1)
        val otherPage = PdfPageKey("second.pdf", 0)
        cache.put(firstPage, 4)
        cache.put(otherPage, 6)
        cache.put(secondPage, 5)

        cache.removeWhere { it.path == "first.pdf" }
        cache.removeWhere { it.path == "missing.pdf" }

        assertNull(cache[firstPage])
        assertNull(cache[secondPage])
        assertEquals(6, cache[otherPage])
        assertEquals(6, cache.sizeBytes)
    }

    @Test
    fun trimmingUsesAccessOrderAndPreservesEntriesWhenAlreadyWithinLimit() {
        val cache = ByteLruCache<String, Int>(12) { it }
        cache.put("first", 4)
        cache.put("second", 4)
        cache.put("third", 4)
        assertEquals(4, cache["first"])

        cache.trimToSize(8)

        assertNull(cache["second"])
        assertEquals(4, cache["third"])
        assertEquals(4, cache["first"])
        assertEquals(8, cache.sizeBytes)
        cache.trimToSize(8)
        cache.trimToSize(20)
        assertEquals(8, cache.sizeBytes)
        assertEquals(4, cache["third"])
        assertEquals(4, cache["first"])

        cache.trimToSize(0)
        assertEquals(0, cache.sizeBytes)
        assertNull(cache["first"])
        assertNull(cache["third"])
    }

    @Test
    fun clearRemovesEveryEntryAndCacheCanBeReused() {
        val cache = ByteLruCache<String, Int>(10) { it }
        cache.put("first", 4)
        cache.put("second", 5)

        cache.clear()
        cache.clear()

        assertEquals(0, cache.sizeBytes)
        assertNull(cache["first"])
        assertNull(cache["second"])
        cache.put("replacement", 10)
        assertEquals(10, cache["replacement"])
        assertEquals(10, cache.sizeBytes)
    }

    @Test
    fun zeroSizedValuesAreAllowedAndClearStillRemovesThem() {
        val cache = ByteLruCache<String, Int>(1) { it }

        cache.put("zero", 0)
        cache.put("one", 1)

        assertEquals(0, cache["zero"])
        assertEquals(1, cache["one"])
        assertEquals(1, cache.sizeBytes)
        cache.clear()
        assertNull(cache["zero"])
        assertNull(cache["one"])
        assertEquals(0, cache.sizeBytes)
    }

    @Test
    fun nonpositiveBudgetsAreRejected() {
        for (budget in listOf(0, -1, Int.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                ByteLruCache<String, Int>(budget) { it }
            }
        }
    }

    @Test
    fun negativeValueSizesAreRejectedWithoutChangingExistingEntries() {
        val cache = ByteLruCache<String, Int>(10) { it }
        cache.put("existing", 4)

        assertThrows(IllegalArgumentException::class.java) { cache.put("invalid", -1) }
        assertThrows(IllegalArgumentException::class.java) { cache.put("existing", -1) }

        assertNull(cache["invalid"])
        assertEquals(4, cache["existing"])
        assertEquals(4, cache.sizeBytes)
    }

    @Test
    fun negativeTrimLimitIsRejectedWithoutChangingEntries() {
        val cache = ByteLruCache<String, Int>(10) { it }
        cache.put("existing", 4)

        assertThrows(IllegalArgumentException::class.java) { cache.trimToSize(-1) }

        assertEquals(4, cache["existing"])
        assertEquals(4, cache.sizeBytes)
    }
}
