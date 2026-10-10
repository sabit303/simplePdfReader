package com.simplepdf.reader.ui

import android.graphics.Bitmap
import com.simplepdf.reader.data.OpenPdfTab
import com.simplepdf.reader.data.PdfDocument
import java.io.File
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfResourcesTest {
    @get:Rule
    val files = TemporaryFolder(File("."))

    private val resourcesToClose = mutableListOf<PdfResources>()
    private val backends = mutableListOf<FakeBackend>()
    private val workers = mutableListOf<ExecutorService>()
    private val pageSizes = listOf(PdfPageSize(600, 800), PdfPageSize(800, 600))

    @After
    fun closeResources() {
        resourcesToClose.asReversed().forEach { it.close() }
    }

    @Test
    fun cacheBudgetScalesDownForSmallHeapsAndCapsLargeHeaps() {
        val mib = 1024 * 1024
        assertEquals(8 * mib, PdfResources.cacheBudget(64L * mib, lowMemoryDevice = true))
        assertEquals(16 * mib, PdfResources.cacheBudget(128L * mib, lowMemoryDevice = false))
        assertEquals(32 * mib, PdfResources.cacheBudget(256L * mib, lowMemoryDevice = false))
        assertEquals(24 * mib, PdfResources.cacheBudget(512L * mib, lowMemoryDevice = true))
        assertEquals(48 * mib, PdfResources.cacheBudget(1024L * mib, lowMemoryDevice = false))
        assertThrows(IllegalArgumentException::class.java) {
            PdfResources.cacheBudget(0, lowMemoryDevice = false)
        }
    }

    @Test
    fun tabActivationsReuseModelsAndBackends() {
        val resources = resources()
        val firstTab = tab("first.pdf")
        val secondTab = tab("second.pdf")
        val first = resources.activate(firstTab)
        val second = resources.activate(secondTab)

        repeat(3) {
            assertSame(first, resources.activate(firstTab))
            assertSame(second, resources.activate(secondTab))
        }

        assertEquals(2, backends.size)
        assertEquals(listOf(firstTab.document.path), backends[0].openedPaths)
        assertEquals(listOf(secondTab.document.path), backends[1].openedPaths)
        assertTrue(backends.all { it.closeCalls == 0 })
        assertSame(pageSizes, first.pages)
        assertSame(pageSizes, second.pages)
        assertSame(workers[0], workers[1])
    }

    @Test
    fun observerDetachAndReattachRetainsPendingLoadAndModel() {
        val resources = resources(completeImmediately = false)
        val tab = tab("first.pdf", pageIndex = 1)
        val document = resources.activate(tab)
        var notifications = 0
        val observer: () -> Unit = { notifications++ }

        document.observe(observer)
        assertEquals(1, notifications)
        document.removeObserver(observer)
        backends.single().complete(Result.success(pageSizes))
        assertEquals(1, notifications)

        assertSame(document, resources.activate(tab))
        document.observe(observer)
        assertEquals(2, notifications)
        assertSame(pageSizes, document.pages)
        assertEquals(PdfReadingPosition(1), document.position)
        assertEquals(1, backends.size)
        assertEquals(0, backends.single().closeCalls)

        resources.invalidate(tab.document.path)
        resources.activate(tab)
        backends.last().complete(Result.success(pageSizes))
        assertEquals(3, notifications)
        document.removeObserver(observer)
    }

    @Test
    fun readingPositionsSurviveSwitchingTabsAndHome() {
        val resources = resources()
        val firstTab = tab("first.pdf", pageIndex = 1)
        val secondTab = tab("second.pdf")
        val first = resources.activate(firstTab)
        assertEquals(PdfReadingPosition(1), first.position)
        val firstPosition = PdfReadingPosition(1, 0.35f, 2.5f, 0.65f)
        first.position = firstPosition
        val second = resources.activate(secondTab)
        val secondPosition = PdfReadingPosition(0, 0.2f, 1.5f, 0.1f)
        second.position = secondPosition

        resources.showHome()
        assertTrue(first.isOpen)
        assertTrue(second.isOpen)
        assertSame(first, resources.activate(firstTab.copy(pageIndex = 0)))
        assertEquals(firstPosition, first.position)
        assertSame(second, resources.activate(secondTab.copy(pageIndex = 1)))
        assertEquals(secondPosition, second.position)
        assertEquals(2, backends.size)
        assertTrue(backends.all { it.closeCalls == 0 })
    }

    @Test
    fun nativeRendererLimitEvictsLeastRecentlyActivatedDocumentButRetainsItsState() {
        val resources = resources(maxOpenDocuments = 2)
        val firstTab = tab("first.pdf")
        val secondTab = tab("second.pdf")
        val thirdTab = tab("third.pdf")
        val first = resources.activate(firstTab)
        val second = resources.activate(secondTab)
        val position = PdfReadingPosition(1, 0.45f, 3f, 0.6f)
        second.position = position
        resources.activate(firstTab)

        val third = resources.activate(thirdTab)

        assertTrue(first.isOpen)
        assertFalse(second.isOpen)
        assertTrue(third.isOpen)
        assertNull(second.pages)
        assertEquals(listOf(0, 1, 0), backends.map { it.closeCalls })
        assertEquals(2, backends.count { it.closeCalls == 0 })

        assertSame(second, resources.activate(secondTab))
        assertEquals(position, second.position)
        assertFalse(first.isOpen)
        assertTrue(second.isOpen)
        assertTrue(third.isOpen)
        assertEquals(listOf(1, 1, 0, 0), backends.map { it.closeCalls })
        assertEquals(2, backends.count { it.closeCalls == 0 })
    }

    @Test
    fun allTabsUseTheSameByteBoundedCacheEvenAfterNativeEviction() {
        val resources = resources(maxOpenDocuments = 1)
        val firstTab = tab("first.pdf")
        val first = resources.activate(firstTab)
        val second = resources.activate(tab("second.pdf"))

        assertSame(resources.cache, first.cache)
        assertSame(first.cache, second.cache)
        assertEquals(1024, resources.cache.maxBytes)
        assertSame(first.cache, resources.activate(firstTab).cache)
    }

    @Test
    fun memoryTrimReleasesInactiveDocumentsAndPreservesActiveDocumentAndPositions() {
        val resources = resources()
        val firstTab = tab("first.pdf")
        val secondTab = tab("second.pdf")
        val first = resources.activate(firstTab)
        val firstPosition = PdfReadingPosition(1, 0.3f, 2f, 0.4f)
        first.position = firstPosition
        val second = resources.activate(secondTab)
        val secondPosition = PdfReadingPosition(0, 0.6f, 3f, 0.8f)
        second.position = secondPosition

        resources.trimMemory()
        resources.trimMemory()

        assertFalse(first.isOpen)
        assertNull(first.pages)
        assertTrue(second.isOpen)
        assertSame(pageSizes, second.pages)
        assertEquals(firstPosition, first.position)
        assertEquals(secondPosition, second.position)
        assertEquals(listOf(1, 0), backends.map { it.closeCalls })
        assertSame(second, resources.activate(secondTab))
        assertEquals(2, backends.size)
        assertSame(first, resources.activate(firstTab))
        assertEquals(firstPosition, first.position)
        assertEquals(3, backends.size)
    }

    @Test
    fun memoryTrimAtHomeReleasesEveryBackendButRetainsModelsAndPositions() {
        val resources = resources()
        val firstTab = tab("first.pdf")
        val secondTab = tab("second.pdf")
        val first = resources.activate(firstTab)
        val second = resources.activate(secondTab)
        val position = PdfReadingPosition(1, 0.25f, 2f, 0.75f)
        second.position = position

        resources.showHome()
        resources.trimMemory()

        assertFalse(first.isOpen)
        assertFalse(second.isOpen)
        assertEquals(listOf(1, 1), backends.map { it.closeCalls })
        assertSame(second, resources.activate(secondTab))
        assertEquals(position, second.position)
        assertSame(first, resources.activate(firstTab))
    }

    @Test
    fun closingATabDiscardsItsStateWithoutReleasingTheActiveNeighbor() {
        val resources = resources()
        val firstTab = tab("first.pdf", pageIndex = 1)
        val first = resources.activate(firstTab)
        first.position = PdfReadingPosition(0, 0.8f, 4f, 0.5f)
        val second = resources.activate(tab("second.pdf"))

        resources.closeTab(firstTab.document.path)
        resources.closeTab(firstTab.document.path)
        resources.trimMemory()

        assertFalse(first.isOpen)
        assertNull(first.pages)
        assertTrue(second.isOpen)
        assertEquals(listOf(1, 0), backends.map { it.closeCalls })

        val reopened = resources.activate(firstTab)
        assertNotSame(first, reopened)
        assertEquals(PdfReadingPosition(1), reopened.position)
        assertEquals(3, backends.size)
    }

    @Test
    fun closingTheActiveTabDiscardsItsState() {
        val resources = resources()
        val tab = tab("first.pdf")
        val original = resources.activate(tab)
        original.position = PdfReadingPosition(1, 0.5f, 2f, 0.5f)

        resources.closeTab(tab.document.path)

        assertFalse(original.isOpen)
        assertEquals(1, backends.single().closeCalls)
        val reopened = resources.activate(tab)
        assertNotSame(original, reopened)
        assertEquals(PdfReadingPosition(0), reopened.position)
    }

    @Test
    fun closingResourcesIsIdempotentAndShutsDownTheSharedWorker() {
        val resources = resources()
        val tab = tab("first.pdf")
        val first = resources.activate(tab)
        val second = resources.activate(tab("second.pdf"))
        val worker = workers.first()
        assertFalse(worker.isShutdown)

        resources.close()
        resources.close()

        assertFalse(first.isOpen)
        assertFalse(second.isOpen)
        assertNull(first.pages)
        assertNull(second.pages)
        assertEquals(listOf(1, 1), backends.map { it.closeCalls })
        assertEquals(0, resources.cache.sizeBytes)
        assertTrue(worker.isShutdown)
        assertThrows(IllegalStateException::class.java) { resources.activate(tab) }
    }

    @Test
    fun newResourcesAfterCloseStartWithNewModelBackendCacheAndInitialPosition() {
        val originalResources = resources()
        val tab = tab("first.pdf", pageIndex = 1)
        val original = originalResources.activate(tab)
        original.position = PdfReadingPosition(0, 0.7f, 4f, 0.8f)
        originalResources.close()

        val restartedResources = resources()
        val restarted = restartedResources.activate(tab)

        assertNotSame(original, restarted)
        assertNotSame(originalResources.cache, restartedResources.cache)
        assertNotSame(backends[0], backends[1])
        assertNotSame(workers[0], workers[1])
        assertEquals(PdfReadingPosition(1), restarted.position)
        assertSame(pageSizes, restarted.pages)
        assertEquals(0, restartedResources.cache.sizeBytes)
        assertEquals(listOf(1, 0), backends.map { it.closeCalls })
    }

    @Test
    fun changingFileSizeInvalidatesBackendEvenWhenModifiedTimeIsUnchanged() {
        val resources = resources()
        val tab = tab("first.pdf")
        val file = File(tab.document.path)
        val originalModified = file.lastModified()
        val document = resources.activate(tab)
        val position = PdfReadingPosition(1, 0.4f, 2f, 0.2f)
        document.position = position

        file.appendText("changed source")
        assertTrue(file.setLastModified(originalModified))
        assertEquals(originalModified, file.lastModified())
        assertSame(document, resources.activate(tab))

        assertEquals(2, backends.size)
        assertEquals(listOf(1, 0), backends.map { it.closeCalls })
        assertEquals(position, document.position)
        assertSame(pageSizes, document.pages)
        resources.activate(tab)
        assertEquals(2, backends.size)
    }

    @Test
    fun changingModifiedTimeInvalidatesBackendEvenWhenFileSizeIsUnchanged() {
        val resources = resources()
        val tab = tab("first.pdf")
        val file = File(tab.document.path)
        val originalSize = file.length()
        val document = resources.activate(tab)
        val position = PdfReadingPosition(1, 0.6f, 3f, 0.3f)
        document.position = position

        assertTrue(file.setLastModified(file.lastModified() + 10_000L))
        assertEquals(originalSize, file.length())
        assertSame(document, resources.activate(tab))

        assertEquals(2, backends.size)
        assertEquals(listOf(1, 0), backends.map { it.closeCalls })
        assertEquals(position, document.position)
    }

    @Test
    fun loadingErrorIsRetainedUntilExplicitInvalidationAndRetry() {
        val resources = resources(completeImmediately = false)
        val tab = tab("first.pdf")
        val document = resources.activate(tab)
        val position = PdfReadingPosition(1, 0.3f, 2f, 0.7f)
        document.position = position
        val failure = IllegalStateException("Cannot open PDF")
        backends.single().complete(Result.failure(failure))

        assertSame(failure, document.error)
        assertNull(document.pages)
        assertSame(document, resources.activate(tab))
        assertEquals(1, backends.size)
        assertSame(failure, document.error)

        resources.invalidate(tab.document.path)
        assertFalse(document.isOpen)
        assertNull(document.error)
        assertNull(document.pages)
        assertEquals(1, backends.first().closeCalls)
        assertSame(document, resources.activate(tab))
        assertEquals(2, backends.size)
        backends.last().complete(Result.success(pageSizes))

        assertTrue(document.isOpen)
        assertNull(document.error)
        assertSame(pageSizes, document.pages)
        assertEquals(position, document.position)
    }

    @Test
    fun invalidatingAllDocumentsRetainsTheirModelsAndPositionsForReload() {
        val resources = resources()
        val firstTab = tab("first.pdf")
        val secondTab = tab("second.pdf")
        val first = resources.activate(firstTab)
        val second = resources.activate(secondTab)
        val position = PdfReadingPosition(1, 0.5f, 2f, 0.9f)
        first.position = position
        second.position = position

        resources.invalidateAll()

        assertFalse(first.isOpen)
        assertFalse(second.isOpen)
        assertNull(first.pages)
        assertNull(second.pages)
        assertEquals(listOf(1, 1), backends.map { it.closeCalls })
        assertSame(first, resources.activate(firstTab))
        assertSame(second, resources.activate(secondTab))
        assertEquals(position, first.position)
        assertEquals(position, second.position)
        assertEquals(4, backends.size)
    }

    @Test
    fun lateSuccessfulLoadFromEvictedBackendCannotReplaceNewPages() {
        assertLateCompletionIgnored(Result.success(listOf(PdfPageSize(100, 200))))
    }

    @Test
    fun lateFailedLoadFromEvictedBackendCannotSetErrorOnReloadedDocument() {
        assertLateCompletionIgnored(Result.failure(IllegalStateException("Stale load")))
    }

    private fun assertLateCompletionIgnored(result: Result<List<PdfPageSize>>) {
        val resources = resources(maxOpenDocuments = 1, completeImmediately = false)
        val tab = tab("first.pdf")
        val document = resources.activate(tab)
        val oldBackend = backends.single()
        var notifications = 0
        val observer: () -> Unit = { notifications++ }
        document.observe(observer)
        resources.activate(tab("second.pdf"))
        assertEquals(1, oldBackend.closeCalls)
        assertFalse(document.isOpen)

        assertSame(document, resources.activate(tab))
        backends.last().complete(Result.success(pageSizes))
        assertEquals(2, notifications)
        oldBackend.complete(result)

        assertSame(pageSizes, document.pages)
        assertNull(document.error)
        assertTrue(document.isOpen)
        assertEquals(2, notifications)
        assertEquals(3, backends.size)
        document.removeObserver(observer)
    }

    private fun resources(
        maxOpenDocuments: Int = 4,
        completeImmediately: Boolean = true,
    ): PdfResources = PdfResources(
        cacheBytes = 1024,
        maxOpenDocuments = maxOpenDocuments,
        createBackend = { worker ->
            workers.add(worker)
            FakeBackend(if (completeImmediately) pageSizes else null).also { backends.add(it) }
        },
    ).also { resourcesToClose.add(it) }

    private fun tab(name: String, pageIndex: Int = 0): OpenPdfTab {
        val file = files.newFile(name)
        return OpenPdfTab(
            PdfDocument(file.absolutePath, name, file.lastModified(), file.length()),
            pageIndex,
        )
    }

    private class FakeBackend(private val initialPages: List<PdfPageSize>?) : PdfBackend {
        val openedPaths = mutableListOf<String>()
        var closeCalls = 0
            private set
        private var callback: ((Result<List<PdfPageSize>>) -> Unit)? = null

        override fun open(path: String, callback: (Result<List<PdfPageSize>>) -> Unit) {
            openedPaths.add(path)
            this.callback = callback
            initialPages?.let { complete(Result.success(it)) }
        }

        fun complete(result: Result<List<PdfPageSize>>) {
            checkNotNull(callback)(result)
        }

        override fun render(index: Int, requestedWidth: Int, callback: (Result<Bitmap>) -> Unit) {
            throw UnsupportedOperationException("Bitmap rendering is not part of JVM resource tests")
        }

        override fun close() {
            closeCalls++
        }
    }
}
