package com.tastiera45.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Region
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot

class Tastiera45Service : InputMethodService(), AngledKeyboardView.KeyboardActionListener {

    companion object {
        val BUILTIN_IDS = listOf(
            "TOGGLE_KB",
            "SWITCH_STYLE",
            "VOICE",
            "MOUSE",
            "CLIPBOARD",
            "TRANSLATE",
            "INCOGNITO",
            "SETTINGS"
        )
    }

    private data class StoredShortcut(
        val id: String,
        val type: String,
        val label: String,
        val payload: String
    )

    private data class MenuEntry(
        val id: String,
        val displayTitle: String,
        val enabled: Boolean
    )

    private inner class FullScreenImeFrame(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(320)
            val fullHeightSpec = MeasureSpec.makeMeasureSpec(screenH, MeasureSpec.EXACTLY)
            super.onMeasure(widthMeasureSpec, fullHeightSpec)
        }
    }

    private lateinit var rootFrame: FrameLayout
    private lateinit var keyboardView: AngledKeyboardView
    private lateinit var cornerOrbView: CornerOrbView
    private lateinit var popupColumnWrapper: LinearLayout
    private lateinit var extraPanelContainer: LinearLayout
    private lateinit var swipeBarContainer: LinearLayout
    private lateinit var clipboardPanel: LinearLayout
    private lateinit var clipboardListLayout: LinearLayout
    private lateinit var remoteCursorOverlay: RemoteCursorOverlay

    private val extraOptionViews = mutableListOf<Pair<String, TextView>>()
    private var hoveredExtraId: String? = null
    private var isEditingExtraMenu = false

    private var isKeyboardVisible = false
    private var isAutoIncognito = false
    private var isManualIncognito = false
    private val clipboardHistory = mutableListOf<String>()
    private var clipboardManager: ClipboardManager? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningVoice = false
    private var cursorPadRect = Rect()

    private var orbXPct = 0.96f
    private var orbBottomOffsetPx = 0
    private var kbXPct = 1.0f
    private var kbBottomOffsetPx = 0

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        syncFromSystemClipboard()
    }

    private fun isIncognitoActive(): Boolean = isAutoIncognito || isManualIncognito

    override fun onEvaluateFullscreenMode(): Boolean = false

    private fun safeFloat(prefs: SharedPreferences, key: String, def: Float): Float =
        try { prefs.getFloat(key, def) } catch (_: Throwable) { def }

    private fun safeInt(prefs: SharedPreferences, key: String, def: Int): Int =
        try { prefs.getInt(key, def) } catch (_: Throwable) { def }

    private fun safeBool(prefs: SharedPreferences, key: String, def: Boolean): Boolean =
        try { prefs.getBoolean(key, def) } catch (_: Throwable) { def }

    private fun safeString(prefs: SharedPreferences, key: String, def: String?): String? =
        try { prefs.getString(key, def) } catch (_: Throwable) { def }

    override fun onCreate() {
        super.onCreate()
        loadPersistedClipboard()
        try {
            clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboardManager?.addPrimaryClipChangedListener(clipListener)
            syncFromSystemClipboard()
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        try {
            clipboardManager?.removePrimaryClipChangedListener(clipListener)
        } catch (_: Throwable) {
        }
        try {
            speechRecognizer?.destroy()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    private fun loadPersistedClipboard() {
        val prefs = getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)
        val raw = safeString(prefs, "saved_clipboard", "") ?: ""
        clipboardHistory.clear()
        if (raw.isNotEmpty()) {
            raw.split("\u001E").filter { it.isNotEmpty() }.forEach { clipboardHistory.add(it) }
        }
    }

    private fun savePersistedClipboard() {
        try {
            val encoded = clipboardHistory.take(30).joinToString("\u001E")
            getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("saved_clipboard", encoded)
                .apply()
        } catch (_: Throwable) {
        }
    }

    private fun syncFromSystemClipboard() {
        if (isIncognitoActive()) return
        try {
            val clip = clipboardManager?.primaryClip ?: return
            if (clip.itemCount <= 0) return

            var added = false
            for (i in (clip.itemCount - 1) downTo 0) {
                val item = clip.getItemAt(i) ?: continue
                val text = try {
                    item.text?.toString()?.trim()
                        ?: item.coerceToText(this)?.toString()?.trim()
                } catch (_: Throwable) {
                    null
                }
                if (!text.isNullOrEmpty()) {
                    if (clipboardHistory.firstOrNull() != text) {
                        clipboardHistory.remove(text)
                        clipboardHistory.add(0, text)
                        added = true
                    }
                }
            }
            while (clipboardHistory.size > 30) {
                clipboardHistory.removeAt(clipboardHistory.lastIndex)
            }
            if (added) {
                savePersistedClipboard()
            }
        } catch (_: Throwable) {
        }
    }

    private fun loadAndApplyActiveStyleConfig(prefs: SharedPreferences) {
        val density = resources.displayMetrics.density
        val isArcMode = safeBool(prefs, "kb_arc_mode", true)
        val isArcLeft = safeBool(prefs, "arc_left_side", safeBool(prefs, "kb_arc_left", false))

        val prefix = if (isArcMode) "arc_" else "popup_"
        val defaultScale = if (isArcMode) 0.86f else 0.82f
        val defaultXPct = if (isArcMode) 1.0f else 0.5f

        val scale = safeFloat(prefs, "${prefix}scale", safeFloat(prefs, "kb_scale", defaultScale))
        val alpha = safeFloat(prefs, "${prefix}alpha", 0.0f)
        val tilt = safeFloat(prefs, "${prefix}tilt", safeFloat(prefs, "kb_tilt", 0f))
        val letterSize = safeFloat(prefs, "${prefix}letter_size", safeFloat(prefs, "kb_letter_size", 1.0f))
        val spacing = safeFloat(prefs, "${prefix}key_spacing", safeFloat(prefs, "kb_key_spacing", 0.25f))
        val shadowStrength = safeFloat(prefs, "${prefix}shadow_strength", safeFloat(prefs, "kb_shadow_strength", 0.92f))
        val shadowBlur = safeFloat(prefs, "${prefix}shadow_blur", safeFloat(prefs, "kb_shadow_blur", 7.0f))

        kbXPct = safeFloat(prefs, "${prefix}x_pct", safeFloat(prefs, "kb_x_pct", defaultXPct))
        kbBottomOffsetPx = safeInt(prefs, "${prefix}bottom_offset", safeInt(prefs, "kb_bottom_offset", (36 * density).toInt()))

        keyboardView.setArcLayoutMode(isArcMode)
        keyboardView.setArcLeftSide(isArcLeft)
        keyboardView.setScaleFactor(scale)
        keyboardView.setOpacity(alpha)
        keyboardView.setTiltAngle(tilt)
        keyboardView.setLetterSizeScale(letterSize)
        keyboardView.setKeySpacingScale(spacing)
        keyboardView.setShadowConfig(shadowStrength, shadowBlur)
    }

    override fun onCreateInputView(): View {
        val density = resources.displayMetrics.density
        val prefs = getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)
        val orbOnlyStart = safeBool(prefs, "orb_only_start", true)

        orbXPct = safeFloat(prefs, "orb_x_pct", 0.96f)
        orbBottomOffsetPx = safeInt(prefs, "orb_bottom_offset", (36 * density).toInt())
        isKeyboardVisible = !orbOnlyStart

        rootFrame = FullScreenImeFrame(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                val newW = right - left
                val newH = bottom - top
                val oldW = oldRight - oldLeft
                val oldH = oldBottom - oldTop
                if (newW != oldW || newH != oldH) {
                    post { applyIndependentPositions() }
                }
            }
        }

        // 1. Tastiera (Arco Concentrico o Pop-up Scolpito)
        keyboardView = AngledKeyboardView(this, this)
        loadAndApplyActiveStyleConfig(prefs)
        keyboardView.visibility = if (isKeyboardVisible) View.VISIBLE else View.GONE

        val kbParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START
        )
        rootFrame.addView(keyboardView, kbParams)

        // 2. Colonna popup per Menu ✦ e Appunti
        popupColumnWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                post { applyIndependentPositions() }
            }
        }

        extraPanelContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F208080A"))
                cornerRadius = 14f * density
                setStroke((1f * density).toInt(), Color.parseColor("#383840"))
            }
            setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
        }
        popupColumnWrapper.addView(
            extraPanelContainer,
            LinearLayout.LayoutParams((235 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (6 * density).toInt()
            }
        )

        clipboardPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F208080A"))
                cornerRadius = 14f * density
                setStroke((1f * density).toInt(), Color.parseColor("#383840"))
            }
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
        }
        val scroll = ScrollView(this)
        clipboardListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(clipboardListLayout)
        clipboardPanel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (145 * density).toInt()))
        popupColumnWrapper.addView(
            clipboardPanel,
            LinearLayout.LayoutParams((235 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (6 * density).toInt()
            }
        )

        val popupParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START
        )
        rootFrame.addView(popupColumnWrapper, popupParams)

        // 3. Barra Suggerimenti Swipe & Dettatura: ancorata SEMPRE direttamente sopra il pallino magico (✦)
        swipeBarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE08080A"))
                cornerRadius = 12f * density
                setStroke((1f * density).toInt(), Color.parseColor("#45454F"))
            }
            setPadding((8 * density).toInt(), (5 * density).toInt(), (8 * density).toInt(), (5 * density).toInt())
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                post { applyIndependentPositions() }
            }
        }
        val swipeParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START
        )
        rootFrame.addView(swipeBarContainer, swipeParams)

        // 4. Tasto Magico (✦)
        val orbSizePx = (44 * density).toInt()
        cornerOrbView = CornerOrbView(this).apply {
            setIncognitoVisual(isIncognitoActive())
        }
        val orbParams = FrameLayout.LayoutParams(
            orbSizePx,
            orbSizePx,
            Gravity.BOTTOM or Gravity.START
        )
        rootFrame.addView(cornerOrbView, orbParams)

        // 5. Overlay Remote Cursor (Mouse)
        remoteCursorOverlay = RemoteCursorOverlay(
            context = this,
            onClose = { hideRemoteCursor() },
            onRequestOpenAccessibility = {
                Toast.makeText(this, "Attiva 'Tastiera 45° - Remote Cursor' in Accessibilità", Toast.LENGTH_LONG).show()
                try {
                    val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (_: Throwable) {
                }
            },
            onTouchableRegionChanged = { rect ->
                cursorPadRect = rect
                if (::rootFrame.isInitialized) rootFrame.requestLayout()
            }
        ).apply {
            visibility = View.GONE
        }
        rootFrame.addView(
            remoteCursorOverlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        return rootFrame
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val prefs = getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)
        val orbOnlyStart = safeBool(prefs, "orb_only_start", true)

        orbXPct = safeFloat(prefs, "orb_x_pct", orbXPct)
        orbBottomOffsetPx = safeInt(prefs, "orb_bottom_offset", orbBottomOffsetPx)
        isKeyboardVisible = !orbOnlyStart
        isEditingExtraMenu = false

        val imeOptions = info?.imeOptions ?: 0
        val inputType = info?.inputType ?: 0
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        val noLearning = (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        val isPassword = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD

        isAutoIncognito = noLearning || isPassword
        syncFromSystemClipboard()

        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val actionName = when (action) {
            EditorInfo.IME_ACTION_SEARCH -> "SEARCH"
            EditorInfo.IME_ACTION_SEND -> "SEND"
            EditorInfo.IME_ACTION_NEXT -> "NEXT"
            EditorInfo.IME_ACTION_DONE -> "DONE"
            else -> "RETURN"
        }

        if (::keyboardView.isInitialized) {
            loadAndApplyActiveStyleConfig(prefs)
            keyboardView.setActionType(actionName)
            keyboardView.visibility = if (isKeyboardVisible) View.VISIBLE else View.GONE
        }
        if (::cornerOrbView.isInitialized) {
            cornerOrbView.setIncognitoVisual(isIncognitoActive())
        }
        if (::extraPanelContainer.isInitialized) {
            extraPanelContainer.visibility = View.GONE
        }
        if (::swipeBarContainer.isInitialized && !isListeningVoice) {
            swipeBarContainer.visibility = View.GONE
        }
        if (::rootFrame.isInitialized) {
            rootFrame.requestLayout()
            rootFrame.post { applyIndependentPositions() }
        }
    }

    private fun applyIndependentPositions() {
        if (!::rootFrame.isInitialized || rootFrame.width <= 0 || rootFrame.height <= 0) return
        val density = resources.displayMetrics.density
        val screenW = rootFrame.width
        val screenH = rootFrame.height
        val marginEdge = (6 * density).toInt()
        var changed = false

        // 1. Posizione autonoma Tasto Magico (✦)
        val orbW = cornerOrbView.measuredWidth.takeIf { it > 0 } ?: (44 * density).toInt()
        val orbH = cornerOrbView.measuredHeight.takeIf { it > 0 } ?: (44 * density).toInt()
        val maxOrbLeft = (screenW - orbW).coerceAtLeast(0)
        val maxOrbBottom = (screenH - orbH).coerceAtLeast(0)
        val orbLeft = (maxOrbLeft * orbXPct.coerceIn(0f, 1f)).toInt()
        val orbBottom = orbBottomOffsetPx.coerceIn(0, maxOrbBottom)

        (cornerOrbView.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            if (lp.leftMargin != orbLeft || lp.bottomMargin != orbBottom) {
                lp.leftMargin = orbLeft
                lp.bottomMargin = orbBottom
                cornerOrbView.layoutParams = lp
                changed = true
            }
        }

        // 2. Posizione autonoma Tastiera su tutto lo schermo
        val kbW = keyboardView.measuredWidth.takeIf { it > 0 } ?: (screenW * 0.7f).toInt()
        val kbH = keyboardView.measuredHeight.takeIf { it > 0 } ?: (screenW * 0.4f).toInt()
        val maxKbLeft = (screenW - kbW).coerceAtLeast(0)
        val maxKbBottom = (screenH - kbH).coerceAtLeast(0)
        val kbLeft = (maxKbLeft * kbXPct.coerceIn(0f, 1f)).toInt()
        val kbBottom = kbBottomOffsetPx.coerceIn(0, maxKbBottom)

        (keyboardView.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            if (lp.leftMargin != kbLeft || lp.bottomMargin != kbBottom) {
                lp.leftMargin = kbLeft
                lp.bottomMargin = kbBottom
                keyboardView.layoutParams = lp
                changed = true
            }
        }

        // 3. Barra suggerimenti swipe: SEMPRE centrata direttamente sopra il pallino magico (✦)
        if (swipeBarContainer.visibility == View.VISIBLE) {
            val swipeW = swipeBarContainer.measuredWidth.takeIf { it > 0 } ?: (160 * density).toInt()
            val swipeH = swipeBarContainer.measuredHeight.takeIf { it > 0 } ?: (34 * density).toInt()
            val orbCenterX = orbLeft + orbW / 2
            val maxSwipeLeft = (screenW - swipeW - marginEdge).coerceAtLeast(marginEdge)
            val swipeLeft = (orbCenterX - swipeW / 2).coerceIn(marginEdge, maxSwipeLeft)

            val desiredAboveOrb = orbBottom + orbH + (8 * density).toInt()
            val swipeBottom = if (desiredAboveOrb + swipeH <= screenH - marginEdge) {
                desiredAboveOrb
            } else {
                (orbBottom - swipeH - (8 * density).toInt()).coerceIn(marginEdge, (screenH - swipeH - marginEdge).coerceAtLeast(marginEdge))
            }

            (swipeBarContainer.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (lp.leftMargin != swipeLeft || lp.bottomMargin != swipeBottom) {
                    lp.leftMargin = swipeLeft
                    lp.bottomMargin = swipeBottom
                    swipeBarContainer.layoutParams = lp
                    changed = true
                }
            }
        }

        // 4. Posizione menu funzioni extra e appunti ancorati vicino al Tasto Magico (✦)
        val popW = popupColumnWrapper.measuredWidth.takeIf { it > 0 } ?: (235 * density).toInt()
        val popH = popupColumnWrapper.measuredHeight.takeIf { it > 0 } ?: (240 * density).toInt()
        val popLeft = if (orbLeft + popW <= screenW - marginEdge) {
            orbLeft
        } else {
            (orbLeft + orbW - popW).coerceIn(marginEdge, (screenW - popW - marginEdge).coerceAtLeast(marginEdge))
        }
        val swipeOffset = if (swipeBarContainer.visibility == View.VISIBLE) {
            (swipeBarContainer.measuredHeight.takeIf { it > 0 } ?: (34 * density).toInt()) + (8 * density).toInt()
        } else {
            0
        }
        val aboveOrbBottom = orbBottom + orbH + (8 * density).toInt() + swipeOffset
        val popBottom = if (aboveOrbBottom + popH <= screenH - marginEdge) {
            aboveOrbBottom
        } else {
            (orbBottom - popH - (8 * density).toInt()).coerceIn(marginEdge, (screenH - popH - marginEdge).coerceAtLeast(marginEdge))
        }

        (popupColumnWrapper.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            if (lp.leftMargin != popLeft || lp.bottomMargin != popBottom) {
                lp.leftMargin = popLeft
                lp.bottomMargin = popBottom
                popupColumnWrapper.layoutParams = lp
                changed = true
            }
        }

        if (changed) {
            rootFrame.requestLayout()
        }
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (!::rootFrame.isInitialized || !::cornerOrbView.isInitialized) return

        val screenH = rootFrame.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        if (screenH <= 0) return

        outInsets.contentTopInsets = screenH
        outInsets.visibleTopInsets = screenH
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.setEmpty()

        // 1. Tasto Magico (✦)
        if (cornerOrbView.width > 0 && cornerOrbView.height > 0) {
            outInsets.touchableRegion.op(
                Rect(
                    cornerOrbView.left - 8,
                    cornerOrbView.top - 8,
                    cornerOrbView.right + 8,
                    cornerOrbView.bottom + 8
                ),
                Region.Op.UNION
            )
        }

        // 2. Sagoma tastiera (Arco o Pop-up Scolpito)
        if (keyboardView.visibility == View.VISIBLE && keyboardView.width > 0 && keyboardView.height > 0) {
            keyboardView.appendContourToRegion(outInsets.touchableRegion, keyboardView.left, keyboardView.top)
        }

        // 3. Barra Suggerimenti Swipe (direttamente su rootFrame sopra il pallino magico)
        if (swipeBarContainer.visibility == View.VISIBLE && swipeBarContainer.width > 0 && swipeBarContainer.height > 0) {
            outInsets.touchableRegion.op(
                Rect(
                    swipeBarContainer.left,
                    swipeBarContainer.top,
                    swipeBarContainer.right,
                    swipeBarContainer.bottom
                ),
                Region.Op.UNION
            )
        }

        // 4. Menu Funzioni Extra (incluso tastino Edit ✎ nell'angolo) e Appunti
        if (extraPanelContainer.visibility == View.VISIBLE && extraPanelContainer.width > 0 && extraPanelContainer.height > 0) {
            outInsets.touchableRegion.op(
                Rect(
                    popupColumnWrapper.left + extraPanelContainer.left,
                    popupColumnWrapper.top + extraPanelContainer.top,
                    popupColumnWrapper.left + extraPanelContainer.right,
                    popupColumnWrapper.top + extraPanelContainer.bottom
                ),
                Region.Op.UNION
            )
        }
        if (clipboardPanel.visibility == View.VISIBLE && clipboardPanel.width > 0 && clipboardPanel.height > 0) {
            outInsets.touchableRegion.op(
                Rect(
                    popupColumnWrapper.left + clipboardPanel.left,
                    popupColumnWrapper.top + clipboardPanel.top,
                    popupColumnWrapper.left + clipboardPanel.right,
                    popupColumnWrapper.top + clipboardPanel.bottom
                ),
                Region.Op.UNION
            )
        }

        // 5. Remote Cursor (Mouse)
        if (::remoteCursorOverlay.isInitialized && remoteCursorOverlay.visibility == View.VISIBLE && !cursorPadRect.isEmpty) {
            outInsets.touchableRegion.op(cursorPadRect, Region.Op.UNION)
        }
    }

    // --- Tasto Magico (✦) bianco e nero minimal ---

    private inner class CornerOrbView(context: Context) : View(context) {
        private var incognitoActive = false
        private var downRawX = 0f
        private var downRawY = 0f
        private var holdTriggered = false
        private var dragMoved = false
        private val orbHandler = Handler(Looper.getMainLooper())

        private val holdRunnable = Runnable {
            holdTriggered = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showExtraPanel()
        }

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EE08080A")
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#606068")
            style = Paint.Style.STROKE
            strokeWidth = 2.2f
        }
        private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }

        fun setIncognitoVisual(active: Boolean) {
            incognitoActive = active
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = (minOf(width, height) / 2f) - 2f
            if (r <= 0f) return
            borderPaint.color = if (incognitoActive) Color.WHITE else Color.parseColor("#606068")
            borderPaint.strokeWidth = if (incognitoActive) 3.2f else 2.0f
            canvas.drawCircle(cx, cy, r, bgPaint)
            canvas.drawCircle(cx, cy, r, borderPaint)

            symbolPaint.textSize = r * 0.85f
            symbolPaint.color = Color.WHITE
            val textY = cy - (symbolPaint.descent() + symbolPaint.ascent()) / 2f
            canvas.drawText("✦", cx, textY, symbolPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val density = resources.displayMetrics.density
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    holdTriggered = false
                    dragMoved = false
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (!isEditingExtraMenu) {
                        orbHandler.postDelayed(holdRunnable, 150L)
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isEditingExtraMenu) return true
                    val moved = hypot(event.rawX - downRawX, event.rawY - downRawY)
                    if (moved > 8f * density && !dragMoved) {
                        dragMoved = true
                        orbHandler.removeCallbacks(holdRunnable)
                        showExtraPanel()
                    }
                    if (dragMoved || holdTriggered) {
                        updateExtraPanelHover(event.rawX, event.rawY)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    orbHandler.removeCallbacks(holdRunnable)
                    if (!isEditingExtraMenu && (dragMoved || holdTriggered)) {
                        releaseExtraPanelAt(event.rawX, event.rawY)
                    } else {
                        toggleExtraPanel()
                    }
                    return true
                }
            }
            return true
        }
    }

    // =========================================================================
    // GESTIONE ORDINE, VISIBILITÀ E TASTINO EDIT (✎) NEL MENU ✦
    // =========================================================================

    private fun loadCustomShortcuts(prefs: SharedPreferences): List<StoredShortcut> {
        val rawV2 = safeString(prefs, "custom_shortcuts_v2", "") ?: ""
        if (rawV2.isNotBlank()) {
            return rawV2.split("\u001E").mapNotNull { entry ->
                val parts = entry.split("\u001F")
                if (parts.size == 4 && parts[2].isNotBlank()) {
                    StoredShortcut(parts[0], parts[1], parts[2], parts[3])
                } else null
            }
        }
        // Migrazione automatica eventuale formato precedente
        val rawOld = safeString(prefs, "custom_shortcuts", "") ?: ""
        if (rawOld.isBlank()) return emptyList()
        val migrated = rawOld.split("\u001E").mapIndexedNotNull { idx, entry ->
            val parts = entry.split("\u001F")
            when {
                parts.size == 3 && parts[1].isNotBlank() -> StoredShortcut("sc_m_$idx", parts[0], parts[1], parts[2])
                parts.size == 2 && parts[0].isNotBlank() -> StoredShortcut("sc_m_$idx", "TEXT", parts[0], parts[1])
                else -> null
            }
        }
        if (migrated.isNotEmpty()) {
            val encoded = migrated.joinToString("\u001E") { "${it.id}\u001F${it.type}\u001F${it.label}\u001F${it.payload}" }
            prefs.edit().putString("custom_shortcuts_v2", encoded).apply()
        }
        return migrated
    }

    private fun getResolvedMenuEntries(prefs: SharedPreferences): List<MenuEntry> {
        val shortcuts = loadCustomShortcuts(prefs)
        val shortcutMap = shortcuts.associateBy { it.id }

        val allValidIds = mutableListOf<String>()
        allValidIds.addAll(BUILTIN_IDS)
        allValidIds.addAll(shortcuts.map { it.id })

        val savedOrderRaw = safeString(prefs, "orb_menu_order", "") ?: ""
        val savedOrder = savedOrderRaw.split(",").filter { it.isNotBlank() }

        val orderedIds = mutableListOf<String>()
        for (id in savedOrder) {
            if (id in allValidIds && id !in orderedIds) {
                orderedIds.add(id)
            }
        }
        for (id in allValidIds) {
            if (id !in orderedIds) {
                orderedIds.add(id)
            }
        }

        val hiddenSet = (safeString(prefs, "orb_menu_hidden", "") ?: "")
            .split(",")
            .filter { it.isNotBlank() }
            .toSet()

        return orderedIds.map { id ->
            val label = when (id) {
                "TOGGLE_KB" -> if (isKeyboardVisible) "✕   Chiudi tastiera" else "⊞   Tastiera"
                "SWITCH_STYLE" -> if (keyboardView.isArcLayoutMode()) "◐   Stile: Pop-up Scolpito" else "◑   Stile: Arco Concentrico"
                "VOICE" -> if (isListeningVoice) "■   Ferma dettatura" else "◉   Dettatura"
                "MOUSE" -> if (remoteCursorOverlay.visibility == View.VISIBLE) "✕   Chiudi mouse" else "⌖   Mouse"
                "CLIPBOARD" -> "⎘   Appunti"
                "TRANSLATE" -> "文   Traduci"
                "INCOGNITO" -> if (isIncognitoActive()) "●   Incognito ON" else "◌   Incognito OFF"
                "SETTINGS" -> "⚙   Impostazioni"
                else -> {
                    val sc = shortcutMap[id]
                    val prefix = when (sc?.type) {
                        "SAMSUNG_ASSIST" -> "✦"
                        "INTENT_URI" -> "↗"
                        "APP" -> "▣"
                        else -> "›"
                    }
                    "$prefix   ${sc?.label ?: id}"
                }
            }
            MenuEntry(id = id, displayTitle = label, enabled = id !in hiddenSet)
        }
    }

    private fun saveMenuOrder(prefs: SharedPreferences, orderedIds: List<String>) {
        prefs.edit().putString("orb_menu_order", orderedIds.joinToString(",")).apply()
    }

    private fun toggleMenuItemVisibility(prefs: SharedPreferences, id: String) {
        val hiddenSet = (safeString(prefs, "orb_menu_hidden", "") ?: "")
            .split(",")
            .filter { it.isNotBlank() }
            .toMutableSet()

        if (id in hiddenSet) {
            hiddenSet.remove(id)
        } else {
            hiddenSet.add(id)
        }
        prefs.edit().putString("orb_menu_hidden", hiddenSet.joinToString(",")).apply()
    }

    private fun moveMenuItem(prefs: SharedPreferences, id: String, delta: Int) {
        val currentOrder = getResolvedMenuEntries(prefs).map { it.id }.toMutableList()
        val idx = currentOrder.indexOf(id)
        val targetIdx = idx + delta
        if (idx >= 0 && targetIdx in currentOrder.indices) {
            val item = currentOrder.removeAt(idx)
            currentOrder.add(targetIdx, item)
            saveMenuOrder(prefs, currentOrder)
        }
    }

    private fun rebuildExtraPanel() {
        val density = resources.displayMetrics.density
        val prefs = getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)
        extraPanelContainer.removeAllViews()
        extraOptionViews.clear()
        hoveredExtraId = null

        val allEntries = getResolvedMenuEntries(prefs)

        // Intestazione con tastino EDIT (✎ / Fine) nell'angolo in alto a destra
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * density).toInt(), (3 * density).toInt(), (4 * density).toInt(), (5 * density).toInt())
        }
        val headerTitle = TextView(this).apply {
            text = if (isEditingExtraMenu) "Modifica menu ✦" else "Menu ✦"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#8E8E93"))
        }
        val editCornerBtn = TextView(this).apply {
            text = if (isEditingExtraMenu) "✓ Fine" else "✎ Edit"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isEditingExtraMenu) Color.BLACK else Color.WHITE)
            background = GradientDrawable().apply {
                setColor(if (isEditingExtraMenu) Color.WHITE else Color.parseColor("#242429"))
                cornerRadius = 7f * density
            }
            setPadding((8 * density).toInt(), (3 * density).toInt(), (8 * density).toInt(), (3 * density).toInt())
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                isEditingExtraMenu = !isEditingExtraMenu
                rebuildExtraPanel()
                rootFrame.requestLayout()
                rootFrame.post { applyIndependentPositions() }
            }
        }
        headerRow.addView(headerTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(editCornerBtn)
        extraPanelContainer.addView(headerRow)

        if (isEditingExtraMenu) {
            // Modalità EDIT: tutti i tasti con attiva/disattiva (☑/☐) e frecce ordine (▲/▼)
            val scroll = ScrollView(this).apply {
                isVerticalScrollBarEnabled = true
            }
            val listCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

            for ((index, entry) in allEntries.withIndex()) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#141418"))
                        cornerRadius = 8f * density
                    }
                    setPadding((6 * density).toInt(), (5 * density).toInt(), (4 * density).toInt(), (5 * density).toInt())
                }

                val toggleBtn = TextView(this).apply {
                    text = if (entry.enabled) "☑" else "☐"
                    textSize = 15f
                    setTextColor(if (entry.enabled) Color.WHITE else Color.parseColor("#6E6E73"))
                    setPadding((4 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        toggleMenuItemVisibility(prefs, entry.id)
                        rebuildExtraPanel()
                    }
                }

                val labelTv = TextView(this).apply {
                    text = entry.displayTitle
                    textSize = 12f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(if (entry.enabled) Color.WHITE else Color.parseColor("#6E6E73"))
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        toggleMenuItemVisibility(prefs, entry.id)
                        rebuildExtraPanel()
                    }
                }

                val upBtn = TextView(this).apply {
                    text = "▲"
                    textSize = 11.5f
                    setTextColor(if (index > 0) Color.WHITE else Color.parseColor("#3A3A40"))
                    setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
                    setOnClickListener {
                        if (index > 0) {
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            moveMenuItem(prefs, entry.id, -1)
                            rebuildExtraPanel()
                        }
                    }
                }

                val downBtn = TextView(this).apply {
                    text = "▼"
                    textSize = 11.5f
                    setTextColor(if (index < allEntries.lastIndex) Color.WHITE else Color.parseColor("#3A3A40"))
                    setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
                    setOnClickListener {
                        if (index < allEntries.lastIndex) {
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            moveMenuItem(prefs, entry.id, +1)
                            rebuildExtraPanel()
                        }
                    }
                }

                row.addView(toggleBtn)
                row.addView(labelTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(upBtn)
                row.addView(downBtn)

                val rowLp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (3 * density).toInt()
                }
                listCol.addView(row, rowLp)
            }

            scroll.addView(listCol)
            val maxScrollH = (260 * density).toInt()
            extraPanelContainer.addView(
                scroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxScrollH)
            )
        } else {
            // Modalità NORMALE: mostra solo i tasti attivi nell'ordine scelto
            val visibleEntries = allEntries.filter { it.enabled }
            if (visibleEntries.isEmpty()) {
                val emptyHint = TextView(this).apply {
                    text = "Nessun tasto attivo.\nTocca '✎ Edit' in alto a destra."
                    textSize = 12f
                    setTextColor(Color.parseColor("#8E8E93"))
                    setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                }
                extraPanelContainer.addView(emptyHint)
            } else {
                for ((index, entry) in visibleEntries.withIndex()) {
                    val tv = TextView(this).apply {
                        text = entry.displayTitle
                        textSize = 13f
                        setTextColor(Color.WHITE)
                        background = makeMinimalOptionBg(false, density)
                        setPadding((10 * density).toInt(), (7 * density).toInt(), (10 * density).toInt(), (7 * density).toInt())
                        setOnClickListener {
                            extraPanelContainer.visibility = View.GONE
                            rootFrame.requestLayout()
                            executeMenuEntryById(entry.id)
                        }
                    }
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        if (index < visibleEntries.lastIndex) {
                            bottomMargin = (2 * density).toInt()
                        }
                    }
                    extraPanelContainer.addView(tv, lp)
                    extraOptionViews.add(entry.id to tv)
                }
            }
        }
    }

    private fun makeMinimalOptionBg(highlighted: Boolean, density: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(if (highlighted) Color.WHITE else Color.TRANSPARENT)
            cornerRadius = 8f * density
        }
    }

    private fun showExtraPanel() {
        if (extraPanelContainer.visibility != View.VISIBLE) {
            isEditingExtraMenu = false
            rebuildExtraPanel()
            extraPanelContainer.visibility = View.VISIBLE
            rootFrame.requestLayout()
            rootFrame.post { applyIndependentPositions() }
        }
    }

    private fun toggleExtraPanel() {
        if (extraPanelContainer.visibility == View.VISIBLE) {
            isEditingExtraMenu = false
            extraPanelContainer.visibility = View.GONE
            rootFrame.requestLayout()
        } else {
            isEditingExtraMenu = false
            rebuildExtraPanel()
            extraPanelContainer.visibility = View.VISIBLE
            rootFrame.requestLayout()
            rootFrame.post { applyIndependentPositions() }
        }
    }

    private fun updateExtraPanelHover(rawX: Float, rawY: Float) {
        if (extraPanelContainer.visibility != View.VISIBLE || isEditingExtraMenu) return
        val density = resources.displayMetrics.density
        val loc = IntArray(2)
        var currentMatch: String? = null

        for ((id, view) in extraOptionViews) {
            view.getLocationOnScreen(loc)
            val left = loc[0].toFloat()
            val top = loc[1].toFloat()
            val right = left + view.width
            val bottom = top + view.height

            val isInside = rawX in left..right && rawY in top..bottom
            if (isInside) {
                currentMatch = id
            }
            view.background = makeMinimalOptionBg(isInside, density)
            view.setTextColor(if (isInside) Color.BLACK else Color.WHITE)
        }

        if (currentMatch != null && currentMatch != hoveredExtraId) {
            cornerOrbView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        hoveredExtraId = currentMatch
    }

    private fun releaseExtraPanelAt(rawX: Float, rawY: Float) {
        updateExtraPanelHover(rawX, rawY)
        val chosenId = hoveredExtraId
        extraPanelContainer.visibility = View.GONE
        hoveredExtraId = null
        rootFrame.requestLayout()

        if (chosenId != null) {
            executeMenuEntryById(chosenId)
        }
    }

    private fun executeMenuEntryById(id: String) {
        val prefs = getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)
        when (id) {
            "TOGGLE_KB" -> {
                isKeyboardVisible = !isKeyboardVisible
                keyboardView.visibility = if (isKeyboardVisible) View.VISIBLE else View.GONE
                rootFrame.requestLayout()
                rootFrame.post { applyIndependentPositions() }
            }
            "SWITCH_STYLE" -> {
                val nextArc = !keyboardView.isArcLayoutMode()
                prefs.edit().putBoolean("kb_arc_mode", nextArc).apply()
                loadAndApplyActiveStyleConfig(prefs)
                isKeyboardVisible = true
                keyboardView.visibility = View.VISIBLE
                rootFrame.requestLayout()
                rootFrame.post { applyIndependentPositions() }
            }
            "VOICE" -> startOrStopVoiceDictation()
            "MOUSE" -> {
                if (remoteCursorOverlay.visibility == View.VISIBLE) {
                    hideRemoteCursor()
                } else {
                    activateRemoteCursor()
                }
            }
            "CLIPBOARD" -> toggleClipboardPanel()
            "TRANSLATE" -> openTranslate()
            "INCOGNITO" -> {
                isManualIncognito = !isManualIncognito
                cornerOrbView.setIncognitoVisual(isIncognitoActive())
                if (!isIncognitoActive()) {
                    syncFromSystemClipboard()
                }
                Toast.makeText(
                    this,
                    if (isIncognitoActive()) "Incognito ON" else "Incognito OFF",
                    Toast.LENGTH_SHORT
                ).show()
            }
            "SETTINGS" -> {
                try {
                    val intent = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (_: Throwable) {
                }
            }
            else -> {
                val sc = loadCustomShortcuts(prefs).firstOrNull { it.id == id }
                if (sc != null) {
                    executeCustomShortcut(sc)
                }
            }
        }
    }

    private fun executeCustomShortcut(sc: StoredShortcut) {
        when (sc.type) {
            "SAMSUNG_ASSIST" -> openSamsungWritingAssist()
            "INTENT_URI" -> {
                try {
                    val intent = Intent.parseUri(sc.payload, Intent.URI_INTENT_SCHEME).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (_: Throwable) {
                    Toast.makeText(this, "Impossibile avviare '${sc.label}'", Toast.LENGTH_SHORT).show()
                }
            }
            "APP" -> {
                try {
                    val launch = packageManager.getLaunchIntentForPackage(sc.payload)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launch)
                    } else {
                        Toast.makeText(this, "App non trovata", Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Throwable) {
                    Toast.makeText(this, "Impossibile aprire l'app", Toast.LENGTH_SHORT).show()
                }
            }
            else -> {
                currentInputConnection?.commitText(sc.payload, 1)
            }
        }
    }

    private fun openSamsungWritingAssist() {
        val ic = currentInputConnection
        val selected = ic?.getSelectedText(0)?.toString()
        val targetText = if (!selected.isNullOrEmpty()) {
            selected
        } else {
            ic?.getTextBeforeCursor(500, 0)?.toString().orEmpty()
        }

        val processIntent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, targetText)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val samsungPackages = listOf(
            "com.samsung.android.honeyboard",
            "com.samsung.android.smartsuggestions"
        )
        for (pkg in samsungPackages) {
            val testIntent = Intent(processIntent).setPackage(pkg)
            if (packageManager.resolveActivity(testIntent, 0) != null) {
                try {
                    startActivity(testIntent)
                    return
                } catch (_: Throwable) {
                }
            }
        }

        try {
            startActivity(Intent.createChooser(processIntent, "Assistente di scrittura").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Throwable) {
            Toast.makeText(this, "Nessun assistente di scrittura trovato", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Azioni tastiera standard ---

    override fun onCommitText(text: String) {
        if (extraPanelContainer.visibility == View.VISIBLE && !isEditingExtraMenu) {
            extraPanelContainer.visibility = View.GONE
        }
        if (!isListeningVoice && swipeBarContainer.visibility == View.VISIBLE) {
            swipeBarContainer.visibility = View.GONE
        }
        currentInputConnection?.commitText(text, 1)
    }

    override fun onDeleteChar() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    override fun onEnterPressed() {
        val ic = currentInputConnection ?: return
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_UNSPECIFIED
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    override fun onSwitchIme() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
        } catch (_: Throwable) {
        }
    }

    private fun toggleClipboardPanel() {
        if (clipboardPanel.visibility == View.VISIBLE) {
            clipboardPanel.visibility = View.GONE
            rootFrame.requestLayout()
            return
        }
        syncFromSystemClipboard()

        val density = resources.displayMetrics.density
        clipboardListLayout.removeAllViews()

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val header = TextView(this).apply {
            text = if (isIncognitoActive()) "Incognito ON" else "Appunti sistema"
            textSize = 11.5f
            setTextColor(Color.parseColor("#8E8E93"))
        }
        val clearBtn = TextView(this).apply {
            text = "Svuota"
            textSize = 11.5f
            setTextColor(Color.parseColor("#D1D1D6"))
            setPadding((8 * density).toInt(), (2 * density).toInt(), (8 * density).toInt(), (2 * density).toInt())
            setOnClickListener {
                clipboardHistory.clear()
                savePersistedClipboard()
                toggleClipboardPanel()
            }
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding((6 * density).toInt(), (2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt())
            setOnClickListener {
                clipboardPanel.visibility = View.GONE
                rootFrame.requestLayout()
            }
        }
        headerRow.addView(header, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (clipboardHistory.isNotEmpty()) {
            headerRow.addView(clearBtn)
        }
        headerRow.addView(closeBtn)
        clipboardListLayout.addView(headerRow)

        if (clipboardHistory.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Nessun testo negli appunti."
                textSize = 12.5f
                setTextColor(Color.WHITE)
                setPadding(0, (6 * density).toInt(), 0, (4 * density).toInt())
            }
            clipboardListLayout.addView(empty)
        } else {
            for (item in clipboardHistory) {
                val tv = TextView(this).apply {
                    text = item
                    maxLines = 2
                    textSize = 12.5f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#16161A"))
                    setPadding((8 * density).toInt(), (7 * density).toInt(), (8 * density).toInt(), (7 * density).toInt())
                    setOnClickListener {
                        currentInputConnection?.commitText(item, 1)
                        if (!isIncognitoActive()) {
                            try {
                                clipboardManager?.setPrimaryClip(ClipData.newPlainText("text", item))
                            } catch (_: Throwable) {
                            }
                        }
                        clipboardPanel.visibility = View.GONE
                        rootFrame.requestLayout()
                    }
                }
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (4 * density).toInt()
                }
                clipboardListLayout.addView(tv, lp)
            }
        }
        clipboardPanel.visibility = View.VISIBLE
        rootFrame.requestLayout()
        rootFrame.post { applyIndependentPositions() }
    }

    private fun startOrStopVoiceDictation() {
        if (isListeningVoice) {
            stopVoiceDictation()
            return
        }
        val prefs = getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)
        val savedComponent = safeString(prefs, "voice_service_component", null)

        try {
            speechRecognizer?.destroy()
            speechRecognizer = if (!savedComponent.isNullOrEmpty()) {
                val cn = ComponentName.unflattenFromString(savedComponent)
                if (cn != null) SpeechRecognizer.createSpeechRecognizer(this, cn)
                else SpeechRecognizer.createSpeechRecognizer(this)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListeningVoice = true
                    showVoiceBanner()
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    isListeningVoice = false
                    swipeBarContainer.visibility = View.GONE
                    rootFrame.requestLayout()
                    Toast.makeText(this@Tastiera45Service, "Dettatura terminata ($error)", Toast.LENGTH_SHORT).show()
                }
                override fun onResults(results: Bundle?) {
                    isListeningVoice = false
                    swipeBarContainer.visibility = View.GONE
                    rootFrame.requestLayout()
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!spoken.isNullOrBlank()) {
                        currentInputConnection?.commitText("$spoken ", 1)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            speechRecognizer?.startListening(intent)
        } catch (_: Throwable) {
            Toast.makeText(this, "Apri l'app per abilitare il microfono o scegliere il motore vocale.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showVoiceBanner() {
        val density = resources.displayMetrics.density
        swipeBarContainer.removeAllViews()
        val tv = TextView(this).apply {
            text = "◉  Ascolto... (tocca per fermare)"
            textSize = 12.5f
            setTextColor(Color.WHITE)
            setPadding((6 * density).toInt(), (3 * density).toInt(), (6 * density).toInt(), (3 * density).toInt())
            setOnClickListener { stopVoiceDictation() }
        }
        swipeBarContainer.addView(tv)
        swipeBarContainer.visibility = View.VISIBLE
        rootFrame.requestLayout()
        rootFrame.post { applyIndependentPositions() }
    }

    private fun stopVoiceDictation() {
        isListeningVoice = false
        try {
            speechRecognizer?.stopListening()
        } catch (_: Throwable) {
        }
        swipeBarContainer.visibility = View.GONE
        rootFrame.requestLayout()
    }

    private fun openTranslate() {
        val ic = currentInputConnection
        val selected = ic?.getSelectedText(0)?.toString()
            ?: ic?.getTextBeforeCursor(200, 0)?.toString().orEmpty()

        val processIntent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, selected)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(Intent.createChooser(processIntent, "Traduci testo").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Throwable) {
            Toast.makeText(this, "Installa Google Traduttore o Samsung Traduttore", Toast.LENGTH_SHORT).show()
        }
    }

    private fun activateRemoteCursor() {
        try {
            remoteCursorOverlay.resetPositions(orbXPct < 0.5f)
            remoteCursorOverlay.visibility = View.VISIBLE
            rootFrame.requestLayout()
        } catch (_: Throwable) {
        }
    }

    private fun hideRemoteCursor() {
        remoteCursorOverlay.visibility = View.GONE
        rootFrame.requestLayout()
    }

    override fun onMoveTextCursor(deltaXSteps: Int, deltaYSteps: Int) {
        val ic = currentInputConnection ?: return

        if (deltaXSteps != 0) {
            val keyCodeX = if (deltaXSteps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            val countX = abs(deltaXSteps).coerceAtMost(12)
            repeat(countX) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCodeX))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCodeX))
            }
        }

        if (deltaYSteps != 0) {
            val keyCodeY = if (deltaYSteps < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
            val countY = abs(deltaYSteps).coerceAtMost(8)
            repeat(countY) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCodeY))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCodeY))
            }
        }
    }

    override fun onSwipeWordDecoded(candidates: List<String>) {
        if (candidates.size <= 1) {
            swipeBarContainer.visibility = View.GONE
            return
        }
        val density = resources.displayMetrics.density
        swipeBarContainer.removeAllViews()

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        for ((idx, word) in candidates.withIndex()) {
            val chip = TextView(this).apply {
                text = word
                textSize = 13f
                typeface = if (idx == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(Color.WHITE)
                setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt())
                setOnClickListener {
                    val ic = currentInputConnection ?: return@setOnClickListener
                    val before = ic.getTextBeforeCursor(40, 0)?.toString() ?: ""
                    val trimmed = before.trimEnd()
                    val lastSpace = trimmed.lastIndexOf(' ')
                    val charsToDelete = before.length - (lastSpace + 1)
                    if (charsToDelete > 0) {
                        ic.deleteSurroundingText(charsToDelete, 0)
                    }
                    ic.commitText("$word ", 1)
                    swipeBarContainer.visibility = View.GONE
                    rootFrame.requestLayout()
                }
            }
            row.addView(chip)
        }

        val closeChip = TextView(this).apply {
            text = "✕"
            textSize = 11.5f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding((8 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
            setOnClickListener {
                swipeBarContainer.visibility = View.GONE
                rootFrame.requestLayout()
            }
        }
        row.addView(closeChip)

        scroll.addView(row)
        val maxBarWidth = (resources.displayMetrics.widthPixels * 0.86f).toInt().coerceAtLeast((180 * density).toInt())
        swipeBarContainer.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            // Limita la larghezza massima senza allargare fuori dallo schermo
        })
        swipeBarContainer.measure(
            View.MeasureSpec.makeMeasureSpec(maxBarWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        swipeBarContainer.visibility = View.VISIBLE
        applyIndependentPositions()
        rootFrame.requestLayout()
    }

    override fun onDragFloatingKeyboard(deltaScreenX: Float, deltaScreenY: Float) {
        val screenW = rootFrame.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val screenH = rootFrame.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        if (screenW <= 0 || screenH <= 0) return

        val kbW = keyboardView.width
        val kbH = keyboardView.height
        val maxKbLeft = (screenW - kbW).coerceAtLeast(1)
        val maxKbBottom = (screenH - kbH).coerceAtLeast(0)

        val currentLeft = (maxKbLeft * kbXPct).toInt()
        val nextLeft = (currentLeft + deltaScreenX.toInt()).coerceIn(0, maxKbLeft)
        kbXPct = (nextLeft.toFloat() / maxKbLeft.toFloat()).coerceIn(0f, 1f)
        kbBottomOffsetPx = (kbBottomOffsetPx - deltaScreenY.toInt()).coerceIn(0, maxKbBottom)

        applyIndependentPositions()

        val prefix = if (keyboardView.isArcLayoutMode()) "arc_" else "popup_"
        getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)
            .edit()
            .putFloat("${prefix}x_pct", kbXPct)
            .putInt("${prefix}bottom_offset", kbBottomOffsetPx)
            .apply()
    }
}
