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
import android.graphics.Region
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
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
        fun onMoveTextCursor(steps: Int)
        fun onSwipeWordDecoded(candidates: List<String>)
        fun onDragFloatingKeyboard(deltaScreenX: Float, deltaScreenY: Float)
    }

    data class KeyDef(
        val code: String,
        val label: String,
        val subLabel: String = "",
        val weight: Float = 1f,
        val isActionKey: Boolean = false,
        val isSpecial: Boolean = false
    )

    private var scaleFactor = 0.82f
    private var bgOpacity = 0.94f
    private var tiltAngleDeg = -22f
    private var letterSizeScale = 1.0f
    private var keySpacingScale = 0.25f

    private var isShifted = false
    private var isCapsLock = false
    private var isSymbolsMode = false
    private var actionType = "SEARCH"

    // Geometria scolpita aderente ai tasti (nessuno spazio nero sprecato)
    private val localContourPath = Path()
    private val screenContourPath = Path()
    private val dragHandleLocalRect = RectF()
    private val forwardMatrix = Matrix()
    private val inverseMatrix = Matrix()

    private val computedKeys = mutableListOf<Pair<KeyDef, RectF>>()
    private val letterCenters = mutableMapOf<Char, PointF>()
    private var localClusterWidth = 600f

    private enum class GestureMode {
        NONE, KEY_TAP, SWIPE_TYPING, SPACE_CURSOR, DRAG_POPUP
    }

    private var gestureMode = GestureMode.NONE
    private var activeKey: KeyDef? = null
    private var lastRawX = 0f
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

    // Tema 100% Bianco e Nero Minimal
    private val sculptedBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val sculptedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#323236")
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
    }
    private val dragPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8E8E93")
        style = Paint.Style.FILL
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
        color = Color.argb(55, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val actionCircleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val vectorIconWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val vectorIconBlackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val swipeTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = CornerPathEffect(18f)
    }

    fun setScaleFactor(scale: Float) {
        val clamped = scale.coerceIn(0.50f, 0.98f)
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
            requestLayout()
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
            requestLayout()
            invalidate()
        }
    }

    fun setActionType(type: String) {
        actionType = type
        invalidate()
    }

    fun appendContourToRegion(targetRegion: Region, offsetX: Int, offsetY: Int) {
        if (width <= 0 || height <= 0 || screenContourPath.isEmpty) return
        val shifted = Path(screenContourPath)
        shifted.offset(offsetX.toFloat(), offsetY.toFloat())
        val pathRegion = Region()
        val clip = Region(offsetX, offsetY, offsetX + width, offsetY + height)
        pathRegion.setPath(shifted, clip)
        targetRegion.op(pathRegion, Region.Op.UNION)
    }

    private fun getRowDefinitions(): List<List<KeyDef>> {
        return if (isSymbolsMode) {
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
                    KeyDef("DEL", "", isSpecial = true, weight = 1.3f)
                ),
                listOf(
                    KeyDef("ABC", "ABC", isSpecial = true, weight = 1.35f),
                    KeyDef("GLOBE", "", isSpecial = true, weight = 1.15f),
                    KeyDef("SPACE", "Italiano", weight = 4.4f),
                    KeyDef(",", ","),
                    KeyDef("ENTER", "", isActionKey = true, weight = 1.35f)
                )
            )
        } else {
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
                    KeyDef("SHIFT", "", isSpecial = true, weight = 1.35f),
                    KeyDef("z", c("z"), ""), KeyDef("x", c("x"), "-"), KeyDef("c", c("c"), "'"),
                    KeyDef("v", c("v"), "\""), KeyDef("b", c("b"), ":"), KeyDef("n", c("n"), ";"),
                    KeyDef("m", c("m"), "?"),
                    KeyDef("DEL", "", isSpecial = true, weight = 1.35f)
                ),
                listOf(
                    KeyDef("SYM", "!#1", isSpecial = true, weight = 1.35f),
                    KeyDef("GLOBE", "", isSpecial = true, weight = 1.15f),
                    KeyDef("SPACE", "Italiano", weight = 4.4f),
                    KeyDef(".", ".", ""),
                    KeyDef("ENTER", "", isActionKey = true, weight = 1.35f)
                )
            )
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels

        localClusterWidth = (screenW * scaleFactor).coerceAtLeast(210f * density)
        val verticalGap = (10f * density) * keySpacingScale
        val baseRowH = localClusterWidth * 0.128f
        val dragTabH = 22f * density
        val pad = 5f * density

        val localTotalW = localClusterWidth + pad * 2f
        val localTotalH = dragTabH + (baseRowH * 4f) + (verticalGap * 3f) + pad * 2f

        // Calcola l'ingombro esatto dopo la rotazione per evitare tagli o spazi vuoti
        val m = Matrix()
        m.setRotate(tiltAngleDeg, localTotalW / 2f, localTotalH / 2f)
        val bounds = RectF(0f, 0f, localTotalW, localTotalH)
        m.mapRect(bounds)

        val measW = ceil(bounds.width() + 8f * density).toInt()
        val measH = ceil(bounds.height() + 8f * density).toInt()
        setMeasuredDimension(measW, measH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildSculptedLayout()
    }

    private fun rebuildSculptedLayout() {
        computedKeys.clear()
        letterCenters.clear()
        if (width <= 0 || height <= 0) return

        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels
        localClusterWidth = (screenW * scaleFactor).coerceAtLeast(210f * density)

        val verticalGap = (10f * density) * keySpacingScale
        val horizontalGap = (8f * density) * keySpacingScale
        val baseRowH = localClusterWidth * 0.128f
        val dragTabH = 22f * density
        val pad = 5f * density

        val localTotalW = localClusterWidth + pad * 2f
        val localTotalH = dragTabH + (baseRowH * 4f) + (verticalGap * 3f) + pad * 2f

        // Matrice che ruota la tastiera scolpita e la centra nella View
        forwardMatrix.reset()
        forwardMatrix.postRotate(tiltAngleDeg, localTotalW / 2f, localTotalH / 2f)
        forwardMatrix.postTranslate((width - localTotalW) / 2f, (height - localTotalH) / 2f)
        forwardMatrix.invert(inverseMatrix)

        // Linguetta superiore per trascinare il pop-up sullo schermo
        val tabW = (localClusterWidth * 0.34f).coerceAtLeast(74f * density)
        val tabLeft = (localTotalW - tabW) / 2f
        val tabRight = tabLeft + tabW
        dragHandleLocalRect.set(tabLeft, 0f, tabRight, dragTabH + pad)

        // Righe scolpite che seguono la sagoma reale dei tasti senza eccessi di nero
        val rows = getRowDefinitions()
        val rowBoundsList = mutableListOf<RectF>()

        rows.forEachIndexed { rowIndex, rowKeys ->
            val top = dragTabH + pad + rowIndex * (baseRowH + verticalGap)
            val bottom = top + baseRowH

            // Leggera rastremazione ergonomica delle righe per seguire esattamente le lettere
            val sideInset = when (rowIndex) {
                0 -> 0f
                1 -> localClusterWidth * 0.032f
                2 -> localClusterWidth * 0.018f
                else -> localClusterWidth * 0.048f
            }

            val rowLeft = pad + sideInset
            val rowRight = localTotalW - pad - sideInset
            val rowSpanW = (rowRight - rowLeft).coerceAtLeast(60f)

            rowBoundsList.add(RectF(rowLeft - pad, top - pad * 0.6f, rowRight + pad, bottom + pad * 0.6f))

            val totalRowGap = horizontalGap * (rowKeys.size - 1).coerceAtLeast(0)
            val usableRowW = (rowSpanW - totalRowGap).coerceAtLeast(40f)
            val totalWeight = rowKeys.sumOf { it.weight.toDouble() }.toFloat()

            var curX = rowLeft
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

        // Costruisce il contorno poligonale aderente alla linguetta e alle 4 righe
        localContourPath.reset()
        if (rowBoundsList.size == 4) {
            val r0 = rowBoundsList[0]
            val r1 = rowBoundsList[1]
            val r2 = rowBoundsList[2]
            val r3 = rowBoundsList[3]

            localContourPath.moveTo(dragHandleLocalRect.left, dragHandleLocalRect.top)
            localContourPath.lineTo(dragHandleLocalRect.right, dragHandleLocalRect.top)
            localContourPath.lineTo(dragHandleLocalRect.right, r0.top)

            // Lato destro aderente alle 4 righe
            localContourPath.lineTo(r0.right, r0.top)
            localContourPath.lineTo(r0.right, r0.bottom)
            localContourPath.lineTo(r1.right, r1.top)
            localContourPath.lineTo(r1.right, r1.bottom)
            localContourPath.lineTo(r2.right, r2.top)
            localContourPath.lineTo(r2.right, r2.bottom)
            localContourPath.lineTo(r3.right, r3.top)
            localContourPath.lineTo(r3.right, r3.bottom)

            // Base e lato sinistro aderente alle 4 righe
            localContourPath.lineTo(r3.left, r3.bottom)
            localContourPath.lineTo(r3.left, r3.top)
            localContourPath.lineTo(r2.left, r2.bottom)
            localContourPath.lineTo(r2.left, r2.top)
            localContourPath.lineTo(r1.left, r1.bottom)
            localContourPath.lineTo(r1.left, r1.top)
            localContourPath.lineTo(r0.left, r0.bottom)
            localContourPath.lineTo(r0.left, r0.top)

            localContourPath.lineTo(dragHandleLocalRect.left, r0.top)
            localContourPath.close()
        }

        screenContourPath.reset()
        localContourPath.transform(forwardMatrix, screenContourPath)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val alphaInt = (bgOpacity * 255).toInt().coerceIn(30, 255)
        val cornerFx = CornerPathEffect(12f * density)

        sculptedBgPaint.color = Color.argb(alphaInt, 0, 0, 0)
        sculptedBgPaint.pathEffect = cornerFx
        sculptedBorderPaint.color = Color.argb((alphaInt * 0.85f).toInt().coerceAtLeast(60), 54, 54, 60)
        sculptedBorderPaint.pathEffect = cornerFx

        canvas.save()
        canvas.concat(forwardMatrix)

        // 1. Disegna lo sfondo scolpito aderente ai tasti (zero nero in eccesso)
        canvas.drawPath(localContourPath, sculptedBgPaint)
        canvas.drawPath(localContourPath, sculptedBorderPaint)

        // 2. Disegna la barra minimal per trascinare il pop-up sullo schermo
        val pillW = dragHandleLocalRect.width() * 0.46f
        val pillH = 3.5f * density
        val pillLeft = dragHandleLocalRect.centerX() - pillW / 2f
        val pillTop = (dragHandleLocalRect.top + 8f * density)
        canvas.drawRoundRect(
            pillLeft, pillTop, pillLeft + pillW, pillTop + pillH,
            pillH, pillH, dragPillPaint
        )

        // 3. Disegna tutti i tasti in tema Bianco e Nero minimal
        val keyUnitW = (localClusterWidth / 10f) * letterSizeScale
        subTextPaint.textSize = (keyUnitW * 0.23f).coerceAtLeast(7f * density)

        for ((key, rect) in computedKeys) {
            if (activeKey == key) {
                canvas.drawCircle(
                    rect.centerX(),
                    rect.centerY(),
                    minOf(rect.width(), rect.height()) * 0.45f,
                    keyHighlightPaint
                )
            }

            when {
                key.isActionKey -> drawMonochromeActionKey(canvas, rect, density)
                key.code == "SHIFT" -> drawMonochromeShiftKey(canvas, rect, density)
                key.code == "DEL" -> drawMonochromeBackspaceKey(canvas, rect, density)
                key.code == "GLOBE" -> drawMonochromeGlobeKey(canvas, rect, density)
                else -> {
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
            }
        }

        if (gestureMode == GestureMode.SWIPE_TYPING && swipePathPoints.size > 1) {
            canvas.drawPath(swipeDrawPath, swipeTrailPaint)
        }

        canvas.restore()
    }

    private fun drawMonochromeActionKey(canvas: Canvas, rect: RectF, density: Float) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val r = minOf(rect.width(), rect.height()) * 0.35f

        // Cerchio bianco puro minimal con icona nera ad alto contrasto (zero blu)
        canvas.drawCircle(cx, cy, r, actionCircleFillPaint)
        vectorIconBlackPaint.strokeWidth = 2.0f * density
        vectorIconBlackPaint.style = Paint.Style.STROKE

        val s = r * 0.48f
        when (actionType) {
            "SEARCH" -> {
                val lensR = s * 0.68f
                val lensCx = cx - s * 0.16f
                val lensCy = cy - s * 0.16f
                canvas.drawCircle(lensCx, lensCy, lensR, vectorIconBlackPaint)
                canvas.drawLine(
                    lensCx + lensR * 0.70f,
                    lensCy + lensR * 0.70f,
                    cx + s * 0.85f,
                    cy + s * 0.85f,
                    vectorIconBlackPaint
                )
            }
            "SEND" -> {
                val p = Path().apply {
                    moveTo(cx - s * 0.65f, cy - s * 0.55f)
                    lineTo(cx + s * 0.75f, cy)
                    lineTo(cx - s * 0.65f, cy + s * 0.55f)
                    lineTo(cx - s * 0.30f, cy)
                    close()
                }
                canvas.drawPath(p, vectorIconBlackPaint)
            }
            "DONE" -> {
                val p = Path().apply {
                    moveTo(cx - s * 0.65f, cy + s * 0.05f)
                    lineTo(cx - s * 0.15f, cy + s * 0.55f)
                    lineTo(cx + s * 0.70f, cy - s * 0.45f)
                }
                canvas.drawPath(p, vectorIconBlackPaint)
            }
            "NEXT" -> {
                canvas.drawLine(cx - s * 0.65f, cy, cx + s * 0.45f, cy, vectorIconBlackPaint)
                canvas.drawLine(cx + s * 0.05f, cy - s * 0.45f, cx + s * 0.50f, cy, vectorIconBlackPaint)
                canvas.drawLine(cx + s * 0.05f, cy + s * 0.45f, cx + s * 0.50f, cy, vectorIconBlackPaint)
                canvas.drawLine(cx + s * 0.70f, cy - s * 0.45f, cx + s * 0.70f, cy + s * 0.45f, vectorIconBlackPaint)
            }
            else -> {
                // Invio classico minimal (↵)
                val p = Path().apply {
                    moveTo(cx + s * 0.55f, cy - s * 0.45f)
                    lineTo(cx + s * 0.55f, cy + s * 0.20f)
                    lineTo(cx - s * 0.55f, cy + s * 0.20f)
                }
                canvas.drawPath(p, vectorIconBlackPaint)
                canvas.drawLine(cx - s * 0.20f, cy - s * 0.15f, cx - s * 0.60f, cy + s * 0.20f, vectorIconBlackPaint)
                canvas.drawLine(cx - s * 0.20f, cy + s * 0.55f, cx - s * 0.60f, cy + s * 0.20f, vectorIconBlackPaint)
            }
        }
    }

    private fun drawMonochromeShiftKey(canvas: Canvas, rect: RectF, density: Float) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val s = minOf(rect.width(), rect.height()) * 0.24f

        val arrow = Path().apply {
            moveTo(cx, cy - s)
            lineTo(cx + s * 0.95f, cy + s * 0.05f)
            lineTo(cx + s * 0.42f, cy + s * 0.05f)
            lineTo(cx + s * 0.42f, cy + s * 0.85f)
            lineTo(cx - s * 0.42f, cy + s * 0.85f)
            lineTo(cx - s * 0.42f, cy + s * 0.05f)
            lineTo(cx - s * 0.95f, cy + s * 0.05f)
            close()
        }

        vectorIconWhitePaint.strokeWidth = 1.8f * density
        vectorIconWhitePaint.style = if (isShifted || isCapsLock) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        canvas.drawPath(arrow, vectorIconWhitePaint)

        if (isCapsLock) {
            canvas.drawLine(cx - s * 0.55f, cy + s * 1.18f, cx + s * 0.55f, cy + s * 1.18f, vectorIconWhitePaint)
        }
    }

    private fun drawMonochromeBackspaceKey(canvas: Canvas, rect: RectF, density: Float) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val s = minOf(rect.width(), rect.height()) * 0.25f

        vectorIconWhitePaint.style = Paint.Style.STROKE
        vectorIconWhitePaint.strokeWidth = 1.8f * density

        val box = Path().apply {
            moveTo(cx - s * 1.1f, cy)
            lineTo(cx - s * 0.45f, cy - s * 0.72f)
            lineTo(cx + s * 0.95f, cy - s * 0.72f)
            lineTo(cx + s * 0.95f, cy + s * 0.72f)
            lineTo(cx - s * 0.45f, cy + s * 0.72f)
            close()
        }
        canvas.drawPath(box, vectorIconWhitePaint)
        val xOff = s * 0.24f
        val xSize = s * 0.34f
        canvas.drawLine(cx + xOff - xSize, cy - xSize, cx + xOff + xSize, cy + xSize, vectorIconWhitePaint)
        canvas.drawLine(cx + xOff + xSize, cy - xSize, cx + xOff - xSize, cy + xSize, vectorIconWhitePaint)
    }

    private fun drawMonochromeGlobeKey(canvas: Canvas, rect: RectF, density: Float) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val r = minOf(rect.width(), rect.height()) * 0.24f

        vectorIconWhitePaint.style = Paint.Style.STROKE
        vectorIconWhitePaint.strokeWidth = 1.6f * density
        canvas.drawCircle(cx, cy, r, vectorIconWhitePaint)
        canvas.drawLine(cx - r, cy, cx + r, cy, vectorIconWhitePaint)
        canvas.drawOval(cx - r * 0.46f, cy - r, cx + r * 0.46f, cy + r, vectorIconWhitePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        val pts = floatArrayOf(event.x, event.y)
        inverseMatrix.mapPoints(pts)
        val lx = pts[0]
        val ly = pts[1]

        // Ignora i tocchi fuori dalla sagoma scolpita
        val expandedDragHandle = RectF(dragHandleLocalRect).apply { inset(-8f * density, -8f * density) }
        val inKey = computedKeys.any { it.second.contains(lx, ly) }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !inKey && !expandedDragHandle.contains(lx, ly)) {
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastRawX = event.rawX
                lastRawY = event.rawY
                downLocalX = lx
                downLocalY = ly
                longPressTriggered = false
                swipePathPoints.clear()
                swipeDrawPath.reset()

                // 1. Linguetta superiore -> trascina la tastiera pop-up in qualsiasi punto dello schermo
                if (expandedDragHandle.contains(lx, ly)) {
                    gestureMode = GestureMode.DRAG_POPUP
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    return true
                }

                // 2. Tasto
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
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                when (gestureMode) {
                    GestureMode.DRAG_POPUP -> {
                        val dx = event.rawX - lastRawX
                        val dy = event.rawY - lastRawY
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                        listener.onDragFloatingKeyboard(dx, dy)
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
                    val candidates = swipeDecoder.decode(swipePathPoints, letterCenters, localClusterWidth / 10f)
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
                rebuildSculptedLayout()
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
