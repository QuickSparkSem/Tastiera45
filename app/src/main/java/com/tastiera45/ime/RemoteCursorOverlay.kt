package com.tastiera45.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.hypot

/**
 * Puntatore tondo minimal guidato dal trascinamento sullo schermo:
 * - Trascina il dito -> guida il piccolo puntatore tondo ovunque sullo schermo
 * - Tap singolo -> Click Sinistro alle coordinate del puntatore tondo
 * - Doppio Tap -> Click Destro (pressione contestuale) alle coordinate del puntatore tondo
 */
class RemoteCursorOverlay(
    context: Context,
    private val onClose: () -> Unit,
    private val onRequestOpenAccessibility: () -> Unit,
    private val onTouchableRegionChanged: () -> Unit
) : FrameLayout(context) {

    private val pointerView = PointerDotView(context)
    private val puckView = CursorPuckView(context)
    private val puckLP: LayoutParams
    private val pointerLP: LayoutParams

    private var pointerX = 0f
    private var pointerY = 0f

    init {
        val ptrSize = pointerView.desiredSizePx()
        pointerLP = LayoutParams(ptrSize, ptrSize, Gravity.TOP or Gravity.START)
        addView(pointerView, pointerLP)

        val puckSize = puckView.desiredSizePx()
        puckLP = LayoutParams(puckSize, puckSize, Gravity.TOP or Gravity.START)
        addView(puckView, puckLP)

        puckView.onMovePointerDelta = { dx, dy ->
            val sw = (width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
            val sh = (height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels).toFloat()
            pointerX = (pointerX + dx).coerceIn(4f, sw - 4f)
            pointerY = (pointerY + dy).coerceIn(4f, sh - 4f)
            updatePointerLayout()
        }
        puckView.onRelocatePuckDelta = { dx, dy ->
            val sw = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val sh = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            puckLP.leftMargin = (puckLP.leftMargin + dx).coerceIn(0, (sw - puckSize).coerceAtLeast(0))
            puckLP.topMargin = (puckLP.topMargin + dy).coerceIn(0, (sh - puckSize).coerceAtLeast(0))
            puckView.requestLayout()
            onTouchableRegionChanged()
        }
        puckView.onLeftClick = {
            pointerView.triggerClickPulse(isRightClick = false)
            val svc = RemoteCursorAccessibilityService.instance
            if (svc != null) svc.dispatchClick(pointerX, pointerY)
            else onRequestOpenAccessibility()
        }
        puckView.onRightClick = {
            pointerView.triggerClickPulse(isRightClick = true)
            val svc = RemoteCursorAccessibilityService.instance
            if (svc != null) svc.dispatchRightClick(pointerX, pointerY)
            else onRequestOpenAccessibility()
        }
        puckView.onCloseRequested = onClose
    }

    fun resetPositions(leftSide: Boolean) {
        val sw = resources.displayMetrics.widthPixels.coerceAtLeast(320)
        val sh = resources.displayMetrics.heightPixels.coerceAtLeast(320)
        val puckSize = puckView.desiredSizePx()

        pointerX = sw * 0.50f
        pointerY = sh * 0.42f
        updatePointerLayout()

        puckLP.leftMargin = if (leftSide) (sw * 0.14f).toInt() else (sw * 0.68f).toInt()
        puckLP.topMargin = (sh * 0.62f).toInt().coerceIn(0, (sh - puckSize).coerceAtLeast(0))
        puckView.requestLayout()
    }

    private fun updatePointerLayout() {
        val half = pointerView.desiredSizePx() / 2
        pointerLP.leftMargin = (pointerX - half).toInt()
        pointerLP.topMargin = (pointerY - half).toInt()
        pointerView.requestLayout()
    }

    fun getPuckBounds(): Rect {
        val size = puckView.desiredSizePx()
        return Rect(puckLP.leftMargin, puckLP.topMargin, puckLP.leftMargin + size, puckLP.topMargin + size)
    }

    /**
     * Controller globale sopra tutte le app (usato da FloatingOrbService)
     */
    class GlobalCursorController(
        private val context: Context,
        private val wm: WindowManager,
        windowType: Int,
        private val onClose: () -> Unit,
        private val onAccessibilityMissing: () -> Unit
    ) {
        private val pointerView = PointerDotView(context)
        private val pointerParams: WindowManager.LayoutParams
        private val puckView = CursorPuckView(context)
        private val puckParams: WindowManager.LayoutParams

        private var pointerX = 0f
        private var pointerY = 0f
        private var shown = false

        init {
            val sw = context.resources.displayMetrics.widthPixels.coerceAtLeast(320)
            val sh = context.resources.displayMetrics.heightPixels.coerceAtLeast(320)
            pointerX = sw * 0.50f
            pointerY = sh * 0.42f

            val ptrSize = pointerView.desiredSizePx()
            pointerParams = WindowManager.LayoutParams(
                ptrSize, ptrSize,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (pointerX - ptrSize / 2f).toInt()
                y = (pointerY - ptrSize / 2f).toInt()
            }

            val puckSize = puckView.desiredSizePx()
            puckParams = WindowManager.LayoutParams(
                puckSize, puckSize,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (sw * 0.44f).toInt().coerceIn(0, (sw - puckSize).coerceAtLeast(0))
                y = (sh * 0.62f).toInt().coerceIn(0, (sh - puckSize).coerceAtLeast(0))
            }

            puckView.onMovePointerDelta = { dx, dy ->
                val curW = context.resources.displayMetrics.widthPixels.coerceAtLeast(320).toFloat()
                val curH = context.resources.displayMetrics.heightPixels.coerceAtLeast(320).toFloat()
                pointerX = (pointerX + dx).coerceIn(4f, curW - 4f)
                pointerY = (pointerY + dy).coerceIn(4f, curH - 4f)
                pointerParams.x = (pointerX - ptrSize / 2f).toInt()
                pointerParams.y = (pointerY - ptrSize / 2f).toInt()
                try { wm.updateViewLayout(pointerView, pointerParams) } catch (_: Throwable) {}
            }

            puckView.onRelocatePuckDelta = { dx, dy ->
                val curW = context.resources.displayMetrics.widthPixels.coerceAtLeast(320)
                val curH = context.resources.displayMetrics.heightPixels.coerceAtLeast(320)
                puckParams.x = (puckParams.x + dx).coerceIn(0, (curW - puckSize).coerceAtLeast(0))
                puckParams.y = (puckParams.y + dy).coerceIn(0, (curH - puckSize).coerceAtLeast(0))
                try { wm.updateViewLayout(puckView, puckParams) } catch (_: Throwable) {}
            }

            puckView.onLeftClick = {
                pointerView.triggerClickPulse(isRightClick = false)
                val svc = RemoteCursorAccessibilityService.instance
                if (svc != null) svc.dispatchClick(pointerX, pointerY)
                else onAccessibilityMissing()
            }

            puckView.onRightClick = {
                pointerView.triggerClickPulse(isRightClick = true)
                val svc = RemoteCursorAccessibilityService.instance
                if (svc != null) svc.dispatchRightClick(pointerX, pointerY)
                else onAccessibilityMissing()
            }

            puckView.onCloseRequested = {
                dismiss()
                onClose()
            }
        }

        fun show() {
            if (shown) return
            shown = true
            try { wm.addView(pointerView, pointerParams) } catch (_: Throwable) {}
            try { wm.addView(puckView, puckParams) } catch (_: Throwable) {}
        }

        fun dismiss() {
            if (!shown) return
            shown = false
            try { wm.removeView(puckView) } catch (_: Throwable) {}
            try { wm.removeView(pointerView) } catch (_: Throwable) {}
        }
    }

    /**
     * Piccolo puntatore tondo minimal (14dp + anello di feedback al click sinistro / destro)
     */
    class PointerDotView(context: Context) : View(context) {
        private var pulseStartMs = 0L
        private var pulseRightClick = false

        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(110, 0, 0, 0)
            style = Paint.Style.FILL
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
        }
        private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }

        fun desiredSizePx(): Int = (36f * resources.displayMetrics.density).toInt()

        fun triggerClickPulse(isRightClick: Boolean) {
            pulseStartMs = SystemClock.uptimeMillis()
            pulseRightClick = isRightClick
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val cx = width / 2f
            val cy = height / 2f
            val dotR = 6.5f * d

            // Onda di feedback minimal al click sinistro o destro
            val elapsed = SystemClock.uptimeMillis() - pulseStartMs
            if (pulseStartMs > 0L && elapsed in 0..340L) {
                val p = elapsed / 340f
                val alpha = ((1f - p) * 230f).toInt().coerceIn(0, 230)
                pulsePaint.color = Color.argb(alpha, 255, 255, 255)
                pulsePaint.strokeWidth = 1.8f * d
                val ringR = dotR + p * (10f * d)
                canvas.drawCircle(cx, cy, ringR, pulsePaint)
                if (pulseRightClick) {
                    canvas.drawCircle(cx, cy, (ringR - 3.5f * d).coerceAtLeast(dotR), pulsePaint)
                }
                postInvalidateOnAnimation()
            }

            // Puntatore tondo minimal
            canvas.drawCircle(cx, cy + 1.2f * d, dotR + 1.2f * d, shadowPaint)
            canvas.drawCircle(cx, cy, dotR, fillPaint)
            strokePaint.strokeWidth = 1.6f * d
            canvas.drawCircle(cx, cy, dotR, strokePaint)
            canvas.drawCircle(cx, cy, 1.8f * d, centerDotPaint)
        }
    }

    /**
     * Pad tondo minimal per guidare il puntatore:
     * - Trascinamento -> muove il puntatore tondo su tutto lo schermo
     * - Tap singolo -> Click Sinistro
     * - Doppio Tap -> Click Destro
     * - Pressione prolungata -> riposiziona il pad stesso
     * - Micro-pulsante ✕ in alto a destra -> chiude il cursore
     */
    class CursorPuckView(context: Context) : View(context) {
        var onMovePointerDelta: ((Float, Float) -> Unit)? = null
        var onRelocatePuckDelta: ((Int, Int) -> Unit)? = null
        var onLeftClick: (() -> Unit)? = null
        var onRightClick: (() -> Unit)? = null
        var onCloseRequested: (() -> Unit)? = null

        private var downRawX = 0f
        private var downRawY = 0f
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var isGuidingPointer = false
        private var isRelocatingPuck = false

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E608080A")
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#45FFFFFF")
            style = Paint.Style.STROKE
        }
        private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val closeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F018181C")
            style = Paint.Style.FILL
        }
        private val closeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }

        private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isCloseBadgeHit(e.x, e.y)) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onCloseRequested?.invoke()
                    return true
                }
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onLeftClick?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isCloseBadgeHit(e.x, e.y)) return true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onRightClick?.invoke()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!isGuidingPointer && !isCloseBadgeHit(e.x, e.y)) {
                    isRelocatingPuck = true
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    invalidate()
                }
            }
        })

        fun desiredSizePx(): Int = (64f * resources.displayMetrics.density).toInt()

        private fun closeCenter(): Pair<Float, Float> {
            val d = resources.displayMetrics.density
            return (width - 11f * d) to (11f * d)
        }

        private fun isCloseBadgeHit(x: Float, y: Float): Boolean {
            val d = resources.displayMetrics.density
            val (cx, cy) = closeCenter()
            return hypot(x - cx, y - cy) <= 13f * d
        }

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val cx = width / 2f
            val cy = height / 2f
            val mainR = (minOf(width, height) / 2f) - 6f * d
            if (mainR <= 0f) return

            borderPaint.color = if (isRelocatingPuck) Color.parseColor("#D0BCFF") else Color.parseColor("#787882")
            borderPaint.strokeWidth = 1.6f * d

            canvas.drawCircle(cx, cy, mainR, bgPaint)
            canvas.drawCircle(cx, cy, mainR, borderPaint)

            innerRingPaint.strokeWidth = 1.0f * d
            canvas.drawCircle(cx, cy, mainR * 0.44f, innerRingPaint)
            canvas.drawCircle(cx, cy, 2.2f * d, centerDotPaint)

            // Micro badge ✕ minimal per chiudere
            val (bx, by) = closeCenter()
            val br = 8.5f * d
            canvas.drawCircle(bx, by, br, closeBgPaint)
            borderPaint.strokeWidth = 1.1f * d
            canvas.drawCircle(bx, by, br, borderPaint)
            closeTextPaint.textSize = 9.5f * d
            canvas.drawText("✕", bx, by - (closeTextPaint.descent() + closeTextPaint.ascent()) / 2f, closeTextPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val d = resources.displayMetrics.density
            gestureDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    isGuidingPointer = false
                    isRelocatingPuck = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val movedTotal = hypot(event.rawX - downRawX, event.rawY - downRawY)
                    if (!isGuidingPointer && !isRelocatingPuck && movedTotal > 6f * d) {
                        isGuidingPointer = true
                    }
                    val dx = event.rawX - lastRawX
                    val dy = event.rawY - lastRawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY

                    if (isRelocatingPuck) {
                        onRelocatePuckDelta?.invoke(dx.toInt(), dy.toInt())
                    } else if (isGuidingPointer) {
                        // Fattore 1.45x per guidare il puntatore tondo su tutto lo schermo comodamente con un dito
                        onMovePointerDelta?.invoke(dx * 1.45f, dy * 1.45f)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isGuidingPointer = false
                    if (isRelocatingPuck) {
                        isRelocatingPuck = false
                        invalidate()
                    }
                }
            }
            return true
        }
    }
}
