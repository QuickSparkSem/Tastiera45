package com.tastiera45.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

    private data class ArcSector(
        val key: KeyDef,
        val innerR: Float,
        val outerR: Float,
        val startDeg: Float,
        val sweepDeg: Float,
        val cx: Float,
        val cy: Float,
        val textAngleDeg: Float
    )

    private data class ArcMetrics(
        val outerR: Float,
        val innerLimitR: Float,
        val gripR: Float,
        val startDeg: Float,
        val sweepDeg: Float,
        val bounds: RectF
    )

    private var isArcLeftSide = false
    private var scaleFactor = 0.86f
    private var bgOpacity = 0.0f
    private var tiltAngleDeg = 0f
    private var arcRotationDeg = 0f
    private var arcInnerRadiusPct = 0.34f
    private var arcRowSpacingScale = 0.35f
    private var letterSizeScale = 1.0f
    private var keySpacingScale = 0.25f
    private var shadowStrength = 0.92f
    private var shadowBlurDp = 7.0f

    private var isShifted = false
    private var isCapsLock = false
    private var isSymbolsMode = false
    private var actionType = "SEARCH"

    // Stato e animazione Modalità Incognito
    private var isIncognitoMode = false
    private var incognitoBurstStartMs = 0L

    private fun topHeadroomPx(): Float = 48f * resources.displayMetrics.density

    // Geometria Arco Concentrico
    private var arcCenterX = 0f
    private var arcCenterY = 0f
    private var arcOuterR = 0f
    private var arcInnerLimitR = 0f
    private var arcGripR = 0f
    private var arcFanStartDeg = 180f
    private var arcFanSweepDeg = 86f
    private val arcSectors = mutableListOf<ArcSector>()
    private val arcContourPath = Path()

    private val letterCenters = mutableMapOf<Char, PointF>()

    private enum class GestureMode { NONE, KEY_TAP, CHAR_POPUP, SWIPE, SPACE_CURSOR, DRAG }

    private var gestureMode = GestureMode.NONE
    private var activeKey: KeyDef? = null
    private var activeKeyCenterX = 0f
    private var activeKeyCenterY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var downLocalX = 0f
    private var downLocalY = 0f
    private var lastCursorX = 0f
    private var lastCursorY = 0f
    private var lastShiftTapTime = 0L

    // Specchietto opzioni al long-press
    private var charPopupOptions: List<String> = emptyList()
    private var charPopupSelectedIdx = 0
    private val charPopupBoxRect = RectF()
    private val charPopupItemRects = mutableListOf<RectF>()

    private val swipePoints = mutableListOf<PointF>()
    private val swipeDrawPath = Path()
    private val swipeDecoder = SwipeDecoder(context)
    private val handler = Handler(Looper.getMainLooper())

    private val repeatDelete = object : Runnable {
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
        if (gestureMode != GestureMode.KEY_TAP) return@Runnable
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        if (key.code == "SPACE") {
            gestureMode = GestureMode.SPACE_CURSOR
            lastCursorX = lastRawX
            lastCursorY = lastRawY
            invalidate()
            return@Runnable
        }

        val options = getAlternateOptions(key)
        if (options.isNotEmpty()) {
            gestureMode = GestureMode.CHAR_POPUP
            swipePoints.clear()
            swipeDrawPath.reset()
            buildCharPopupLayout(options, activeKeyCenterX, activeKeyCenterY)
            invalidate()
        }
    }

    // Paint minimal Bianco e Nero + Animazione Incognito
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val spotShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val gripArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val incognitoWavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val incognitoFillWavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255); style = Paint.Style.FILL }
    private val actionCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }

    private val popupBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F5111115"); style = Paint.Style.FILL }
    private val popupBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#52525B")
        style = Paint.Style.STROKE
    }
    private val popupSelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val popupTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val textOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
    private val iconWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconBlackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

    fun learnWord(word: String, isIncognito: Boolean) {
        swipeDecoder.learnWord(word, isIncognito)
    }

    fun setIncognito(active: Boolean) {
        if (active && !isIncognitoMode) {
            incognitoBurstStartMs = SystemClock.uptimeMillis()
        }
        isIncognitoMode = active
        invalidate()
    }

    fun applyStyle(
        arcLeft: Boolean,
        scale: Float,
        opacity: Float,
        tiltDeg: Float,
        arcRotDeg: Float,
        innerRadPct: Float,
        rowSpacing: Float,
        letterScale: Float,
        spacingScale: Float,
        shStrength: Float,
        shBlur: Float
    ) {
        isArcLeftSide = arcLeft
        scaleFactor = scale.coerceIn(0.50f, 0.98f)
        bgOpacity = opacity.coerceIn(0f, 1f)
        tiltAngleDeg = tiltDeg.coerceIn(-90f, 90f)
        arcRotationDeg = arcRotDeg.coerceIn(-180f, 180f)
        arcInnerRadiusPct = innerRadPct.coerceIn(0.15f, 0.72f)
        arcRowSpacingScale = rowSpacing.coerceIn(0f, 1f)
        letterSizeScale = letterScale.coerceIn(0.50f, 3.00f)
        keySpacingScale = spacingScale.coerceIn(0f, 1f)
        shadowStrength = shStrength.coerceIn(0f, 1f)
        shadowBlurDp = shBlur.coerceIn(0f, 16f)
        rebuildArcLayout()
        requestLayout()
        invalidate()
    }

    fun setActionType(type: String) {
        actionType = type
        invalidate()
    }

    fun appendContourToRegion(targetRegion: Region, offsetX: Int, offsetY: Int) {
        if (width <= 0 || height <= 0 || arcContourPath.isEmpty) return
        try {
            val shifted = Path(arcContourPath).apply { offset(offsetX.toFloat(), offsetY.toFloat()) }
            val r = Region()
            val clip = Region(offsetX, offsetY, offsetX + width, offsetY + height)
            if (r.setPath(shifted, clip)) {
                targetRegion.op(r, Region.Op.UNION)
            } else {
                val b = RectF()
                shifted.computeBounds(b, true)
                targetRegion.op(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt(), Region.Op.UNION)
            }
        } catch (_: Throwable) {
        }
    }

    private fun getAlternateOptions(key: KeyDef): List<String> {
        val upper = isShifted || isCapsLock
        fun applyCase(list: List<String>): List<String> =
            if (upper) list.map { it.uppercase() } else list

        val rawList: List<String> = when (key.code.lowercase()) {
            "q" -> listOf("1", "¹", "½", "⅓", "¼")
            "w" -> listOf("2", "²", "⅔")
            "e" -> listOf("3", "è", "é", "ê", "ë", "€")
            "r" -> listOf("4", "⁴", "®")
            "t" -> listOf("5", "™")
            "y" -> listOf("6", "ý", "ÿ")
            "u" -> listOf("7", "ù", "ú", "û", "ü")
            "i" -> listOf("8", "ì", "í", "î", "ï")
            "o" -> listOf("9", "ò", "ó", "ô", "ö", "õ", "ø", "º")
            "p" -> listOf("0", "°", "¶")
            "a" -> listOf("à", "á", "â", "ä", "ã", "å", "æ", "ª")
            "s" -> listOf("!", "¡", "ß", "§", "$")
            "d" -> listOf("@", "ð")
            "f" -> listOf("#", "£")
            "g" -> listOf("€", "¥", "¢")
            "h" -> listOf("%", "‰")
            "j" -> listOf("&")
            "k" -> listOf("*")
            "l" -> listOf("(", "[", "{", "<")
            ")" -> listOf(")", "]", "}", ">")
            "z" -> listOf("ž", "ź", "ż")
            "x" -> listOf("-", "–", "—", "_")
            "c" -> listOf("'", "ç", "©", "‘", "’")
            "v" -> listOf("\"", "«", "»", "“", "”")
            "b" -> listOf(":", "=")
            "n" -> listOf(";", "ñ", "ń")
            "m" -> listOf("?", "¿", "µ")
            "." -> listOf(".", ",", "!", "?", "…", ":", ";", "-", "/", "@")
            "," -> listOf(",", ".", "!", "?", ";", ":", "'", "\"")
            else -> if (key.subLabel.isNotEmpty()) listOf(key.subLabel) else emptyList()
        }

        val combined = LinkedHashSet<String>()
        if (key.subLabel.isNotEmpty()) combined.add(key.subLabel)
        combined.addAll(applyCase(rawList))
        return combined.toList()
    }

    private fun buildCharPopupLayout(options: List<String>, anchorX: Float, anchorY: Float) {
        charPopupOptions = options
        charPopupSelectedIdx = 0
        charPopupItemRects.clear()

        val density = resources.displayMetrics.density
        val cellW = (34f * density).coerceAtLeast(28f * density)
        val cellH = 38f * density
        val pad = 4f * density
        val totalW = options.size * cellW + pad * 2f
        val totalH = cellH + pad * 2f

        val maxW = width.toFloat().coerceAtLeast(totalW + 8f)
        val left = (anchorX - totalW / 2f).coerceIn(4f * density, (maxW - totalW - 4f * density).coerceAtLeast(4f * density))
        val top = (anchorY - totalH - 22f * density).coerceAtLeast(2f * density)

        charPopupBoxRect.set(left, top, left + totalW, top + totalH)
        options.indices.forEach { i ->
            val l = left + pad + i * cellW
            charPopupItemRects.add(RectF(l, top + pad, l + cellW, top + pad + cellH))
        }
    }

    private fun updateCharPopupSelection(touchX: Float) {
        if (charPopupItemRects.isEmpty()) return
        var bestIdx = 0
        var bestDist = Float.MAX_VALUE
        charPopupItemRects.forEachIndexed { idx, rect ->
            val d = abs(touchX - rect.centerX())
            if (d < bestDist) {
                bestDist = d
                bestIdx = idx
            }
        }
        if (bestIdx != charPopupSelectedIdx) {
            charPopupSelectedIdx = bestIdx
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            invalidate()
        }
    }

    private fun getRows(): List<List<KeyDef>> {
        if (isSymbolsMode) {
            return listOf(
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map { KeyDef(it, it) },
                listOf("+", "×", "÷", "=", "/", "_", "<", ">", "[", "]").map { KeyDef(it, it) },
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
        }
        fun c(s: String) = if (isShifted || isCapsLock) s.uppercase() else s
        return listOf(
            listOf(
                KeyDef("q", c("q"), "1"), KeyDef("w", c("w"), "2"), KeyDef("e", c("e"), "3"),
                KeyDef("r", c("r"), "4"), KeyDef("t", c("t"), "5"), KeyDef("y", c("y"), "6"),
                KeyDef("u", c("u"), "7"), KeyDef("i", c("i"), "8"), KeyDef("o", c("o"), "9"),
                KeyDef("p", c("p"), "0")
            ),
            listOf(
                KeyDef("a", c("a")), KeyDef("s", c("s"), "!"), KeyDef("d", c("d"), "@"),
                KeyDef("f", c("f"), "#"), KeyDef("g", c("g"), "€"), KeyDef("h", c("h"), "%"),
                KeyDef("j", c("j"), "&"), KeyDef("k", c("k"), "*"), KeyDef("l", c("l"), "("),
                KeyDef(")", ")")
            ),
            listOf(
                KeyDef("SHIFT", "", isSpecial = true, weight = 1.35f),
                KeyDef("z", c("z")), KeyDef("x", c("x"), "-"), KeyDef("c", c("c"), "'"),
                KeyDef("v", c("v"), "\""), KeyDef("b", c("b"), ":"), KeyDef("n", c("n"), ";"),
                KeyDef("m", c("m"), "?"),
                KeyDef("DEL", "", isSpecial = true, weight = 1.35f)
            ),
            listOf(
                KeyDef("SYM", "!#1", isSpecial = true, weight = 1.35f),
                KeyDef("GLOBE", "", isSpecial = true, weight = 1.15f),
                KeyDef("SPACE", "Spazio", weight = 4.2f),
                KeyDef(".", "."),
                KeyDef("ENTER", "", isActionKey = true, weight = 1.35f)
            )
        )
    }

    private fun isAngleInSweep(angleDeg: Float, startDeg: Float, sweepDeg: Float): Boolean {
        val normAngle = ((angleDeg % 360f) + 360f) % 360f
        val normStart = ((startDeg % 360f) + 360f) % 360f
        val diff = ((normAngle - normStart) % 360f + 360f) % 360f
        return diff <= sweepDeg
    }

    private fun computeArcMetrics(): ArcMetrics {
        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels.coerceAtLeast(320)
        val outerR = (screenW * scaleFactor * 0.96f).coerceAtLeast(200f * density)
        val innerLimitR = (outerR * arcInnerRadiusPct).coerceIn(28f * density, outerR * 0.72f)
        val gripR = (innerLimitR * 0.85f).coerceAtLeast(24f * density)

        val sweep = (86f + tiltAngleDeg * 0.24f).coerceIn(64f, 90f)
        val halfSpare = ((90f - sweep) / 2f).coerceAtLeast(0f)
        val tiltShift = (tiltAngleDeg / 90f) * halfSpare
        val baseStart = if (isArcLeftSide) 270f else 180f
        val startDeg = baseStart + halfSpare + tiltShift + arcRotationDeg

        val endDeg = startDeg + sweep
        val xs = mutableListOf(0f)
        val ys = mutableListOf(0f)
        for (deg in listOf(startDeg, endDeg)) {
            val rad = Math.toRadians(deg.toDouble())
            xs.add(outerR * cos(rad).toFloat())
            ys.add(outerR * sin(rad).toFloat())
        }
        for (cardinal in listOf(0f, 90f, 180f, 270f)) {
            if (isAngleInSweep(cardinal, startDeg, sweep)) {
                val rad = Math.toRadians(cardinal.toDouble())
                xs.add(outerR * cos(rad).toFloat())
                ys.add(outerR * sin(rad).toFloat())
            }
        }
        val bounds = RectF(
            xs.minOrNull() ?: -outerR,
            ys.minOrNull() ?: -outerR,
            xs.maxOrNull() ?: outerR,
            ys.maxOrNull() ?: outerR
        )
        return ArcMetrics(outerR, innerLimitR, gripR, startDeg, sweep, bounds)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val headroom = topHeadroomPx()
        val pad = 10f * density
        val m = computeArcMetrics()
        setMeasuredDimension(
            ceil(m.bounds.width() + pad * 2f).toInt().coerceAtLeast(1),
            ceil(m.bounds.height() + pad * 2f + headroom).toInt().coerceAtLeast(1)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildArcLayout()
    }

    private fun rebuildArcLayout() {
        arcSectors.clear()
        letterCenters.clear()
        arcContourPath.reset()

        val density = resources.displayMetrics.density
        val pad = 10f * density
        val headroom = topHeadroomPx()
        val m = computeArcMetrics()

        arcOuterR = m.outerR
        arcInnerLimitR = m.innerLimitR
        arcGripR = m.gripR
        arcFanStartDeg = m.startDeg
        arcFanSweepDeg = m.sweepDeg
        arcCenterX = -m.bounds.left + pad
        arcCenterY = -m.bounds.top + pad + headroom

        // Settore completo fino al vertice dell'arco: tutta l'impugnatura interna è 100% toccabile per trascinare su X e Y
        val outerOval = RectF(arcCenterX - arcOuterR, arcCenterY - arcOuterR, arcCenterX + arcOuterR, arcCenterY + arcOuterR)
        arcContourPath.moveTo(arcCenterX, arcCenterY)
        arcContourPath.arcTo(outerOval, arcFanStartDeg, arcFanSweepDeg, false)
        arcContourPath.close()

        val totalRadial = (arcOuterR - arcInnerLimitR).coerceAtLeast(36f * density)
        val ringGap = (totalRadial * 0.16f * arcRowSpacingScale).coerceIn(0f, totalRadial * 0.18f)
        val bandThickness = ((totalRadial - ringGap * 3f) / 4f).coerceAtLeast(6f * density)
        val angularGap = 2.4f * keySpacingScale

        getRows().forEachIndexed { rowIndex, rowKeys ->
            val outerR = arcOuterR - rowIndex * (bandThickness + ringGap)
            val innerR = (outerR - bandThickness).coerceAtLeast(arcInnerLimitR * 0.90f)
            val midR = (outerR + innerR) / 2f

            val inset = when (rowIndex) { 0 -> 0f; 1 -> 1.5f; 2 -> 1.0f; else -> 2.5f }
            val startDeg = arcFanStartDeg + inset
            val rowSweep = (arcFanSweepDeg - inset * 2f).coerceAtLeast(24f)
            val usableSweep = (rowSweep - angularGap * (rowKeys.size - 1).coerceAtLeast(0)).coerceAtLeast(18f)
            val totalWeight = rowKeys.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(1f)

            var curDeg = startDeg
            for (k in rowKeys) {
                val keySweep = (k.weight / totalWeight) * usableSweep
                val midDeg = curDeg + keySweep / 2f
                val rad = Math.toRadians(midDeg.toDouble())
                val cx = arcCenterX + (midR * cos(rad)).toFloat()
                val cy = arcCenterY + (midR * sin(rad)).toFloat()

                var textRot = ((midDeg + 90f) % 360f + 360f) % 360f
                if (textRot in 90.01f..269.99f) textRot = (textRot + 180f) % 360f

                arcSectors.add(ArcSector(k, innerR, outerR, curDeg, keySweep, cx, cy, textRot))
                if (k.code.length == 1 && k.code[0] in 'a'..'z') {
                    letterCenters[k.code[0]] = PointF(cx, cy)
                }
                curDeg += keySweep + angularGap
            }
        }
    }

    // =========================================================================
    // DISEGNO E ANIMAZIONE INCOGNITO
    // =========================================================================

    private fun drawUnderShadow(canvas: Canvas, cx: Float, cy: Float, baseRadius: Float, density: Float) {
        if (shadowStrength <= 0.02f) return
        val r = (baseRadius * 0.72f + shadowBlurDp * density * 1.15f).coerceAtLeast(8f * density)
        val a1 = (shadowStrength * 225).toInt().coerceIn(0, 255)
        val a2 = (shadowStrength * 135).toInt().coerceIn(0, 255)
        val offY = 2.2f * density * shadowStrength

        spotShadowPaint.shader = RadialGradient(
            cx, cy + offY, r,
            intArrayOf(Color.argb(a1, 0, 0, 0), Color.argb(a2, 0, 0, 0), Color.TRANSPARENT),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy + offY, r, spotShadowPaint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val density = resources.displayMetrics.density

        textOutlinePaint.color = Color.argb((shadowStrength * 245).toInt().coerceIn(0, 255), 0, 0, 0)
        textOutlinePaint.strokeWidth = (3.2f * density * shadowStrength).coerceAtLeast(0.5f)

        if (arcSectors.isEmpty()) rebuildArcLayout()
        drawArc(canvas, density)

        if (gestureMode == GestureMode.CHAR_POPUP && charPopupOptions.isNotEmpty()) {
            drawCharPopup(canvas, density)
        }

        if (isIncognitoMode) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawIncognitoAnimation(canvas: Canvas, density: Float) {
        if (!isIncognitoMode || arcOuterR <= 10f) return
        val now = SystemClock.uptimeMillis()

        // 1. Onda radar concentrica continua dal centro verso l'esterno dell'arco
        val cycleMs = 2200f
        val p1 = ((now % cycleMs.toLong()) / cycleMs)
        val p2 = (p1 + 0.5f) % 1.0f

        val minWaveR = (arcGripR * 0.42f).coerceAtLeast(10f * density)
        val spanR = (arcOuterR - minWaveR).coerceAtLeast(20f * density)

        for (phase in floatArrayOf(p1, p2)) {
            val waveR = minWaveR + spanR * phase
            val fadeAlpha = ((1f - phase) * 95f).toInt().coerceIn(0, 120)
            if (fadeAlpha > 2) {
                incognitoWavePaint.color = Color.argb(fadeAlpha, 255, 255, 255)
                incognitoWavePaint.strokeWidth = (2.4f + (1f - phase) * 2.2f) * density
                val oval = RectF(arcCenterX - waveR, arcCenterY - waveR, arcCenterX + waveR, arcCenterY + waveR)
                canvas.drawArc(oval, arcFanStartDeg, arcFanSweepDeg, false, incognitoWavePaint)
            }
        }

        // 2. Impulso luminoso extra nei primi 900ms all'attivazione
        val burstElapsed = now - incognitoBurstStartMs
        if (burstElapsed in 0..900L) {
            val bp = burstElapsed / 900f
            val burstR = minWaveR + spanR * bp
            val burstAlpha = ((1f - bp) * 180f).toInt().coerceIn(0, 200)
            incognitoWavePaint.color = Color.argb(burstAlpha, 255, 255, 255)
            incognitoWavePaint.strokeWidth = 4.5f * density
            val oval = RectF(arcCenterX - burstR, arcCenterY - burstR, arcCenterX + burstR, arcCenterY + burstR)
            canvas.drawArc(oval, arcFanStartDeg, arcFanSweepDeg, false, incognitoWavePaint)
        }

        // 3. Bagliore pulsante nella zona dell'impugnatura + micro-icona Incognito (occhiali)
        val pulse = 0.5f + 0.5f * sin((now / 360.0)).toFloat()
        val glowR = (arcInnerLimitR * 0.78f).coerceAtLeast(22f * density)
        incognitoFillWavePaint.color = Color.argb((22 + pulse * 34).toInt(), 255, 255, 255)
        canvas.save()
        canvas.clipPath(arcContourPath)
        canvas.drawCircle(arcCenterX, arcCenterY, glowR, incognitoFillWavePaint)
        canvas.restore()

        val bisectorRad = Math.toRadians((arcFanStartDeg + arcFanSweepDeg / 2f).toDouble())
        val iconR = (arcGripR * 0.44f).coerceAtLeast(14f * density)
        val ix = arcCenterX + (iconR * cos(bisectorRad)).toFloat()
        val iy = arcCenterY + (iconR * sin(bisectorRad)).toFloat()

        // Icona minimal occhiali incognito
        val rLens = 3.2f * density
        val gap = 4.4f * density
        iconWhitePaint.style = Paint.Style.STROKE
        iconWhitePaint.strokeWidth = 1.5f * density
        canvas.drawCircle(ix - gap, iy, rLens, iconWhitePaint)
        canvas.drawCircle(ix + gap, iy, rLens, iconWhitePaint)
        canvas.drawLine(ix - gap + rLens, iy, ix + gap - rLens, iy, iconWhitePaint)
    }

    private fun drawCharPopup(canvas: Canvas, density: Float) {
        val r = 12f * density
        popupBorderPaint.strokeWidth = 1.2f * density
        canvas.drawRoundRect(charPopupBoxRect, r, r, popupBgPaint)
        canvas.drawRoundRect(charPopupBoxRect, r, r, popupBorderPaint)

        popupTextPaint.textSize = 18f * density
        charPopupItemRects.forEachIndexed { idx, rect ->
            val selected = idx == charPopupSelectedIdx
            if (selected) {
                canvas.drawRoundRect(rect, 8f * density, 8f * density, popupSelPaint)
            }
            popupTextPaint.color = if (selected) Color.BLACK else Color.WHITE
            val ty = rect.centerY() - (popupTextPaint.descent() + popupTextPaint.ascent()) / 2f
            canvas.drawText(charPopupOptions[idx], rect.centerX(), ty, popupTextPaint)
        }
    }

    private fun drawArc(canvas: Canvas, density: Float) {
        val alpha = (bgOpacity * 255).toInt().coerceIn(0, 255)
        if (alpha > 0 && !arcContourPath.isEmpty) {
            bgPaint.pathEffect = null
            bgPaint.color = Color.argb(alpha, 0, 0, 0)
            canvas.drawPath(arcContourPath, bgPaint)
        }

        // Animazione onda Incognito sotto i tasti
        if (isIncognitoMode) {
            drawIncognitoAnimation(canvas, density)
        }

        val unit = (((arcOuterR - arcInnerLimitR) / 4f) * letterSizeScale).coerceAtLeast(10f * density)
        subTextPaint.textSize = (unit * 0.24f).coerceAtLeast(7f * density)

        for (s in arcSectors) {
            val spotR = if (s.key.code == "SPACE") unit * 0.85f else unit * 0.54f
            drawUnderShadow(canvas, s.cx, s.cy, spotR, density)
            if (activeKey == s.key) canvas.drawCircle(s.cx, s.cy, spotR * 0.82f, highlightPaint)

            canvas.save()
            canvas.translate(s.cx, s.cy)
            canvas.rotate(s.textAngleDeg)
            drawKeyContent(canvas, s.key, RectF(-unit * 0.45f, -unit * 0.45f, unit * 0.45f, unit * 0.45f), unit, density)
            canvas.restore()
        }

        // Impugnatura curva minimal per trascinare la tastiera concentrica su X e Y
        val gripArcRadius = (arcGripR * 0.72f).coerceAtLeast(18f * density)
        val bisectorDeg = arcFanStartDeg + arcFanSweepDeg / 2f
        val gripSweep = (arcFanSweepDeg * 0.42f).coerceIn(18f, 36f)
        val gripOval = RectF(
            arcCenterX - gripArcRadius,
            arcCenterY - gripArcRadius,
            arcCenterX + gripArcRadius,
            arcCenterY + gripArcRadius
        )
        val gripRad = Math.toRadians(bisectorDeg.toDouble())
        val gx = arcCenterX + (gripArcRadius * cos(gripRad)).toFloat()
        val gy = arcCenterY + (gripArcRadius * sin(gripRad)).toFloat()
        drawUnderShadow(canvas, gx, gy, 14f * density, density)

        gripArcPaint.strokeWidth = (if (gestureMode == GestureMode.DRAG) 5.5f else 4.0f) * density
        canvas.drawArc(gripOval, bisectorDeg - gripSweep / 2f, gripSweep, false, gripArcPaint)

        if (gestureMode == GestureMode.SWIPE && swipePoints.size > 1) {
            canvas.drawPath(swipeDrawPath, swipeTrailPaint)
        }
    }

    private fun drawKeyContent(canvas: Canvas, key: KeyDef, rect: RectF, unit: Float, density: Float) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val r = minOf(rect.width(), rect.height())

        when {
            key.isActionKey -> {
                val cr = r * 0.35f
                canvas.drawCircle(cx, cy, cr, actionCirclePaint)
                iconBlackPaint.strokeWidth = 2.0f * density
                val s = cr * 0.48f
                when (actionType) {
                    "SEARCH" -> {
                        canvas.drawCircle(cx - s * 0.16f, cy - s * 0.16f, s * 0.68f, iconBlackPaint)
                        canvas.drawLine(cx + s * 0.32f, cy + s * 0.32f, cx + s * 0.85f, cy + s * 0.85f, iconBlackPaint)
                    }
                    "SEND" -> {
                        val p = Path().apply {
                            moveTo(cx - s * 0.65f, cy - s * 0.55f); lineTo(cx + s * 0.75f, cy)
                            lineTo(cx - s * 0.65f, cy + s * 0.55f); lineTo(cx - s * 0.30f, cy); close()
                        }
                        canvas.drawPath(p, iconBlackPaint)
                    }
                    "DONE" -> {
                        val p = Path().apply {
                            moveTo(cx - s * 0.65f, cy + s * 0.05f); lineTo(cx - s * 0.15f, cy + s * 0.55f); lineTo(cx + s * 0.70f, cy - s * 0.45f)
                        }
                        canvas.drawPath(p, iconBlackPaint)
                    }
                    "NEXT" -> {
                        canvas.drawLine(cx - s * 0.65f, cy, cx + s * 0.45f, cy, iconBlackPaint)
                        canvas.drawLine(cx + s * 0.05f, cy - s * 0.45f, cx + s * 0.50f, cy, iconBlackPaint)
                        canvas.drawLine(cx + s * 0.05f, cy + s * 0.45f, cx + s * 0.50f, cy, iconBlackPaint)
                        canvas.drawLine(cx + s * 0.70f, cy - s * 0.45f, cx + s * 0.70f, cy + s * 0.45f, iconBlackPaint)
                    }
                    else -> {
                        val p = Path().apply {
                            moveTo(cx + s * 0.55f, cy - s * 0.45f); lineTo(cx + s * 0.55f, cy + s * 0.20f); lineTo(cx - s * 0.55f, cy + s * 0.20f)
                        }
                        canvas.drawPath(p, iconBlackPaint)
                        canvas.drawLine(cx - s * 0.20f, cy - s * 0.15f, cx - s * 0.60f, cy + s * 0.20f, iconBlackPaint)
                        canvas.drawLine(cx - s * 0.20f, cy + s * 0.55f, cx - s * 0.60f, cy + s * 0.20f, iconBlackPaint)
                    }
                }
            }
            key.code == "SHIFT" -> {
                val s = r * 0.24f
                val arrow = Path().apply {
                    moveTo(cx, cy - s); lineTo(cx + s * 0.95f, cy + s * 0.05f); lineTo(cx + s * 0.42f, cy + s * 0.05f)
                    lineTo(cx + s * 0.42f, cy + s * 0.85f); lineTo(cx - s * 0.42f, cy + s * 0.85f)
                    lineTo(cx - s * 0.42f, cy + s * 0.05f); lineTo(cx - s * 0.95f, cy + s * 0.05f); close()
                }
                iconWhitePaint.strokeWidth = 1.8f * density
                iconWhitePaint.style = if (isShifted || isCapsLock) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
                canvas.drawPath(arrow, iconWhitePaint)
                if (isCapsLock) canvas.drawLine(cx - s * 0.55f, cy + s * 1.18f, cx + s * 0.55f, cy + s * 1.18f, iconWhitePaint)
            }
            key.code == "DEL" -> {
                val s = r * 0.25f
                iconWhitePaint.style = Paint.Style.STROKE
                iconWhitePaint.strokeWidth = 1.8f * density
                val box = Path().apply {
                    moveTo(cx - s * 1.1f, cy); lineTo(cx - s * 0.45f, cy - s * 0.72f); lineTo(cx + s * 0.95f, cy - s * 0.72f)
                    lineTo(cx + s * 0.95f, cy + s * 0.72f); lineTo(cx - s * 0.45f, cy + s * 0.72f); close()
                }
                canvas.drawPath(box, iconWhitePaint)
                val xo = s * 0.24f
                val xs = s * 0.34f
                canvas.drawLine(cx + xo - xs, cy - xs, cx + xo + xs, cy + xs, iconWhitePaint)
                canvas.drawLine(cx + xo + xs, cy - xs, cx + xo - xs, cy + xs, iconWhitePaint)
            }
            key.code == "GLOBE" -> {
                val gr = r * 0.24f
                iconWhitePaint.style = Paint.Style.STROKE
                iconWhitePaint.strokeWidth = 1.6f * density
                canvas.drawCircle(cx, cy, gr, iconWhitePaint)
                canvas.drawLine(cx - gr, cy, cx + gr, cy, iconWhitePaint)
                canvas.drawOval(cx - gr * 0.46f, cy - gr, cx + gr * 0.46f, cy + gr, iconWhitePaint)
            }
            else -> {
                val label = if (key.code == "SPACE" && gestureMode == GestureMode.SPACE_CURSOR) "◄ ▲ ▼ ►" else key.label
                textPaint.textSize = when {
                    key.code == "SPACE" -> (unit * 0.34f).coerceAtLeast(9.5f * density)
                    label.length >= 3 -> (unit * 0.32f).coerceAtLeast(9f * density)
                    else -> (unit * 0.48f).coerceAtLeast(12f * density)
                }
                val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
                if (shadowStrength > 0.05f) {
                    textOutlinePaint.textSize = textPaint.textSize
                    canvas.drawText(label, cx, textY, textOutlinePaint)
                }
                canvas.drawText(label, cx, textY, textPaint)

                if (key.subLabel.isNotEmpty()) {
                    val sx = unit * 0.28f
                    val sy = -unit * 0.16f
                    if (shadowStrength > 0.05f) {
                        textOutlinePaint.textSize = subTextPaint.textSize
                        canvas.drawText(key.subLabel, sx, sy, textOutlinePaint)
                    }
                    canvas.drawText(key.subLabel, sx, sy, subTextPaint)
                }
            }
        }
    }

    // =========================================================================
    // DECODIFICA SWIPE
    // =========================================================================

    private fun decodeSwipe(): List<String> {
        if (swipePoints.size < 2 || letterCenters.size < 2) return emptyList()

        val keyStep = letterCenters.values.mapNotNull { p ->
            letterCenters.values.filter { it !== p }.minOfOrNull { hypot(p.x - it.x, p.y - it.y) }
        }.average().toFloat().coerceAtLeast(14f)

        val step = (keyStep * 0.25f).coerceAtLeast(4f)
        val dense = mutableListOf(swipePoints.first())
        for (i in 1 until swipePoints.size) {
            val a = swipePoints[i - 1]
            val b = swipePoints[i]
            val d = hypot(b.x - a.x, b.y - a.y)
            val n = (d / step).toInt().coerceAtLeast(1)
            for (s in 1..n) {
                val t = s.toFloat() / n
                dense.add(PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
            }
        }
        return swipeDecoder.decode(dense, letterCenters, keyStep)
    }

    // =========================================================================
    // TOUCH, TRASCINAMENTO X/Y, SWIPE E CURSORE 2D
    // =========================================================================

    private fun isPointInArcSector(x: Float, y: Float, maxR: Float): Boolean {
        val dist = hypot(x - arcCenterX, y - arcCenterY)
        if (dist > maxR) return false
        var deg = Math.toDegrees(atan2((y - arcCenterY).toDouble(), (x - arcCenterX).toDouble())).toFloat()
        if (deg < 0f) deg += 360f
        return isAngleInSweep(deg, arcFanStartDeg - 4f, arcFanSweepDeg + 8f)
    }

    private fun findArcSector(x: Float, y: Float): ArcSector? {
        if (arcSectors.isEmpty() || arcOuterR <= arcInnerLimitR * 0.7f) return null
        val dist = hypot(x - arcCenterX, y - arcCenterY)
        if (dist < arcInnerLimitR * 0.86f || dist > arcOuterR * 1.04f) return null

        var deg = Math.toDegrees(atan2((y - arcCenterY).toDouble(), (x - arcCenterX).toDouble())).toFloat()
        if (deg < 0f) deg += 360f

        arcSectors.firstOrNull { s ->
            val diff = ((deg - s.startDeg) % 360f + 360f) % 360f
            dist in s.innerR..s.outerR && diff <= s.sweepDeg
        }?.let { return it }

        val snap = ((arcOuterR - arcInnerLimitR) / 3f).coerceAtLeast(10f)
        return arcSectors.minByOrNull { hypot(x - it.cx, y - it.cy) }
            ?.takeIf { hypot(x - it.cx, y - it.cy) < snap }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        val lx = event.x
        val ly = event.y

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val inDragGrip = isPointInArcSector(lx, ly, arcInnerLimitR * 0.90f)
            if (!inDragGrip && findArcSector(lx, ly) == null) return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX; downRawY = event.rawY
                lastRawX = event.rawX; lastRawY = event.rawY
                downLocalX = lx; downLocalY = ly
                charPopupOptions = emptyList()
                charPopupItemRects.clear()
                swipePoints.clear()
                swipeDrawPath.reset()

                if (isPointInArcSector(lx, ly, arcInnerLimitR * 0.90f)) {
                    gestureMode = GestureMode.DRAG
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                    return true
                }

                val hit = findArcSector(lx, ly)?.also {
                    activeKeyCenterX = it.cx
                    activeKeyCenterY = it.cy
                }?.key

                if (hit != null) {
                    activeKey = hit
                    gestureMode = GestureMode.KEY_TAP
                    lastCursorX = event.rawX
                    lastCursorY = event.rawY
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

                    if (hit.code == "DEL") {
                        listener.onDeleteChar()
                        handler.postDelayed(repeatDelete, 360L)
                    } else {
                        handler.postDelayed(longPressRunnable, 280L)
                    }

                    if (!isSymbolsMode && hit.code.length == 1 && hit.code[0] in 'a'..'z') {
                        swipePoints.add(PointF(event.x, event.y))
                        swipeDrawPath.moveTo(lx, ly)
                    }
                    invalidate()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                lastRawX = event.rawX; lastRawY = event.rawY
                when (gestureMode) {
                    GestureMode.DRAG -> {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (dx != 0f || dy != 0f) {
                            listener.onDragFloatingKeyboard(dx, dy)
                            downRawX = event.rawX
                            downRawY = event.rawY
                        }
                    }
                    GestureMode.CHAR_POPUP -> {
                        updateCharPopupSelection(event.x)
                    }
                    GestureMode.KEY_TAP -> {
                        val moved = hypot(event.rawX - downRawX, event.rawY - downRawY)
                        if (activeKey?.code == "SPACE" && moved > 10f * density) {
                            handler.removeCallbacks(longPressRunnable)
                            gestureMode = GestureMode.SPACE_CURSOR
                            lastCursorX = event.rawX; lastCursorY = event.rawY
                            invalidate()
                        } else if (!isSymbolsMode && activeKey != null && !activeKey!!.isSpecial && moved > 15f * density) {
                            handler.removeCallbacks(longPressRunnable)
                            gestureMode = GestureMode.SWIPE
                            if (swipePoints.isEmpty()) {
                                swipePoints.add(PointF(activeKeyCenterX, activeKeyCenterY))
                                swipeDrawPath.moveTo(downLocalX, downLocalY)
                            }
                            swipePoints.add(PointF(event.x, event.y))
                            swipeDrawPath.lineTo(lx, ly)
                            invalidate()
                        }
                    }
                    GestureMode.SPACE_CURSOR -> {
                        val stepX = 14f * density
                        val stepY = 18f * density
                        val sx = ((event.rawX - lastCursorX) / stepX).toInt()
                        val sy = ((event.rawY - lastCursorY) / stepY).toInt()
                        if (sx != 0 || sy != 0) {
                            lastCursorX += sx * stepX
                            lastCursorY += sy * stepY
                            performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
                            listener.onMoveTextCursor(sx, sy)
                        }
                    }
                    GestureMode.SWIPE -> {
                        swipePoints.add(PointF(event.x, event.y))
                        swipeDrawPath.lineTo(lx, ly)
                        invalidate()
                    }
                    GestureMode.NONE -> {}
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                handler.removeCallbacks(repeatDelete)

                when (gestureMode) {
                    GestureMode.CHAR_POPUP -> {
                        val chosen = charPopupOptions.getOrNull(charPopupSelectedIdx)
                        if (!chosen.isNullOrEmpty()) {
                            listener.onCommitText(chosen)
                            if (isShifted && !isCapsLock) isShifted = false
                        }
                    }
                    GestureMode.SWIPE -> {
                        try {
                            val candidates = decodeSwipe()
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
                    }
                    GestureMode.KEY_TAP -> {
                        activeKey?.let { onSingleTap(it) }
                    }
                    else -> {}
                }

                activeKey = null
                gestureMode = GestureMode.NONE
                charPopupOptions = emptyList()
                charPopupItemRects.clear()
                swipePoints.clear()
                swipeDrawPath.reset()
                rebuildArcLayout()
                invalidate()
            }
        }
        return true
    }

    private fun onSingleTap(key: KeyDef) {
        when (key.code) {
            "DEL" -> {}
            "SHIFT" -> {
                val now = System.currentTimeMillis()
                if (now - lastShiftTapTime < 320L) {
                    isCapsLock = !isCapsLock
                    isShifted = isCapsLock
                } else {
                    isCapsLock = false
                    isShifted = !isShifted
                }
                lastShiftTapTime = now
            }
            "SYM" -> isSymbolsMode = true
            "ABC", "SYM_ALT" -> isSymbolsMode = false
            "GLOBE" -> listener.onSwitchIme()
            "SPACE" -> listener.onCommitText(" ")
            "ENTER" -> listener.onEnterPressed()
            else -> {
                listener.onCommitText(key.label)
                if (isShifted && !isCapsLock) isShifted = false
            }
        }
    }
}
