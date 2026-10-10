package com.simplepdf.reader.data

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryStateTest {
    private val first = PdfDocument("/storage/first.pdf", "first.pdf", 1, 10)
    private val second = PdfDocument("/storage/second.pdf", "second.pdf", 2, 20)
    private val states = mutableListOf<LibraryState>()

    @After
    fun closeStates() {
        states.forEach { it.close() }
    }

    @Test
    fun initialSnapshotIsIdleAndObservationIsImmediate() {
        val harness = harness()
        val observed = mutableListOf<LibrarySnapshot>()

        harness.state.observe { observed.add(harness.state.snapshot) }

        assertEquals(LibrarySnapshot(), harness.state.snapshot)
        assertEquals(listOf(LibrarySnapshot()), observed)
        assertEquals(0, harness.worker.pending)
    }

    @Test
    fun loadingAndCompletionArePublishedButWorkerDoesNotMutateSnapshot() {
        val harness = harness()
        val observed = mutableListOf<LibrarySnapshot>()
        harness.state.observe { observed.add(harness.state.snapshot) }

        harness.state.ensureLoaded(true)
        assertEquals(LibrarySnapshot(isLoading = true, access = true), harness.state.snapshot)
        assertEquals(2, observed.size)
        harness.worker.runNext()

        assertTrue(harness.state.snapshot.isLoading)
        assertEquals(2, observed.size)
        harness.dispatch.runNext()
        assertEquals(LibrarySnapshot(listOf(first), 2, access = true), harness.state.snapshot)
        assertEquals(3, observed.size)
    }

    @Test
    fun observerRecreationRetainsSuccessfulResultsWithoutRescanning() {
        val harness = harness()
        var oldCalls = 0
        val oldObserver: () -> Unit = { oldCalls++ }
        harness.state.observe(oldObserver)
        harness.state.ensureLoaded(true)
        harness.completeScan()
        harness.state.removeObserver(oldObserver)
        val observed = mutableListOf<LibrarySnapshot>()

        harness.state.observe { observed.add(harness.state.snapshot) }
        harness.state.ensureLoaded(true)
        harness.state.ensureLoaded(true)

        assertEquals(listOf(harness.state.snapshot), observed)
        assertEquals(listOf(first), observed.single().documents)
        assertEquals(2, observed.single().unreadableDirectories)
        assertEquals(listOf(true), harness.scans)
        assertEquals(0, harness.worker.pending)
        assertEquals(3, oldCalls)
    }

    @Test
    fun observerRecreationRetainsQueuedAndInFlightWork() {
        val harness = harness()
        var originalCalls = 0
        var middleCalls = 0
        val latest = mutableListOf<LibrarySnapshot>()
        harness.state.observe { originalCalls++ }
        harness.state.ensureLoaded(false)
        harness.state.removeObserver()
        harness.state.observe { middleCalls++ }
        harness.state.ensureLoaded(false)
        assertEquals(1, harness.worker.pending)

        harness.worker.runNext()
        harness.state.removeObserver()
        harness.state.observe { latest.add(harness.state.snapshot) }
        harness.state.ensureLoaded(false)
        assertEquals(0, harness.worker.pending)
        assertTrue(latest.single().isLoading)

        harness.dispatch.runNext()
        assertEquals(2, originalCalls)
        assertEquals(1, middleCalls)
        assertEquals(2, latest.size)
        assertFalse(latest.last().isLoading)
        assertEquals(listOf(false), harness.scans)
    }

    @Test
    fun observerRecreationKeepsActiveScanAndCompletionWhileDetached() {
        val dispatch = DispatchQueue()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        val sawCancellation = AtomicBoolean()
        val state = LibraryState(
            dispatch = dispatch::post,
            scan = { _, isCancelled ->
                attempts.incrementAndGet()
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                sawCancellation.set(isCancelled())
                ScanResult(listOf(first), 2)
            },
        ).also { states.add(it) }
        var originalCalls = 0
        var replacementCalls = 0
        val original: () -> Unit = { originalCalls++ }
        val replacement: () -> Unit = { replacementCalls++ }
        state.observe(original)

        try {
            state.ensureLoaded(false)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            state.removeObserver(original)
            state.observe(replacement)
            state.ensureLoaded(false)
            assertTrue(state.snapshot.isLoading)
            state.removeObserver(replacement)
            release.countDown()
            dispatch.awaitNext()
            var retained: LibrarySnapshot? = null
            state.observe { retained = state.snapshot }
            state.ensureLoaded(false)

            assertEquals(LibrarySnapshot(listOf(first), 2, access = false), retained)
            assertEquals(1, attempts.get())
            assertFalse(sawCancellation.get())
            assertEquals(2, originalCalls)
            assertEquals(1, replacementCalls)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun emptySuccessfulLibraryIsStillLoaded() {
        val harness = harness { _, _ -> ScanResult(emptyList(), 0) }
        harness.state.ensureLoaded(false)
        harness.completeScan()
        harness.state.removeObserver()
        harness.state.observe {}
        harness.state.ensureLoaded(false)

        assertEquals(LibrarySnapshot(access = false), harness.state.snapshot)
        assertEquals(0, harness.worker.pending)
        assertEquals(listOf(false), harness.scans)
    }

    @Test
    fun explicitRefreshRetainsDocumentsAndUnreadableCountUntilReplacement() {
        var attempts = 0
        val harness = harness { _, _ ->
            if (++attempts == 1) ScanResult(listOf(first), 2) else ScanResult(listOf(second), 0)
        }
        harness.state.ensureLoaded(true)
        harness.completeScan()

        harness.state.refresh(true)
        assertEquals(LibrarySnapshot(listOf(first), 2, isLoading = true, access = true), harness.state.snapshot)
        harness.completeScan()

        assertEquals(LibrarySnapshot(listOf(second), 0, access = true), harness.state.snapshot)
        assertEquals(listOf(true, true), harness.scans)
    }

    @Test
    fun permissionChangesStartFreshScansInBothDirections() {
        val harness = harness { access, _ ->
            ScanResult(if (access) listOf(first, second) else listOf(first), if (access) 2 else 1)
        }
        harness.state.ensureLoaded(false)
        harness.completeScan()
        harness.state.ensureLoaded(true)
        assertEquals(listOf(first), harness.state.snapshot.documents)
        assertEquals(true, harness.state.snapshot.access)
        harness.completeScan()
        harness.state.ensureLoaded(false)
        assertEquals(listOf(first, second), harness.state.snapshot.documents)
        assertEquals(false, harness.state.snapshot.access)
        harness.completeScan()

        assertEquals(listOf(false, true, false), harness.scans)
        assertEquals(LibrarySnapshot(listOf(first), 1, access = false), harness.state.snapshot)
    }

    @Test
    fun permissionChangeSupersedesWorkThatHasNotStarted() {
        val harness = harness()
        harness.state.ensureLoaded(false)
        harness.state.ensureLoaded(true)
        harness.worker.runNext()

        assertEquals(emptyList<Boolean>(), harness.scans)
        assertEquals(0, harness.dispatch.pending)
        harness.completeScan()
        assertEquals(listOf(true), harness.scans)
        assertEquals(true, harness.state.snapshot.access)
    }

    @Test
    fun supersededSuccessCannotOverwriteNewerCompletedRefresh() {
        var attempts = 0
        val harness = harness { _, _ ->
            if (++attempts == 1) ScanResult(listOf(first), 2) else ScanResult(listOf(second), 0)
        }
        val observed = mutableListOf<LibrarySnapshot>()
        harness.state.observe { observed.add(harness.state.snapshot) }
        harness.state.ensureLoaded(true)
        harness.worker.runNext()
        harness.state.refresh(true)
        harness.worker.runNext()

        harness.dispatch.runLast()
        val latest = harness.state.snapshot
        val notifications = observed.size
        harness.dispatch.runNext()

        assertEquals(LibrarySnapshot(listOf(second), 0, access = true), latest)
        assertSame(latest, harness.state.snapshot)
        assertEquals(notifications, observed.size)
    }

    @Test
    fun supersededFailuresCannotFinishLoadingOrOverwriteNewerSuccess() {
        for (error in listOf(IOException("io"), SecurityException("denied"), CancellationException("cancelled"))) {
            for (newestFirst in listOf(false, true)) {
                var attempts = 0
                val harness = harness { _, _ ->
                    if (++attempts == 1) throw error
                    ScanResult(listOf(second), 1)
                }
                val observed = mutableListOf<LibrarySnapshot>()
                harness.state.observe { observed.add(harness.state.snapshot) }
                harness.state.ensureLoaded(false)
                harness.worker.runNext()
                harness.state.refresh(true)
                harness.worker.runNext()
                if (newestFirst) harness.dispatch.runLast()
                val beforeStale = harness.state.snapshot
                val notifications = observed.size

                harness.dispatch.runNext()

                assertSame(beforeStale, harness.state.snapshot)
                assertEquals(notifications, observed.size)
                if (!newestFirst) {
                    assertTrue(harness.state.snapshot.isLoading)
                    harness.dispatch.runNext()
                }
                assertEquals(LibrarySnapshot(listOf(second), 1, access = true), harness.state.snapshot)
            }
        }
    }

    @Test
    fun ioErrorIsRetainedAcrossObserverRecreationWithoutAutomaticRetry() {
        val error = IOException("Storage unavailable")
        val harness = harness { _, _ -> throw error }
        harness.state.ensureLoaded(true)
        harness.completeScan()
        assertSame(error, harness.state.snapshot.error)
        assertFalse(harness.state.snapshot.isLoading)
        harness.state.removeObserver()
        var observedError: Exception? = null

        harness.state.observe { observedError = harness.state.snapshot.error }
        harness.state.ensureLoaded(true)

        assertSame(error, observedError)
        assertEquals(listOf(true), harness.scans)
        assertEquals(0, harness.worker.pending)
    }

    @Test
    fun expectedFailuresPreserveDocumentsAndCanRecoverWithExplicitRefresh() {
        for (error in listOf(IOException("io"), SecurityException("denied"), CancellationException("cancelled"))) {
            var attempts = 0
            val harness = harness { _, _ ->
                when (++attempts) {
                    1 -> ScanResult(listOf(first), 3)
                    2 -> throw error
                    else -> ScanResult(listOf(second), 0)
                }
            }
            harness.state.ensureLoaded(true)
            harness.completeScan()
            harness.state.refresh(true)
            harness.completeScan()

            assertEquals(LibrarySnapshot(listOf(first), 3, error, access = true), harness.state.snapshot)
            harness.state.ensureLoaded(true)
            assertEquals(0, harness.worker.pending)
            harness.state.refresh(true)
            assertEquals(LibrarySnapshot(listOf(first), 3, isLoading = true, access = true), harness.state.snapshot)
            harness.completeScan()
            assertEquals(LibrarySnapshot(listOf(second), 0, access = true), harness.state.snapshot)
        }
    }

    @Test
    fun permissionChangeRetriesAnErrorWithoutExplicitRefresh() {
        val error = SecurityException("Permission denied")
        val harness = harness { access, _ ->
            if (!access) throw error
            ScanResult(listOf(first), 0)
        }
        harness.state.ensureLoaded(false)
        harness.completeScan()
        assertSame(error, harness.state.snapshot.error)

        harness.state.ensureLoaded(true)
        assertNull(harness.state.snapshot.error)
        harness.completeScan()

        assertEquals(listOf(false, true), harness.scans)
        assertEquals(LibrarySnapshot(listOf(first), 0, access = true), harness.state.snapshot)
    }

    @Test
    fun activeWorkerInterruptionIsPublishedAsCancellationAndCanRecover() {
        val dispatch = DispatchQueue()
        val error = CancellationException("Interrupted scan")
        val attempts = AtomicInteger()
        val state = LibraryState(
            dispatch = dispatch::post,
            scan = { _, isCancelled ->
                if (attempts.incrementAndGet() == 1) {
                    Thread.currentThread().interrupt()
                    try {
                        assertTrue(isCancelled())
                        throw error
                    } finally {
                        Thread.interrupted()
                    }
                }
                ScanResult(listOf(second), 0)
            },
        ).also { states.add(it) }
        val mainThread = Thread.currentThread()
        state.observe { assertSame(mainThread, Thread.currentThread()) }

        state.ensureLoaded(true)
        dispatch.awaitNext()
        assertSame(error, state.snapshot.error)
        assertFalse(state.snapshot.isLoading)
        state.refresh(true)
        dispatch.awaitNext()

        assertEquals(LibrarySnapshot(listOf(second), 0, access = true), state.snapshot)
    }

    @Test
    fun refreshingActiveWorkerCancelsAcrossThreadsEvenAfterInterruptIsCleared() {
        val dispatch = DispatchQueue()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sawCancellation = AtomicBoolean()
        val attempts = AtomicInteger()
        val state = LibraryState(
            dispatch = dispatch::post,
            scan = { _, isCancelled ->
                if (attempts.incrementAndGet() == 1) {
                    assertFalse(isCancelled())
                    entered.countDown()
                    try {
                        release.await(5, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        // InterruptedException clears the flag; the request token must survive.
                    }
                    sawCancellation.set(isCancelled())
                    throw CancellationException("Superseded")
                }
                ScanResult(listOf(second), 0)
            },
        ).also { states.add(it) }

        try {
            state.ensureLoaded(false)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            state.refresh(true)
            dispatch.awaitNext()
            assertTrue(sawCancellation.get())
            assertTrue(state.snapshot.isLoading)
            assertNull(state.snapshot.error)
            dispatch.awaitNext()
            assertEquals(LibrarySnapshot(listOf(second), 0, access = true), state.snapshot)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun removedObserversDoNotReceiveAlreadyQueuedCompletion() {
        val harness = harness()
        var removedCalls = 0
        var retainedCalls = 0
        val removed: () -> Unit = { removedCalls++ }
        harness.state.observe(removed)
        harness.state.observe { retainedCalls++ }
        harness.state.ensureLoaded(true)
        harness.worker.runNext()

        harness.state.removeObserver(removed)
        harness.dispatch.runNext()
        harness.state.removeObserver(removed)

        assertEquals(2, removedCalls)
        assertEquals(3, retainedCalls)
        assertEquals(listOf(first), harness.state.snapshot.documents)
    }

    @Test
    fun observerCanRemoveAnotherObserverDuringNotification() {
        val harness = harness()
        var otherCalls = 0
        val other: () -> Unit = { otherCalls++ }
        harness.state.observe {
            if (harness.state.snapshot.isLoading) harness.state.removeObserver(other)
        }
        harness.state.observe(other)

        harness.state.ensureLoaded(true)
        harness.completeScan()

        assertEquals(1, otherCalls)
    }

    @Test
    fun observerCanSupersedeRefreshDuringLoadingNotification() {
        val harness = harness()
        harness.state.observe {
            if (harness.state.snapshot.access == false) harness.state.refresh(true)
        }

        harness.state.ensureLoaded(false)
        assertEquals(1, harness.worker.pending)
        harness.completeScan()

        assertEquals(listOf(true), harness.scans)
        assertEquals(true, harness.state.snapshot.access)
    }

    @Test
    fun observerCanCloseDuringLoadingNotificationWithoutSubmittingWork() {
        val harness = harness()
        harness.state.observe {
            if (harness.state.snapshot.isLoading) harness.state.close()
        }

        harness.state.ensureLoaded(true)

        assertTrue(harness.worker.isShutdown)
        assertEquals(0, harness.worker.pending)
        assertFalse(harness.state.snapshot.isLoading)
    }

    @Test
    fun closeIsIdempotentCancelsPendingWorkAndRejectsNewLoadsAndObservers() {
        val harness = harness()
        var calls = 0
        harness.state.observe { calls++ }
        harness.state.ensureLoaded(true)

        harness.state.close()
        harness.state.close()
        harness.state.removeObserver()

        assertEquals(1, harness.worker.shutdownCalls)
        assertTrue(harness.worker.isShutdown)
        assertEquals(0, harness.worker.pending)
        assertEquals(emptyList<Boolean>(), harness.scans)
        assertFalse(harness.state.snapshot.isLoading)
        assertEquals(2, calls)
        assertThrows(IllegalStateException::class.java) { harness.state.ensureLoaded(true) }
        assertThrows(IllegalStateException::class.java) { harness.state.ensureLoaded(false) }
        assertThrows(IllegalStateException::class.java) { harness.state.refresh(true) }
        assertThrows(IllegalStateException::class.java) { harness.state.observe { calls++ } }
        assertEquals(2, calls)
    }

    @Test
    fun closeIgnoresAlreadyQueuedSuccessAndFailureCompletions() {
        for (error in listOf(null, IOException("io"), SecurityException("denied"), CancellationException("cancelled"))) {
            val harness = harness { _, _ ->
                if (error != null) throw error
                ScanResult(listOf(first), 2)
            }
            var calls = 0
            harness.state.observe { calls++ }
            harness.state.ensureLoaded(true)
            harness.worker.runNext()
            harness.state.close()
            val closedSnapshot = harness.state.snapshot

            harness.dispatch.runNext()

            assertSame(closedSnapshot, harness.state.snapshot)
            assertEquals(2, calls)
        }
    }

    @Test
    fun closeCancelsAnActiveScanAndIgnoresItsLateResult() {
        val dispatch = DispatchQueue()
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sawCancellation = AtomicBoolean()
        val state = LibraryState(
            dispatch = dispatch::post,
            scan = { _, isCancelled ->
                entered.countDown()
                try {
                    release.await(5, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    // Verify cancellation independently of the cleared interrupt flag.
                }
                sawCancellation.set(isCancelled())
                ScanResult(listOf(first), 2)
            },
            worker = worker,
        ).also { states.add(it) }
        var calls = 0
        state.observe { calls++ }

        try {
            state.ensureLoaded(true)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            state.close()
            val closedSnapshot = state.snapshot
            dispatch.awaitNext()

            assertTrue(sawCancellation.get())
            assertTrue(worker.isShutdown)
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
            assertSame(closedSnapshot, state.snapshot)
            assertEquals(2, calls)
        } finally {
            release.countDown()
        }
    }

    private fun harness(
        scan: (Boolean, () -> Boolean) -> ScanResult = { _, _ -> ScanResult(listOf(first), 2) },
    ): Harness = Harness(scan).also { states.add(it.state) }

    private class Harness(scan: (Boolean, () -> Boolean) -> ScanResult) {
        val worker = QueuedExecutor()
        val dispatch = DispatchQueue()
        val scans = mutableListOf<Boolean>()
        val state = LibraryState(
            dispatch = dispatch::post,
            scan = { access, isCancelled ->
                scans.add(access)
                scan(access, isCancelled)
            },
            worker = worker,
        )

        fun completeScan() {
            worker.runNext()
            dispatch.runNext()
        }
    }

    private class DispatchQueue {
        private val callbacks = LinkedBlockingDeque<() -> Unit>()
        val pending: Int get() = callbacks.size

        fun post(callback: () -> Unit) {
            callbacks.addLast(callback)
        }

        fun runNext() {
            callbacks.removeFirst().invoke()
        }

        fun runLast() {
            callbacks.removeLast().invoke()
        }

        fun awaitNext() {
            val callback = callbacks.pollFirst(5, TimeUnit.SECONDS)
            checkNotNull(callback) { "Worker did not dispatch a completion" }
            callback()
        }
    }

    private class QueuedExecutor : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var stopped = false
        var shutdownCalls = 0
            private set
        val pending: Int get() = tasks.size

        fun runNext() {
            tasks.removeFirst().run()
        }

        override fun execute(command: Runnable) {
            if (stopped) throw RejectedExecutionException("Executor is closed")
            tasks.addLast(command)
        }

        override fun shutdown() {
            stopped = true
            shutdownCalls++
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown()
            val remaining = tasks.toMutableList()
            tasks.clear()
            return remaining
        }

        override fun isShutdown(): Boolean = stopped

        override fun isTerminated(): Boolean = stopped && tasks.isEmpty()

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
    }
}
