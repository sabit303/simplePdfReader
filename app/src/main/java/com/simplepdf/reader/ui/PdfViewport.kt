package com.simplepdf.reader.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.OverScroller
import com.simplepdf.reader.R
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class PdfViewport(
    context: Context,
    private val engine: PdfEngine,
    private val onPosition: (Int, Float) -> Unit,
    private val onScrollDirection: (Boolean) -> Unit,
    private val onTap: () -> Unit,
) : View(context) {
    private val transform = PdfTransform()
    private var geometry: PdfGeometry? = null
    private var sizes: List<PdfPageSize> = emptyList()
    private var initialPage = 0
    private var disposed = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pageRect = RectF()
    private val scroller = OverScroller(context)
    private var zoomAnimation: ValueAnimator? = null
    private var rendering = false
    private var pendingRequest = false
    private var pinching = false
    private var multiplePointers = false
    private var dragDistance = 0f
    private var lastDirection: Boolean? = null
    private var announcedPage = -1
    private val failedPages = mutableSetOf<Int>()
    private val cache = object : LruCache<Int, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceIn(16L * 1024 * 1024, 48L * 1024 * 1024).toInt(),
    ) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.allocationByteCount
    }
    val zoom: Float get() = transform.scale
    val currentPage: Int get() = geometry?.pageAt(transform.y / transform.scale) ?: initialPage
    val scrollPosition: Float get() = transform.y
    val isFlinging: Boolean get() = !scroller.isFinished

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinching = true
                stopMotion()
                return geometry != null
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                transform.zoomTo(transform.scale * detector.scaleFactor, detector.focusX, detector.focusY)
                positionChanged()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                pinching = false
                scheduleRender()
            }
        })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean {
            stopMotion()
            dragDistance = 0f
            lastDirection = null
            return true
        }

        override fun onScroll(first: MotionEvent?, current: MotionEvent, dx: Float, dy: Float): Boolean {
            if (multiplePointers || scaleDetector.isInProgress) return true
            val oldY = transform.y
            transform.moveTo(transform.x + dx, transform.y + dy)
            val moved = transform.y - oldY
            dragDistance = if ((dragDistance > 0) == (moved > 0)) dragDistance + moved else moved
            if (abs(dragDistance) > context.dp(12)) {
                val down = dragDistance > 0
                if (lastDirection != down) {
                    onScrollDirection(down)
                    lastDirection = down
                }
                dragDistance = 0f
            }
            positionChanged()
            return true
        }

        override fun onFling(first: MotionEvent?, current: MotionEvent, vx: Float, vy: Float): Boolean {
            if (multiplePointers || scaleDetector.isInProgress) return true
            scroller.fling(
                transform.x.roundToInt(), transform.y.roundToInt(),
                -vx.roundToInt(), -vy.roundToInt(),
                0, transform.maxX.toInt(), 0, transform.maxY.toInt(),
            )
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            if (!multiplePointers) performClick()
            return true
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            animateZoom(if (transform.scale > 1.1f) 1f else 2.5f, event.x, event.y)
            return true
        }
    })

    init {
        setBackgroundColor(Palette.background)
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setDocument(pages: List<PdfPageSize>, pageIndex: Int) {
        require(pages.isNotEmpty())
        sizes = pages
        initialPage = pageIndex.coerceIn(pages.indices)
        rebuildGeometry()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        stopMotion()
        rebuildGeometry()
    }

    private fun rebuildGeometry() {
        if (sizes.isEmpty() || width == 0 || height == 0) return
        val old = geometry
        val page = currentPage
        val fraction = if (old == null) 0f else
            ((transform.y / transform.scale - old.tops[page]) / old.heights[page]).coerceIn(0f, 1f)
        val updated = PdfGeometry(sizes, width.toFloat(), context.dp(8).toFloat(), context.dp(12).toFloat())
        geometry = updated
        transform.setBounds(width.toFloat(), height.toFloat(), updated.totalHeight)
        transform.moveTo(transform.x, (updated.tops[page] + updated.heights[page] * fraction) * transform.scale)
        positionChanged()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val layout = geometry ?: return
        val first = layout.pageAt(transform.y / transform.scale)
        val last = layout.pageAt((transform.y + height) / transform.scale)
        canvas.save()
        canvas.translate(-transform.x, -transform.y)
        canvas.scale(transform.scale, transform.scale)
        for (index in first..last) {
            pageRect.set(layout.margin, layout.tops[index],
                layout.margin + layout.pageWidth, layout.tops[index] + layout.heights[index])
            paint.color = Color.WHITE
            canvas.drawRect(pageRect, paint)
            val bitmap = cache.get(index)
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null, pageRect, paint)
            } else {
                paint.color = Palette.muted
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = context.dp(14).toFloat()
                val text = context.getString(
                    if (index in failedPages) R.string.render_error else R.string.page_image, index + 1)
                canvas.drawText(text, pageRect.centerX(), pageRect.centerY(), paint)
            }
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) multiplePointers = false
        if (event.pointerCount > 1) multiplePointers = true
        parent.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            pinching = false
            parent.requestDisallowInterceptTouchEvent(false)
            scheduleRender()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        failedPages.clear()
        scheduleRender()
        onTap()
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            transform.moveTo(scroller.currX.toFloat(), scroller.currY.toFloat())
            positionChanged()
            postInvalidateOnAnimation()
        } else {
            scheduleRender()
        }
    }

    fun jumpTo(index: Int) {
        val layout = checkNotNull(geometry) { "The PDF layout is not ready." }
        require(index in sizes.indices)
        stopMotion()
        transform.moveTo(transform.x, layout.tops[index] * transform.scale)
        positionChanged()
    }

    fun scrollToProgress(progress: Float) {
        require(progress.isFinite())
        stopMotion()
        transform.moveTo(transform.x, progress.coerceIn(0f, 1f) * transform.maxY)
        positionChanged()
    }

    private fun animateZoom(target: Float, x: Float, y: Float) {
        stopMotion()
        zoomAnimation = ValueAnimator.ofFloat(transform.scale, target).apply {
            duration = 200
            addUpdateListener {
                transform.zoomTo(it.animatedValue as Float, x, y)
                positionChanged()
            }
            start()
        }
    }

    private fun stopMotion() {
        scroller.forceFinished(true)
        zoomAnimation?.cancel()
        zoomAnimation = null
    }

    private fun positionChanged() {
        val page = currentPage
        if (announcedPage != page) {
            announcedPage = page
            contentDescription = context.getString(R.string.page_image, page + 1)
        }
        onPosition(page, if (transform.maxY == 0f) 0f else transform.y / transform.maxY)
        postInvalidateOnAnimation()
        scheduleRender()
    }

    private fun scheduleRender() {
        if (disposed || rendering || pendingRequest || geometry == null) return
        pendingRequest = true
        postOnAnimation {
            pendingRequest = false
            if (!disposed) renderNext()
        }
    }

    private fun renderNext() {
        val layout = geometry ?: return
        if (rendering) return
        val first = layout.pageAt(transform.y / transform.scale)
        val last = layout.pageAt((transform.y + height) / transform.scale)
        // Prioritize visible pages; only one native render is queued so flings cannot build a backlog.
        val desired = (layout.pageWidth * if (pinching || !scroller.isFinished) 1f else transform.scale)
            .toInt().coerceAtLeast(1)
        val pixelsPerPage = cache.maxSize().toDouble() / (last - first + 2) / 4
        fun targetWidth(index: Int) = sizes[index].renderWidth(
            minOf(desired, sqrt(pixelsPerPage / sizes[index].aspectRatio).toInt().coerceAtLeast(1)))
        var index = (first..last).firstOrNull {
            val cached = cache.get(it)
            it !in failedPages && (cached == null || cached.width < targetWidth(it))
        }
        var requested = index?.let(::targetWidth) ?: 0
        if (index == null) {
            index = listOf(last + 1, first - 1).firstOrNull {
                if (it !in sizes.indices || it in failedPages || cache.get(it) != null) false
                else {
                    val width = sizes[it].renderWidth(layout.pageWidth.toInt())
                    val bytes = width.toDouble() * width * sizes[it].aspectRatio * 4
                    bytes + cache.size() <= cache.maxSize()
                }
            } ?: return
            requested = sizes[index].renderWidth(layout.pageWidth.toInt())
        }
        val page = index
        rendering = true
        engine.render(page, requested) { result ->
            rendering = false
            if (disposed) {
                result.getOrNull()?.recycle()
                return@render
            }
            result.fold(
                onSuccess = { bitmap ->
                    cache.put(page, bitmap)
                    postInvalidateOnAnimation()
                },
                onFailure = { error ->
                    failedPages.add(page)
                    Log.e("PdfViewport", "Could not render PDF page ${page + 1}", error)
                    invalidate()
                },
            )
            scheduleRender()
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.zoom_in, context.getString(R.string.zoom_in)))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.zoom_out, context.getString(R.string.zoom_out)))
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
                stopMotion()
                val direction = if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) 1 else -1
                transform.moveTo(transform.x, transform.y + height * 0.8f * direction)
                positionChanged()
            }
            R.id.zoom_in -> animateZoom((transform.scale * 1.5f).coerceAtMost(5f), width / 2f, height / 2f)
            R.id.zoom_out -> animateZoom((transform.scale / 1.5f).coerceAtLeast(1f), width / 2f, height / 2f)
            else -> return super.performAccessibilityAction(action, arguments)
        }
        return true
    }

    fun dispose() {
        disposed = true
        stopMotion()
        cache.evictAll()
    }
}
