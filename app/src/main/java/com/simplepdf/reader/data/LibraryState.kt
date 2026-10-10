package com.simplepdf.reader.data

import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

internal data class LibrarySnapshot(
    val documents: List<PdfDocument> = emptyList(),
    val unreadableDirectories: Int = 0,
    val error: Exception? = null,
    val isLoading: Boolean = false,
    val access: Boolean? = null,
)

/**
 * Entry points, observers, and dispatched completions must run on the main thread.
 * The scan and dispatch functions must outlive any individual observer.
 */
internal class LibraryState(
    private val dispatch: (() -> Unit) -> Unit,
    private val scan: (Boolean, () -> Boolean) -> ScanResult,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    var snapshot = LibrarySnapshot()
        private set

    private val observers = mutableSetOf<() -> Unit>()
    private var active: ScanRequest? = null
    private var closed = false

    fun ensureLoaded(access: Boolean) {
        check(!closed) { "Library state is closed" }
        if (snapshot.access != access) refresh(access)
    }

    fun refresh(access: Boolean) {
        check(!closed) { "Library state is closed" }
        active?.cancel()
        val request = ScanRequest()
        active = request
        snapshot = snapshot.copy(error = null, isLoading = true, access = access)
        notifyObservers()
        // An observer may close the model or start another refresh synchronously.
        if (closed || active !== request) return
        request.future = worker.submit { load(request, access) }
    }

    fun observe(listener: () -> Unit) {
        check(!closed) { "Library state is closed" }
        observers.add(listener)
        listener()
    }

    fun removeObserver(listener: () -> Unit) {
        observers.remove(listener)
    }

    fun removeObserver() {
        observers.clear()
    }

    fun close() {
        if (closed) return
        closed = true
        observers.clear()
        active?.cancel()
        active = null
        snapshot = snapshot.copy(isLoading = false)
        worker.shutdownNow()
    }

    private fun load(request: ScanRequest, access: Boolean) {
        val result = try {
            scan(access, request::isCancelled)
        } catch (exception: IOException) {
            complete(request, error = exception)
            return
        } catch (exception: SecurityException) {
            complete(request, error = exception)
            return
        } catch (exception: CancellationException) {
            complete(request, error = exception)
            return
        }
        complete(request, result = result)
    }

    private fun complete(
        request: ScanRequest,
        result: ScanResult? = null,
        error: Exception? = null,
    ) {
        dispatch {
            // Cancellation of the current scan is an error, not a stale result.
            if (closed || active !== request) return@dispatch
            active = null
            snapshot = snapshot.copy(
                documents = result?.documents?.toList() ?: snapshot.documents,
                unreadableDirectories = result?.unreadableDirectories ?: snapshot.unreadableDirectories,
                error = error,
                isLoading = false,
            )
            notifyObservers()
        }
    }

    private fun notifyObservers() {
        for (listener in observers.toList()) {
            if (closed) return
            if (listener in observers) listener()
        }
    }

    private class ScanRequest {
        private val cancelled = AtomicBoolean(false)
        var future: Future<*>? = null

        fun isCancelled(): Boolean = cancelled.get() || Thread.currentThread().isInterrupted

        fun cancel() {
            cancelled.set(true)
            future?.cancel(true)
        }
    }
}
