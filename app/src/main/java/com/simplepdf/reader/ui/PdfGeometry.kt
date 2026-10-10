package com.simplepdf.reader.ui

import kotlin.math.sqrt

internal data class PdfPageSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0)
    }

    val aspectRatio: Float get() = height.toFloat() / width

    fun renderWidth(requestedWidth: Int): Int =
        minOf(requestedWidth.toDouble(), 2560.0, sqrt(6_000_000.0 / aspectRatio))
            .toInt().coerceAtLeast(1)
}

internal class PdfGeometry(
    val pages: List<PdfPageSize>,
    val viewportWidth: Float,
    val margin: Float,
    gap: Float,
) {
    val pageWidth = (viewportWidth - margin * 2).coerceAtLeast(1f)
    val tops = FloatArray(pages.size)
    val heights = FloatArray(pages.size)
    val totalHeight: Float

    init {
        require(pages.isNotEmpty())
        var top = margin
        pages.forEachIndexed { index, page ->
            tops[index] = top
            heights[index] = pageWidth * page.aspectRatio
            top += heights[index] + gap
        }
        totalHeight = top - gap + margin
    }

    fun pageAt(y: Float): Int {
        var low = 0
        var high = pages.lastIndex
        while (low < high) {
            val middle = (low + high) / 2
            if (tops[middle] + heights[middle] <= y) low = middle + 1 else high = middle
        }
        return low
    }
}

internal class PdfTransform {
    var scale = 1f
        private set
    var x = 0f
        private set
    var y = 0f
        private set
    private var viewportWidth = 0f
    private var viewportHeight = 0f
    private var documentHeight = 0f
    val maxX: Float get() = (viewportWidth * (scale - 1f)).coerceAtLeast(0f)
    val maxY: Float get() = (documentHeight * scale - viewportHeight).coerceAtLeast(0f)

    fun setBounds(width: Float, height: Float, contentHeight: Float) {
        viewportWidth = width
        viewportHeight = height
        documentHeight = contentHeight
        moveTo(x, y)
    }

    fun moveTo(newX: Float, newY: Float) {
        x = newX.coerceIn(0f, maxX)
        y = newY.coerceIn(0f, maxY)
    }

    fun zoomTo(newScale: Float, focusX: Float, focusY: Float) {
        val clamped = newScale.coerceIn(1f, 5f)
        val factor = clamped / scale
        scale = clamped
        moveTo((x + focusX) * factor - focusX, (y + focusY) * factor - focusY)
    }

    fun capture(layout: PdfGeometry): PdfReadingPosition {
        val page = layout.pageAt(y / scale)
        return PdfReadingPosition(
            pageIndex = page,
            pageOffset = (y / scale - layout.tops[page]) / layout.heights[page],
            zoom = scale,
            horizontalOffset = if (maxX == 0f) 0f else x / maxX,
        )
    }

    fun restore(layout: PdfGeometry, height: Float, position: PdfReadingPosition) {
        setBounds(layout.viewportWidth, height, layout.totalHeight)
        zoomTo(position.zoom, 0f, 0f)
        val page = position.pageIndex.coerceIn(layout.pages.indices)
        moveTo(
            maxX * position.horizontalOffset,
            (layout.tops[page] + layout.heights[page] * position.pageOffset) * scale,
        )
    }
}

internal data class PdfReadingPosition(
    val pageIndex: Int,
    val pageOffset: Float = 0f,
    val zoom: Float = 1f,
    val horizontalOffset: Float = 0f,
) {
    init {
        require(pageIndex >= 0)
        require(pageOffset.isFinite())
        require(zoom in 1f..5f)
        require(horizontalOffset in 0f..1f)
    }
}
