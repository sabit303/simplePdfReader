package com.simplepdf.reader

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Insets
import android.graphics.pdf.PdfDocument as AndroidPdf
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.simplepdf.reader.data.PdfDocument
import com.simplepdf.reader.data.ReaderSession
import com.simplepdf.reader.data.SessionStore
import com.simplepdf.reader.ui.PageFlag
import com.simplepdf.reader.ui.PdfDocumentState
import com.simplepdf.reader.ui.PdfPageKey
import com.simplepdf.reader.ui.PdfReadingPosition
import com.simplepdf.reader.ui.PdfResources
import com.simplepdf.reader.ui.PdfViewport
import com.simplepdf.reader.ui.ReaderView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
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
    fun warmTabSwitchReusesDocumentAndCachedPagesWithoutLoadingScreen() {
        launch()
        awaitPage(1, 3)
        lateinit var document: PdfDocumentState
        lateinit var position: PdfReadingPosition
        lateinit var cachedPage: Bitmap
        onActivity {
            it.views().filterIsInstance<PdfViewport>().single().performAccessibilityAction(R.id.zoom_in, null)
        }
        awaitCondition { it.views().filterIsInstance<PdfViewport>().single().zoom >= 1.49f }
        onActivity {
            val viewport = it.views().filterIsInstance<PdfViewport>().single()
            viewport.scrollToProgress(0.35f)
            document = viewport.document
            position = document.position
        }
        awaitCondition {
            !document.isRendering && document.cache[PdfPageKey(second.path, position.pageIndex)] != null
        }
        onActivity { activity ->
            cachedPage = checkNotNull(document.cache[PdfPageKey(second.path, position.pageIndex)])
            activity.selectPdfTab(first.title)
        }
        awaitPage(4, 8)
        onActivity { activity ->
            assertSame(document.cache, activity.views().filterIsInstance<PdfViewport>().single().document.cache)
            activity.selectPdfTab(second.title)
            assertSame(document, activity.views().filterIsInstance<PdfViewport>().single().document)
            assertNoOpeningMessage(activity)
            assertSame(cachedPage, document.cache[PdfPageKey(second.path, position.pageIndex)])
        }
        awaitCondition { it.views().filterIsInstance<PdfViewport>().single().zoom == position.zoom }
        onActivity { assertPositionEquals(position, document.position) }
    }

    @Test
    fun rotationAndRecreationReuseResourcesAndPreserveZoomAndPosition() {
        launch()
        awaitPage(1, 3)
        onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        awaitCondition { it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        onActivity {
            it.views().filterIsInstance<PdfViewport>().single().performAccessibilityAction(R.id.zoom_in, null)
        }
        awaitCondition { it.views().filterIsInstance<PdfViewport>().single().zoom >= 1.49f }
        lateinit var resources: PdfResources
        lateinit var document: PdfDocumentState
        lateinit var position: PdfReadingPosition
        onActivity {
            resources = it.pdfResources
            val viewport = it.views().filterIsInstance<PdfViewport>().single()
            viewport.scrollToProgress(0.35f)
            document = viewport.document
            position = document.position
        }
        scenario!!.recreate()
        onActivity {
            assertSame(resources, it.pdfResources)
            assertSame(document, it.views().filterIsInstance<PdfViewport>().single().document)
            assertNoOpeningMessage(it)
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        awaitCondition {
            it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                it.views().filterIsInstance<PdfViewport>().single().width >
                it.views().filterIsInstance<PdfViewport>().single().height
        }
        onActivity {
            assertSame(resources, it.pdfResources)
            assertSame(document, it.views().filterIsInstance<PdfViewport>().single().document)
            assertNoOpeningMessage(it)
            assertPositionEquals(position, document.position)
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        awaitCondition {
            it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                it.views().filterIsInstance<PdfViewport>().single().height >
                it.views().filterIsInstance<PdfViewport>().single().width
        }
        onActivity { assertPositionEquals(position, document.position) }
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun landscapeCutoutDoesNotReserveAFullHeightReaderStrip() {
        launch()
        awaitPage(1, 3)
        onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        awaitCondition { it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        onActivity { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val title = activity.findViewById<View>(R.id.reader_top_bar)
            val flag = activity.views().filterIsInstance<PageFlag>().single()
            val normalPadding = (20 * activity.resources.displayMetrics.density + 0.5f).toInt()
            fun applyCutout(left: Int, right: Int) {
                root.dispatchApplyWindowInsets(WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 24, 0, 0))
                    .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 48, 0))
                    .setInsets(WindowInsets.Type.displayCutout(), Insets.of(left, 0, right, 0))
                    .build())
            }
            applyCutout(72, 0)
            assertEquals(0, root.paddingLeft)
            assertEquals(48, root.paddingRight)
            assertEquals(normalPadding + 72, title.paddingLeft)
            assertEquals(0, (flag.layoutParams as FrameLayout.LayoutParams).rightMargin)
            applyCutout(0, 72)
            assertEquals(0, root.paddingLeft)
            assertEquals(48, root.paddingRight)
            assertEquals(normalPadding, title.paddingLeft)
            assertEquals(normalPadding + 24, title.paddingRight)
            assertEquals(24, (flag.layoutParams as FrameLayout.LayoutParams).rightMargin)
            applyCutout(0, 0)
            assertEquals(normalPadding, title.paddingRight)
            assertEquals(0, (flag.layoutParams as FrameLayout.LayoutParams).rightMargin)
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,
                activity.window.attributes.layoutInDisplayCutoutMode)
        }
    }

    @Test
    fun memoryPressureReleasesInactiveRendererButKeepsTabPosition() {
        launch()
        awaitPage(1, 3)
        lateinit var inactive: PdfDocumentState
        lateinit var position: PdfReadingPosition
        onActivity {
            val viewport = it.views().filterIsInstance<PdfViewport>().single()
            viewport.scrollToProgress(0.45f)
            inactive = viewport.document
            position = inactive.position
            it.selectPdfTab(first.title)
        }
        awaitPage(4, 8)
        onActivity {
            it.pdfResources.trimMemory()
            assertFalse(inactive.isOpen)
            assertTrue(it.views().filterIsInstance<PdfViewport>().single().document.isOpen)
            assertPositionEquals(position, inactive.position)
            it.selectPdfTab(second.title)
        }
        awaitPage(position.pageIndex + 1, 3)
        onActivity {
            assertSame(inactive, it.views().filterIsInstance<PdfViewport>().single().document)
            assertPositionEquals(position, inactive.position)
        }
    }

    @Test
    fun finishingAppReleasesResourcesAndRestoresTabsWithFreshDocuments() {
        launch()
        awaitPage(1, 3)
        lateinit var resources: PdfResources
        lateinit var document: PdfDocumentState
        onActivity {
            resources = it.pdfResources
            document = it.views().filterIsInstance<PdfViewport>().single().document
            it.views().filterIsInstance<ReaderView>().single().jumpTo(1)
        }
        awaitPage(2, 3)
        scenario!!.close()
        scenario = null
        assertFalse(document.isOpen)
        assertEquals(0, resources.cache.sizeBytes)
        launch()
        awaitPage(2, 3)
        onActivity {
            assertNotSame(resources, it.pdfResources)
            assertNotSame(document, it.views().filterIsInstance<PdfViewport>().single().document)
            assertEquals(2, SessionStore(context).load().tabs.size)
        }
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

    private fun MainActivity.selectPdfTab(title: String) {
        views().filterIsInstance<android.widget.TextView>().first {
            it.text == title && it.isClickable && it.id != R.id.reader_top_bar
        }.performClick()
    }

    private fun assertNoOpeningMessage(activity: MainActivity) {
        assertFalse(activity.views().filterIsInstance<android.widget.TextView>().any {
            it.text == context.getString(R.string.loading_pdf) && it.visibility == View.VISIBLE &&
                (it.parent as? View)?.visibility == View.VISIBLE
        })
    }

    private fun assertPositionEquals(expected: PdfReadingPosition, actual: PdfReadingPosition) {
        assertEquals(expected.pageIndex, actual.pageIndex)
        assertEquals(expected.pageOffset, actual.pageOffset, 0.001f)
        assertEquals(expected.zoom, actual.zoom, 0.001f)
        assertEquals(expected.horizontalOffset, actual.horizontalOffset, 0.001f)
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
