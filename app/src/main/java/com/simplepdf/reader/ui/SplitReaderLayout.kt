package com.simplepdf.reader.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import com.simplepdf.reader.R

internal class SplitReaderLayout(
    context: Context,
    val topReader: ReaderView,
    val bottomReader: ReaderView,
    initialRatio: Float,
    private val onRatioChanged: (Float) -> Unit,
    private val onRatioCommitted: () -> Unit,
    private val onCollapseTop: () -> Unit,
    private val onCollapseBottom: () -> Unit,
) : ViewGroup(context) {
    private var ratio = initialRatio
    private val divider = SplitDivider(context, ::moveDivider, onRatioCommitted, ::collapseAt)
    private val isLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    init {
        setBackgroundColor(Palette.background)
        addView(topReader)
        addView(bottomReader)
        addView(divider)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val exactWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val exactHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        if (isLandscape) {
            val split = splitPosition(width)
            topReader.measure(MeasureSpec.makeMeasureSpec(split, MeasureSpec.EXACTLY), exactHeight)
            bottomReader.measure(MeasureSpec.makeMeasureSpec(width - split, MeasureSpec.EXACTLY), exactHeight)
            divider.measure(MeasureSpec.makeMeasureSpec(context.dp(DIVIDER_TOUCH_DP), MeasureSpec.EXACTLY), exactHeight)
        } else {
            val split = splitPosition(height)
            topReader.measure(exactWidth, MeasureSpec.makeMeasureSpec(split, MeasureSpec.EXACTLY))
            bottomReader.measure(exactWidth, MeasureSpec.makeMeasureSpec(height - split, MeasureSpec.EXACTLY))
            divider.measure(exactWidth, MeasureSpec.makeMeasureSpec(context.dp(DIVIDER_TOUCH_DP), MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        val halfTouch = divider.measuredHeight / 2
        if (isLandscape) {
            val split = splitPosition(width)
            topReader.layout(0, 0, split, height)
            bottomReader.layout(split, 0, width, height)
            val halfWidth = divider.measuredWidth / 2
            divider.layout(split - halfWidth, 0, split + halfWidth, height)
        } else {
            val split = splitPosition(height)
            topReader.layout(0, 0, width, split)
            bottomReader.layout(0, split, width, height)
            divider.layout(0, split - halfTouch, width, split + halfTouch)
        }
        divider.bringToFront()
    }

    private fun splitPosition(size: Int): Int {
        if (size <= 0) return 0
        val minimum = minOf(context.dp(MIN_PANE_DP), size / 3)
        return (size * ratio).toInt().coerceIn(minimum, size - minimum)
    }

    private fun moveDivider(newRatio: Float) {
        ratio = newRatio.coerceIn(0.01f, 0.99f)
        onRatioChanged(ratio)
        requestLayout()
    }

    private fun collapseAt(position: Float): Boolean {
        val threshold = context.dp(EDGE_CLOSE_DP)
        val size = if (isLandscape) width else height
        return when {
            position <= threshold -> {
                onCollapseTop()
                true
            }
            position >= size - threshold -> {
                onCollapseBottom()
                true
            }
            else -> false
        }
    }

    private class SplitDivider(
        context: Context,
        private val onMove: (Float) -> Unit,
        private val onCommit: () -> Unit,
        private val onRelease: (Float) -> Boolean,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.green }
        private var dragging = false
        private val parentLocation = IntArray(2)

        init {
            id = R.id.split_divider
            isClickable = true
            isFocusable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = context.getString(R.string.resize_split)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val lineHeight = context.dp(1).coerceAtLeast(1).toFloat()
            if ((parent as? SplitReaderLayout)?.isLandscape == true) {
                val center = width / 2f
                canvas.drawRect(center - lineHeight / 2f, 0f, center + lineHeight / 2f, height.toFloat(), paint)
            } else {
                val center = height / 2f
                canvas.drawRect(0f, center - lineHeight / 2f, width.toFloat(), center + lineHeight / 2f, paint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val splitParent = parent as? SplitReaderLayout ?: return false
            splitParent.getLocationOnScreen(parentLocation)
            val position = if (splitParent.isLandscape) {
                event.rawX - parentLocation[0]
            } else {
                event.rawY - parentLocation[1]
            }
            val size = if (splitParent.isLandscape) splitParent.width else splitParent.height
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> if (dragging && size > 0) {
                    onMove(position / size)
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging && !onRelease(position)) {
                        if (size > 0) onMove(position / size)
                        onCommit()
                        performClick()
                    }
                    dragging = false
                    parent.requestDisallowInterceptTouchEvent(false)
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            val splitParent = parent as? SplitReaderLayout
                ?: return super.performAccessibilityAction(action, arguments)
            val size = if (splitParent.isLandscape) splitParent.width else splitParent.height
            if (size <= 0) return false
            return when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
                    val direction = if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) 1 else -1
                    val position = if (splitParent.isLandscape) splitParent.divider.left else splitParent.divider.top
                    onMove((position.toFloat() / size + direction * 0.1f)
                        .coerceIn(0.1f, 0.9f))
                    onCommit()
                    true
                }
                else -> super.performAccessibilityAction(action, arguments)
            }
        }
    }

    companion object {
        private const val DIVIDER_TOUCH_DP = 24
        private const val MIN_PANE_DP = 120
        private const val EDGE_CLOSE_DP = 48
    }
}

internal class SplitDropOverlay(context: Context) : View(context) {
    var dropOnTop = true
        set(value) {
            field = value
            invalidate()
        }
    private val shade = Paint().apply { color = Color.argb(58, 40, 99, 78) }
    private val line = Paint().apply {
        color = Palette.green
        strokeWidth = context.dp(2).toFloat()
    }

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            val middle = width / 2f
            if (dropOnTop) canvas.drawRect(0f, 0f, middle, height.toFloat(), shade)
            else canvas.drawRect(middle, 0f, width.toFloat(), height.toFloat(), shade)
            canvas.drawLine(middle, 0f, middle, height.toFloat(), line)
        } else {
            val middle = height / 2f
            if (dropOnTop) canvas.drawRect(0f, 0f, width.toFloat(), middle, shade)
            else canvas.drawRect(0f, middle, width.toFloat(), height.toFloat(), shade)
            canvas.drawLine(0f, middle, width.toFloat(), middle, line)
        }
    }
}
