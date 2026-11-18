package com.tastiera45.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

class RemoteCursorOverlay(
    context: Context,
    private val onClose: () -> Unit,
    private val onRequestOpenAccessibility: () -> Unit,
    private val onTouchableRegionChanged: (Rect) -> Unit
) : View(context) {

    // Posizione del pad circolare concentrico (dove appoggi il pollice)
    private var padX = 0f
    private var padY = 0f
    private val padRadiusDp = 76f
    private var padRadius = 210f

    // Posizione del puntatore bianco sullo schermo
    private var cursorX = 0f
    private var cursorY = 0f

    private var initialized = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downTouchX = 0f
    private var downTouchY = 0f
    private var downTime = 0L
    private var clickFlashAlpha = 0

    private val sensitivity = 2.35f

    private val padOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(185, 195, 202, 210)
        style = Paint.Style.FILL
    }
    private val padMidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(205, 130, 138, 148)
        style = Paint.Style.FILL
    }
    private val padCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val padStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val cursorGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val cursorDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val closeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 35, 38, 44)
        style = Paint.Style.FILL
    }
    private val closeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density
        padRadius = padRadiusDp * density
        closeTextPaint.textSize = 15f * density

        if (!initialized && w > 0 && h > 0) {
            padX = w * 0.68f
            padY = h * 0.56f
            cursorX = w * 0.35f
            cursorY = h * 0.28f
            initialized = true
        }
        notifyTouchableBounds()
    }

    fun resetPositions(isLeftHanded: Boolean) {
        if (width > 0 && height > 0) {
            padX = if (isLeftHanded) width * 0.32f else width * 0.68f
            padY = height * 0.56f
            cursorX = width * 0.50f
            cursorY = height * 0.25f
            notifyTouchableBounds()
            invalidate()
        }
    }

    private fun notifyTouchableBounds() {
        val margin = (padRadius * 1.35f).toInt()
        val rect = Rect(
            (padX - margin).toInt().coerceAtLeast(0),
            (padY - margin).toInt().coerceAtLeast(0),
            (padX + margin).toInt().coerceAtMost(width),
            (padY + margin).toInt().coerceAtMost(height)
        )
        onTouchableRegionChanged(rect)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density

        // Fascio luminoso che collega il pad al puntatore (come nell'allegato 2)
        beamPaint.shader = LinearGradient(
            cursorX, cursorY, padX, padY,
            Color.argb(150, 255, 255, 255),
            Color.argb(15, 255, 255, 255),
            Shader.TileMode.CLAMP
        )
        canvas.drawLine(cursorX, cursorY, padX, padY, beamPaint)

        // Puntatore remoto sullo schermo
        canvas.drawCircle(cursorX, cursorY, 22f * density, cursorGlowPaint)
        canvas.drawCircle(cursorX, cursorY, 11f * density, cursorDotPaint)

        if (clickFlashAlpha > 0) {
            val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(clickFlashAlpha, 90, 175, 255)
                style = Paint.Style.STROKE
                strokeWidth = 6f * density
            }
            canvas.drawCircle(cursorX, cursorY, 28f * density, flashPaint)
            clickFlashAlpha = (clickFlashAlpha - 25).coerceAtLeast(0)
            postInvalidateDelayed(16L)
        }

        // Pad circolare concentrico (Remote Cursor)
        canvas.drawCircle(padX, padY, padRadius, padOuterPaint)
        canvas.drawCircle(padX, padY, padRadius, padStrokePaint)
        canvas.drawCircle(padX, padY, padRadius * 0.56f, padMidPaint)
        canvas.drawCircle(padX, padY, padRadius * 0.22f, padCenterPaint)

        // Pulsante chiusura X sul bordo superiore del pad
        val closeX = padX + padRadius * 0.78f
        val closeY = padY - padRadius * 0.78f
        val closeR = 16f * density
        canvas.drawCircle(closeX, closeY, closeR, closeBgPaint)
        canvas.drawText("✕", closeX, closeY + (5f * density), closeTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        val closeX = padX + padRadius * 0.78f
        val closeY = padY - padRadius * 0.78f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Controllo click sul pulsante chiusura X
                if (hypot(event.x - closeX, event.y - closeY) <= 28f * density) {
                    onClose()
                    return true
                }
                downTouchX = event.x
                downTouchY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                downTime = System.currentTimeMillis()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                lastTouchX = event.x
                lastTouchY = event.y

                // Sposta il puntatore remoto con moltiplicatore per raggiungere tutto lo schermo
                cursorX = (cursorX + dx * sensitivity).coerceIn(10f, width - 10f)
                cursorY = (cursorY + dy * sensitivity).coerceIn(10f, height - 10f)

                // Se il dito arriva al bordo del pad, trascina leggermente anche il pad
                val distFromPadCenter = hypot(event.x - padX, event.y - padY)
                if (distFromPadCenter > padRadius * 0.72f) {
                    padX = (padX + dx * 0.45f).coerceIn(padRadius, width - padRadius)
                    padY = (padY + dy * 0.45f).coerceIn(padRadius, height - padRadius)
                    notifyTouchableBounds()
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                val totalMove = hypot(event.x - downTouchX, event.y - downTouchY)
                val duration = System.currentTimeMillis() - downTime
                // Un tap rapido sul pad esegue il click reale nel punto del puntatore
                if (totalMove < 14f * density && duration < 300L) {
                    if (!CursorAccessibilityService.isEnabled()) {
                        onRequestOpenAccessibility()
                    } else {
                        val loc = IntArray(2)
                        getLocationOnScreen(loc)
                        val realScreenX = loc[0] + cursorX
                        val realScreenY = loc[1] + cursorY
                        clickFlashAlpha = 255
                        invalidate()
                        CursorAccessibilityService.performClickAt(realScreenX, realScreenY)
                    }
                }
                return true
            }
        }
        return true
    }
}
