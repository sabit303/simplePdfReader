package com.simplepdf.reader.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.simplepdf.reader.R
import com.simplepdf.reader.data.OpenPdfTab

class ReaderView(
    context: Context,
    val tab: OpenPdfTab,
    private val onPageChanged: (Int) -> Unit,
    onScrollDirection: (Boolean) -> Unit,
    private val onPageTap: () -> Unit,
    private val onControlsInteraction: (Boolean) -> Unit,
    onRetry: () -> Unit,
) : FrameLayout(context) {
    private val engine = PdfEngine()
    private val viewport = PdfViewport(
        context, engine, ::updatePosition, ::onViewportScrollChanged, onScrollDirection, onPageTap,
    )
    private val topBar = context.label(tab.document.title, 16f, true).apply {
        id = R.id.reader_top_bar
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.MIDDLE
        setPadding(context.dp(20), 0, context.dp(20), 0)
        setBackgroundColor(Palette.background)
        setOnClickListener { onPageTap() }
    }
    private var barsVisible = true
    private var viewportScrolling = false
    private var flagVisible = true
    private val hideFlag = Runnable {
        if (!barsVisible && !viewportScrolling) setFlagVisible(false, animate = true)
    }
    private val flag = PageFlag(context, viewport::scrollToProgress, ::jumpTo, ::showJumpDialog, onControlsInteraction)
    private var pageCount = 0
    private var pageIndex = -1
    private var ready = false
    private val message = context.label(context.getString(R.string.loading_pdf), 16f).apply {
        gravity = Gravity.CENTER
        setPadding(context.dp(24), context.dp(24), context.dp(24), context.dp(24))
    }
    private val errorPanel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(message)
        addView(context.action(context.getString(R.string.retry), onRetry).apply {
            visibility = GONE
        })
    }

    init {
        setBackgroundColor(Palette.background)
        addView(viewport, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(errorPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = context.dp(48)
            bottomMargin = context.dp(80)
        })
        addView(topBar, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(48), Gravity.TOP))
        addView(flag, LayoutParams(context.dp(56), context.dp(48), Gravity.RIGHT or Gravity.TOP))
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> flag.updatePosition() }
        flag.visibility = GONE
        engine.open(tab.document.path) { result ->
            result.fold(
                onSuccess = { sizes ->
                    pageCount = sizes.size
                    flag.pageCount = pageCount
                    ready = true
                    setFlagVisible(flagVisible, animate = false)
                    errorPanel.visibility = GONE
                    viewport.setDocument(sizes, tab.pageIndex)
                },
                onFailure = { error ->
                    message.text = context.getString(R.string.pdf_error, error.localizedMessage.orEmpty())
                    errorPanel.getChildAt(1).visibility = VISIBLE
                },
            )
        }
    }

    private fun updatePosition(index: Int, progress: Float) {
        flag.update(index, progress)
        if (pageIndex == index) return
        pageIndex = index
        onPageChanged(index)
    }

    fun setBarsVisible(visible: Boolean, animate: Boolean) {
        barsVisible = visible
        removeCallbacks(hideFlag)
        setFlagVisible(visible || viewportScrolling, animate)
        topBar.animate().cancel()
        if (visible) topBar.visibility = VISIBLE
        val offset = if (visible) 0f else -context.dp(48).toFloat()
        if (animate) {
            topBar.animate().translationY(offset).alpha(if (visible) 1f else 0f).setDuration(180)
                .withEndAction { if (!barsVisible) topBar.visibility = INVISIBLE }.start()
        } else {
            topBar.translationY = offset
            topBar.alpha = if (visible) 1f else 0f
            topBar.visibility = if (visible) VISIBLE else INVISIBLE
        }
    }

    private fun onViewportScrollChanged(active: Boolean) {
        viewportScrolling = active
        removeCallbacks(hideFlag)
        if (active) {
            setFlagVisible(true, animate = true)
        } else if (!barsVisible) {
            postDelayed(hideFlag, 2000)
        }
    }

    private fun setFlagVisible(visible: Boolean, animate: Boolean) {
        if (animate && flagVisible == visible) return
        flagVisible = visible
        flag.animate().cancel()
        val show = visible && ready
        if (show) flag.visibility = VISIBLE
        val offset = if (show) 0f else context.dp(56).toFloat()
        if (animate && ready) {
            flag.animate().translationX(offset).alpha(if (show) 1f else 0f).setDuration(180)
                .withEndAction { if (!flagVisible) flag.visibility = INVISIBLE }.start()
        } else {
            flag.translationX = offset
            flag.alpha = if (show) 1f else 0f
            flag.visibility = if (!ready) GONE else if (show) VISIBLE else INVISIBLE
        }
    }

    fun jumpTo(index: Int) {
        check(ready) { "Page navigation requested before the PDF was ready." }
        require(index in 0 until pageCount)
        viewport.jumpTo(index)
        onControlsInteraction(false)
    }

    private fun showJumpDialog() {
        check(ready) { "Page navigation requested before the PDF was ready." }
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = context.getString(R.string.jump_hint, pageCount)
            setText((pageIndex + 1).toString())
            selectAll()
            setPadding(context.dp(24), context.dp(16), context.dp(24), context.dp(16))
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.jump_title)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.jump, null)
            .create()
        dialog.setOnShowListener {
            onControlsInteraction(true)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val page = input.text.toString().toIntOrNull()
                if (page == null || page !in 1..pageCount) {
                    input.error = context.getString(R.string.invalid_page, pageCount)
                } else {
                    jumpTo(page - 1)
                    dialog.dismiss()
                }
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.setOnDismissListener { onControlsInteraction(false) }
        dialog.show()
    }

    fun dispose() {
        removeCallbacks(hideFlag)
        topBar.animate().cancel()
        flag.animate().cancel()
        viewport.dispose()
        engine.close()
    }
}
