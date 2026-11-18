package com.tastiera45.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

class AngledKeyboardView(
    context: Context,
    private val listener: KeyboardActionListener
) : View(context) {

    interface KeyboardActionListener {
        fun onCommitText(text: String)
        fun onDeleteChar()
        fun onEnterPressed()
        fun onSwitchIme()
        fun onMoveTextCursor(steps: Int)
        fun onSwipeWordDecoded(candidates: List<String>)
        fun onDragFloatingKeyboard(deltaScreenY: Float)
    }

    data class KeyDef(
        val code: String,
        val label: String,
        val subLabel: String = "",
        val weight: Float = 1f,
        val isActionBlue: Boolean = false,
        val isSpecial: Boolean = false
    )

    private var isLeftSide = false
    private var scaleFactor = 0.80f
    private var bgOpacity = 0.92f
    private var tiltAngleDeg = -28f
    private var letterSizeScale = 1.0f
    private var keySpacingScale = 0.25f

    private var isShifted = false
    private var isCapsLock = false
    private var isSymbolsMode = false
    private var actionLabel = "🔍"

    // Geometria del cerchio costruito intorno al lato e all'angolo del pollice
    private var circleCenterX = 0f
    private var circleCenterY = 0f
    private var circleRadius = 0f
    private val domeClipPath = Path()

    // Matrici per inclinare tastiera e tasti lungo l'arco del pollice
    private val forwardMatrix = Matrix()
    private val inverseMatrix = Matrix()

    private val computedKeys = mutableListOf<Pair<KeyDef, RectF>>()
    private val letterCenters = mutableMapOf<Char, PointF>()

    private enum class GestureMode {
        NONE, KEY_TAP, SWIPE_TYPING, SPACE_CURSOR, DRAG_POSITION
    }

    private var gestureMode = GestureMode.NONE
    private var activeKey: KeyDef? = null
    private var lastRawY = 0f
    private var downLocalX = 0f
    private var downLocalY = 0f
    private var lastCursorDragX = 0f
    private var lastShiftTapTime = 0L
    private var longPressTriggered = false

    private val swipePathPoints = mutableListOf<PointF>()
    private val swipeDrawPath = Path()
    private val swipeDecoder = SwipeDecoder(context)

    private val handler = Handler(Looper.getMainLooper())

    private val repeatDeleteRunnable = object : Runnable {
        override fun run() {
            if (activeKey?.code == "DEL") {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                listener.onDeleteChar()
                handler.postDelayed(this, 55L)
            }
        }
    }

    private val longPressRunnable = Runnable {
        val key = activeKey ?: return@Runnable
        if (gestureMode == GestureMode.KEY_TAP) {
            longPressTriggered = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (key.code == "SPACE") {
                gestureMode = GestureMode.SPACE_CURSOR
                invalidate()
            } else if (key.subLabel.isNotEmpty()) {
                listener.onCommitText(key.subLabel)
                activeKey = null
                invalidate()
            } else {
                val accent = italianAccentFor(key.label.lowercase())
                if (accent != null) {
                    val out = if (isShifted || isCapsLock) accent.uppercase() else accent
                    listener.onCommitText(out)
                    if (isShifted && !isCapsLock) isShifted = false
                    activeKey = null
                    invalidate()
                }
            }
        }
    }

    // Stili minimal AMOLED
    private val domeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val domeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2A30")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val mainTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8E8E93")
        textAlign = Paint.Align.CENTER
    }
    private val keyHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(52, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val blueActionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4B7BD6")
        style = Paint.Style.FILL
    }
    private val swipeTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(185, 96, 165, 250)
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = CornerPathEffect(20f)
    }

    fun setLeftHanded(left: Boolean) {
        isLeftSide = left
        rebuildCircularGeometry()
        invalidate()
    }

    fun isLeftHandedMode(): Boolean = isLeftSide

    fun setScaleFactor(scale: Float) {
        val clamped = scale.coerceIn(0.52f, 0.98f)
        if (abs(clamped - scaleFactor) > 0.005f) {
            scaleFactor = clamped
            requestLayout()
            invalidate()
        }
    }

    fun setOpacity(opacity: Float) {
        bgOpacity = opacity.coerceIn(0.15f, 1.0f)
        invalidate()
    }

    fun setTiltAngle(angleDeg: Float) {
        val clamped = angleDeg.coerceIn(-90f, 90f)
        if (abs(clamped - tiltAngleDeg) > 0.2f) {
            tiltAngleDeg = clamped
            rebuildCircularGeometry()
            invalidate()
        }
    }

    fun setLetterSizeScale(scale: Float) {
        val clamped = scale.coerceIn(0.60f, 1.50f)
        if (abs(clamped - letterSizeScale) > 0.005f) {
            letterSizeScale = clamped
            invalidate()
        }
    }

    fun setKeySpacingScale(scale: Float) {
        val clamped = scale.coerceIn(0.0f, 1.0f)
        if (abs(clamped - keySpacingScale) > 0.005f) {
            keySpacingScale = clamped
            rebuildCircularGeometry()
            invalidate()
        }
    }

    fun setActionIcon(symbol: String) {
        actionLabel = symbol
        buildKeys()
        invalidate()
    }

    fun appendCircleToRegion(targetRegion: Region, offsetX: Int, offsetY: Int) {
        if (width <= 0 || height <= 0 || circleRadius <= 0f) return
        val steps = 22
        val topY = (circleCenterY - circleRadius).coerceAtLeast(0f)
        val botY = (circleCenterY + circleRadius).coerceAtMost(height.toFloat())
        val stepH = (botY - topY) / steps
        if (stepH <= 0f) return

        for (i in 0 until steps) {
            val y1 = topY + i * stepH
            val y2 = y1 + stepH
            val midY = (y1 + y2) / 2f
            val dy = abs(midY - circleCenterY)
            if (dy < circleRadius) {
                val halfChord = sqrt(circleRadius * circleRadius - dy * dy)
                val x1 = (circleCenterX - halfChord).coerceIn(0f, width.toFloat()).toInt()
                val x2 = (circleCenterX + halfChord).coerceIn(0f, width.toFloat()).toInt()
                if (x2 > x1) {
                    targetRegion.op(
                        Rect(offsetX + x1, offsetY + y1.toInt(), offsetX + x2, offsetY + y2.toInt()),
                        Region.Op.UNION
                    )
                }
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels
        val panelW = (screenW * scaleFactor).toInt().coerceAtLeast((215 * density).toInt())
        val panelH = (panelW * 1.18f).toInt()
        setMeasuredDimension(panelW, panelH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildCircularGeometry()
    }

    private fun rebuildCircularGeometry() {
        if (width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()

        // Cerchio costruito intorno al lato e all'angolo del pollice
        circleRadius = w * 0.94f
        circleCenterX = if (isLeftSide) w * 0.02f else w * 0.98f
        circleCenterY = h * 0.56f

        domeClipPath.reset()
        domeClipPath.addCircle(circleCenterX, circleCenterY, circleRadius, Path.Direction.CW)

        val pivotX = if (isLeftSide) w * 0.46f else w * 0.54f
        val pivotY = h * 0.56f
        val effectiveAngle = if (isLeftSide) -tiltAngleDeg else tiltAngleDeg

        forwardMatrix.reset()
        forwardMatrix.setRotate(effectiveAngle, pivotX, pivotY)
        forwardMatrix.invert(inverseMatrix)

        buildKeys()
    }

    private fun computeTiltedRowSpan(rowMidYLocal: Float, marginPx: Float): Pair<Float, Float> {
        val w = width.toFloat()
        val h = height.toFloat()
        val scanStart = -w * 0.8f
        val scanEnd = w * 1.8f
        val samples = 80
        val step = (scanEnd - scanStart) / samples

        var minValidX = Float.MAX_VALUE
        var maxValidX = -Float.MAX_VALUE
        val pt = FloatArray(2)

        for (i in 0..samples) {
            val lx = scanStart + i * step
            pt[0] = lx
            pt[1] = rowMidYLocal
            forwardMatrix.mapPoints(pt)
            val sx = pt[0]
            val sy = pt[1]

            val insideCircle = hypot(sx - circleCenterX, sy - circleCenterY) <= (circleRadius - marginPx)
            val insideView = sx >= marginPx && sx <= (w - marginPx) && sy >= marginPx && sy <= (h - marginPx)
            if (insideCircle && insideView) {
                if (lx < minValidX) minValidX = lx
                if (lx > maxValidX) maxValidX = lx
            }
        }

        return if (maxValidX > minValidX + 40f) {
            minValidX to maxValidX
        } else {
            (w * 0.14f) to (w * 0.86f)
        }
    }

    private fun buildKeys() {
        computedKeys.clear()
        letterCenters.clear()
        if (width <= 0 || height <= 0) return

        val density = resources.displayMetrics.density
        val h = height.toFloat()

        val rows: List<List<KeyDef>> = if (isSymbolsMode) {
            listOf(
                listOf(
                    KeyDef("1", "1"), KeyDef("2", "2"), KeyDef("3", "3"), KeyDef("4", "4"), KeyDef("5", "5"),
                    KeyDef("6", "6"), KeyDef("7", "7"), KeyDef("8", "8"), KeyDef("9", "9"), KeyDef("0", "0")
                ),
                listOf(
                    KeyDef("+", "+"), KeyDef("×", "×"), KeyDef("÷", "÷"), KeyDef("=", "="), KeyDef("/", "/"),
                    KeyDef("_", "_"), KeyDef("<", "<"), KeyDef(">", ">"), KeyDef("[", "["), KeyDef("]", "]")
                ),
                listOf(
                    KeyDef("SYM_ALT", "1/2", isSpecial = true, weight = 1.3f),
                    KeyDef("!", "!"), KeyDef("@", "@"), KeyDef("#", "#"), KeyDef("€", "€"),
                    KeyDef("%", "%"), KeyDef("&", "&"), KeyDef("*", "*"),
                    KeyDef("DEL", "⌫", isSpecial = true, weight = 1.3f)
                ),
                listOf(
                    KeyDef("ABC", "ABC", isSpecial = true, weight = 1.35f),
                    KeyDef("GLOBE", "🌐", isSpecial = true, weight = 1.15f),
                    KeyDef("SPACE", "Italiano", weight = 4.4f),
                    KeyDef(",", ","),
                    KeyDef("ENTER", actionLabel, isActionBlue = true, weight = 1.35f)
                )
            )
        } else {
            val shiftChar = if (isCapsLock) "⇪" else if (isShifted) "⬆" else "⇧"
            fun c(s: String) = if (isShifted || isCapsLock) s.uppercase() else s
            listOf(
                listOf(
                    KeyDef("q", c("q"), "1"), KeyDef("w", c("w"), "2"), KeyDef("e", c("e"), "3"),
                    KeyDef("r", c("r"), "4"), KeyDef("t", c("t"), "5"), KeyDef("y", c("y"), "6"),
                    KeyDef("u", c("u"), "7"), KeyDef("i", c("i"), "8"), KeyDef("o", c("o"), "9"),
                    KeyDef("p", c("p"), "0")
                ),
                listOf(
                    KeyDef("a", c("a"), ""), KeyDef("s", c("s"), "!"), KeyDef("d", c("d"), "@"),
                    KeyDef("f", c("f"), "#"), KeyDef("g", c("g"), "€"), KeyDef("h", c("h"), "%"),
                    KeyDef("j", c("j"), "&"), KeyDef("k", c("k"), "*"), KeyDef("l", c("l"), "("),
                    KeyDef(")", ")", "")
                ),
                listOf(
                    KeyDef("SHIFT", shiftChar, isSpecial = true, weight = 1.35f),
                    KeyDef("z", c("z"), ""), KeyDef("x", c("x"), "-"), KeyDef("c", c("c"), "'"),
                    KeyDef("v", c("v"), "\""), KeyDef("b", c("b"), ":"), KeyDef("n", c("n"), ";"),
                    KeyDef("m", c("m"), "?"),
                    KeyDef("DEL", "⌫", isSpecial = true, weight = 1.35f)
                ),
                listOf(
                    KeyDef("SYM", "!#1", isSpecial = true, weight = 1.35f),
                    KeyDef("GLOBE", "🌐", isSpecial = true, weight = 1.15f),
                    KeyDef("SPACE", "Italiano", weight = 4.4f),
                    KeyDef(".", ".", ""),
                    KeyDef("ENTER", actionLabel, isActionBlue = true, weight = 1.35f)
                )
            )
        }

        // Lo spazio tra gli elementi controlla sia il gap verticale tra le righe sia il gap orizzontale tra i tasti
        val verticalGap = (12f * density) * keySpacingScale
        val horizontalGap = (9f * density) * keySpacingScale
        val blockCenterY = h * 0.56f
        val totalBlockH = (h * 0.62f) + (verticalGap * 3f)
        val blockTop = blockCenterY - totalBlockH / 2f
        val rowHeight = (totalBlockH - verticalGap * 3f) / rows.size

        rows.forEachIndexed { rowIndex, rowKeys ->
            val top = blockTop + rowIndex * (rowHeight + verticalGap)
            val bottom = top + rowHeight
            val rowMidY = (top + bottom) / 2f

            val (leftBound, rightBound) = computeTiltedRowSpan(rowMidY, 12f * density)
            val rowSpanW = (rightBound - leftBound).coerceAtLeast(60f)
            val row2Indent = if (rowIndex == 1) (rowSpanW / 10f) * 0.20f else 0f
            val totalRowGap = horizontalGap * (rowKeys.size - 1).coerceAtLeast(0)
            val usableRowW = (rowSpanW - row2Indent * 2f - totalRowGap).coerceAtLeast(40f)

            val totalWeight = rowKeys.sumOf { it.weight.toDouble() }.toFloat()
            var curX = leftBound + row2Indent

            for (k in rowKeys) {
                val kw = (k.weight / totalWeight) * usableRowW
                val rect = RectF(curX, top, curX + kw, bottom)
                computedKeys.add(k to rect)
                if (k.code.length == 1 && k.code[0] in 'a'..'z') {
                    letterCenters[k.code[0]] = PointF(rect.centerX(), rect.centerY())
                }
                curX += kw + horizontalGap
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val alphaInt = (bgOpacity * 255).toInt().coerceIn(30, 255)

        // 1. Disco tondo AMOLED con la trasparenza scelta
        domeBgPaint.color = Color.argb(alphaInt, 0, 0, 0)
        domeBorderPaint.color = Color.argb((alphaInt * 0.75f).toInt().coerceAtLeast(50), 48, 50, 58)

        canvas.save()
        canvas.clipPath(domeClipPath)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), domeBgPaint)

        // 2. Ruota il canvas per inclinare fisicamente tastiera e tasti
        canvas.save()
        canvas.concat(forwardMatrix)

        val keyUnitW = (width / 10.5f) * letterSizeScale
        subTextPaint.textSize = (keyUnitW * 0.23f).coerceAtLeast(7f * density)

        for ((key, rect) in computedKeys) {
            if (activeKey == key) {
                canvas.drawCircle(rect.centerX(), rect.centerY(), minOf(rect.width(), rect.height()) * 0.46f, keyHighlightPaint)
            }

            if (key.isActionBlue) {
                val r = minOf(rect.width(), rect.height()) * 0.36f
                canvas.drawCircle(rect.centerX(), rect.centerY(), r, blueActionPaint)
            }

            mainTextPaint.textSize = when {
                key.code == "SPACE" -> (keyUnitW * 0.34f).coerceAtLeast(9.5f * density)
                key.label.length >= 3 -> (keyUnitW * 0.32f).coerceAtLeast(9f * density)
                else -> (keyUnitW * 0.49f).coerceAtLeast(12f * density)
            }
            mainTextPaint.color = if (key.code == "SPACE") Color.parseColor("#E5E5EA") else Color.WHITE
            val textY = rect.centerY() - (mainTextPaint.descent() + mainTextPaint.ascent()) / 2f
            canvas.drawText(key.label, rect.centerX(), textY, mainTextPaint)

            if (key.subLabel.isNotEmpty()) {
                val subX = rect.right - rect.width() * 0.16f
                val subY = rect.top + rect.height() * 0.30f
                canvas.drawText(key.subLabel, subX, subY, subTextPaint)
            }
        }

        if (gestureMode == GestureMode.SWIPE_TYPING && swipePathPoints.size > 1) {
            canvas.drawPath(swipeDrawPath, swipeTrailPaint)
        }

        canvas.restore()
        canvas.restore()

        // 3. Bordo circolare sottile attorno al disco
        canvas.drawCircle(circleCenterX, circleCenterY, circleRadius - 1.5f, domeBorderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        val insideCircle = hypot(event.x - circleCenterX, event.y - circleCenterY) <= (circleRadius + 4f * density)

        if (event.actionMasked == MotionEvent.ACTION_DOWN && !insideCircle) {
            return false
        }

        val pts = floatArrayOf(event.x, event.y)
        inverseMatrix.mapPoints(pts)
        val lx = pts[0]
        val ly = pts[1]

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastRawY = event.rawY
                downLocalX = lx
                downLocalY = ly
                longPressTriggered = false
                swipePathPoints.clear()
                swipeDrawPath.reset()

                val hit = computedKeys.firstOrNull { it.second.contains(lx, ly) }
                if (hit != null) {
                    activeKey = hit.first
                    gestureMode = GestureMode.KEY_TAP
                    lastCursorDragX = lx
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

                    if (hit.first.code == "DEL") {
                        listener.onDeleteChar()
                        handler.postDelayed(repeatDeleteRunnable, 380L)
                    } else {
                        handler.postDelayed(longPressRunnable, 360L)
                    }

                    if (!isSymbolsMode && hit.first.code.length == 1 && hit.first.code[0] in 'a'..'z') {
                        swipePathPoints.add(PointF(lx, ly))
                        swipeDrawPath.moveTo(lx, ly)
                    }
                    invalidate()
                } else {
                    gestureMode = GestureMode.DRAG_POSITION
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                when (gestureMode) {
                    GestureMode.DRAG_POSITION -> {
                        val dy = event.rawY - lastRawY
                        lastRawY = event.rawY
                        listener.onDragFloatingKeyboard(dy)
                    }

                    GestureMode.KEY_TAP -> {
                        val moveLocal = hypot(lx - downLocalX, ly - downLocalY)
                        if (activeKey?.code == "SPACE" && abs(lx - downLocalX) > 14f * density) {
                            handler.removeCallbacks(longPressRunnable)
                            gestureMode = GestureMode.SPACE_CURSOR
                            lastCursorDragX = lx
                        } else if (!isSymbolsMode && activeKey != null && !activeKey!!.isSpecial && moveLocal > 22f * density) {
                            handler.removeCallbacks(longPressRunnable)
                            gestureMode = GestureMode.SWIPE_TYPING
                            swipePathPoints.add(PointF(lx, ly))
                            swipeDrawPath.lineTo(lx, ly)
                            invalidate()
                        }
                    }

                    GestureMode.SPACE_CURSOR -> {
                        val stepSize = 15f * density
                        val deltaX = lx - lastCursorDragX
                        if (abs(deltaX) >= stepSize) {
                            val steps = (deltaX / stepSize).toInt()
                            lastCursorDragX += steps * stepSize
                            performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
                            listener.onMoveTextCursor(steps)
                        }
                    }

                    GestureMode.SWIPE_TYPING -> {
                        swipePathPoints.add(PointF(lx, ly))
                        swipeDrawPath.lineTo(lx, ly)
                        invalidate()
                    }

                    GestureMode.NONE -> {}
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                handler.removeCallbacks(repeatDeleteRunnable)

                if (gestureMode == GestureMode.SWIPE_TYPING) {
                    val candidates = swipeDecoder.decode(swipePathPoints, letterCenters, width / 10.5f)
                    if (candidates.isNotEmpty()) {
                        var word = candidates.first()
                        if (isCapsLock) word = word.uppercase()
                        else if (isShifted) {
                            word = word.replaceFirstChar { it.uppercase() }
                            isShifted = false
                        }
                        listener.onCommitText("$word ")
                        listener.onSwipeWordDecoded(candidates)
                    }
                } else if (gestureMode == GestureMode.KEY_TAP && !longPressTriggered) {
                    val key = activeKey
                    if (key != null) {
                        handleSingleKeyTap(key)
                    }
                }

                activeKey = null
                gestureMode = GestureMode.NONE
                swipePathPoints.clear()
                swipeDrawPath.reset()
                buildKeys()
                invalidate()
                return true
            }
        }
        return true
    }

    private fun handleSingleKeyTap(key: KeyDef) {
        when (key.code) {
            "DEL" -> { /* già gestito in ACTION_DOWN */ }
            "SHIFT" -> {
                val now = System.currentTimeMillis()
                if (now - lastShiftTapTime < 320L) {
                    isCapsLock = !isCapsLock
                    isShifted = isCapsLock
                } else {
                    if (isCapsLock) {
                        isCapsLock = false
                        isShifted = false
                    } else {
                        isShifted = !isShifted
                    }
                }
                lastShiftTapTime = now
            }
            "SYM" -> isSymbolsMode = true
            "ABC" -> isSymbolsMode = false
            "SYM_ALT" -> isSymbolsMode = false
            "GLOBE" -> listener.onSwitchIme()
            "SPACE" -> listener.onCommitText(" ")
            "ENTER" -> listener.onEnterPressed()
            else -> {
                listener.onCommitText(key.label)
                if (isShifted && !isCapsLock) {
                    isShifted = false
                }
            }
        }
    }

    private fun italianAccentFor(ch: String): String? = when (ch) {
        "a" -> "à"
        "e" -> "è"
        "i" -> "ì"
        "o" -> "ò"
        "u" -> "ù"
        else -> null
    }
}
