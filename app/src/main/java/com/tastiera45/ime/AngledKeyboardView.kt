package com.tastiera45.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Matrix
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

class AngledKeyboardView(
    context: Context,
    private val listener: KeyboardActionListener
) : View(context) {

    interface KeyboardActionListener {
        fun onCommitText(text: String)
        fun onDeleteChar()
        fun onEnterPressed()
        fun onSwitchIme()
        fun onOpenClipboard()
        fun onStartVoiceDictation()
        fun onOpenVoicePicker()
        fun onOpenTranslate()
        fun onActivateRemoteCursor()
        fun onToggleHandSide()
        fun onMoveTextCursor(steps: Int)
        fun onSwipeWordDecoded(candidates: List<String>)
    }

    data class KeyDef(
        val code: String,
        val label: String,
        val subLabel: String = "",
        val weight: Float = 1f,
        val isActionBlue: Boolean = false,
        val isSpecial: Boolean = false
    )

    private var isLeftHanded = false
    private var isShifted = false
    private var isCapsLock = false
    private var isSymbolsMode = false
    private var actionLabel = "🔍"

    // Matrici di trasformazione per ruotare esattamente a 45 gradi nell'angolo
    private val forwardMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private var localW = 900f
    private var localH = 540f

    private val computedKeys = mutableListOf<Pair<KeyDef, RectF>>()
    private val letterCenters = mutableMapOf<Char, PointF>()

    // Pulsanti rapidi nell'angolo libero creato dall'inclinazione a 45°
    private val cornerMicRect = RectF()
    private val cornerHandRect = RectF()
    private val cornerTransRect = RectF()
    private val topEdgeBarRect = RectF()

    // Gestione gesti: Swipe parola, Cursore spazio, Attivazione Remote Cursor
    private enum class GestureMode { NONE, KEY_TAP, SWIPE_TYPING, SPACE_CURSOR, EDGE_MOUSE }
    private var gestureMode = GestureMode.NONE
    private var activeKey: KeyDef? = null
    private var activeKeyRect: RectF? = null
    private var downScreenX = 0f
    private var downScreenY = 0f
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

    // Stili visivi fedeli al primo allegato (AMOLED nero puro, lettere bianche, sub-label grigie)
    private val bgPaint = Paint().apply { color = Color.BLACK }

    private val mainTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8E8E93")
        textAlign = Paint.Align.CENTER
    }
    private val keyHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(48, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val blueActionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4B7BD6")
        style = Paint.Style.FILL
    }
    private val cornerChipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#141416")
        style = Paint.Style.FILL
    }
    private val cornerChipStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2C2C30")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val topEdgeBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#121316")
        style = Paint.Style.FILL
    }
    private val handlePillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555861")
        style = Paint.Style.FILL
    }
    private val swipeTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 96, 165, 250)
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = CornerPathEffect(24f)
    }

    fun setLeftHanded(left: Boolean) {
        isLeftHanded = left
        rebuildGeometry()
        invalidate()
    }

    fun isLeftHandedMode(): Boolean = isLeftHanded

    fun setActionIcon(symbol: String) {
        actionLabel = symbol
        rebuildGeometry()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val density = resources.displayMetrics.density
        // Altezza calibrata affinché l'intera tastiera ruotata a 45° entri comoda in basso
        val h = (w * 0.94f + 36f * density).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildGeometry()
    }

    private fun rebuildGeometry() {
        if (width <= 0 || height <= 0) return
        val density = resources.displayMetrics.density
        val topStripH = 34f * density
        topEdgeBarRect.set(0f, 0f, width.toFloat(), topStripH)

        val availW = width.toFloat()
        val availH = height - topStripH

        // Dimensioni nel sistema locale prima della rotazione a 45 gradi
        localW = availW * 0.80f
        localH = availW * 0.50f

        forwardMatrix.reset()
        val angle = if (isLeftHanded) 45f else -45f
        forwardMatrix.postRotate(angle, localW / 2f, localH / 2f)

        // Calcola i vertici ruotati per ancorare esattamente nell'angolo inferiore scelto
        val corners = floatArrayOf(
            0f, 0f,
            localW, 0f,
            localW, localH,
            0f, localH
        )
        forwardMatrix.mapPoints(corners)
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until 4) {
            val cx = corners[i * 2]
            val cy = corners[i * 2 + 1]
            if (cx < minX) minX = cx
            if (cx > maxX) maxX = cx
            if (cy < minY) minY = cy
            if (cy > maxY) maxY = cy
        }

        val pad = 10f * density
        val targetBottom = height - pad
        val dx = if (isLeftHanded) {
            pad - minX
        } else {
            (availW - pad) - maxX
        }
        val dy = targetBottom - maxY
        forwardMatrix.postTranslate(dx, dy)
        forwardMatrix.invert(inverseMatrix)

        // Posiziona i 3 tasti ergonomici nell'angolo vuoto lasciato dal taglio a 45°
        val chipSize = 46f * density
        val chipMargin = 14f * density
        if (isLeftHanded) {
            val rx = width - chipMargin - chipSize
            cornerMicRect.set(rx, height - chipMargin - chipSize, rx + chipSize, height - chipMargin)
            cornerHandRect.set(rx - chipSize - 10f * density, height - chipMargin - chipSize, rx - 10f * density, height - chipMargin)
            cornerTransRect.set(rx, height - chipMargin - chipSize * 2 - 10f * density, rx + chipSize, height - chipMargin - chipSize - 10f * density)
        } else {
            val lx = chipMargin
            cornerMicRect.set(lx, height - chipMargin - chipSize, lx + chipSize, height - chipMargin)
            cornerHandRect.set(lx + chipSize + 10f * density, height - chipMargin - chipSize, lx + chipSize * 2 + 10f * density, height - chipMargin)
            cornerTransRect.set(lx, height - chipMargin - chipSize * 2 - 10f * density, lx + chipSize, height - chipMargin - chipSize - 10f * density)
        }

        buildKeys()
    }

    private fun buildKeys() {
        computedKeys.clear()
        letterCenters.clear()

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
                    KeyDef("GLOBE", "🌐", isSpecial = true, weight = 1.1f),
                    KeyDef("CLIP", "📋", isSpecial = true, weight = 1.1f),
                    KeyDef("SPACE", "Italiano", weight = 3.8f),
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
                    KeyDef("GLOBE", "🌐", isSpecial = true, weight = 1.1f),
                    KeyDef("CLIP", "📋", isSpecial = true, weight = 1.1f),
                    KeyDef("SPACE", "Italiano", weight = 3.8f),
                    KeyDef(".", ".", ""),
                    KeyDef("ENTER", actionLabel, isActionBlue = true, weight = 1.35f)
                )
            )
        }

        val rowHeight = localH / rows.size
        rows.forEachIndexed { rowIndex, rowKeys ->
            val totalWeight = rowKeys.sumOf { it.weight.toDouble() }.toFloat()
            val horizontalMargin = if (rowIndex == 1) (localW / 10f) * 0.25f else 0f
            val usableW = localW - horizontalMargin * 2f
            var curX = horizontalMargin
            val top = rowIndex * rowHeight
            val bottom = top + rowHeight

            for (k in rowKeys) {
                val kw = (k.weight / totalWeight) * usableW
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

        // Sfondo nero puro AMOLED
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Barra superiore sottile per attivare il Remote Cursor scorrendo verso l'alto
        canvas.drawRect(topEdgeBarRect, topEdgeBarPaint)
        val pillW = 46f * density
        val pillH = 4f * density
        val pillLeft = (width - pillW) / 2f
        val pillTop = (topEdgeBarRect.height() - pillH) / 2f
        canvas.drawRoundRect(
            pillLeft, pillTop, pillLeft + pillW, pillTop + pillH,
            pillH, pillH, handlePillPaint
        )

        // Pulsanti ergonomici nell'angolo libero (Microfono, Inversione Mano 45°, Traduttore)
        drawCornerChip(canvas, cornerMicRect, "🎤", density)
        drawCornerChip(canvas, cornerHandRect, if (isLeftHanded) "45°→" else "←45°", density)
        drawCornerChip(canvas, cornerTransRect, "A文", density)

        // Disegna la tastiera inclinata a 45 gradi
        canvas.save()
        canvas.concat(forwardMatrix)

        val keyUnitW = localW / 10f
        mainTextPaint.textSize = keyUnitW * 0.48f
        subTextPaint.textSize = keyUnitW * 0.23f

        for ((key, rect) in computedKeys) {
            if (activeKey == key) {
                canvas.drawCircle(rect.centerX(), rect.centerY(), rect.height() * 0.42f, keyHighlightPaint)
            }

            if (key.isActionBlue) {
                val r = minOf(rect.width(), rect.height()) * 0.36f
                canvas.drawCircle(rect.centerX(), rect.centerY(), r, blueActionPaint)
            }

            // Etichetta principale
            mainTextPaint.textSize = when {
                key.code == "SPACE" -> keyUnitW * 0.34f
                key.label.length >= 3 -> keyUnitW * 0.32f
                else -> keyUnitW * 0.50f
            }
            mainTextPaint.color = if (key.code == "SPACE") Color.parseColor("#E5E5EA") else Color.WHITE
            val textY = rect.centerY() - (mainTextPaint.descent() + mainTextPaint.ascent()) / 2f
            canvas.drawText(key.label, rect.centerX(), textY, mainTextPaint)

            // Simbolo secondario in grigio in alto a destra (identico all'allegato 1)
            if (key.subLabel.isNotEmpty()) {
                val subX = rect.right - rect.width() * 0.20f
                val subY = rect.top + rect.height() * 0.30f
                canvas.drawText(key.subLabel, subX, subY, subTextPaint)
            }
        }

        // Scia luminosa dello swipe-to-type
        if (gestureMode == GestureMode.SWIPE_TYPING && swipePathPoints.size > 1) {
            canvas.drawPath(swipeDrawPath, swipeTrailPaint)
        }

        canvas.restore()
    }

    private fun drawCornerChip(canvas: Canvas, rect: RectF, icon: String, density: Float) {
        val r = 14f * density
        canvas.drawRoundRect(rect, r, r, cornerChipPaint)
        canvas.drawRoundRect(rect, r, r, cornerChipStroke)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D1D1D6")
            textAlign = Paint.Align.CENTER
            textSize = 15f * density
        }
        val y = rect.centerY() - (p.descent() + p.ascent()) / 2f
        canvas.drawText(icon, rect.centerX(), y, p)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        val pts = floatArrayOf(event.x, event.y)
        inverseMatrix.mapPoints(pts)
        val lx = pts[0]
        val ly = pts[1]

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downScreenX = event.x
                downScreenY = event.y
                downLocalX = lx
                downLocalY = ly
                longPressTriggered = false
                swipePathPoints.clear()
                swipeDrawPath.reset()

                // 1. Tocco sulla barra superiore -> pronto per attivare il Remote Cursor
                if (topEdgeBarRect.contains(event.x, event.y)) {
                    gestureMode = GestureMode.EDGE_MOUSE
                    return true
                }

                // 2. Pulsanti rapidi nell'angolo
                if (cornerMicRect.contains(event.x, event.y)) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    listener.onStartVoiceDictation()
                    return true
                }
                if (cornerHandRect.contains(event.x, event.y)) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    listener.onToggleHandSide()
                    return true
                }
                if (cornerTransRect.contains(event.x, event.y)) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    listener.onOpenTranslate()
                    return true
                }

                // 3. Ricerca tasto toccato nel sistema ruotato a 45°
                val hit = computedKeys.firstOrNull { it.second.contains(lx, ly) }
                if (hit != null) {
                    activeKey = hit.first
                    activeKeyRect = hit.second
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
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                when (gestureMode) {
                    GestureMode.EDGE_MOUSE -> {
                        val dy = downScreenY - event.y
                        val dx = abs(event.x - downScreenX)
                        // Scorrendo dalla barra superiore si attiva il mouse Remote Cursor
                        if (dy > 14f * density || dx > 22f * density) {
                            gestureMode = GestureMode.NONE
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            listener.onActivateRemoteCursor()
                        }
                    }

                    GestureMode.KEY_TAP -> {
                        val moveLocal = hypot(lx - downLocalX, ly - downLocalY)
                        if (activeKey?.code == "SPACE" && abs(lx - downLocalX) > 16f * density) {
                            handler.removeCallbacks(longPressRunnable)
                            gestureMode = GestureMode.SPACE_CURSOR
                            lastCursorDragX = lx
                        } else if (!isSymbolsMode && activeKey != null && !activeKey!!.isSpecial && moveLocal > 26f * density) {
                            handler.removeCallbacks(longPressRunnable)
                            gestureMode = GestureMode.SWIPE_TYPING
                            swipePathPoints.add(PointF(lx, ly))
                            swipeDrawPath.lineTo(lx, ly)
                            invalidate()
                        }
                    }

                    GestureMode.SPACE_CURSOR -> {
                        val stepSize = 18f * density
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

                if (gestureMode == GestureMode.EDGE_MOUSE) {
                    // Anche un tap veloce sulla barra superiore apre comodamente il Remote Cursor
                    listener.onActivateRemoteCursor()
                } else if (gestureMode == GestureMode.SWIPE_TYPING) {
                    val candidates = swipeDecoder.decode(swipePathPoints, letterCenters, localW / 10f)
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
                activeKeyRect = null
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
            "CLIP" -> listener.onOpenClipboard()
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
