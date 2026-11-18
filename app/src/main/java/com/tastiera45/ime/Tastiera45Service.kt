package com.tastiera45.ime

import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
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
import kotlin.math.hypot

class Tastiera45Service : InputMethodService(), AngledKeyboardView.KeyboardActionListener {

    private sealed class ExtraAction {
        object ToggleKeyboard : ExtraAction()
        object Voice : ExtraAction()
        object Mouse : ExtraAction()
        object Clipboard : ExtraAction()
        object Translate : ExtraAction()
        object Incognito : ExtraAction()
        object FlipSide : ExtraAction()
        object Settings : ExtraAction()
        data class CustomShortcut(val type: String, val label: String, val payload: String) : ExtraAction()
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

    private val extraOptionViews = mutableListOf<Pair<ExtraAction, TextView>>()
    private var hoveredExtraAction: ExtraAction? = null

    private var isKeyboardVisible = false
    private var isAutoIncognito = false
    private var isManualIncognito = false
    private val clipboardHistory = mutableListOf<String>()
    private var clipboardManager: ClipboardManager? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningVoice = false
    private var cursorPadRect = Rect()
    private var bottomOffsetPx = 0

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (isIncognitoActive()) return@OnPrimaryClipChangedListener
        val text = clipboardManager?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            clipboardHistory.remove(text)
            clipboardHistory.add(0, text)
            if (clipboardHistory.size > 25) {
                clipboardHistory.removeAt(clipboardHistory.lastIndex)
            }
        }
    }

    private fun isIncognitoActive(): Boolean = isAutoIncognito || isManualIncognito

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val density = resources.displayMetrics.density
        val prefs = getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)
        val isLeftSide = prefs.getBoolean("left_handed", false)
        val savedScale = prefs.getFloat("kb_scale", 0.80f)
        val savedOpacity = prefs.getFloat("kb_alpha", 0.92f)
        val savedTilt = prefs.getFloat("kb_tilt", -28f)
        val savedLetterSize = prefs.getFloat("kb_letter_size", 1.0f)
        val savedKeySpacing = prefs.getFloat("kb_key_spacing", 0.25f)
        val orbOnlyStart = prefs.getBoolean("orb_only_start", true)
        bottomOffsetPx = prefs.getInt("kb_bottom_offset", (42 * density).toInt())

        isKeyboardVisible = !orbOnlyStart

        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        // 1. Tastiera tonda laterale (può restare chiusa lasciando solo il tasto ✦ nell'angolo)
        keyboardView = AngledKeyboardView(this, this).apply {
            setLeftHanded(isLeftSide)
            setScaleFactor(savedScale)
            setOpacity(savedOpacity)
            setTiltAngle(savedTilt)
            setLetterSizeScale(savedLetterSize)
            setKeySpacingScale(savedKeySpacing)
            visibility = if (isKeyboardVisible) View.VISIBLE else View.GONE
        }
        val kbParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or (if (isLeftSide) Gravity.START else Gravity.END)
        ).apply {
            bottomMargin = bottomOffsetPx
        }
        rootFrame.addView(keyboardView, kbParams)

        // 2. Colonna popup minimal (Menu ✦, Suggerimenti Swipe, Stato Voce, Appunti)
        popupColumnWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }

        extraPanelContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EB0B0C0E"))
                cornerRadius = 14f * density
                setStroke((1f * density).toInt(), Color.parseColor("#2A2C34"))
            }
            setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
        }
        popupColumnWrapper.addView(
            extraPanelContainer,
            LinearLayout.LayoutParams((225 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (6 * density).toInt()
            }
        )

        swipeBarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E00D0E12"))
                cornerRadius = 12f * density
                setStroke((1f * density).toInt(), Color.parseColor("#25272E"))
            }
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
        }
        popupColumnWrapper.addView(
            swipeBarContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (6 * density).toInt()
            }
        )

        clipboardPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EB0B0C0E"))
                cornerRadius = 14f * density
                setStroke((1f * density).toInt(), Color.parseColor("#2A2C34"))
            }
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
        }
        val scroll = ScrollView(this)
        clipboardListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(clipboardListLayout)
        clipboardPanel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (140 * density).toInt()))
        popupColumnWrapper.addView(
            clipboardPanel,
            LinearLayout.LayoutParams((235 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (6 * density).toInt()
            }
        )

        val orbSizePx = (44 * density).toInt()
        val popupParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or (if (isLeftSide) Gravity.START else Gravity.END)
        ).apply {
            bottomMargin = bottomOffsetPx + orbSizePx + (10 * density).toInt()
            marginStart = (10 * density).toInt()
            marginEnd = (10 * density).toInt()
        }
        rootFrame.addView(popupColumnWrapper, popupParams)

        // 3. Tasto unico nell'angolo (✦) — sempre presente sull'angolo scelto
        cornerOrbView = CornerOrbView(this).apply {
            setIncognitoVisual(isIncognitoActive())
        }
        val orbParams = FrameLayout.LayoutParams(
            orbSizePx,
            orbSizePx,
            Gravity.BOTTOM or (if (isLeftSide) Gravity.START else Gravity.END)
        ).apply {
            bottomMargin = bottomOffsetPx
            marginStart = (10 * density).toInt()
            marginEnd = (10 * density).toInt()
        }
        rootFrame.addView(cornerOrbView, orbParams)

        // 4. Overlay Remote Cursor (Mouse) — utilizzabile anche insieme a tastiera e voce
        remoteCursorOverlay = RemoteCursorOverlay(
            context = this,
            onClose = { hideRemoteCursor() },
            onRequestOpenAccessibility = {
                Toast.makeText(this, "Attiva 'Tastiera 45° - Remote Cursor' in Accessibilità", Toast.LENGTH_LONG).show()
                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            },
            onTouchableRegionChanged = { rect ->
                cursorPadRect = rect
                rootFrame.requestLayout()
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
        val density = resources.displayMetrics.density
        val prefs = getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)
        val savedScale = prefs.getFloat("kb_scale", 0.80f)
        val savedOpacity = prefs.getFloat("kb_alpha", 0.92f)
        val savedTilt = prefs.getFloat("kb_tilt", -28f)
        val savedLetterSize = prefs.getFloat("kb_letter_size", 1.0f)
        val savedKeySpacing = prefs.getFloat("kb_key_spacing", 0.25f)
        val isLeftSide = prefs.getBoolean("left_handed", false)
        val orbOnlyStart = prefs.getBoolean("orb_only_start", true)
        bottomOffsetPx = prefs.getInt("kb_bottom_offset", bottomOffsetPx)

        isKeyboardVisible = !orbOnlyStart

        val imeOptions = info?.imeOptions ?: 0
        val inputType = info?.inputType ?: 0
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        val noLearning = (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        val isPassword = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD

        isAutoIncognito = noLearning || isPassword

        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val actionSymbol = when (action) {
            EditorInfo.IME_ACTION_SEARCH -> "🔍"
            EditorInfo.IME_ACTION_SEND -> "➤"
            EditorInfo.IME_ACTION_NEXT -> "⇥"
            EditorInfo.IME_ACTION_DONE -> "✓"
            else -> "↵"
        }

        if (::keyboardView.isInitialized) {
            keyboardView.setLeftHanded(isLeftSide)
            keyboardView.setScaleFactor(savedScale)
            keyboardView.setOpacity(savedOpacity)
            keyboardView.setTiltAngle(savedTilt)
            keyboardView.setLetterSizeScale(savedLetterSize)
            keyboardView.setKeySpacingScale(savedKeySpacing)
            keyboardView.setActionIcon(actionSymbol)
            keyboardView.visibility = if (isKeyboardVisible) View.VISIBLE else View.GONE
        }
        if (::cornerOrbView.isInitialized) {
            cornerOrbView.setIncognitoVisual(isIncognitoActive())
        }
        applySideAndOffsetLayout(isLeftSide, density)

        if (::extraPanelContainer.isInitialized) {
            extraPanelContainer.visibility = View.GONE
        }
        if (::swipeBarContainer.isInitialized && !isListeningVoice) {
            swipeBarContainer.visibility = View.GONE
        }
    }

    private fun applySideAndOffsetLayout(isLeftSide: Boolean, density: Float) {
        val sideGravity = Gravity.BOTTOM or (if (isLeftSide) Gravity.START else Gravity.END)
        val orbSizePx = (44 * density).toInt()

        (keyboardView.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.gravity = sideGravity
            lp.bottomMargin = bottomOffsetPx
            keyboardView.layoutParams = lp
        }
        (cornerOrbView.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.gravity = sideGravity
            lp.bottomMargin = bottomOffsetPx
            cornerOrbView.layoutParams = lp
        }
        (popupColumnWrapper.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.gravity = sideGravity
            lp.bottomMargin = bottomOffsetPx + orbSizePx + (10 * density).toInt()
            popupColumnWrapper.layoutParams = lp
        }
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (!::rootFrame.isInitialized || !::cornerOrbView.isInitialized) return

        val screenH = rootFrame.height
        if (screenH <= 0) return

        outInsets.contentTopInsets = screenH
        outInsets.visibleTopInsets = screenH
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.setEmpty()

        // 1. Il tasto unico ✦ nell'angolo è sempre toccabile
        outInsets.touchableRegion.op(
            Rect(
                cornerOrbView.left - 8,
                cornerOrbView.top - 8,
                cornerOrbView.right + 8,
                cornerOrbView.bottom + 8
            ),
            Region.Op.UNION
        )

        // 2. Se la tastiera tonda è aperta, aggiunge SOLO la sua forma circolare
        if (keyboardView.visibility == View.VISIBLE) {
            keyboardView.appendCircleToRegion(outInsets.touchableRegion, keyboardView.left, keyboardView.top)
        }

        // 3. Menu funzioni extra / barra suggerimenti / appunti se aperti
        if (extraPanelContainer.visibility == View.VISIBLE) {
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
        if (swipeBarContainer.visibility == View.VISIBLE) {
            outInsets.touchableRegion.op(
                Rect(
                    popupColumnWrapper.left + swipeBarContainer.left,
                    popupColumnWrapper.top + swipeBarContainer.top,
                    popupColumnWrapper.left + swipeBarContainer.right,
                    popupColumnWrapper.top + swipeBarContainer.bottom
                ),
                Region.Op.UNION
            )
        }
        if (clipboardPanel.visibility == View.VISIBLE) {
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

        // 4. Remote Cursor (Mouse) se attivo
        if (::remoteCursorOverlay.isInitialized && remoteCursorOverlay.visibility == View.VISIBLE) {
            outInsets.touchableRegion.op(cursorPadRect, Region.Op.UNION)
        }
    }

    // --- Tasto unico nell'angolo: hold-drag & tap ---

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
            color = Color.parseColor("#EE0E0F13")
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3B3E48")
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
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
            borderPaint.color = if (incognitoActive) Color.parseColor("#60A5FA") else Color.parseColor("#3B3E48")
            canvas.drawCircle(cx, cy, r, bgPaint)
            canvas.drawCircle(cx, cy, r, borderPaint)

            symbolPaint.textSize = r * 0.85f
            symbolPaint.color = if (incognitoActive) Color.parseColor("#60A5FA") else Color.WHITE
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
                    orbHandler.postDelayed(holdRunnable, 150L)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
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
                    if (dragMoved || holdTriggered) {
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

    // --- Menu Minimal Funzioni Extra + Scorciatoie Personalizzate ---

    private data class StoredShortcut(val type: String, val label: String, val payload: String)

    private fun loadCustomShortcuts(): List<StoredShortcut> {
        val prefs = getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("custom_shortcuts", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\u001E").mapNotNull { entry ->
            val parts = entry.split("\u001F")
            when {
                parts.size == 3 && parts[1].isNotBlank() -> StoredShortcut(parts[0], parts[1], parts[2])
                parts.size == 2 && parts[0].isNotBlank() -> StoredShortcut("TEXT", parts[0], parts[1])
                else -> null
            }
        }
    }

    private fun rebuildExtraPanel() {
        val density = resources.displayMetrics.density
        extraPanelContainer.removeAllViews()
        extraOptionViews.clear()
        hoveredExtraAction = null

        val items = mutableListOf<Pair<ExtraAction, String>>(
            ExtraAction.ToggleKeyboard to if (isKeyboardVisible) "⌨   Chiudi tastiera" else "⌨   Tastiera",
            ExtraAction.Voice to if (isListeningVoice) "■   Ferma dettatura" else "◉   Dettatura",
            ExtraAction.Mouse to if (remoteCursorOverlay.visibility == View.VISIBLE) "⌖   Chiudi mouse" else "⌖   Mouse",
            ExtraAction.Clipboard to "⎘   Appunti",
            ExtraAction.Translate to "文   Traduci",
            ExtraAction.Incognito to if (isIncognitoActive()) "●   Incognito ON" else "◌   Incognito OFF",
            ExtraAction.FlipSide to if (keyboardView.isLeftHandedMode()) "⇄   Lato destro" else "⇄   Lato sinistro",
            ExtraAction.Settings to "⚙   Impostazioni"
        )

        // Aggiunge le scorciatoie personalizzate (Shortcut Maker, Assistente Samsung, App, Testi)
        for (sc in loadCustomShortcuts()) {
            val prefix = when (sc.type) {
                "SAMSUNG_ASSIST" -> "✦"
                "INTENT_URI" -> "↗"
                "APP" -> "▣"
                else -> "›"
            }
            items.add(ExtraAction.CustomShortcut(sc.type, sc.label, sc.payload) to "$prefix   ${sc.label}")
        }

        for ((index, pair) in items.withIndex()) {
            val (action, label) = pair
            val tv = TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(Color.parseColor("#F2F2F7"))
                background = makeMinimalOptionBg(false, density)
                setPadding((10 * density).toInt(), (7 * density).toInt(), (10 * density).toInt(), (7 * density).toInt())
                setOnClickListener {
                    extraPanelContainer.visibility = View.GONE
                    rootFrame.requestLayout()
                    executeExtraAction(action)
                }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index < items.lastIndex) {
                    bottomMargin = (2 * density).toInt()
                }
            }
            extraPanelContainer.addView(tv, lp)
            extraOptionViews.add(action to tv)
        }
    }

    private fun makeMinimalOptionBg(highlighted: Boolean, density: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(if (highlighted) Color.argb(65, 255, 255, 255) else Color.TRANSPARENT)
            cornerRadius = 9f * density
        }
    }

    private fun showExtraPanel() {
        if (extraPanelContainer.visibility != View.VISIBLE) {
            rebuildExtraPanel()
            extraPanelContainer.visibility = View.VISIBLE
            rootFrame.requestLayout()
        }
    }

    private fun toggleExtraPanel() {
        if (extraPanelContainer.visibility == View.VISIBLE) {
            extraPanelContainer.visibility = View.GONE
        } else {
            rebuildExtraPanel()
            extraPanelContainer.visibility = View.VISIBLE
        }
        rootFrame.requestLayout()
    }

    private fun updateExtraPanelHover(rawX: Float, rawY: Float) {
        if (extraPanelContainer.visibility != View.VISIBLE) return
        val density = resources.displayMetrics.density
        val loc = IntArray(2)
        var currentMatch: ExtraAction? = null

        for ((action, view) in extraOptionViews) {
            view.getLocationOnScreen(loc)
            val left = loc[0].toFloat()
            val top = loc[1].toFloat()
            val right = left + view.width
            val bottom = top + view.height

            val isInside = rawX in left..right && rawY in top..bottom
            if (isInside) {
                currentMatch = action
            }
            view.background = makeMinimalOptionBg(isInside, density)
        }

        if (currentMatch != null && currentMatch != hoveredExtraAction) {
            cornerOrbView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        hoveredExtraAction = currentMatch
    }

    private fun releaseExtraPanelAt(rawX: Float, rawY: Float) {
        updateExtraPanelHover(rawX, rawY)
        val chosen = hoveredExtraAction
        extraPanelContainer.visibility = View.GONE
        hoveredExtraAction = null
        rootFrame.requestLayout()

        if (chosen != null) {
            executeExtraAction(chosen)
        }
    }

    private fun executeExtraAction(action: ExtraAction) {
        when (action) {
            is ExtraAction.ToggleKeyboard -> {
                isKeyboardVisible = !isKeyboardVisible
                keyboardView.visibility = if (isKeyboardVisible) View.VISIBLE else View.GONE
                rootFrame.requestLayout()
            }
            is ExtraAction.Voice -> startOrStopVoiceDictation()
            is ExtraAction.Mouse -> {
                if (remoteCursorOverlay.visibility == View.VISIBLE) {
                    hideRemoteCursor()
                } else {
                    activateRemoteCursor()
                }
            }
            is ExtraAction.Clipboard -> toggleClipboardPanel()
            is ExtraAction.Translate -> openTranslate()
            is ExtraAction.Incognito -> {
                isManualIncognito = !isManualIncognito
                cornerOrbView.setIncognitoVisual(isIncognitoActive())
                Toast.makeText(
                    this,
                    if (isIncognitoActive()) "Incognito ON" else "Incognito OFF",
                    Toast.LENGTH_SHORT
                ).show()
            }
            is ExtraAction.FlipSide -> toggleHandSide()
            is ExtraAction.Settings -> {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
            is ExtraAction.CustomShortcut -> executeCustomShortcut(action)
        }
    }

    private fun executeCustomShortcut(sc: ExtraAction.CustomShortcut) {
        when (sc.type) {
            "SAMSUNG_ASSIST" -> openSamsungWritingAssist()
            "INTENT_URI" -> {
                try {
                    val intent = Intent.parseUri(sc.payload, Intent.URI_INTENT_SCHEME).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(this, "Impossibile avviare la scorciatoia '${sc.label}'", Toast.LENGTH_SHORT).show()
                }
            }
            "APP" -> {
                val launch = packageManager.getLaunchIntentForPackage(sc.payload)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                } else {
                    Toast.makeText(this, "App non trovata", Toast.LENGTH_SHORT).show()
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

        // Cerca prima i pacchetti Samsung (Tastiera Samsung / Assistente di scrittura / Smart Suggestions)
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
                } catch (_: Exception) {
                }
            }
        }

        // Altrimenti apre il selettore degli assistenti di testo installati
        try {
            startActivity(Intent.createChooser(processIntent, "Assistente di scrittura").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            Toast.makeText(this, "Nessun assistente di scrittura trovato su questo dispositivo", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Azioni tastiera standard ---

    override fun onCommitText(text: String) {
        if (extraPanelContainer.visibility == View.VISIBLE) {
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
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showInputMethodPicker()
    }

    private fun toggleClipboardPanel() {
        if (clipboardPanel.visibility == View.VISIBLE) {
            clipboardPanel.visibility = View.GONE
            rootFrame.requestLayout()
            return
        }
        val density = resources.displayMetrics.density
        clipboardListLayout.removeAllViews()

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val header = TextView(this).apply {
            text = if (isIncognitoActive()) "Incognito ON: sospeso" else "Appunti"
            textSize = 11.5f
            setTextColor(Color.parseColor("#8E8E93"))
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding((10 * density).toInt(), (2 * density).toInt(), (4 * density).toInt(), (2 * density).toInt())
            setOnClickListener {
                clipboardPanel.visibility = View.GONE
                rootFrame.requestLayout()
            }
        }
        headerRow.addView(header, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(closeBtn)
        clipboardListLayout.addView(headerRow)

        if (clipboardHistory.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Nessun testo salvato."
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
                    setBackgroundColor(Color.parseColor("#18191E"))
                    setPadding((8 * density).toInt(), (7 * density).toInt(), (8 * density).toInt(), (7 * density).toInt())
                    setOnClickListener {
                        currentInputConnection?.commitText(item, 1)
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
    }

    private fun startOrStopVoiceDictation() {
        if (isListeningVoice) {
            stopVoiceDictation()
            return
        }
        val prefs = getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)
        val savedComponent = prefs.getString("voice_service_component", null)

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
        } catch (_: Exception) {
            Toast.makeText(this, "Apri l'app Tastiera 45° per abilitare il microfono o scegliere il motore vocale.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showVoiceBanner() {
        val density = resources.displayMetrics.density
        swipeBarContainer.removeAllViews()
        val tv = TextView(this).apply {
            text = "◉  Ascolto... (tocca per fermare)"
            textSize = 12.5f
            setTextColor(Color.parseColor("#60A5FA"))
            setPadding((6 * density).toInt(), (3 * density).toInt(), (6 * density).toInt(), (3 * density).toInt())
            setOnClickListener { stopVoiceDictation() }
        }
        swipeBarContainer.addView(tv)
        swipeBarContainer.visibility = View.VISIBLE
        rootFrame.requestLayout()
    }

    private fun stopVoiceDictation() {
        isListeningVoice = false
        speechRecognizer?.stopListening()
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
        } catch (_: Exception) {
            Toast.makeText(this, "Installa Google Traduttore o Samsung Traduttore", Toast.LENGTH_SHORT).show()
        }
    }

    private fun activateRemoteCursor() {
        remoteCursorOverlay.resetPositions(keyboardView.isLeftHandedMode())
        remoteCursorOverlay.visibility = View.VISIBLE
        rootFrame.requestLayout()
    }

    private fun hideRemoteCursor() {
        remoteCursorOverlay.visibility = View.GONE
        rootFrame.requestLayout()
    }

    private fun toggleHandSide() {
        val density = resources.displayMetrics.density
        val nextLeft = !keyboardView.isLeftHandedMode()
        keyboardView.setLeftHanded(nextLeft)
        applySideAndOffsetLayout(nextLeft, density)

        getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("left_handed", nextLeft)
            .apply()
    }

    override fun onMoveTextCursor(steps: Int) {
        val ic = currentInputConnection ?: return
        val keyCode = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        val count = kotlin.math.abs(steps).coerceAtMost(10)
        repeat(count) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
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
        }

        for (word in candidates) {
            val chip = TextView(this).apply {
                text = word
                textSize = 13f
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
        scroll.addView(row)
        swipeBarContainer.addView(scroll)
        swipeBarContainer.visibility = View.VISIBLE
        rootFrame.requestLayout()
    }

    override fun onDragFloatingKeyboard(deltaScreenY: Float) {
        val density = resources.displayMetrics.density
        val maxBottom = (rootFrame.height - keyboardView.height).coerceAtLeast(0)
        bottomOffsetPx = (bottomOffsetPx - deltaScreenY.toInt()).coerceIn(0, maxBottom)

        applySideAndOffsetLayout(keyboardView.isLeftHandedMode(), density)

        getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("kb_bottom_offset", bottomOffsetPx)
            .apply()
    }
}
