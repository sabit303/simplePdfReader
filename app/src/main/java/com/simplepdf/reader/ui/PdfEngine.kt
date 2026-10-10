package com.simplepdf.reader.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

internal class PdfEngine(private val worker: ExecutorService) : PdfBackend {
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private var renderer: PdfRenderer? = null
    private var pageSizes: List<PdfPageSize> = emptyList()

    override fun open(path: String, callback: (Result<List<PdfPageSize>>) -> Unit) {
        worker.execute {
            if (closed.get()) return@execute
            val result = pdfResult {
                val descriptor = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    renderer = PdfRenderer(descriptor)
                } finally {
                    if (renderer == null) descriptor.close()
                }
                val count = checkNotNull(renderer).pageCount
                require(count > 0) { "This document has no pages." }
                List(count) { index ->
                    check(!closed.get()) { "PDF was closed." }
                    checkNotNull(renderer).openPage(index).use { page ->
                        PdfPageSize(page.width, page.height)
                    }
                }.also { pageSizes = it }
            }
            main.post { if (!closed.get()) callback(result) }
        }
    }

    override fun render(index: Int, requestedWidth: Int, callback: (Result<Bitmap>) -> Unit) {
        worker.execute {
            if (closed.get() || Thread.currentThread().isInterrupted) return@execute
            val result = pdfResult {
                checkNotNull(renderer).openPage(index).use { page ->
                    val size = pageSizes[index]
                    val width = size.renderWidth(requestedWidth)
                    val height = (width * size.aspectRatio).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    var rendered = false
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        rendered = true
                    } finally {
                        if (!rendered) bitmap.recycle()
                    }
                    bitmap
                }
            }
            main.post {
                if (!closed.get()) callback(result)
                else result.getOrNull()?.recycle()
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            worker.execute {
                renderer?.close()
                renderer = null
                pageSizes = emptyList()
            }
        }
    }

    private fun <T> pdfResult(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: IOException) {
        Result.failure(error)
    } catch (error: SecurityException) {
        Result.failure(error)
    } catch (error: IllegalArgumentException) {
        Result.failure(error)
    } catch (error: IllegalStateException) {
        Result.failure(error)
    }
}
