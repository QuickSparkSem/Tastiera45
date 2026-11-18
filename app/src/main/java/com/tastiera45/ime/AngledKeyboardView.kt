package com.tastiera45.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
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
        fun onShowExtraPanel()
        fun onToggleExtraPanel()
        fun onUpdateExtraPanelHover(rawX: Float, rawY: Float)
        fun onReleaseExtraPanelAt(rawX: Float, rawY: Float)
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
    private var scaleFactor = 0.74f
    private var bgOpacity = 0.92f
    private var incognitoHighlight = false
    private var isShifted = false
    private var isCapsLock = false
    private var isSymbolsMode = false
    private var actionLabel = "🔍"

    // Geometria cupola circolare e bottone unico funzioni extra
    private var topMarginPx = 0f
    private var circleCenterX = 0f
    private var circleCenterY = 0f
    private var circleRadius = 0f
    private val domeClipPath = Path()
    private val hubButtonRect = RectF()

    private val computedKeys = mutableListOf<Pair<KeyDef, RectF>>()
    private val letterCenters = mutableMapOf<Char, PointF>()

    private enum class GestureMode {
        NONE, KEY_TAP, SWIPE_TYPING, SPACE_CURSOR, HUB_MENU, DRAG_POSITION
    }

    private var gestureMode = GestureMode.NONE
    private var activeKey: KeyDef? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawY = 0f
    private var downLocalX = 0f
    private var downLocalY = 0f
    private var lastCursorDragX = 0f
    private var lastShiftTapTime = 0L
    private var longPressTriggered = false
    private var hubHoldTriggered = false
    private var hubDragMoved = false

    private val swipePathPoints = mutableListOf<PointF>()
    private val swipeDrawPath = Path()
    private val swipeDecoder = SwipeDecoder(context)

    private val handler = Handler(Looper.getMainLooper())

    private val hubHoldRunnable = Runnable {
        if (gestureMode == GestureMode.HUB_MENU) {
            hubHoldTriggered = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener.onShowExtraPanel()
        }
    }

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

    // Stili fedeli all'allegato 1 (AMOLED nero, lettere bianche, simboli secondari grigi)
    private val domeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val domeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2C2C32")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val hubBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#18191E")
        style = Paint.Style.FILL
    }
    private val hubBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A3D46")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val hubIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
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
        color = Color.argb(54, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val blueActionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4B7BD6")
        style = Paint.Style.FILL
    }
    private val swipeTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(195, 96, 165, 250)
        style = Paint.Style.STROKE
        strokeWidth = 9f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = CornerPathEffect(22f)
    }

    fun setLeftHanded(left: Boolean) {
        isLeftSide = left
        requestLayout()
        invalidate()
    }

    fun isLeftHandedMode(): Boolean = isLeftSide

    fun setScaleFactor(scale: Float) {
        val clamped = scale.coerceIn(0.50f, 0.96f)
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

    fun setIncognitoVisual(active: Boolean) {
        incognitoHighlight = active
        invalidate()
    }

    fun setActionIcon(symbol: String) {
        actionLabel = symbol
        buildKeys()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels
        val panelW = (screenW * scaleFactor).toInt().coerceAtLeast((200 * density).toInt())
        val topSpace = (26 * density).toInt()
        val domeH = (panelW * 0.95f).toInt()
        setMeasuredDimension(panelW, domeH + topSpace)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildCircularGeometry()
    }

    private fun rebuildCircularGeometry() {
        if (width <= 0 || height <= 0) return
        val density = resources.displayMetrics.density
        topMarginPx = 26f * density
        val domeH = height - topMarginPx

        // Posiziona l'unico bottone extra (✦) sul bordo superiore interno della cupola
        val hubSize = 42f * density
        val pad = 4f * density
        if (isLeftSide) {
            hubButtonRect.set(width - pad - hubSize, pad, width - pad, pad + hubSize)
        } else {
            hubButtonRect.set(pad, pad, pad + hubSize, pad + hubSize)
        }

        // Cerchio che emerge dal lato scelto dello schermo
        circleCenterY = topMarginPx + domeH / 2f
        circleRadius = max(width * 0.98f, domeH * 0.56f)
        circleCenterX = if (isLeftSide) 0f else width.toFloat()

        domeClipPath.reset()
        domeClipPath.addCircle(circleCenterX, circleCenterY, circleRadius, Path.Direction.CW)

        buildKeys()
    }

    private fun chordBoundsAtY(y: Float, insetMargin: Float): Pair<Float, Float> {
        val dy = abs(y - circleCenterY)
        val rEff = (circleRadius - insetMargin).coerceAtLeast(10f)
        val halfChord = if (dy < rEff) sqrt((rEff * rEff) - (dy * dy)) else rEff * 0.5f
        val chordWidth = halfChord.coerceAtMost(width - insetMargin * 2f)
        return if (isLeftSide) {
            insetMargin to (insetMargin + chordWidth)
        } else {
            (width - insetMargin - chordWidth) to (width - insetMargin)
        }
    }

    private fun buildKeys() {
        computedKeys.clear()
        letterCenters.clear()
        if (width <= 0 || height <= 0) return

        val density = resources.displayMetrics.density
        val domeTop = topMarginPx + 6f * density
        val domeBottom = height - 6f * density
        val usableH = (domeBottom - domeTop).coerceAtLeast(40f)

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
                    KeyDef("SPACE", "Italiano", weight = 4.5f),
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
                    KeyDef("SPACE", "Italiano", weight = 4.5f),
                    KeyDef(".", ".", ""),
                    KeyDef("ENTER", actionLabel, isActionBlue = true, weight = 1.35f)
                )
            )
        }

        val rowHeight = usableH / rows.size
        rows.forEachIndexed { rowIndex, rowKeys ->
            val top = domeTop + rowIndex * rowHeight
            val bottom = top + rowHeight
            val rowMidY = (top + bottom) / 2f

            val (leftBound, rightBound) = chordBoundsAtY(rowMidY, 6f * density)
            val rowSpanW = (rightBound - leftBound).coerceAtLeast(80f)
            val row2Indent = if (rowIndex == 1) (rowSpanW / 10f) * 0.22f else 0f
            val usableRowW = rowSpanW - row2Indent * 2f

            val totalWeight = rowKeys.sumOf { it.weight.toDouble() }.toFloat()
            var curX = leftBound + row2Indent

            for (k in rowKeys) {
                val kw = (k.weight / totalWeight) * usableRowW
                val rect = RectF(curX, top, curX + kw, bottom)
                computedKeys.add(k to rect)
                if (k.code.length == 1 && k.code[0] in 'a'..'z') {
                    letterCenters[k.code[0]] = PointF(rect.centerX(), rect.centerY())
                }
                curX += kw
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val alphaInt = (bgOpacity * 255).toInt().coerceIn(30, 255)

        // Cupola circolare AMOLED con la trasparenza scelta nelle impostazioni
        domeBgPaint.color = Color.argb(alphaInt, 0, 0, 0)
        domeBorderPaint.color = Color.argb((alphaInt * 0.85f).toInt().coerceAtLeast(60), 54, 56, 64)

        canvas.save()
        canvas.clipPath(domeClipPath)
        canvas.drawRect(0f, topMarginPx, width.toFloat(), height.toFloat(), domeBgPaint)

        val keyUnitW = width / 10f
        subTextPaint.textSize = (keyUnitW * 0.24f).coerceAtLeast(8f * density)

        for ((key, rect) in computedKeys) {
            if (activeKey == key) {
                canvas.drawCircle(rect.centerX(), rect.centerY(), minOf(rect.width(), rect.height()) * 0.44f, keyHighlightPaint)
            }

            if (key.isActionBlue) {
                val r = minOf(rect.width(), rect.height()) * 0.36f
                canvas.drawCircle(rect.centerX(), rect.centerY(), r, blueActionPaint)
            }

            mainTextPaint.textSize = when {
                key.code == "SPACE" -> (keyUnitW * 0.35f).coerceAtLeast(11f * density)
                key.label.length >= 3 -> (keyUnitW * 0.33f).coerceAtLeast(10f * density)
                else -> (keyUnitW * 0.50f).coerceAtLeast(14f * density)
            }
            mainTextPaint.color = if (key.code == "SPACE") Color.parseColor("#E5E5EA") else Color.WHITE
            val textY = rect.centerY() - (mainTextPaint.descent() + mainTextPaint.ascent()) / 2f
            canvas.drawText(key.label, rect.centerX(), textY, mainTextPaint)

            if (key.subLabel.isNotEmpty()) {
                val subX = rect.right - rect.width() * 0.18f
                val subY = rect.top + rect.height() * 0.30f
                canvas.drawText(key.subLabel, subX, subY, subTextPaint)
            }
        }

        if (gestureMode == GestureMode.SWIPE_TYPING && swipePathPoints.size > 1) {
            canvas.drawPath(swipeDrawPath, swipeTrailPaint)
        }

        canvas.restore()

        // Bordo circolare sottile attorno alla cupola
        canvas.drawCircle(circleCenterX, circleCenterY, circleRadius - 1.5f, domeBorderPaint)

        // Bottone unico funzioni extra (✦)
        hubBgPaint.color = Color.argb(alphaInt.coerceAtLeast(190), 22, 23, 28)
        hubBorderPaint.color = if (incognitoHighlight) {
            Color.parseColor("#60A5FA")
        } else {
            Color.parseColor("#3E414B")
        }
        val hubRadius = hubButtonRect.width() / 2f
        canvas.drawCircle(hubButtonRect.centerX(), hubButtonRect.centerY(), hubRadius, hubBgPaint)
        canvas.drawCircle(hubButtonRect.centerX(), hubButtonRect.centerY(), hubRadius, hubBorderPaint)

        hubIconPaint.textSize = 18f * density
        hubIconPaint.color = if (incognitoHighlight) Color.parseColor("#60A5FA") else Color.WHITE
        val hubY = hubButtonRect.centerY() - (hubIconPaint.descent() + hubIconPaint.ascent()) / 2f
        canvas.drawText("✦", hubButtonRect.centerX(), hubY, hubIconPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawY = event.rawY
                downLocalX = event.x
                downLocalY = event.y
                longPressTriggered = false
                hubHoldTriggered = false
                hubDragMoved = false
                swipePathPoints.clear()
                swipeDrawPath.reset()

                // 1. Tocco sull'unico bottone delle funzioni extra (✦)
                val expandedHub = RectF(hubButtonRect).apply { inset(-6f * density, -6f * density) }
                if (expandedHub.contains(event.x, event.y)) {
                    gestureMode = GestureMode.HUB_MENU
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    handler.postDelayed(hubHoldRunnable, 170L)
                    return true
                }

                // 2. Ricerca tasto toccato dentro la cupola circolare
                val hit = computedKeys.firstOrNull { it.second.contains(event.x, event.y) }
                if (hit != null) {
                    activeKey = hit.first
                    gestureMode = GestureMode.KEY_TAP
                    lastCursorDragX = event.x
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

                    if (hit.first.code == "DEL") {
                        listener.onDeleteChar()
                        handler.postDelayed(repeatDeleteRunnable, 380L)
                    } else {
                        handler.postDelayed(longPressRunnable, 360L)
                    }

                    if (!isSymbolsMode && hit.first.code.length == 1 && hit.first.code[0] in 'a'..'z') {
                        swipePathPoints.add(PointF(event.x, event.y))
                        swipeDrawPath.moveTo(event.x, event.y)
                    }
                    invalidate()
                } else {
                    // Tocco sul bordo libero della cupola -> permette di trascinare su/giù la tastiera
                    gestureMode = GestureMode.DRAG_POSITION
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                when (gestureMode) {
                    GestureMode.HUB_MENU -> {
                        val moved = hypot(event.rawX - downRawX, event.rawY - downRawY)
                        if (moved > 10f * density && !hubDragMoved) {
                            hubDragMoved = true
                            handler.removeCallbacks(hubHoldRunnable)
                            listener.onShowExtraPanel()
                        }
                        if (hubDragMoved || hubHoldTriggered) {
                            listener.onUpdateExtraPanelHover(event.rawX, event.rawY)
                        }
                    }

                    GestureMode.DRAG_POSITION -> {
                        val dy = event.rawY - lastRawY
                        lastRawY = event.rawY
                        listener.onDragFloatingKeyboard(dy)
                    }

                    GestureMode.KEY_TAP -> {
                        val moveLocal = hypot(event.x - downLocalX, event.y - downLocalY)
                        if (activeKey?.code == "SPACE" && abs(event.x - downLocalX) > 14f * density) {
                            handler.removeCallbacks(longPressRunnable)
                            gestureMode = GestureMode.SPACE_CURSOR
                            lastCursorDragX = event.x
                        } else if (!isSymbolsMode && activeKey != null && !activeKey!!.isSpecial && moveLocal > 22f * density) {
                            handler.removeCallbacks(longPressRunnable)
                            gestureMode = GestureMode.SWIPE_TYPING
                            swipePathPoints.add(PointF(event.x, event.y))
                            swipeDrawPath.lineTo(event.x, event.y)
                            invalidate()
                        }
                    }

                    GestureMode.SPACE_CURSOR -> {
                        val stepSize = 15f * density
                        val deltaX = event.x - lastCursorDragX
                        if (abs(deltaX) >= stepSize) {
                            val steps = (deltaX / stepSize).toInt()
                            lastCursorDragX += steps * stepSize
                            performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
                            listener.onMoveTextCursor(steps)
                        }
                    }

                    GestureMode.SWIPE_TYPING -> {
                        swipePathPoints.add(PointF(event.x, event.y))
                        swipeDrawPath.lineTo(event.x, event.y)
                        invalidate()
                    }

                    GestureMode.NONE -> {}
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(hubHoldRunnable)
                handler.removeCallbacks(longPressRunnable)
                handler.removeCallbacks(repeatDeleteRunnable)

                if (gestureMode == GestureMode.HUB_MENU) {
                    if (hubDragMoved || hubHoldTriggered) {
                        // Ha trascinato o tenuto premuto -> avvia l'opzione sotto il dito e chiude
                        listener.onReleaseExtraPanelAt(event.rawX, event.rawY)
                    } else {
                        // Tap veloce -> apre/chiude il pannello così può cliccare sulle voci
                        listener.onToggleExtraPanel()
                    }
                } else if (gestureMode == GestureMode.SWIPE_TYPING) {
                    val candidates = swipeDecoder.decode(swipePathPoints, letterCenters, width / 10f)
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
            "DEL" -> { /* gestito in ACTION_DOWN */ }
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
