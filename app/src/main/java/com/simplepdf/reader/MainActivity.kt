package com.simplepdf.reader

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import com.simplepdf.reader.data.PdfDocument
import com.simplepdf.reader.data.PdfScanner
import com.simplepdf.reader.data.ReaderSession
import com.simplepdf.reader.data.SessionStore
import com.simplepdf.reader.data.sharedStorageRoots
import com.simplepdf.reader.ui.HomeView
import com.simplepdf.reader.ui.Palette
import com.simplepdf.reader.ui.ReaderView
import com.simplepdf.reader.ui.action
import com.simplepdf.reader.ui.dp
import com.simplepdf.reader.ui.label
import com.simplepdf.reader.ui.rounded
import org.json.JSONException
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val sessionWrites = Executors.newSingleThreadExecutor()
    private lateinit var store: SessionStore
    private lateinit var importer: DocumentImporter
    private var session = ReaderSession()
    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var home: HomeView
    private lateinit var tabBar: LinearLayout
    private var reader: ReaderView? = null
    private var library: List<PdfDocument> = emptyList()
    private var scan: Future<*>? = null
    private var scanGeneration = 0
    private var tabsVisible = true
    private var previousAccess = false
    private var stopped = false
    private val persist = Runnable { saveSession() }
    private val hideTabs = Runnable { setTabsVisible(false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SessionStore(this)
        importer = DocumentImporter(this)
        session = try {
            store.load()
        } catch (error: JSONException) {
            reportSessionError(error)
            ReaderSession()
        } catch (error: IllegalArgumentException) {
            reportSessionError(error)
            ReaderSession()
        } catch (error: IllegalStateException) {
            reportSessionError(error)
            ReaderSession()
        }
        root = FrameLayout(this).apply { setBackgroundColor(Palette.background) }
        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        home = HomeView(this, ::openPicker, ::refreshLibrary, ::requestStorageAccess, ::openDocument)
        tabBar = LinearLayout(this).apply {
            id = R.id.tab_bar
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = dp(12).toFloat()
            background = rounded(Color.WHITE, dp(20).toFloat())
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        root.addView(tabBar, FrameLayout.LayoutParams(
            minOf(dp(420), (resources.displayMetrics.widthPixels * 0.86f).toInt()),
            dp(60), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply {
            bottomMargin = dp(12)
        })
        setContentView(root)
        applyInsets()
        previousAccess = hasStorageAccess()
        showSelectedTab()
        refreshLibrary()
        if (savedInstanceState == null) handleOpenIntent(intent)
    }

    private fun applyInsets() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        } else {
            root.fitsSystemWindows = true
        }
    }

    override fun onResume() {
        super.onResume()
        stopped = false
        val access = hasStorageAccess()
        if (::home.isInitialized && access != previousAccess) {
            previousAccess = access
            refreshLibrary()
            if (session.selectedPath != null) showSelectedTab()
        }
    }

    override fun onStop() {
        stopped = true
        main.removeCallbacks(persist)
        main.removeCallbacks(hideTabs)
        saveSession(waitForWrite = true)
        super.onStop()
    }

    override fun onDestroy() {
        scanGeneration++
        scan?.cancel(true)
        reader?.dispose()
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        sessionWrites.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenIntent(intent)
    }

    private fun handleOpenIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) intent.data?.let(::importDocument)
    }

    @Deprecated("Framework back handling for the supported Android versions")
    override fun onBackPressed() {
        if (session.selectedPath != null) selectTab(null) else super.onBackPressed()
    }

    private fun hasStorageAccess(): Boolean = if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")))
            } catch (error: ActivityNotFoundException) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (fallbackError: ActivityNotFoundException) {
                    notifyError(getString(R.string.storage_settings_unavailable), fallbackError)
                }
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), STORAGE_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_REQUEST) {
            previousAccess = hasStorageAccess()
            refreshLibrary()
            if (!previousAccess) Toast.makeText(this, R.string.access_denied, Toast.LENGTH_LONG).show()
            else if (session.selectedPath != null) showSelectedTab()
        }
    }

    private fun refreshLibrary() {
        val access = hasStorageAccess()
        home.setAccess(access)
        home.showLoading()
        scan?.cancel(true)
        val generation = ++scanGeneration
        scan = io.submit {
            try {
                val result = if (access) PdfScanner.scan(sharedStorageRoots(this)) {
                    Thread.currentThread().isInterrupted
                } else null
                val documents = (result?.documents.orEmpty() + importer.documents())
                    .distinctBy { it.path }
                    .sortedWith(compareByDescending<PdfDocument> { it.modifiedAt }
                        .thenBy { it.title.lowercase(Locale.ROOT) }
                        .thenBy { it.title }
                        .thenBy { it.path })
                main.post {
                    if (!isDestroyed && generation == scanGeneration) {
                        library = documents
                        val unreadable = result?.unreadableDirectories ?: 0
                        home.showDocuments(library, if (unreadable > 0)
                            resources.getQuantityString(R.plurals.scan_warning, unreadable, unreadable) else null)
                    }
                }
            } catch (_: CancellationException) {
                Log.d(TAG, "Superseded library scan cancelled.")
            } catch (error: IOException) {
                scanFailed(generation, error)
            } catch (error: SecurityException) {
                scanFailed(generation, error)
            }
        }
    }

    private fun scanFailed(generation: Int, error: Exception) {
        Log.e(TAG, "Library scan failed", error)
        main.post {
            if (!isDestroyed && generation == scanGeneration) {
                home.showDocuments(library, getString(R.string.scan_failed, error.localizedMessage.orEmpty()))
            }
        }
    }

    private fun openPicker() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "application/pdf"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, OPEN_REQUEST)
        } catch (error: ActivityNotFoundException) {
            notifyError(getString(R.string.picker_unavailable), error)
        }
    }

    @Deprecated("Framework document picker result for the supported Android versions")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OPEN_REQUEST && resultCode == RESULT_OK) data?.data?.let(::importDocument)
    }

    private fun importDocument(uri: Uri) {
        Toast.makeText(this, R.string.importing, Toast.LENGTH_SHORT).show()
        io.execute {
            try {
                val document = importer.import(uri)
                main.post {
                    if (!isDestroyed) {
                        openDocument(document)
                        refreshLibrary()
                    }
                }
            } catch (error: IOException) {
                importFailed(error)
            } catch (error: SecurityException) {
                importFailed(error)
            } catch (error: IllegalArgumentException) {
                importFailed(error)
            }
        }
    }

    private fun importFailed(error: Exception) {
        main.post {
            if (!isDestroyed) notifyError(getString(R.string.import_error, error.localizedMessage.orEmpty()), error)
        }
    }

    private fun openDocument(document: PdfDocument) {
        session = session.open(document)
        saveSession()
        showSelectedTab()
    }

    private fun selectTab(path: String?) {
        if (session.selectedPath == path) {
            showTabsTemporarily()
            return
        }
        session = session.select(path)
        saveSession()
        showSelectedTab()
    }

    private fun closeTab(path: String) {
        val wasSelected = session.selectedPath == path
        session = session.close(path)
        saveSession()
        if (wasSelected) showSelectedTab() else rebuildTabs()
    }

    private fun showSelectedTab() {
        main.removeCallbacks(hideTabs)
        reader?.dispose()
        reader = null
        content.removeAllViews()
        val selected = session.tabs.firstOrNull { it.document.path == session.selectedPath }
        if (selected == null) {
            content.addView(home, FrameLayout.LayoutParams(-1, -1))
        } else {
            reader = ReaderView(this, selected, { page ->
                if (session.tabs.any { it.document.path == selected.document.path }) {
                    session = session.updatePage(selected.document.path, page)
                    main.removeCallbacks(persist)
                    if (stopped) saveSession() else main.postDelayed(persist, 200)
                }
            }, { scrollingDown ->
                if (scrollingDown) {
                    main.removeCallbacks(hideTabs)
                    setTabsVisible(false)
                } else {
                    showTabsTemporarily()
                }
            }, ::toggleReaderControls, ::onControlsInteraction, ::showSelectedTab)
            content.addView(reader, FrameLayout.LayoutParams(-1, -1))
        }
        rebuildTabs()
        setTabsVisible(true, animate = false)
        if (selected != null) main.postDelayed(hideTabs, 3500)
    }

    private fun rebuildTabs() {
        tabBar.removeAllViews()
        val homeButton = action(getString(R.string.home)) { selectTab(null) }.apply {
            background = rounded(if (session.selectedPath == null) Palette.softGreen else Color.WHITE, dp(14).toFloat())
            isSelected = session.selectedPath == null
        }
        tabBar.addView(homeButton, LinearLayout.LayoutParams(dp(68), -1))
        val strip = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
        tabBar.addView(scroller, LinearLayout.LayoutParams(0, -1, 1f))
        for (tab in session.tabs) {
            val active = tab.document.path == session.selectedPath
            val cell = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(if (active) Palette.softGreen else Palette.background, dp(14).toFloat())
            }
            cell.addView(label(tab.document.title, 13f, active).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(12), 0, 0, 0)
                isFocusable = true
                isSelected = active
                setOnClickListener { selectTab(tab.document.path) }
            }, LinearLayout.LayoutParams(0, -1, 1f))
            cell.addView(action("\u00d7") { closeTab(tab.document.path) }.apply {
                contentDescription = getString(R.string.close_tab, tab.document.title)
                textSize = 24f
                setPadding(0, 0, 0, 0)
                background = rounded(Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(dp(48), -1))
            strip.addView(cell, LinearLayout.LayoutParams(dp(144), dp(48)).apply {
                marginStart = dp(6)
            })
            if (active) scroller.post { scroller.smoothScrollTo(cell.left - dp(8), 0) }
        }
    }

    private fun showTabsTemporarily() {
        main.removeCallbacks(hideTabs)
        setTabsVisible(true)
        if (session.selectedPath != null) main.postDelayed(hideTabs, 3500)
    }

    private fun toggleReaderControls() {
        if (tabsVisible) {
            main.removeCallbacks(hideTabs)
            setTabsVisible(false)
        } else {
            showTabsTemporarily()
        }
    }

    private fun onControlsInteraction(active: Boolean) {
        if (active) {
            main.removeCallbacks(hideTabs)
            setTabsVisible(true)
        } else {
            showTabsTemporarily()
        }
    }

    private fun setTabsVisible(visible: Boolean, animate: Boolean = true) {
        val accessibility = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val show = visible || session.selectedPath == null || accessibility.isTouchExplorationEnabled
        if (animate && tabsVisible == show) return
        tabsVisible = show
        reader?.setBarsVisible(show, animate)
        tabBar.animate().cancel()
        if (show) tabBar.visibility = View.VISIBLE
        val offset = if (show) 0f else dp(100).toFloat()
        if (animate) {
            tabBar.animate().translationY(offset).alpha(if (show) 1f else 0f).setDuration(180)
                .withEndAction { if (!tabsVisible) tabBar.visibility = View.INVISIBLE }.start()
        } else {
            tabBar.translationY = offset
            tabBar.alpha = if (show) 1f else 0f
            tabBar.visibility = if (show) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun saveSession(waitForWrite: Boolean = false) {
        if (!::store.isInitialized) return
        val snapshot = session
        val write = sessionWrites.submit {
            try {
                store.save(snapshot)
            } catch (error: IOException) {
                reportSaveError(error)
            } catch (error: IllegalStateException) {
                reportSaveError(error)
            }
        }
        if (waitForWrite) {
            try {
                write.get()
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                notifyError(getString(R.string.save_error), error)
            } catch (error: java.util.concurrent.ExecutionException) {
                notifyError(getString(R.string.save_error), error)
            }
        }
    }

    private fun reportSaveError(error: Exception) {
        Log.e(TAG, "Could not save reader session", error)
        main.post {
            if (!isDestroyed) Toast.makeText(this, R.string.save_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun reportSessionError(error: Exception) {
        notifyError(getString(R.string.session_error, error.localizedMessage.orEmpty()), error)
    }

    private fun notifyError(message: String, error: Exception) {
        Log.e(TAG, message, error)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "SimplePdf"
        private const val STORAGE_REQUEST = 100
        private const val OPEN_REQUEST = 101
    }
}
