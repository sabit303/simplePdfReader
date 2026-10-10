package com.simplepdf.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfGeometryTest {
    @Test
    fun mixedPageSizesHaveStableOffsetsBeforeRendering() {
        val geometry = PdfGeometry(
            listOf(PdfPageSize(400, 600), PdfPageSize(600, 400), PdfPageSize(400, 1200)),
            420f, 10f, 12f,
        )
        assertEquals(400f, geometry.pageWidth, 0.01f)
        assertEquals(10f, geometry.tops[0], 0.01f)
        assertEquals(622f, geometry.tops[1], 0.01f)
        assertEquals(900.6667f, geometry.tops[2], 0.01f)
        assertEquals(2110.6667f, geometry.totalHeight, 0.01f)
        repeat(100) {
            assertEquals(1, geometry.pageAt(700f))
            assertEquals(2, geometry.pageAt(2000f))
        }
        assertEquals(0, geometry.pageAt(-1f))
        assertEquals(2, geometry.pageAt(Float.MAX_VALUE))
    }

    @Test
    fun pinchKeepsDocumentPointUnderFingers() {
        val transform = PdfTransform()
        transform.setBounds(400f, 800f, 4000f)
        transform.moveTo(0f, 500f)
        val documentX = (transform.x + 150f) / transform.scale
        val documentY = (transform.y + 250f) / transform.scale
        transform.zoomTo(2.5f, 150f, 250f)
        assertEquals(documentX, (transform.x + 150f) / transform.scale, 0.01f)
        assertEquals(documentY, (transform.y + 250f) / transform.scale, 0.01f)
        transform.zoomTo(1f, 150f, 250f)
        assertEquals(0f, transform.x, 0.01f)
        assertEquals(500f, transform.y, 0.01f)
    }

    @Test
    fun zoomAndPanCannotLeaveDocumentBounds() {
        val transform = PdfTransform()
        transform.setBounds(400f, 800f, 2000f)
        transform.zoomTo(20f, 200f, 400f)
        assertEquals(5f, transform.scale, 0f)
        transform.moveTo(Float.MAX_VALUE, Float.MAX_VALUE)
        assertEquals(1600f, transform.x, 0f)
        assertEquals(9200f, transform.y, 0f)
        transform.zoomTo(0.1f, 200f, 400f)
        assertEquals(1f, transform.scale, 0f)
        assertEquals(0f, transform.x, 0f)
        assertTrue(transform.y <= 1200f)
        transform.moveTo(-100f, -100f)
        assertEquals(0f, transform.y, 0f)
    }

    @Test
    fun shortDocumentsDoNotScrollPastTheirEdges() {
        val transform = PdfTransform()
        transform.setBounds(400f, 800f, 300f)
        transform.moveTo(500f, 500f)
        assertEquals(0f, transform.maxY, 0f)
        assertEquals(0f, transform.y, 0f)
    }

    @Test
    fun renderingIsBoundedEvenAtMaximumZoomOnTallPages() {
        val tall = PdfPageSize(100, 10_000)
        val width = tall.renderWidth(50_000)
        assertTrue(width.toDouble() * width * tall.aspectRatio <= 6_000_000)
        assertEquals(2000, PdfPageSize(400, 600).renderWidth(9000))
        assertEquals(2560, PdfPageSize(600, 400).renderWidth(9000))
        assertEquals(400, PdfPageSize(400, 600).renderWidth(400))
    }
}
