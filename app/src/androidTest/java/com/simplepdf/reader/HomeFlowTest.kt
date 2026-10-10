package com.simplepdf.reader

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.simplepdf.reader.data.LibrarySnapshot
import com.simplepdf.reader.data.LibraryState
import com.simplepdf.reader.data.ReaderSession
import com.simplepdf.reader.data.SessionStore
import com.simplepdf.reader.ui.HomeView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HomeFlowTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "landscape-test-${UUID.randomUUID()}"
    private val files = mutableListOf<File>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        SessionStore(context).save(ReaderSession())
        val directory = File(context.filesDir, "opened-pdfs")
        check(directory.isDirectory || directory.mkdirs())
        val modified = System.currentTimeMillis()
        repeat(40) { index ->
            val file = File(directory, "$prefix-$index.pdf")
            files.add(file)
            val pdf = PdfDocument()
            try {
                pdf.finishPage(pdf.startPage(PdfDocument.PageInfo.Builder(400, 600, 1).create()))
                file.outputStream().use(pdf::writeTo)
            } finally {
                pdf.close()
            }
            check(file.setLastModified(modified + index))
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        SessionStore(context).save(ReaderSession())
        files.forEach { check(it.delete()) }
    }

    @Test
    fun landscapeHeaderAndPermissionNoticeScrollAwayToExposePdfRows() {
        launch()
        onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        awaitCondition {
            it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                it.list().width > it.list().height
        }
        onActivity {
            it.findViewById<EditText>(R.id.library_search).setText(prefix)
            it.home().showLibrary(it.libraryState.snapshot.documents, false, false, null)
            assertEquals(41, it.list().adapter!!.itemCount)
            assertTrue(it.list().height > 0)
        }
        repeat(3) {
            onActivity { activity ->
                val list = activity.list()
                list.clearFocus()
                val headerHeight = list.findViewHolderForAdapterPosition(0)!!.itemView.height
                list.scrollBy(0, headerHeight + list.height)
            }
            awaitCondition { it.layout().findFirstVisibleItemPosition() > 0 }
            onActivity { activity ->
                val list = activity.list()
                val position = activity.layout().findFirstVisibleItemPosition()
                val row = list.findViewHolderForAdapterPosition(position)!!.itemView
                assertTrue(row.bottom > list.paddingTop)
                assertTrue(row.top < list.height - list.paddingBottom)
                assertTrue(list.childCount < list.adapter!!.itemCount)
                activity.layout().scrollToPositionWithOffset(0, 0)
            }
            awaitCondition { it.findViewById<View>(R.id.library_search) != null }
            onActivity { activity ->
                assertEquals(prefix, activity.findViewById<EditText>(R.id.library_search).text.toString())
            }
        }
    }

    @Test
    fun rotationKeepsLibrarySnapshotSearchAndListOffsetWithoutRescanning() {
        launch()
        onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        awaitCondition { it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        lateinit var library: LibraryState
        lateinit var snapshot: LibrarySnapshot
        onActivity {
            library = it.libraryState
            snapshot = library.snapshot
            it.findViewById<EditText>(R.id.library_search).setText(prefix)
            it.list().clearFocus()
            it.layout().scrollToPositionWithOffset(6, -17)
        }
        awaitCondition { it.layout().findFirstVisibleItemPosition() == 6 }
        var offset = 0
        onActivity {
            offset = it.layout().getDecoratedTop(it.layout().findViewByPosition(6)!!) - it.list().paddingTop
        }
        scenario!!.recreate()
        awaitCondition { it.layout().findFirstVisibleItemPosition() == 6 }
        fun assertRetained(activity: MainActivity) {
            assertSame(library, activity.libraryState)
            assertSame(snapshot, activity.libraryState.snapshot)
            assertFalse(activity.libraryState.snapshot.isLoading)
            val saved = Bundle()
            activity.home().saveState(saved)
            assertEquals(prefix, saved.getString("library.query"))
            assertEquals(41, activity.list().adapter!!.itemCount)
            assertEquals(offset,
                activity.layout().getDecoratedTop(activity.layout().findViewByPosition(6)!!) -
                    activity.list().paddingTop)
        }
        onActivity {
            assertRetained(it)
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        awaitCondition {
            it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                it.list().width > it.list().height && it.layout().findFirstVisibleItemPosition() == 6
        }
        onActivity {
            assertRetained(it)
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        awaitCondition {
            it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                it.list().height > it.list().width && it.layout().findFirstVisibleItemPosition() == 6
        }
        onActivity(::assertRetained)
    }

    @Test
    fun restoredLibraryStillRefreshesExplicitly() {
        launch()
        lateinit var library: LibraryState
        lateinit var snapshot: LibrarySnapshot
        onActivity {
            library = it.libraryState
            snapshot = library.snapshot
        }
        scenario!!.recreate()
        awaitCondition { it.list().findViewHolderForAdapterPosition(0) != null }
        onActivity {
            assertSame(library, it.libraryState)
            assertSame(snapshot, it.libraryState.snapshot)
            it.findViewById<RecyclerView>(R.id.library_list).let { list ->
                val header = list.findViewHolderForAdapterPosition(0)!!.itemView
                fun findRefresh(view: View): View? {
                    if (view is android.widget.Button && view.text == context.getString(R.string.refresh)) return view
                    if (view is android.view.ViewGroup) {
                        for (index in 0 until view.childCount) findRefresh(view.getChildAt(index))?.let { return it }
                    }
                    return null
                }
                val refresh = findRefresh(header)
                assertNotNull(refresh)
                refresh!!.performClick()
            }
            assertTrue(library.snapshot.isLoading)
        }
        awaitCondition { !it.libraryState.snapshot.isLoading }
        onActivity { assertTrue(snapshot !== it.libraryState.snapshot) }
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitCondition {
            !it.libraryState.snapshot.isLoading &&
                it.libraryState.snapshot.documents.count { document -> document.title.startsWith(prefix) } == 40 &&
                it.list().childCount > 0
        }
    }

    private fun MainActivity.list(): RecyclerView = findViewById(R.id.library_list)
    private fun MainActivity.layout(): LinearLayoutManager = list().layoutManager as LinearLayoutManager
    private fun MainActivity.home(): HomeView = list().parent as HomeView

    private fun onActivity(block: (MainActivity) -> Unit) {
        scenario!!.onActivity(block)
    }

    private fun awaitCondition(condition: (MainActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        do {
            var passed = false
            onActivity { passed = condition(it) }
            if (passed) return
            Thread.sleep(100)
        } while (System.currentTimeMillis() < deadline)
        throw AssertionError("Home did not reach the expected state.")
    }
}
