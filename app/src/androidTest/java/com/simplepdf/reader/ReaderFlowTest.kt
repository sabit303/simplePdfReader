package com.simplepdf.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument as AndroidPdf
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.simplepdf.reader.data.PdfDocument
import com.simplepdf.reader.data.ReaderSession
import com.simplepdf.reader.data.SessionStore
import com.simplepdf.reader.ui.PageFlag
import com.simplepdf.reader.ui.PdfViewport
import com.simplepdf.reader.ui.ReaderView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReaderFlowTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var first: PdfDocument
    private lateinit var second: PdfDocument
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        first = createPdf("test-first.pdf", 8)
        second = createPdf("test-second.pdf", 3)
        SessionStore(context).save(ReaderSession().open(first).updatePage(first.path, 3).open(second))
    }

    @After
    fun tearDown() {
        scenario?.close()
        SessionStore(context).save(ReaderSession())
        File(first.path).delete()
        File(second.path).delete()
    }

    @Test
    fun restoresTabsNavigatesPagesAndClosesOnlyExplicitly() {
        launch()
        awaitPage(1, 3)
        onActivity { activity ->
            val title = activity.views().filterIsInstance<android.widget.TextView>()
                .first { it.text == first.title && it.isClickable }
            title.performClick()
        }
        awaitPage(4, 8)
        onActivity { activity ->
            activity.views().filterIsInstance<ReaderView>().single().jumpTo(6)
        }
        awaitPage(7, 8)
        onActivity { activity ->
            val flag = activity.views().filterIsInstance<PageFlag>().single()
            assertEquals(8, flag.pageCount)
            assertEquals(6, flag.pageIndex)
            assertTrue(flag.height < activity.resources.displayMetrics.density * 60)
        }
        scenario!!.recreate()
        awaitPage(7, 8)
        scenario!!.close()
        scenario = null
        val saved = SessionStore(context).load()
        assertEquals(2, saved.tabs.size)
        assertEquals(6, saved.tabs.first().pageIndex)

        launch()
        awaitPage(7, 8)
        onActivity { activity ->
            activity.views().first {
                it.contentDescription == context.getString(R.string.close_tab, first.title)
            }.performClick()
        }
        awaitPage(1, 3)
        onActivity { activity ->
            activity.views().first {
                it.contentDescription == context.getString(R.string.close_tab, second.title)
            }.performClick()
        }
        onActivity { activity ->
            assertTrue(activity.views().none { it is ReaderView })
            assertNotNull(activity.views().filterIsInstance<Button>().find {
                it.text == context.getString(R.string.home)
            })
        }
        awaitCondition { SessionStore(context).load().tabs.isEmpty() }
    }

    @Test
    fun allControlsAutoHideAndToggleOnPageTapWithoutMovingThePdf() {
        launch()
        awaitPage(1, 3)
        var viewportHeight = 0
        var scrollPosition = 0f
        onActivity { activity ->
            val topBar = activity.findViewById<android.widget.TextView>(R.id.reader_top_bar)
            assertEquals(second.title, topBar.text.toString())
            assertEquals(View.VISIBLE, topBar.visibility)
            assertEquals(View.VISIBLE, activity.views().filterIsInstance<PageFlag>().single().visibility)
            val reader = activity.views().filterIsInstance<ReaderView>().single()
            assertTrue(reader.views().filterIsInstance<Button>().none { it.isShown })
            val viewport = activity.views().filterIsInstance<PdfViewport>().single()
            viewportHeight = viewport.height
            scrollPosition = viewport.scrollPosition
        }
        awaitCondition { it.controlsHaveVisibility(View.INVISIBLE) }
        onActivity { activity ->
            val viewport = activity.views().filterIsInstance<PdfViewport>().single()
            assertEquals(viewportHeight, viewport.height)
            assertEquals(0, viewport.top)
            assertEquals(scrollPosition, viewport.scrollPosition, 0.01f)
            activity.views().first {
                it.contentDescription == context.getString(R.string.page_image, 1) && it.isClickable
            }.performClick()
        }
        onActivity { activity ->
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.tab_bar).visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.reader_top_bar).visibility)
            assertEquals(View.VISIBLE, activity.views().filterIsInstance<PageFlag>().single().visibility)
            assertEquals(viewportHeight, activity.views().filterIsInstance<PdfViewport>().single().height)
            assertTrue(activity.views().filterIsInstance<android.widget.TextView>().none { it.text == "..." })
            assertTrue(activity.findViewById<View>(R.id.tab_bar).width < activity.window.decorView.width * 0.9f)
        }
        awaitCondition { it.controlsHaveVisibility(View.VISIBLE) }
        onActivity { it.views().filterIsInstance<PdfViewport>().single().performClick() }
        awaitCondition { it.controlsHaveVisibility(View.INVISIBLE) }
        onActivity { it.views().filterIsInstance<PdfViewport>().single().performClick() }
        awaitCondition { it.controlsHaveVisibility(View.VISIBLE) }
        awaitCondition { it.controlsHaveVisibility(View.INVISIBLE) }
    }

    @Test
    fun scrollingUpRestoresBarsAndScrollingDownShowsOnlyFlag() {
        launch()
        awaitPage(1, 3)
        onActivity { it.views().filterIsInstance<PdfViewport>().single().performClick() }
        awaitCondition { it.controlsHaveVisibility(View.INVISIBLE) }
        onActivity { activity ->
            swipe(activity.views().filterIsInstance<PdfViewport>().single(), down = true)
        }
        awaitCondition { it.onlyScrollFlagIsVisible() }
        onActivity { activity ->
            swipe(activity.views().filterIsInstance<PdfViewport>().single(), down = false)
        }
        awaitCondition { it.controlsHaveVisibility(View.VISIBLE) }
        onActivity { activity ->
            swipe(activity.views().filterIsInstance<PdfViewport>().single(), down = true)
        }
        awaitCondition { it.onlyScrollFlagIsVisible() }
        awaitCondition { it.controlsHaveVisibility(View.INVISIBLE) }
    }

    @Test
    fun upwardSwipeAtTopRestoresHiddenBars() {
        launch()
        awaitPage(1, 3)
        onActivity { activity ->
            activity.views().filterIsInstance<PdfViewport>().single().scrollToProgress(0f)
            activity.views().filterIsInstance<PdfViewport>().single().performClick()
        }
        awaitCondition { it.controlsHaveVisibility(View.INVISIBLE) }
        onActivity { activity ->
            swipe(activity.views().filterIsInstance<PdfViewport>().single(), down = false)
        }
        awaitCondition { it.controlsHaveVisibility(View.VISIBLE) }
        onActivity {
            assertEquals(0f, it.views().filterIsInstance<PdfViewport>().single().scrollPosition, 0.01f)
        }
    }

    @Test
    fun reversingDirectionDuringOneDragRestoresBars() {
        launch()
        awaitPage(1, 3)
        val downTime = SystemClock.uptimeMillis()
        onActivity { activity ->
            val viewport = activity.views().filterIsInstance<PdfViewport>().single()
            pageTouch(viewport, downTime, downTime, MotionEvent.ACTION_DOWN, viewport.height * 0.75f)
            pageTouch(viewport, downTime, downTime + 20, MotionEvent.ACTION_MOVE, viewport.height * 0.4f)
        }
        awaitCondition { it.onlyScrollFlagIsVisible() }
        onActivity { activity ->
            val viewport = activity.views().filterIsInstance<PdfViewport>().single()
            val time = SystemClock.uptimeMillis()
            pageTouch(viewport, downTime, time, MotionEvent.ACTION_MOVE, viewport.height * 0.6f)
            pageTouch(viewport, downTime, time + 20, MotionEvent.ACTION_CANCEL, viewport.height * 0.6f)
        }
        awaitCondition { it.controlsHaveVisibility(View.VISIBLE) }
    }

    @Test
    fun flagStartsHidingTwoSecondsAfterDownwardScrollStops() {
        launch()
        awaitPage(1, 3)
        onActivity {
            swipe(it.views().filterIsInstance<PdfViewport>().single(), down = true)
        }
        val stoppedAt = SystemClock.uptimeMillis()
        awaitCondition { it.onlyScrollFlagIsVisible() }
        Thread.sleep(1000)
        onActivity { assertTrue(it.onlyScrollFlagIsVisible()) }
        awaitCondition { it.controlsHaveVisibility(View.INVISIBLE) }
        val elapsed = SystemClock.uptimeMillis() - stoppedAt
        assertTrue("Flag hid too early: $elapsed ms", elapsed >= 2000)
        assertTrue("Flag did not hide after the two-second delay: $elapsed ms", elapsed < 3000)
    }

    @Test
    fun upwardFlingToTopRestoresBars() {
        launch()
        awaitPage(1, 3)
        onActivity { activity ->
            val viewport = activity.views().filterIsInstance<PdfViewport>().single()
            viewport.scrollToProgress(0.5f)
            viewport.performClick()
        }
        awaitCondition { it.controlsHaveVisibility(View.INVISIBLE) }
        onActivity { activity ->
            swipe(activity.views().filterIsInstance<PdfViewport>().single(), down = false, fling = true)
        }
        awaitCondition {
            it.controlsHaveVisibility(View.VISIBLE) &&
                it.views().filterIsInstance<PdfViewport>().single().scrollPosition == 0f
        }
    }

    @Test
    fun holdingAPageDragKeepsFlagVisiblePastAutoHideDelay() {
        launch()
        awaitPage(1, 3)
        val downTime = SystemClock.uptimeMillis()
        onActivity { activity ->
            val viewport = activity.views().filterIsInstance<PdfViewport>().single()
            pageTouch(viewport, downTime, downTime, MotionEvent.ACTION_DOWN, viewport.height * 0.75f)
            pageTouch(viewport, downTime, downTime + 20, MotionEvent.ACTION_MOVE, viewport.height * 0.5f)
        }
        awaitCondition { it.onlyScrollFlagIsVisible() }
        Thread.sleep(4000)
        onActivity { activity ->
            assertTrue(activity.onlyScrollFlagIsVisible())
            val viewport = activity.views().filterIsInstance<PdfViewport>().single()
            pageTouch(viewport, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, viewport.height * 0.5f)
        }
        awaitCondition { it.controlsHaveVisibility(View.INVISIBLE) }
    }

    @Test
    fun flingKeepsFlagVisibleAndPageTapStillRestoresBars() {
        launch()
        awaitPage(1, 3)
        onActivity { activity ->
            activity.views().filterIsInstance<android.widget.TextView>()
                .first { it.text == first.title && it.isClickable }.performClick()
        }
        awaitPage(4, 8)
        onActivity { activity ->
            swipe(activity.views().filterIsInstance<PdfViewport>().single(), down = true, fling = true)
        }
        awaitCondition {
            it.onlyScrollFlagIsVisible() && it.views().filterIsInstance<PdfViewport>().single().isFlinging
        }
        awaitCondition { !it.views().filterIsInstance<PdfViewport>().single().isFlinging }
        onActivity { activity ->
            assertTrue(activity.onlyScrollFlagIsVisible())
            activity.views().filterIsInstance<PdfViewport>().single().performClick()
        }
        awaitCondition { it.controlsHaveVisibility(View.VISIBLE) }
        onActivity { it.views().filterIsInstance<PdfViewport>().single().performClick() }
        awaitCondition { it.controlsHaveVisibility(View.INVISIBLE) }
    }

    @Test
    fun restoredFlagRetainsPageJumpAndStaysVisibleWhileDialogIsOpen() {
        launch()
        awaitPage(1, 3)
        onActivity { activity ->
            val flag = activity.views().filterIsInstance<PageFlag>().single()
            val density = activity.resources.displayMetrics.density
            assertEquals((56 * density).toInt(), flag.width)
            assertEquals((48 * density).toInt(), flag.height)
            val bitmap = Bitmap.createBitmap(flag.width, flag.height, Bitmap.Config.ARGB_8888)
            try {
                flag.draw(Canvas(bitmap))
                assertEquals(255, Color.alpha(bitmap.getPixel((4 * density).toInt(), flag.height / 2)))
                assertEquals(255, Color.alpha(bitmap.getPixel(flag.width / 2, (4 * density).toInt())))
                assertEquals(255, Color.alpha(bitmap.getPixel(flag.width - 1, flag.height / 2)))
            } finally {
                bitmap.recycle()
            }
            flag.performClick()
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val root = instrumentation.uiAutomation.rootInActiveWindow
        assertNotNull(root)
        assertTrue(root.findAccessibilityNodeInfosByText(context.getString(R.string.jump_title)).isNotEmpty())
        Thread.sleep(4000)
        onActivity { assertTrue(it.controlsHaveVisibility(View.VISIBLE)) }
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
    }

    @Test
    fun flagTouchPausesAutoHideUntilReleased() {
        launch()
        awaitPage(1, 3)
        val downTime = SystemClock.uptimeMillis()
        onActivity { activity ->
            val flag = activity.views().filterIsInstance<PageFlag>().single()
            val event = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN,
                flag.width / 2f, flag.height / 2f, 0)
            try {
                flag.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
        Thread.sleep(4000)
        onActivity { activity ->
            assertTrue(activity.controlsHaveVisibility(View.VISIBLE))
            val flag = activity.views().filterIsInstance<PageFlag>().single()
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL,
                flag.width / 2f, flag.height / 2f, 0)
            try {
                flag.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
        awaitCondition { it.controlsHaveVisibility(View.INVISIBLE) }
    }

    @Test
    fun rapidPageTapsKeepAllControlAnimationsInSync() {
        launch()
        awaitPage(1, 3)
        onActivity { activity ->
            val viewport = activity.views().filterIsInstance<PdfViewport>().single()
            repeat(4) { viewport.performClick() }
        }
        awaitCondition { it.controlsHaveVisibility(View.VISIBLE) }
        onActivity { activity ->
            val viewport = activity.views().filterIsInstance<PdfViewport>().single()
            repeat(3) { viewport.performClick() }
        }
        awaitCondition { it.controlsHaveVisibility(View.INVISIBLE) }
    }

    @Test
    fun zoomAndFlagNavigationKeepTheReaderUsable() {
        launch()
        awaitPage(1, 3)
        onActivity { activity ->
            val viewport = activity.views().filterIsInstance<PdfViewport>().single()
            viewport.performAccessibilityAction(R.id.zoom_in, null)
        }
        awaitCondition { it.views().filterIsInstance<PdfViewport>().single().zoom >= 1.49f }
        onActivity { activity ->
            val flag = activity.views().filterIsInstance<PageFlag>().single()
            flag.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
        }
        awaitPage(2, 3)
        onActivity { activity ->
            activity.views().filterIsInstance<PdfViewport>().single()
                .performAccessibilityAction(R.id.zoom_out, null)
        }
        awaitCondition { it.views().filterIsInstance<PdfViewport>().single().zoom == 1f }
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun swipe(view: View, down: Boolean, fling: Boolean = false) {
        val start = if (down) view.height * 0.75f else view.height * 0.25f
        val end = if (down) view.height * 0.25f else view.height * 0.75f
        val time = SystemClock.uptimeMillis()
        repeat(5) { step ->
            val action = when (step) {
                0 -> MotionEvent.ACTION_DOWN
                4 -> if (fling) MotionEvent.ACTION_UP else MotionEvent.ACTION_CANCEL
                else -> MotionEvent.ACTION_MOVE
            }
            pageTouch(view, time, time + step * 20, action, start + (end - start) * step / 4)
        }
    }

    private fun pageTouch(view: View, downTime: Long, eventTime: Long, action: Int, y: Float) {
        val event = MotionEvent.obtain(downTime, eventTime, action, view.width / 2f, y, 0)
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun onActivity(block: (MainActivity) -> Unit) {
        scenario!!.onActivity(block)
    }

    private fun MainActivity.controlsHaveVisibility(visibility: Int): Boolean {
        val controls = listOf(findViewById<View>(R.id.tab_bar), findViewById<View>(R.id.reader_top_bar),
            views().filterIsInstance<PageFlag>().single())
        return controls.all {
            it.visibility == visibility && it.alpha == if (visibility == View.VISIBLE) 1f else 0f
        }
    }

    private fun MainActivity.onlyScrollFlagIsVisible(): Boolean {
        val flag = views().filterIsInstance<PageFlag>().single()
        return findViewById<View>(R.id.tab_bar).visibility == View.INVISIBLE &&
            findViewById<View>(R.id.reader_top_bar).visibility == View.INVISIBLE &&
            flag.visibility == View.VISIBLE && flag.alpha == 1f
    }

    private fun awaitPage(page: Int, count: Int) {
        awaitCondition { activity ->
            activity.views().any {
                it.contentDescription == context.getString(R.string.page_flag_accessibility, page, count)
            }
        }
    }

    private fun awaitCondition(condition: (MainActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        do {
            var passed = false
            onActivity { passed = condition(it) }
            if (passed) return
            Thread.sleep(100)
        } while (System.currentTimeMillis() < deadline)
        throw AssertionError("Reader UI did not reach the expected state.")
    }

    private fun MainActivity.views(): List<View> = window.decorView.views()

    private fun View.views(): List<View> {
        fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap {
                descendants(view.getChildAt(it))
            } else emptyList()
        return descendants(this)
    }

    private fun createPdf(name: String, pages: Int): PdfDocument {
        val file = File(context.filesDir, name)
        val pdf = AndroidPdf()
        try {
            repeat(pages) { index ->
                val page = pdf.startPage(AndroidPdf.PageInfo.Builder(400, 600, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText("Sample page ${index + 1}", 40f, 80f, Paint().apply {
                    color = Color.BLACK
                    textSize = 24f
                })
                pdf.finishPage(page)
            }
            file.outputStream().use(pdf::writeTo)
        } finally {
            pdf.close()
        }
        return PdfDocument(file.path, name, file.lastModified(), file.length())
    }
}
