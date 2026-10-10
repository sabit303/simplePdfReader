package com.simplepdf.reader

import android.graphics.Bitmap
import androidx.test.runner.AndroidJUnit4
import com.simplepdf.reader.ui.ByteLruCache
import com.simplepdf.reader.ui.PdfPageKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfCacheTest {
    @Test
    fun renderedPagesAcrossTabsShareOneByteBudget() {
        val first = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val third = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val cache = ByteLruCache<PdfPageKey, Bitmap>(first.allocationByteCount * 2) { it.allocationByteCount }
        try {
            cache.put(PdfPageKey("first.pdf", 0), first)
            cache.put(PdfPageKey("second.pdf", 0), second)
            assertSame(first, cache[PdfPageKey("first.pdf", 0)])
            cache.put(PdfPageKey("third.pdf", 0), third)
            assertSame(first, cache[PdfPageKey("first.pdf", 0)])
            assertNull(cache[PdfPageKey("second.pdf", 0)])
            assertTrue(cache.sizeBytes <= cache.maxBytes)
            cache.removeWhere { it.path == "first.pdf" }
            assertEquals(third.allocationByteCount, cache.sizeBytes)
        } finally {
            cache.clear()
            first.recycle()
            second.recycle()
            third.recycle()
        }
    }
}
