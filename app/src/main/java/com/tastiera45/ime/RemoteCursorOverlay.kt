package com.tastiera45.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

class RemoteCursorOverlay(
    context: Context,
    private val onClose: () -> Unit,
    private val onRequestOpenAccessibility: () -> Unit,
    private val onTouchableRegionChanged: (Rect) -> Unit
) : FrameLayout(context) {

    private var targetX = 500f
    private var targetY = 800f
    private var renderX = 500f
    private var renderY = 800f
    private var rippleProgress = 0f
    private var isDraggingHold = false
    private var dragStartX = 0f
    private var dragStartY = 0f

    private var padLeft = 60
    private var padTop = 700
    private var padWidth = 0
    private var padHeight = 0

    private val trackpadCard: LinearLayout
    private val pointerCanvas: PointerCanvasView

    init {
        setBackgroundColor(Color.TRANSPARENT)
        val density = resources.displayMetrics.density
        padWidth = (224 * density).toInt()
        padHeight = (206 * density).toInt()

        pointerCanvas = PointerCanvasView(context)
        addView(pointerCanvas, LayoutParams(-1, -1))

        // Card Material Design 3 (Surface Container High + Top Handle + Trackpad + Action Pills)
        trackpadCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            elevation = 10f * density
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F41C1B1F")) // M3 SurfaceContainerHigh
                cornerRadius = 26f * density
                setStroke((1.2f * density).toInt(), Color.parseColor("#49454F")) // M3 OutlineVariant
            }
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
        }

        // 1. Maniglia superiore Material Design per spostare il trackpad
        val handleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (2 * density).toInt(), 0, (6 * density).toInt())
        }
        val pill = View(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#938F99"))
                cornerRadius = 4f * density
            }
        }
        handleRow.addView(pill, LinearLayout.LayoutParams((36 * density).toInt(), (4 * density).toInt()))
        var moveDownX = 0f
        var moveDownY = 0f
        var moveStartL = 0
        var moveStartT = 0
        handleRow.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    moveDownX = ev.rawX; moveDownY = ev.rawY
                    moveStartL = padLeft; moveStartT = padTop
                }
                MotionEvent.ACTION_MOVE -> {
                    val sw = resources.displayMetrics.widthPixels.coerceAtLeast(320)
                    val sh = resources.displayMetrics.heightPixels.coerceAtLeast(320)
                    padLeft = (moveStartL + (ev.rawX - moveDownX).toInt()).coerceIn(8, (sw - padWidth - 8).coerceAtLeast(8))
                    padTop = (moveStartT + (ev.rawY - moveDownY).toInt()).coerceIn(8, (sh - padHeight - 8).coerceAtLeast(8))
                    updateCardPlacement()
                }
            }
            true
        }
        trackpadCard.addView(handleRow)

        // 2. Superficie Touchpad + Barra Scroll laterale
        val surfaceRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val touchSurface = TrackpadSurfaceView(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#25232A"))
                cornerRadius = 18f * density
                setStroke((1f * density).toInt(), Color.parseColor("#36343B"))
            }
        }
        surfaceRow.addView(touchSurface, LinearLayout.LayoutParams(0, -1, 1f).apply {
            rightMargin = (6 * density).toInt()
        })

        val scrollStrip = ScrollStripView(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2B2930"))
                cornerRadius = 14f * density
            }
        }
        surfaceRow.addView(scrollStrip, LinearLayout.LayoutParams((28 * density).toInt(), -1))
        trackpadCard.addView(surfaceRow, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            bottomMargin = (8 * density).toInt()
        })

        // 3. Barra pulsanti Material Design 3
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val clickBtn = m3PillButton("Clic", "#D0BCFF", "#381E72", density) {
            performCursorClick()
        }
        val dragBtn = m3PillButton("Trascina", "#332D41", "#E8DEF8", density) {}
        dragBtn.setOnClickListener {
            isDraggingHold = !isDraggingHold
            if (isDraggingHold) {
                dragStartX = targetX
                dragStartY = targetY
                dragBtn.background = GradientDrawable().apply { setColor(Color.parseColor("#D0BCFF")); cornerRadius = 14f * density }
                dragBtn.setTextColor(Color.parseColor("#381E72"))
            } else {
                dragBtn.background = GradientDrawable().apply { setColor(Color.parseColor("#332D41")); cornerRadius = 14f * density }
                dragBtn.setTextColor(Color.parseColor("#E8DEF8"))
                dispatchSwipe(dragStartX, dragStartY, targetX, targetY, 260L)
            }
        }
        val backBtn = m3PillButton("◁", "#2B2930", "#E6E1E5", density) {
            RemoteCursorAccessibilityService.instance?.performBack() ?: onRequestOpenAccessibility()
        }
        val closeBtn = m3PillButton("✕", "#3F2A2E", "#F2B8B5", density) {
            onClose()
        }

        btnRow.addView(clickBtn, LinearLayout.LayoutParams(0, (34 * density).toInt(), 1.3f).apply { rightMargin = (5 * density).toInt() })
        btnRow.addView(dragBtn, LinearLayout.LayoutParams(0, (34 * density).toInt(), 1.4f).apply { rightMargin = (5 * density).toInt() })
        btnRow.addView(backBtn, LinearLayout.LayoutParams((36 * density).toInt(), (34 * density).toInt()).apply { rightMargin = (5 * density).toInt() })
        btnRow.addView(closeBtn, LinearLayout.LayoutParams((36 * density).toInt(), (34 * density).toInt()))
        trackpadCard.addView(btnRow)

        addView(trackpadCard, LayoutParams(padWidth, padHeight))
    }

    fun detachTrackpadForWindow(): View {
        (trackpadCard.parent as? ViewGroup)?.removeView(trackpadCard)
        return trackpadCard
    }

    fun getPadBounds(): Rect = Rect(padLeft, padTop, padLeft + padWidth, padTop + padHeight)

    fun resetPositions(orbOnLeft: Boolean) {
        val sw = resources.displayMetrics.widthPixels.coerceAtLeast(320)
        val sh = resources.displayMetrics.heightPixels.coerceAtLeast(320)
        targetX = sw * 0.5f
        targetY = sh * 0.42f
        renderX = targetX
        renderY = targetY

        padLeft = if (orbOnLeft) (sw - padWidth - 24).coerceAtLeast(12) else 24
        padTop = (sh - padHeight - (110 * resources.displayMetrics.density).toInt()).coerceAtLeast(40)
        updateCardPlacement()
        pointerCanvas.invalidate()
    }

    private fun updateCardPlacement() {
        (trackpadCard.layoutParams as? LayoutParams)?.apply {
            leftMargin = padLeft
            topMargin = padTop
        }
        trackpadCard.requestLayout()
        onTouchableRegionChanged(getPadBounds())
    }

    private fun performCursorClick() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        rippleProgress = 0.05f
        pointerCanvas.invalidate()
        val svc = RemoteCursorAccessibilityService.instance
        if (svc == null) {
            onRequestOpenAccessibility()
        } else {
            svc.tap(targetX, targetY)
        }
    }

    private fun dispatchSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val svc = RemoteCursorAccessibilityService.instance
        if (svc == null) {
            onRequestOpenAccessibility()
        } else {
            svc.swipe(x1, y1, x2, y2, durationMs)
        }
    }

    private fun m3PillButton(label: String, bgHex: String, fgHex: String, density: Float, onClick: () -> Unit) =
        TextView(context).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(fgHex))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = 14f * density
            }
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }

    // Superficie Trackpad con accelerazione fluida stile Material
    private inner class TrackpadSurfaceView(context: Context) : View(context) {
        private var lastX = 0f
        private var lastY = 0f
        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L
        private var moved = false
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3D3B44") }

        override fun onDraw(canvas: Canvas) {
            val step = 18f * resources.displayMetrics.density
            var y = step
            while (y < height - step * 0.5f) {
                var x = step
                while (x < width - step * 0.5f) {
                    canvas.drawCircle(x, y, 1.4f * resources.displayMetrics.density, dotPaint)
                    x += step
                }
                y += step
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val density = resources.displayMetrics.density
            val sw = resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(320f)
            val sh = resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(320f)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y
                    lastX = event.x; lastY = event.y
                    downTime = System.currentTimeMillis()
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    lastX = event.x; lastY = event.y
                    if (hypot(event.x - downX, event.y - downY) > 6f * density) moved = true

                    // Curva di accelerazione progressiva: precisa ai micro-movimenti, veloce negli spostamenti ampi
                    val speed = hypot(dx, dy) / density
                    val gain = (1.35f + speed * 0.085f).coerceIn(1.35f, 3.4f)
                    targetX = (targetX + dx * gain).coerceIn(4f, sw - 4f)
                    targetY = (targetY + dy * gain).coerceIn(4f, sh - 4f)
                    pointerCanvas.wakeLoop()
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - downTime
                    if (!moved && elapsed < 260L) {
                        performCursorClick()
                    }
                }
            }
            return true
        }
    }

    // Barra verticale per lo scorrimento pagina fluido
    private inner class ScrollStripView(context: Context) : View(context) {
        private var startY = 0f
        private var accumDy = 0f
        private val txtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CAC4D0")
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            txtPaint.textSize = 13f * resources.displayMetrics.density
            canvas.drawText("↕", width / 2f, height / 2f - (txtPaint.descent() + txtPaint.ascent()) / 2f, txtPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = event.y; accumDy = 0f }
                MotionEvent.ACTION_MOVE -> {
                    accumDy += (event.y - startY)
                    startY = event.y
                    if (abs(accumDy) > 28f * resources.displayMetrics.density) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        val scrollSpan = 260f * resources.displayMetrics.density
                        val dir = if (accumDy > 0) 1f else -1f
                        dispatchSwipe(targetX, targetY, targetX, targetY + dir * scrollSpan, 140L)
                        accumDy = 0f
                    }
                }
            }
            return true
        }
    }

    // Puntatore Material Design 3 interpolato a 60fps
    private inner class PointerCanvasView(context: Context) : View(context) {
        private var animating = false
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D0BCFF"); style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1C1B1F")
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }
        private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D0BCFF")
            style = Paint.Style.STROKE
        }

        private val frameRunnable = object : Runnable {
            override fun run() {
                val dx = targetX - renderX
                val dy = targetY - renderY
                var keepGoing = false

                if (abs(dx) > 0.25f || abs(dy) > 0.25f) {
                    renderX += dx * 0.44f
                    renderY += dy * 0.44f
                    keepGoing = true
                } else {
                    renderX = targetX
                    renderY = targetY
                }

                if (rippleProgress > 0f && rippleProgress < 1f) {
                    rippleProgress = (rippleProgress + 0.09f).coerceAtMost(1f)
                    keepGoing = true
                } else if (rippleProgress >= 1f) {
                    rippleProgress = 0f
                }

                invalidate()
                if (keepGoing) {
                    postOnAnimation(this)
                } else {
                    animating = false
                }
            }
        }

        fun wakeLoop() {
            if (!animating) {
                animating = true
                postOnAnimation(frameRunnable)
            }
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(frameRunnable)
            animating = false
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            if (rippleProgress > 0f) {
                ripplePaint.strokeWidth = (2.5f * d * (1f - rippleProgress)).coerceAtLeast(0.5f)
                ripplePaint.alpha = ((1f - rippleProgress) * 220).toInt().coerceIn(0, 255)
                canvas.drawCircle(renderX, renderY, (6f + 22f * rippleProgress) * d, ripplePaint)
            }

            strokePaint.strokeWidth = 2.0f * d
            val s = 18f * d
            val arrow = Path().apply {
                moveTo(renderX, renderY)
                lineTo(renderX, renderY + s)
                lineTo(renderX + s * 0.30f, renderY + s * 0.74f)
                lineTo(renderX + s * 0.52f, renderY + s * 1.15f)
                lineTo(renderX + s * 0.70f, renderY + s * 1.06f)
                lineTo(renderX + s * 0.48f, renderY + s * 0.65f)
                lineTo(renderX + s * 0.82f, renderY + s * 0.65f)
                close()
            }
            canvas.drawPath(arrow, fillPaint)
            canvas.drawPath(arrow, strokePaint)
        }
    }
}
