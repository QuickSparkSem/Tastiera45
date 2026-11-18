package com.tastiera45.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class AngledKeyboardView(
    context: Context,
    private val listener: KeyboardActionListener
) : View(context) {

    interface KeyboardActionListener {
        fun onCommitText(text: String)
        fun onDeleteChar()
        fun onEnterPressed()
        fun onSwitchIme()
        fun onMoveTextCursor(deltaXSteps: Int, deltaYSteps: Int)
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

    private data class ArcKeySector(
        val key: KeyDef,
        val innerR: Float,
        val outerR: Float,
        val startDeg: Float,
        val sweepDeg: Float,
        val path: Path,
        val centerX: Float,
        val centerY: Float,
        val textAngleDeg: Float
    )

    private var isArcLayout = true
    private var isArcLeftSide = false
    private var scaleFactor = 0.84f
    private var bgOpacity = 0.0f
    private var tiltAngleDeg = 0f
    private var letterSizeScale = 1.0f
    private var keySpacingScale = 0.25f

    // Parametri ombra nera sotto ogni carattere/pulsante
    private var shadowStrength = 0.92f
    private var shadowBlurDp = 7.0f

    private var isShifted = false
    private var isCapsLock = false
    private var isSymbolsMode = false
    private var actionType = "SEARCH"

    // Geometria Pop-up Scolpito
    private val localContourPath = Path()
    private val screenContourPath = Path()
    private val dragHandleLocalRect = RectF()
    private val forwardMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val computedKeys = mutableListOf<Pair<KeyDef, RectF>>()
    private var localClusterWidth = 600f

    // Geometria Ad Arco Concentrico
    private var arcCenterX = 0f
    private var arcCenterY = 0f
    private var arcOuterR = 0f
    private var arcHubR = 0f
    private var arcFanStartDeg = 180f
    private var arcFanSweepDeg = 90f
    private val arcSectors = mutableListOf<ArcKeySector>()
    private val arcOuterContourPath = Path()

    private val letterCenters = mutableMapOf<Char, PointF>()

    private enum class GestureMode {
        NONE, KEY_TAP, SWIPE_TYPING, SPACE_CURSOR, DRAG_POPUP
    }

    private var gestureMode = GestureMode.NONE
    private var activeKey: KeyDef? = null
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var downLocalX = 0f
    private var downLocalY = 0f
    private var lastCursorRawX = 0f
    private var lastCursorRawY = 0f
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
                lastCursorRawX = lastRawX
                lastCursorRawY = lastRawY
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

    // Paint minimal Bianco e Nero + Ombra nera morbida sotto ogni carattere
    private val sculptedBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val charSpotShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dragPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val mainTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val textShadowOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        textAlign = Paint.Align.CENTER
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E5E5EA")
        textAlign = Paint.Align.CENTER
    }
    private val keyHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255)
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
        color = Color.argb(200, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 6.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = CornerPathEffect(18f)
    }

    private fun rebuildCurrentGeometry() {
        if (width <= 0 || height <= 0) return
        if (isArcLayout) {
            rebuildArcLayout()
        } else {
            rebuildSculptedLayout()
        }
    }

    fun setArcLayoutMode(arcMode: Boolean) {
        if (isArcLayout != arcMode) {
            isArcLayout = arcMode
            rebuildCurrentGeometry()
            requestLayout()
            invalidate()
        }
    }

    fun isArcLayoutMode(): Boolean = isArcLayout

    fun setArcLeftSide(left: Boolean) {
        if (isArcLeftSide != left) {
            isArcLeftSide = left
            rebuildCurrentGeometry()
            requestLayout()
            invalidate()
        }
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
        bgOpacity = opacity.coerceIn(0.0f, 1.0f)
        invalidate()
    }

    fun setTiltAngle(angleDeg: Float) {
        val clamped = angleDeg.coerceIn(-90f, 90f)
        if (abs(clamped - tiltAngleDeg) > 0.2f) {
            tiltAngleDeg = clamped
            rebuildCurrentGeometry()
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
            rebuildCurrentGeometry()
            requestLayout()
            invalidate()
        }
    }

    fun setShadowConfig(strength: Float, blurDp: Float) {
        shadowStrength = strength.coerceIn(0.0f, 1.0f)
        shadowBlurDp = blurDp.coerceIn(0.0f, 16.0f)
        invalidate()
    }

    fun setActionType(type: String) {
        actionType = type
        invalidate()
    }

    fun appendContourToRegion(targetRegion: Region, offsetX: Int, offsetY: Int) {
        if (width <= 0 || height <= 0) return
        val sourcePath = if (isArcLayout) arcOuterContourPath else screenContourPath
        if (sourcePath.isEmpty) return

        try {
            val shifted = Path(sourcePath)
            shifted.offset(offsetX.toFloat(), offsetY.toFloat())
            val pathRegion = Region()
            val clip = Region(offsetX, offsetY, offsetX + width, offsetY + height)
            if (pathRegion.setPath(shifted, clip)) {
                targetRegion.op(pathRegion, Region.Op.UNION)
            }
        } catch (_: Throwable) {
        }
    }

    private fun configureShadowPaints(density: Float) {
        val shadowAlpha = (shadowStrength * 255).toInt().coerceIn(0, 255)
        val radiusPx = (shadowBlurDp * density).coerceAtLeast(0.5f)
        val offsetYPx = 2.4f * density * shadowStrength
        val shadowColor = Color.argb(shadowAlpha, 0, 0, 0)

        if (shadowStrength > 0.02f) {
            mainTextPaint.setShadowLayer(radiusPx, 0f, offsetYPx, shadowColor)
            subTextPaint.setShadowLayer(radiusPx * 0.85f, 0f, offsetYPx * 0.85f, shadowColor)
            vectorIconWhitePaint.setShadowLayer(radiusPx, 0f, offsetYPx, shadowColor)
            actionCircleFillPaint.setShadowLayer(radiusPx, 0f, offsetYPx, shadowColor)
            dragPillPaint.setShadowLayer(radiusPx, 0f, offsetYPx, shadowColor)
        } else {
            mainTextPaint.clearShadowLayer()
            subTextPaint.clearShadowLayer()
            vectorIconWhitePaint.clearShadowLayer()
            actionCircleFillPaint.clearShadowLayer()
            dragPillPaint.clearShadowLayer()
        }

        textShadowOutlinePaint.color = Color.argb((shadowStrength * 245).toInt().coerceIn(0, 255), 0, 0, 0)
        textShadowOutlinePaint.strokeWidth = (3.2f * density * shadowStrength).coerceAtLeast(0.5f)
    }

    private fun drawButtonUnderShadow(canvas: Canvas, cx: Float, cy: Float, baseRadius: Float, density: Float) {
        if (shadowStrength <= 0.02f) return
        val extraSpread = shadowBlurDp * density * 1.15f
        val r = (baseRadius * 0.72f + extraSpread).coerceAtLeast(8f * density)
        val centerAlpha = (shadowStrength * 225).toInt().coerceIn(0, 255)
        val midAlpha = (shadowStrength * 135).toInt().coerceIn(0, 255)
        val offsetY = 2.2f * density * shadowStrength

        charSpotShadowPaint.shader = RadialGradient(
            cx,
            cy + offsetY,
            r,
            intArrayOf(
                Color.argb(centerAlpha, 0, 0, 0),
                Color.argb(midAlpha, 0, 0, 0),
                Color.TRANSPARENT
            ),
            floatArrayOf(0.0f, 0.52f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy + offsetY, r, charSpotShadowPaint)
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
                    KeyDef("SPACE", "Spazio", weight = 4.2f),
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
                    KeyDef("SPACE", "Spazio", weight = 4.2f),
                    KeyDef(".", ".", ""),
                    KeyDef("ENTER", "", isActionKey = true, weight = 1.35f)
                )
            )
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels.coerceAtLeast(320)

        if (isArcLayout) {
            val radius = (screenW * scaleFactor * 0.96f).coerceAtLeast(220f * density)
            val pad = 8f * density
            val measW = ceil(radius + pad * 2f).toInt().coerceAtLeast(1)
            val measH = ceil(radius + pad * 2f).toInt().coerceAtLeast(1)
            setMeasuredDimension(measW, measH)
        } else {
            localClusterWidth = (screenW * scaleFactor).coerceAtLeast(210f * density)
            val verticalGap = (10f * density) * keySpacingScale
            val baseRowH = localClusterWidth * 0.128f
            val dragTabH = 22f * density
            val pad = 5f * density

            val localTotalW = localClusterWidth + pad * 2f
            val localTotalH = dragTabH + (baseRowH * 4f) + (verticalGap * 3f) + pad * 2f

            val m = Matrix()
            m.setRotate(tiltAngleDeg, localTotalW / 2f, localTotalH / 2f)
            val bounds = RectF(0f, 0f, localTotalW, localTotalH)
            m.mapRect(bounds)

            val measW = ceil(bounds.width() + 8f * density).toInt().coerceAtLeast(1)
            val measH = ceil(bounds.height() + 8f * density).toInt().coerceAtLeast(1)
            setMeasuredDimension(measW, measH)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildCurrentGeometry()
    }

    // =========================================================================
    // COSTRUZIONE LAYOUT 1: AD ARCO CONCENTRICO DALL'ANGOLO (SENZA BORDI)
    // =========================================================================

    private fun rebuildArcLayout() {
        arcSectors.clear()
        letterCenters.clear()
        arcOuterContourPath.reset()
        if (width <= 0 || height <= 0) return

        val density = resources.displayMetrics.density
        val pad = 8f * density
        arcOuterR = (minOf(width, height) - pad * 2f).coerceAtLeast(80f * density)
        arcHubR = (arcOuterR * 0.24f).coerceAtLeast(20f * density)

        if (isArcLeftSide) {
            arcCenterX = pad
            arcCenterY = height - pad
            val baseCenter = 315f
            arcFanSweepDeg = (90f + tiltAngleDeg * 0.22f).coerceIn(72f, 92f)
            arcFanStartDeg = (baseCenter - arcFanSweepDeg / 2f).coerceIn(270f, 360f - arcFanSweepDeg)
        } else {
            arcCenterX = width - pad
            arcCenterY = height - pad
            val baseCenter = 225f
            arcFanSweepDeg = (90f + tiltAngleDeg * 0.22f).coerceIn(72f, 92f)
            arcFanStartDeg = (baseCenter - arcFanSweepDeg / 2f).coerceIn(180f, 270f - arcFanSweepDeg)
        }

        val fullOuterOval = RectF(
            arcCenterX - arcOuterR,
            arcCenterY - arcOuterR,
            arcCenterX + arcOuterR,
            arcCenterY + arcOuterR
        )
        val hubInnerR = arcHubR * 0.35f
        val fullInnerOval = RectF(
            arcCenterX - hubInnerR,
            arcCenterY - hubInnerR,
            arcCenterX + hubInnerR,
            arcCenterY + hubInnerR
        )
        arcOuterContourPath.arcTo(fullOuterOval, arcFanStartDeg, arcFanSweepDeg, true)
        arcOuterContourPath.arcTo(fullInnerOval, arcFanStartDeg + arcFanSweepDeg, -arcFanSweepDeg, false)
        arcOuterContourPath.close()

        val rows = getRowDefinitions()
        val ringGap = (7f * density) * keySpacingScale
        val angularGapDeg = 2.4f * keySpacingScale
        val usableRadialSpan = (arcOuterR - arcHubR - ringGap * 3f).coerceAtLeast(40f)
        val bandThickness = usableRadialSpan / 4f

        rows.forEachIndexed { rowIndex, rowKeys ->
            val outerR = arcOuterR - rowIndex * (bandThickness + ringGap)
            val innerR = (outerR - bandThickness).coerceAtLeast(arcHubR * 0.5f)
            val midR = (outerR + innerR) / 2f

            val rowAngleInset = when (rowIndex) {
                0 -> 0f
                1 -> 1.5f
                2 -> 1.0f
                else -> 2.5f
            }
            val rowStartDeg = arcFanStartDeg + rowAngleInset
            val rowSweepDeg = (arcFanSweepDeg - rowAngleInset * 2f).coerceAtLeast(30f)
            val totalGapDeg = angularGapDeg * (rowKeys.size - 1).coerceAtLeast(0)
            val usableSweepDeg = (rowSweepDeg - totalGapDeg).coerceAtLeast(20f)
            val totalWeight = rowKeys.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(1f)

            val outerOval = RectF(arcCenterX - outerR, arcCenterY - outerR, arcCenterX + outerR, arcCenterY + outerR)
            val innerOval = RectF(arcCenterX - innerR, arcCenterY - innerR, arcCenterX + innerR, arcCenterY + innerR)

            var curDeg = rowStartDeg
            for (k in rowKeys) {
                val keySweep = (k.weight / totalWeight) * usableSweepDeg
                val midDeg = curDeg + keySweep / 2f
                val rad = Math.toRadians(midDeg.toDouble())
                val cx = arcCenterX + (midR * cos(rad)).toFloat()
                val cy = arcCenterY + (midR * sin(rad)).toFloat()

                val sectorPath = Path().apply {
                    arcTo(outerOval, curDeg, keySweep, true)
                    arcTo(innerOval, curDeg + keySweep, -keySweep, false)
                    close()
                }

                var textRot = (midDeg + 90f) % 360f
                if (textRot < 0f) textRot += 360f
                if (textRot in 90.01f..269.99f) {
                    textRot = (textRot + 180f) % 360f
                }

                arcSectors.add(
                    ArcKeySector(
                        key = k,
                        innerR = innerR,
                        outerR = outerR,
                        startDeg = curDeg,
                        sweepDeg = keySweep,
                        path = sectorPath,
                        centerX = cx,
                        centerY = cy,
                        textAngleDeg = textRot
                    )
                )

                if (k.code.length == 1 && k.code[0] in 'a'..'z') {
                    letterCenters[k.code[0]] = PointF(cx, cy)
                }

                curDeg += keySweep + angularGapDeg
            }
        }
    }

    // =========================================================================
    // COSTRUZIONE LAYOUT 2: POP-UP SCOLPITO FLUTTUANTE (SENZA BORDI)
    // =========================================================================

    private fun rebuildSculptedLayout() {
        computedKeys.clear()
        letterCenters.clear()
        localContourPath.reset()
        screenContourPath.reset()
        if (width <= 0 || height <= 0) return

        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels.coerceAtLeast(320)
        localClusterWidth = (screenW * scaleFactor).coerceAtLeast(210f * density)

        val verticalGap = (10f * density) * keySpacingScale
        val horizontalGap = (8f * density) * keySpacingScale
        val baseRowH = localClusterWidth * 0.128f
        val dragTabH = 22f * density
        val pad = 5f * density

        val localTotalW = localClusterWidth + pad * 2f
        val localTotalH = dragTabH + (baseRowH * 4f) + (verticalGap * 3f) + pad * 2f

        forwardMatrix.reset()
        forwardMatrix.postRotate(tiltAngleDeg, localTotalW / 2f, localTotalH / 2f)
        forwardMatrix.postTranslate((width - localTotalW) / 2f, (height - localTotalH) / 2f)
        if (!forwardMatrix.invert(inverseMatrix)) {
            inverseMatrix.reset()
        }

        val tabW = (localClusterWidth * 0.34f).coerceAtLeast(74f * density)
        val tabLeft = (localTotalW - tabW) / 2f
        val tabRight = tabLeft + tabW
        dragHandleLocalRect.set(tabLeft, 0f, tabRight, dragTabH + pad)

        val rows = getRowDefinitions()
        val rowBoundsList = mutableListOf<RectF>()

        rows.forEachIndexed { rowIndex, rowKeys ->
            val top = dragTabH + pad + rowIndex * (baseRowH + verticalGap)
            val bottom = top + baseRowH

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
            val totalWeight = rowKeys.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(1f)

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

        if (rowBoundsList.size == 4) {
            val r0 = rowBoundsList[0]
            val r1 = rowBoundsList[1]
            val r2 = rowBoundsList[2]
            val r3 = rowBoundsList[3]

            localContourPath.moveTo(dragHandleLocalRect.left, dragHandleLocalRect.top)
            localContourPath.lineTo(dragHandleLocalRect.right, dragHandleLocalRect.top)
            localContourPath.lineTo(dragHandleLocalRect.right, r0.top)

            localContourPath.lineTo(r0.right, r0.top)
            localContourPath.lineTo(r0.right, r0.bottom)
            localContourPath.lineTo(r1.right, r1.top)
            localContourPath.lineTo(r1.right, r1.bottom)
            localContourPath.lineTo(r2.right, r2.top)
            localContourPath.lineTo(r2.right, r2.bottom)
            localContourPath.lineTo(r3.right, r3.top)
            localContourPath.lineTo(r3.right, r3.bottom)

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

        localContourPath.transform(forwardMatrix, screenContourPath)
    }

    // =========================================================================
    // DISEGNO SU CANVAS (SENZA CONTORNI TASTI, OMBRA NERA SOTTO OGNI CARATTERE)
    // =========================================================================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val density = resources.displayMetrics.density
        configureShadowPaints(density)

        if (isArcLayout) {
            if (arcSectors.isEmpty()) rebuildArcLayout()
            drawArcKeyboard(canvas, density)
        } else {
            if (computedKeys.isEmpty()) rebuildSculptedLayout()
            drawSculptedKeyboard(canvas, density)
        }
    }

    private fun drawArcKeyboard(canvas: Canvas, density: Float) {
        val alphaInt = (bgOpacity * 255).toInt().coerceIn(0, 255)
        if (alphaInt > 0 && !arcOuterContourPath.isEmpty) {
            sculptedBgPaint.pathEffect = null
            sculptedBgPaint.color = Color.argb(alphaInt, 0, 0, 0)
            canvas.drawPath(arcOuterContourPath, sculptedBgPaint)
        }

        val unitSize = (((arcOuterR - arcHubR) / 4f) * letterSizeScale).coerceAtLeast(10f * density)
        subTextPaint.textSize = (unitSize * 0.24f).coerceAtLeast(7f * density)

        for (sector in arcSectors) {
            val key = sector.key
            val spotRadius = if (key.code == "SPACE") unitSize * 0.90f else unitSize * 0.56f

            drawButtonUnderShadow(canvas, sector.centerX, sector.centerY, spotRadius, density)

            if (activeKey == key) {
                canvas.drawCircle(sector.centerX, sector.centerY, spotRadius * 0.85f, keyHighlightPaint)
            }

            canvas.save()
            canvas.translate(sector.centerX, sector.centerY)
            canvas.rotate(sector.textAngleDeg)

            val iconBox = RectF(-unitSize * 0.45f, -unitSize * 0.45f, unitSize * 0.45f, unitSize * 0.45f)
            when {
                key.isActionKey -> drawMonochromeActionKey(canvas, iconBox, density)
                key.code == "SHIFT" -> drawMonochromeShiftKey(canvas, iconBox, density)
                key.code == "DEL" -> drawMonochromeBackspaceKey(canvas, iconBox, density)
                key.code == "GLOBE" -> drawMonochromeGlobeKey(canvas, iconBox, density)
                else -> {
                    val labelToDraw = if (key.code == "SPACE" && gestureMode == GestureMode.SPACE_CURSOR) {
                        "◄ ▲ ▼ ►"
                    } else {
                        key.label
                    }
                    mainTextPaint.textSize = when {
                        key.code == "SPACE" -> (unitSize * 0.34f).coerceAtLeast(10f * density)
                        labelToDraw.length >= 3 -> (unitSize * 0.32f).coerceAtLeast(9.5f * density)
                        else -> (unitSize * 0.48f).coerceAtLeast(13f * density)
                    }
                    mainTextPaint.color = Color.WHITE
                    val textY = -(mainTextPaint.descent() + mainTextPaint.ascent()) / 2f

                    if (shadowStrength > 0.05f) {
                        textShadowOutlinePaint.textSize = mainTextPaint.textSize
                        canvas.drawText(labelToDraw, 0f, textY, textShadowOutlinePaint)
                    }
                    canvas.drawText(labelToDraw, 0f, textY, mainTextPaint)

                    if (key.subLabel.isNotEmpty()) {
                        val subX = unitSize * 0.28f
                        val subY = -unitSize * 0.16f
                        if (shadowStrength > 0.05f) {
                            textShadowOutlinePaint.textSize = subTextPaint.textSize
                            canvas.drawText(key.subLabel, subX, subY, textShadowOutlinePaint)
                        }
                        canvas.drawText(key.subLabel, subX, subY, subTextPaint)
                    }
                }
            }
            canvas.restore()
        }

        val gripDist = arcHubR * 0.55f
        val gripRad = Math.toRadians((arcFanStartDeg + arcFanSweepDeg / 2f).toDouble())
        val gripX = arcCenterX + (gripDist * cos(gripRad)).toFloat()
        val gripY = arcCenterY + (gripDist * sin(gripRad)).toFloat()
        drawButtonUnderShadow(canvas, gripX, gripY, 10f * density, density)
        canvas.drawCircle(gripX, gripY, 3.8f * density, dragPillPaint)

        if (gestureMode == GestureMode.SWIPE_TYPING && swipePathPoints.size > 1) {
            canvas.drawPath(swipeDrawPath, swipeTrailPaint)
        }
    }

    private fun drawSculptedKeyboard(canvas: Canvas, density: Float) {
        val alphaInt = (bgOpacity * 255).toInt().coerceIn(0, 255)
        val cornerFx = CornerPathEffect(12f * density)

        canvas.save()
        canvas.concat(forwardMatrix)

        if (alphaInt > 0 && !localContourPath.isEmpty) {
            sculptedBgPaint.color = Color.argb(alphaInt, 0, 0, 0)
            sculptedBgPaint.pathEffect = cornerFx
            canvas.drawPath(localContourPath, sculptedBgPaint)
        }

        drawButtonUnderShadow(canvas, dragHandleLocalRect.centerX(), dragHandleLocalRect.top + 9.5f * density, 14f * density, density)
        val pillW = dragHandleLocalRect.width() * 0.48f
        val pillH = 3.5f * density
        val pillLeft = dragHandleLocalRect.centerX() - pillW / 2f
        val pillTop = dragHandleLocalRect.top + 8f * density
        canvas.drawRoundRect(
            pillLeft, pillTop, pillLeft + pillW, pillTop + pillH,
            pillH, pillH, dragPillPaint
        )

        val keyUnitW = ((localClusterWidth / 10f) * letterSizeScale).coerceAtLeast(10f * density)
        subTextPaint.textSize = (keyUnitW * 0.23f).coerceAtLeast(7f * density)

        for ((key, rect) in computedKeys) {
            val spotRadius = if (key.code == "SPACE") keyUnitW * 0.90f else keyUnitW * 0.56f
            drawButtonUnderShadow(canvas, rect.centerX(), rect.centerY(), spotRadius, density)

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
                    val labelToDraw = if (key.code == "SPACE" && gestureMode == GestureMode.SPACE_CURSOR) {
                        "◄ ▲ ▼ ►"
                    } else {
                        key.label
                    }
                    mainTextPaint.textSize = when {
                        key.code == "SPACE" -> (keyUnitW * 0.34f).coerceAtLeast(9.5f * density)
                        labelToDraw.length >= 3 -> (keyUnitW * 0.32f).coerceAtLeast(9f * density)
                        else -> (keyUnitW * 0.49f).coerceAtLeast(12f * density)
                    }
                    mainTextPaint.color = Color.WHITE
                    val textY = rect.centerY() - (mainTextPaint.descent() + mainTextPaint.ascent()) / 2f

                    if (shadowStrength > 0.05f) {
                        textShadowOutlinePaint.textSize = mainTextPaint.textSize
                        canvas.drawText(labelToDraw, rect.centerX(), textY, textShadowOutlinePaint)
                    }
                    canvas.drawText(labelToDraw, rect.centerX(), textY, mainTextPaint)

                    if (key.subLabel.isNotEmpty()) {
                        val subX = rect.right - rect.width() * 0.16f
                        val subY = rect.top + rect.height() * 0.30f
                        if (shadowStrength > 0.05f) {
                            textShadowOutlinePaint.textSize = subTextPaint.textSize
                            canvas.drawText(key.subLabel, subX, subY, textShadowOutlinePaint)
                        }
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

    // =========================================================================
    // DECODIFICA SWIPE POTENZIATA
    // =========================================================================

    private fun computeAverageKeyStep(): Float {
        if (letterCenters.size < 2) return 40f
        var sumMinDist = 0f
        var count = 0
        for ((ch, pt) in letterCenters) {
            var minDist = Float.MAX_VALUE
            for ((otherCh, otherPt) in letterCenters) {
                if (ch != otherCh) {
                    val d = hypot(pt.x - otherPt.x, pt.y - otherPt.y)
                    if (d < minDist) minDist = d
                }
            }
            if (minDist < Float.MAX_VALUE) {
                sumMinDist += minDist
                count++
            }
        }
        return if (count > 0) (sumMinDist / count).coerceAtLeast(14f) else 40f
    }

    private fun nearestLetterChar(pt: PointF): Char? {
        var bestChar: Char? = null
        var bestDist = Float.MAX_VALUE
        for ((ch, center) in letterCenters) {
            val d = hypot(pt.x - center.x, pt.y - center.y)
            if (d < bestDist) {
                bestDist = d
                bestChar = ch
            }
        }
        return bestChar
    }

    private fun buildEnhancedSwipePoints(rawPoints: List<PointF>, keyStep: Float): List<PointF> {
        if (rawPoints.size < 2) return rawPoints

        val step = (keyStep * 0.26f).coerceAtLeast(4f)
        val resampled = mutableListOf<PointF>()
        resampled.add(rawPoints.first())

        for (i in 1 until rawPoints.size) {
            val p0 = rawPoints[i - 1]
            val p1 = rawPoints[i]
            val segLen = hypot(p1.x - p0.x, p1.y - p0.y)
            if (segLen < 1f) continue
            val subdivisions = (segLen / step).toInt().coerceAtLeast(1)
            for (s in 1..subdivisions) {
                val t = s.toFloat() / subdivisions.toFloat()
                resampled.add(PointF(p0.x + (p1.x - p0.x) * t, p0.y + (p1.y - p0.y) * t))
            }
        }

        val boosted = mutableListOf<PointF>()
        repeat(3) { boosted.add(resampled.first()) }

        for (i in 1 until resampled.lastIndex) {
            val prev = resampled[i - 1]
            val curr = resampled[i]
            val next = resampled[i + 1]
            boosted.add(curr)

            val v1x = curr.x - prev.x
            val v1y = curr.y - prev.y
            val v2x = next.x - curr.x
            val v2y = next.y - curr.y
            val mag1 = hypot(v1x, v1y)
            val mag2 = hypot(v2x, v2y)
            if (mag1 > 0.5f && mag2 > 0.5f) {
                val cosAngle = (v1x * v2x + v1y * v2y) / (mag1 * mag2)
                if (cosAngle < 0.65f) {
                    boosted.add(curr)
                    boosted.add(curr)
                }
            }
        }

        repeat(3) { boosted.add(resampled.last()) }
        return boosted
    }

    private fun decodeWithPrecision(): List<String> {
        if (swipePathPoints.size < 2 || letterCenters.isEmpty()) return emptyList()
        val avgKeyStep = computeAverageKeyStep()
        val enhancedPoints = buildEnhancedSwipePoints(swipePathPoints, avgKeyStep)

        val rawCandidates = swipeDecoder.decode(enhancedPoints, letterCenters, avgKeyStep * 0.92f)
        if (rawCandidates.size <= 1) return rawCandidates

        val startChar = nearestLetterChar(swipePathPoints.first())
        val endChar = nearestLetterChar(swipePathPoints.last())

        val passedSequence = mutableListOf<Char>()
        for (pt in enhancedPoints) {
            val ch = nearestLetterChar(pt) ?: continue
            if (passedSequence.lastOrNull() != ch) {
                passedSequence.add(ch)
            }
        }

        return rawCandidates.sortedByDescending { word ->
            val clean = word.lowercase().filter { it in 'a'..'z' }
            if (clean.isEmpty()) return@sortedByDescending -1000f
            var score = 0f

            if (startChar != null) {
                val firstCenter = letterCenters[clean.first()]
                if (firstCenter != null) {
                    val d = hypot(swipePathPoints.first().x - firstCenter.x, swipePathPoints.first().y - firstCenter.y)
                    score -= (d / avgKeyStep) * 3.0f
                }
                if (clean.first() == startChar) score += 4.0f
            }

            if (endChar != null) {
                val lastCenter = letterCenters[clean.last()]
                if (lastCenter != null) {
                    val d = hypot(swipePathPoints.last().x - lastCenter.x, swipePathPoints.last().y - lastCenter.y)
                    score -= (d / avgKeyStep) * 3.0f
                }
                if (clean.last() == endChar) score += 4.0f
            }

            var seqIdx = 0
            var matchedChars = 0
            for (ch in clean) {
                val foundIdx = passedSequence.subList(seqIdx, passedSequence.size).indexOf(ch)
                if (foundIdx >= 0) {
                    matchedChars++
                    seqIdx += foundIdx
                }
            }
            score += (matchedChars.toFloat() / clean.length.toFloat()) * 5.0f
            score
        }
    }

    // =========================================================================
    // GESTIONE TOUCH, SWIPE E SCORRIMENTO CURSORE 2D DALLA BARRA SPAZIATRICE
    // =========================================================================

    private fun isAngleInArc(angleDeg: Float, startDeg: Float, sweepDeg: Float): Boolean {
        val diff = ((angleDeg - startDeg) % 360f + 360f) % 360f
        return diff <= sweepDeg
    }

    private fun findArcSectorAt(x: Float, y: Float): ArcKeySector? {
        if (arcSectors.isEmpty() || arcOuterR <= arcHubR) return null
        val dist = hypot(x - arcCenterX, y - arcCenterY)
        if (dist < arcHubR * 0.85f || dist > arcOuterR * 1.04f) return null

        var deg = Math.toDegrees(atan2((y - arcCenterY).toDouble(), (x - arcCenterX).toDouble())).toFloat()
        if (deg < 0f) deg += 360f

        arcSectors.firstOrNull { s ->
            dist in s.innerR..s.outerR && isAngleInArc(deg, s.startDeg, s.sweepDeg)
        }?.let { return it }

        val maxSnapDist = ((arcOuterR - arcHubR) / 3.0f).coerceAtLeast(10f)
        return arcSectors.minByOrNull { s ->
            hypot(x - s.centerX, y - s.centerY)
        }?.takeIf { hypot(x - it.centerX, y - it.centerY) < maxSnapDist }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density

        val lx: Float
        val ly: Float
        if (isArcLayout) {
            lx = event.x
            ly = event.y
        } else {
            val pts = floatArrayOf(event.x, event.y)
            inverseMatrix.mapPoints(pts)
            lx = pts[0]
            ly = pts[1]
        }

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (isArcLayout) {
                val dist = hypot(lx - arcCenterX, ly - arcCenterY)
                val hitSector = findArcSectorAt(lx, ly)
                val inCornerHub = dist <= arcHubR * 0.95f
                if (hitSector == null && !inCornerHub) {
                    return false
                }
            } else {
                val expandedDragHandle = RectF(dragHandleLocalRect).apply { inset(-10f * density, -10f * density) }
                val inKey = computedKeys.any { it.second.contains(lx, ly) }
                if (!inKey && !expandedDragHandle.contains(lx, ly)) {
                    return false
                }
            }
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastRawX = event.rawX
                lastRawY = event.rawY
                downRawX = event.rawX
                downRawY = event.rawY
                downLocalX = lx
                downLocalY = ly
                longPressTriggered = false
                swipePathPoints.clear()
                swipeDrawPath.reset()

                val hitKey: KeyDef? = if (isArcLayout) {
                    val dist = hypot(lx - arcCenterX, ly - arcCenterY)
                    if (dist <= arcHubR * 0.95f) {
                        gestureMode = GestureMode.DRAG_POPUP
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        return true
                    }
                    findArcSectorAt(lx, ly)?.key
                } else {
                    val expandedDragHandle = RectF(dragHandleLocalRect).apply { inset(-10f * density, -10f * density) }
                    if (expandedDragHandle.contains(lx, ly)) {
                        gestureMode = GestureMode.DRAG_POPUP
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        return true
                    }
                    computedKeys.firstOrNull { it.second.contains(lx, ly) }?.first
                }

                if (hitKey != null) {
                    activeKey = hitKey
                    gestureMode = GestureMode.KEY_TAP
                    lastCursorRawX = event.rawX
                    lastCursorRawY = event.rawY
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

                    if (hitKey.code == "DEL") {
                        listener.onDeleteChar()
                        handler.postDelayed(repeatDeleteRunnable, 380L)
                    } else {
                        handler.postDelayed(longPressRunnable, 360L)
                    }

                    if (!isSymbolsMode && hitKey.code.length == 1 && hitKey.code[0] in 'a'..'z') {
                        swipePathPoints.add(PointF(lx, ly))
                        swipeDrawPath.moveTo(lx, ly)
                    }
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                lastRawX = event.rawX
                lastRawY = event.rawY

                when (gestureMode) {
                    GestureMode.DRAG_POPUP -> {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        downRawX = event.rawX
                        downRawY = event.rawY
                        listener.onDragFloatingKeyboard(dx, dy)
                    }

                    GestureMode.KEY_TAP -> {
                        val moveScreen = hypot(event.rawX - downRawX, event.rawY - downRawY)
                        if (activeKey?.code == "SPACE" && moveScreen > 10f * density) {
                            handler.removeCallbacks(longPressRunnable)
                            gestureMode = GestureMode.SPACE_CURSOR
                            lastCursorRawX = event.rawX
                            lastCursorRawY = event.rawY
                            invalidate()
                        } else if (!isSymbolsMode && activeKey != null && !activeKey!!.isSpecial && moveScreen > 14f * density) {
                            handler.removeCallbacks(longPressRunnable)
                            gestureMode = GestureMode.SWIPE_TYPING
                            if (swipePathPoints.isEmpty()) {
                                swipePathPoints.add(PointF(downLocalX, downLocalY))
                                swipeDrawPath.moveTo(downLocalX, downLocalY)
                            }
                            swipePathPoints.add(PointF(lx, ly))
                            swipeDrawPath.lineTo(lx, ly)
                            invalidate()
                        }
                    }

                    GestureMode.SPACE_CURSOR -> {
                        val stepSizeX = 14f * density
                        val stepSizeY = 18f * density
                        val deltaX = event.rawX - lastCursorRawX
                        val deltaY = event.rawY - lastCursorRawY

                        var stepsX = 0
                        var stepsY = 0
                        if (abs(deltaX) >= stepSizeX) {
                            stepsX = (deltaX / stepSizeX).toInt()
                            lastCursorRawX += stepsX * stepSizeX
                        }
                        if (abs(deltaY) >= stepSizeY) {
                            stepsY = (deltaY / stepSizeY).toInt()
                            lastCursorRawY += stepsY * stepSizeY
                        }
                        if (stepsX != 0 || stepsY != 0) {
                            performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
                            listener.onMoveTextCursor(stepsX, stepsY)
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
                    try {
                        val candidates = decodeWithPrecision()
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
                    } catch (_: Throwable) {
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
                rebuildCurrentGeometry()
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
