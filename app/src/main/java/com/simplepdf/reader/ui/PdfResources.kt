package com.simplepdf.reader.ui

import android.graphics.Bitmap
import com.simplepdf.reader.data.OpenPdfTab
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal data class PdfPageKey(val path: String, val page: Int)

internal interface PdfBackend {
    fun open(path: String, callback: (Result<List<PdfPageSize>>) -> Unit)
    fun render(index: Int, requestedWidth: Int, callback: (Result<Bitmap>) -> Unit)
    fun close()
}

internal class PdfResources(
    cacheBytes: Int,
    private val maxOpenDocuments: Int = 4,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
    private val createBackend: (ExecutorService) -> PdfBackend = { PdfEngine(it) },
) {
    val cache = ByteLruCache<PdfPageKey, Bitmap>(cacheBytes) { it.allocationByteCount }
    private val documents = LinkedHashMap<String, PdfDocumentState>(16, 0.75f, true)
    private var activePath: String? = null
    private var closed = false

    init {
        require(maxOpenDocuments > 0)
    }

    fun activate(tab: OpenPdfTab): PdfDocumentState {
        check(!closed) { "Reader resources are closed." }
        val path = tab.document.path
        activePath = path
        val document = documents.getOrPut(path) {
            PdfDocumentState(path, PdfReadingPosition(tab.pageIndex), cache) { createBackend(worker) }
        }
        document.checkSource()
        if (!document.isOpen) {
            val retained = documents.values.filter { it.isOpen && it !== document }
            retained.take((retained.size - maxOpenDocuments + 1).coerceAtLeast(0))
                .forEach { it.release() }
            document.load()
        }
        return document
    }

    fun showHome() {
        activePath = null
    }

    fun invalidate(path: String) {
        documents[path]?.release()
    }

    fun invalidateAll() {
        documents.values.forEach { it.release() }
    }

    fun closeTab(path: String) {
        documents.remove(path)?.release()
        if (activePath == path) activePath = null
    }

    fun trimMemory() {
        documents.values.filter { it.path != activePath }.forEach { it.release() }
        cache.trimToSize(cache.maxBytes / 2)
    }

    fun close() {
        if (closed) return
        closed = true
        documents.values.forEach { it.release() }
        documents.clear()
        cache.clear()
        worker.shutdown()
    }

    companion object {
        fun cacheBudget(heapBytes: Long, lowMemoryDevice: Boolean): Int {
            require(heapBytes > 0)
            val cap = (if (lowMemoryDevice) 24L else 48L) * 1024 * 1024
            return minOf(heapBytes / 8, cap).coerceAtLeast(1).toInt()
        }
    }
}

internal class PdfDocumentState(
    val path: String,
    var position: PdfReadingPosition,
    val cache: ByteLruCache<PdfPageKey, Bitmap>,
    private val createBackend: () -> PdfBackend,
) {
    private data class FileStamp(val modified: Long, val size: Long)
    private var source: FileStamp? = null
    private var backend: PdfBackend? = null
    private val listeners = mutableSetOf<() -> Unit>()
    var pages: List<PdfPageSize>? = null
        private set
    var error: Throwable? = null
        private set
    var isRendering = false
        private set
    val isOpen: Boolean get() = backend != null
    val failedPages = mutableSetOf<Int>()

    fun checkSource() {
        val file = File(path)
        val stamp = FileStamp(file.lastModified(), file.length())
        if (source != null && source != stamp) release()
        source = stamp
    }

    fun load() {
        if (backend != null) return
        val opened = createBackend()
        backend = opened
        opened.open(path) { result ->
            if (backend !== opened) return@open
            result.fold(
                onSuccess = { pages = it },
                onFailure = { error = it },
            )
            notifyChanged()
        }
    }

    fun observe(listener: () -> Unit) {
        listeners.add(listener)
        listener()
    }

    fun removeObserver(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun render(index: Int, width: Int) {
        val opened = checkNotNull(backend) { "PDF is not open." }
        check(pages != null) { "PDF has not finished loading." }
        if (isRendering) return
        isRendering = true
        opened.render(index, width) { result ->
            if (backend !== opened) {
                result.getOrNull()?.recycle()
                return@render
            }
            isRendering = false
            result.fold(
                onSuccess = { cache.put(PdfPageKey(path, index), it) },
                onFailure = {
                    failedPages.add(index)
                    android.util.Log.e("PdfDocumentState", "Could not render PDF page ${index + 1}", it)
                },
            )
            notifyChanged()
        }
    }

    fun release() {
        val opened = backend
        backend = null
        opened?.close()
        pages = null
        error = null
        isRendering = false
        failedPages.clear()
        cache.removeWhere { it.path == path }
    }

    private fun notifyChanged() {
        listeners.toList().forEach { it() }
    }
}
