package com.tastiera45.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.hypot

/**
 * Cursore minimal: un semplice pallino trascinabile con puntatore.
 * - Trascina il pallino ovunque sullo schermo
 * - Fai tap sul pallino per cliccare sulla punta del puntatore
 * - Tieni premuto sul pallino per eseguire un trascinamento/pressione lunga
 * - Tocca la piccola × in alto a destra del pallino per chiuderlo
 */
class RemoteCursorOverlay(
    context: Context,
    private val onClose: () -> Unit,
    private val onRequestOpenAccessibility: () -> Unit,
    private val onTouchableRegionChanged: (Rect) -> Unit
) : FrameLayout(context) {

    val puckView = CursorPuckView(context)
    private var puckX = 400
    private var puckY = 700

    init {
        setBackgroundColor(Color.TRANSPARENT)
        val size = puckView.desiredSizePx()
        addView(puckView, LayoutParams(size, size))
        puckView.onDragDelta = { dx, dy ->
            val sw = resources.displayMetrics.widthPixels.coerceAtLeast(320)
            val sh = resources.displayMetrics.heightPixels.coerceAtLeast(320)
            puckX = (puckX + dx).coerceIn(0, (sw - size).coerceAtLeast(0))
            puckY = (puckY + dy).coerceIn(0, (sh - size).coerceAtLeast(0))
            applyLocalPosition()
        }
        puckView.onCloseRequested = { onClose() }
        puckView.onAccessibilityMissing = { onRequestOpenAccessibility() }
    }

    fun resetPositions(orbOnLeft: Boolean) {
        val sw = resources.displayMetrics.widthPixels.coerceAtLeast(320)
        val sh = resources.displayMetrics.heightPixels.coerceAtLeast(320)
        val size = puckView.desiredSizePx()
        puckX = if (orbOnLeft) (sw * 0.55f).toInt() else (sw * 0.35f).toInt()
        puckY = (sh * 0.45f).toInt()
        puckX = puckX.coerceIn(0, (sw - size).coerceAtLeast(0))
        puckY = puckY.coerceIn(0, (sh - size).coerceAtLeast(0))
        applyLocalPosition()
    }

    fun getPuckBounds(): Rect {
        val size = puckView.desiredSizePx()
        return Rect(puckX, puckY, puckX + size, puckY + size)
    }

    private fun applyLocalPosition() {
        (puckView.layoutParams as? LayoutParams)?.apply {
            leftMargin = puckX
            topMargin = puckY
        }
        puckView.requestLayout()
        onTouchableRegionChanged(getPuckBounds())
    }

    class CursorPuckView(context: Context) : View(context) {
        var onDragDelta: ((Int, Int) -> Unit)? = null
        var onCloseRequested: (() -> Unit)? = null
        var onAccessibilityMissing: (() -> Unit)? = null

        private var downRawX = 0f
        private var downRawY = 0f
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var downTime = 0L
        private var moved = false
        private var isClickFlash = false

        private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E6101014")
            style = Paint.Style.FILL
        }
        private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
        }
        private val pointerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val pointerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }
        private val closeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EE1C1C20")
            style = Paint.Style.FILL
        }
        private val closeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }

        fun desiredSizePx(): Int = (68f * resources.displayMetrics.density).toInt()

        private fun tipOffsetPx(): Float = 14f * resources.displayMetrics.density
        private fun handleCenterOffsetPx(): Float = 38f * resources.displayMetrics.density
        private fun handleRadiusPx(): Float = 20f * resources.displayMetrics.density
        private fun closeCenterX(): Float = 56f * resources.displayMetrics.density
        private fun closeCenterY(): Float = 14f * resources.displayMetrics.density
        private fun closeRadiusPx(): Float = 10f * resources.displayMetrics.density

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val tip = tipOffsetPx()
            val hcx = handleCenterOffsetPx()
            val hcy = handleCenterOffsetPx()
            val hr = handleRadiusPx()

            // 1. Pallino principale di presa
            handleStroke.strokeWidth = if (isClickFlash) 3.4f * d else 2.2f * d
            canvas.drawCircle(hcx, hcy, hr, handleFill)
            canvas.drawCircle(hcx, hcy, hr, handleStroke)
            canvas.drawCircle(hcx, hcy, 3.2f * d, pointerFill)

            // 2. Freccina puntatore in alto a sinistra del pallino (così il dito non copre il bersaglio)
            val s = 14f * d
            pointerStroke.strokeWidth = 1.6f * d
            val arrow = Path().apply {
                moveTo(tip, tip)
                lineTo(tip, tip + s)
                lineTo(tip + s * 0.30f, tip + s * 0.74f)
                lineTo(tip + s * 0.52f, tip + s * 1.12f)
                lineTo(tip + s * 0.68f, tip + s * 1.04f)
                lineTo(tip + s * 0.46f, tip + s * 0.66f)
                lineTo(tip + s * 0.78f, tip + s * 0.66f)
                close()
            }
            canvas.drawPath(arrow, pointerFill)
            canvas.drawPath(arrow, pointerStroke)

            // 3. Piccola × per chiudere il cursore
            val ccx = closeCenterX()
            val ccy = closeCenterY()
            val cr = closeRadiusPx()
            canvas.drawCircle(ccx, ccy, cr, closeFill)
            handleStroke.strokeWidth = 1.2f * d
            canvas.drawCircle(ccx, ccy, cr, handleStroke)
            closeText.textSize = 11f * d
            canvas.drawText("×", ccx, ccy - (closeText.descent() + closeText.ascent()) / 2f, closeText)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val d = resources.displayMetrics.density
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (hypot(event.x - closeCenterX(), event.y - closeCenterY()) <= closeRadiusPx() * 1.35f) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onCloseRequested?.invoke()
                        return true
                    }
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    downTime = System.currentTimeMillis()
                    moved = false
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastRawX).toInt()
                    val dy = (event.rawY - lastRawY).toInt()
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    if (hypot(event.rawX - downRawX, event.rawY - downRawY) > 6f * d) {
                        moved = true
                    }
                    if (dx != 0 || dy != 0) {
                        onDragDelta?.invoke(dx, dy)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val elapsed = System.currentTimeMillis() - downTime
                    if (!moved && elapsed < 350L) {
                        performTapAtPointer()
                    }
                }
            }
            return true
        }

        private fun performTapAtPointer() {
            val svc = RemoteCursorAccessibilityService.instance
            if (svc == null) {
                onAccessibilityMissing?.invoke()
                return
            }
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val targetX = loc[0] + tipOffsetPx()
            val targetY = loc[1] + tipOffsetPx()

            isClickFlash = true
            invalidate()
            postDelayed({ isClickFlash = false; invalidate() }, 130L)
            svc.tap(targetX, targetY)
        }
    }
}
