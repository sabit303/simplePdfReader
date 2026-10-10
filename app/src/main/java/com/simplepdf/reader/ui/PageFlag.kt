package com.simplepdf.reader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import com.simplepdf.reader.R
import kotlin.math.abs
import kotlin.math.roundToInt

class PageFlag(
    context: Context,
    private val onProgress: (Float) -> Unit,
    private val onPage: (Int) -> Unit,
    private val onJump: () -> Unit,
    private val onInteraction: (Boolean) -> Unit,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var downProgress = 0f
    private var dragging = false
    private var progress = 0f
    private var travel = 0f
    var pageCount = 1
    var pageIndex = 0
        private set

    init {
        val radius = context.dp(12).toFloat()
        background = GradientDrawable().apply {
            setColor(Palette.green)
            cornerRadii = floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
        }
        elevation = context.dp(3).toFloat()
        isFocusable = true
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun update(index: Int, fraction: Float) {
        pageIndex = index
        progress = fraction.coerceIn(0f, 1f)
        contentDescription = context.getString(R.string.page_flag_accessibility, index + 1, pageCount)
        updatePosition()
        invalidate()
    }

    fun updatePosition() {
        val parentHeight = (parent as? View)?.height ?: 0
        travel = (parentHeight - height - context.dp(140)).coerceAtLeast(0).toFloat()
        translationY = context.dp(56) + progress * travel
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 13 * resources.displayMetrics.scaledDensity
        val text = (pageIndex + 1).toString()
        val availableWidth = (width - context.dp(16)).coerceAtLeast(1)
        val textWidth = paint.measureText(text)
        if (textWidth > availableWidth) paint.textSize *= availableWidth / textWidth
        val textY = height / 2f - (paint.ascent() + paint.descent()) / 2f
        val x = width / 2f
        canvas.drawText(text, x, textY, paint)
        paint.alpha = 120
        paint.strokeWidth = context.dp(2).toFloat()
        val grip = context.dp(6)
        canvas.drawLine(x - grip, context.dp(7).toFloat(), x + grip, context.dp(7).toFloat(), paint)
        canvas.drawLine(x - grip, height - context.dp(7).toFloat(), x + grip, height - context.dp(7).toFloat(), paint)
        paint.alpha = 255
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.rawY
                downProgress = progress
                dragging = false
                parent.requestDisallowInterceptTouchEvent(true)
                onInteraction(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.rawY - downY) > slop) dragging = true
                if (dragging) onProgress((downProgress + (event.rawY - downY) / travel.coerceAtLeast(1f)).coerceIn(0f, 1f))
            }
            MotionEvent.ACTION_UP -> {
                onInteraction(false)
                if (!dragging) performClick()
                dragging = false
                parent.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent.requestDisallowInterceptTouchEvent(false)
                onInteraction(false)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onJump()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 1f, pageCount.toFloat(), (pageIndex + 1).toFloat())
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        if (pageIndex > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        if (pageIndex < pageCount - 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val target = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> pageIndex + 1
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> pageIndex - 1
            android.R.id.accessibilityActionSetProgress ->
                (arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) ?: return false).roundToInt() - 1
            else -> return super.performAccessibilityAction(action, arguments)
        }
        onPage(target.coerceIn(0, pageCount - 1))
        return true
    }
}
