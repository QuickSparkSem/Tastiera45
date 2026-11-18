package com.tastiera45.ime

import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
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

class Tastiera45Service : InputMethodService(), AngledKeyboardView.KeyboardActionListener {

    private sealed class ExtraAction {
        object Mouse : ExtraAction()
        object Voice : ExtraAction()
        object Clipboard : ExtraAction()
        object Translate : ExtraAction()
        object Incognito : ExtraAction()
        object FlipSide : ExtraAction()
        object Settings : ExtraAction()
        data class CustomShortcut(val label: String, val textToInsert: String) : ExtraAction()
    }

    private lateinit var rootFrame: FrameLayout
    private lateinit var floatingDockWrapper: LinearLayout
    private lateinit var extraPanelContainer: LinearLayout
    private lateinit var swipeBarContainer: LinearLayout
    private lateinit var keyboardView: AngledKeyboardView
    private lateinit var clipboardPanel: LinearLayout
    private lateinit var clipboardListLayout: LinearLayout
    private lateinit var remoteCursorOverlay: RemoteCursorOverlay

    private val extraOptionViews = mutableListOf<Pair<ExtraAction, TextView>>()
    private var hoveredExtraAction: ExtraAction? = null

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
        val savedScale = prefs.getFloat("kb_scale", 0.78f)
        val savedOpacity = prefs.getFloat("kb_alpha", 0.92f)
        val savedTilt = prefs.getFloat("kb_tilt", -28f)
        bottomOffsetPx = prefs.getInt("kb_bottom_offset", (56 * density).toInt())

        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        floatingDockWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }

        // 1. Pannello minimal delle funzioni extra (attivato dal bottone unico ✦)
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
        val panelParams = LinearLayout.LayoutParams(
            (215 * density).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (isLeftSide) Gravity.START else Gravity.END
            bottomMargin = (6 * density).toInt()
        }
        floatingDockWrapper.addView(extraPanelContainer, panelParams)

        // 2. Barra minimal suggerimenti Swipe / stato Voce
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
        val swipeParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (isLeftSide) Gravity.START else Gravity.END
            bottomMargin = (6 * density).toInt()
        }
        floatingDockWrapper.addView(swipeBarContainer, swipeParams)

        // 3. Pannello minimal Appunti
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
        val clipParams = LinearLayout.LayoutParams(
            (235 * density).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (isLeftSide) Gravity.START else Gravity.END
            bottomMargin = (6 * density).toInt()
        }
        floatingDockWrapper.addView(clipboardPanel, clipParams)

        // 4. Tastiera tonda laterale con tasti inclinati
        keyboardView = AngledKeyboardView(this, this).apply {
            setLeftHanded(isLeftSide)
            setScaleFactor(savedScale)
            setOpacity(savedOpacity)
            setTiltAngle(savedTilt)
            setIncognitoVisual(isIncognitoActive())
        }
        floatingDockWrapper.addView(
            keyboardView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val dockParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or (if (isLeftSide) Gravity.START else Gravity.END)
        ).apply {
            bottomMargin = bottomOffsetPx
        }
        rootFrame.addView(floatingDockWrapper, dockParams)

        // 5. Overlay Remote Cursor (Mouse)
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
        val prefs = getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)
        val savedScale = prefs.getFloat("kb_scale", 0.78f)
        val savedOpacity = prefs.getFloat("kb_alpha", 0.92f)
        val savedTilt = prefs.getFloat("kb_tilt", -28f)
        val isLeftSide = prefs.getBoolean("left_handed", false)
        bottomOffsetPx = prefs.getInt("kb_bottom_offset", bottomOffsetPx)

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
            keyboardView.setIncognitoVisual(isIncognitoActive())
            keyboardView.setActionIcon(actionSymbol)
        }
        if (::floatingDockWrapper.isInitialized) {
            val lp = floatingDockWrapper.layoutParams as FrameLayout.LayoutParams
            lp.gravity = Gravity.BOTTOM or (if (isLeftSide) Gravity.START else Gravity.END)
            lp.bottomMargin = bottomOffsetPx
            floatingDockWrapper.layoutParams = lp
            updateSubPanelAlignments(isLeftSide)
        }
        if (::extraPanelContainer.isInitialized) {
            extraPanelContainer.visibility = View.GONE
        }
        if (::swipeBarContainer.isInitialized && !isListeningVoice) {
            swipeBarContainer.visibility = View.GONE
        }
    }

    private fun updateSubPanelAlignments(isLeftSide: Boolean) {
        val align = if (isLeftSide) Gravity.START else Gravity.END
        (extraPanelContainer.layoutParams as? LinearLayout.LayoutParams)?.gravity = align
        (swipeBarContainer.layoutParams as? LinearLayout.LayoutParams)?.gravity = align
        (clipboardPanel.layoutParams as? LinearLayout.LayoutParams)?.gravity = align
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (!::rootFrame.isInitialized || !::keyboardView.isInitialized) return

        val screenH = rootFrame.height
        if (screenH <= 0) return

        outInsets.contentTopInsets = screenH
        outInsets.visibleTopInsets = screenH
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.setEmpty()

        // Rende toccabile SOLO l'area circolare della tastiera (niente riquadro quadrato!)
        val kbOffsetX = floatingDockWrapper.left + keyboardView.left
        val kbOffsetY = floatingDockWrapper.top + keyboardView.top
        keyboardView.appendCircleToRegion(outInsets.touchableRegion, kbOffsetX, kbOffsetY)

        if (extraPanelContainer.visibility == View.VISIBLE) {
            outInsets.touchableRegion.op(
                Rect(
                    floatingDockWrapper.left + extraPanelContainer.left,
                    floatingDockWrapper.top + extraPanelContainer.top,
                    floatingDockWrapper.left + extraPanelContainer.right,
                    floatingDockWrapper.top + extraPanelContainer.bottom
                ),
                Region.Op.UNION
            )
        }
        if (swipeBarContainer.visibility == View.VISIBLE) {
            outInsets.touchableRegion.op(
                Rect(
                    floatingDockWrapper.left + swipeBarContainer.left,
                    floatingDockWrapper.top + swipeBarContainer.top,
                    floatingDockWrapper.left + swipeBarContainer.right,
                    floatingDockWrapper.top + swipeBarContainer.bottom
                ),
                Region.Op.UNION
            )
        }
        if (clipboardPanel.visibility == View.VISIBLE) {
            outInsets.touchableRegion.op(
                Rect(
                    floatingDockWrapper.left + clipboardPanel.left,
                    floatingDockWrapper.top + clipboardPanel.top,
                    floatingDockWrapper.left + clipboardPanel.right,
                    floatingDockWrapper.top + clipboardPanel.bottom
                ),
                Region.Op.UNION
            )
        }
        if (::remoteCursorOverlay.isInitialized && remoteCursorOverlay.visibility == View.VISIBLE) {
            outInsets.touchableRegion.op(cursorPadRect, Region.Op.UNION)
        }
    }

    // --- Menu Minimal Funzioni Extra + Scorciatoie Personalizzate (dal bottone ✦) ---

    private fun loadCustomShortcuts(): List<Pair<String, String>> {
        val prefs = getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("custom_shortcuts", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\u001E").mapNotNull { entry ->
            val parts = entry.split("\u001F")
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotEmpty()) {
                parts[0] to parts[1]
            } else {
                null
            }
        }
    }

    private fun rebuildExtraPanel() {
        val density = resources.displayMetrics.density
        extraPanelContainer.removeAllViews()
        extraOptionViews.clear()
        hoveredExtraAction = null

        val items = mutableListOf<Pair<ExtraAction, String>>(
            ExtraAction.Mouse to "⌖   Mouse",
            ExtraAction.Voice to if (isListeningVoice) "■   Ferma voce" else "◉   Dettatura",
            ExtraAction.Clipboard to "⎘   Appunti",
            ExtraAction.Translate to "文   Traduci",
            ExtraAction.Incognito to if (isIncognitoActive()) "●   Incognito ON" else "◌   Incognito OFF",
            ExtraAction.FlipSide to if (keyboardView.isLeftHandedMode()) "⇄   Lato destro" else "⇄   Lato sinistro",
            ExtraAction.Settings to "⚙   Impostazioni"
        )

        // Aggiunge i tasti personalizzati creati dall'utente nelle impostazioni
        val customShortcuts = loadCustomShortcuts()
        for ((label, text) in customShortcuts) {
            items.add(ExtraAction.CustomShortcut(label, text) to "›   $label")
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
            setColor(if (highlighted) Color.argb(58, 255, 255, 255) else Color.TRANSPARENT)
            cornerRadius = 9f * density
        }
    }

    override fun onShowExtraPanel() {
        if (extraPanelContainer.visibility != View.VISIBLE) {
            rebuildExtraPanel()
            extraPanelContainer.visibility = View.VISIBLE
            rootFrame.requestLayout()
        }
    }

    override fun onToggleExtraPanel() {
        if (extraPanelContainer.visibility == View.VISIBLE) {
            extraPanelContainer.visibility = View.GONE
        } else {
            rebuildExtraPanel()
            extraPanelContainer.visibility = View.VISIBLE
        }
        rootFrame.requestLayout()
    }

    override fun onUpdateExtraPanelHover(rawX: Float, rawY: Float) {
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
            keyboardView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        hoveredExtraAction = currentMatch
    }

    override fun onReleaseExtraPanelAt(rawX: Float, rawY: Float) {
        onUpdateExtraPanelHover(rawX, rawY)
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
            is ExtraAction.Mouse -> activateRemoteCursor()
            is ExtraAction.Voice -> startOrStopVoiceDictation()
            is ExtraAction.Clipboard -> toggleClipboardPanel()
            is ExtraAction.Translate -> openTranslate()
            is ExtraAction.Incognito -> {
                isManualIncognito = !isManualIncognito
                keyboardView.setIncognitoVisual(isIncognitoActive())
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
            is ExtraAction.CustomShortcut -> {
                currentInputConnection?.commitText(action.textToInsert, 1)
            }
        }
    }

    // --- Azioni tastiera ---

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
        val nextLeft = !keyboardView.isLeftHandedMode()
        keyboardView.setLeftHanded(nextLeft)

        val lp = floatingDockWrapper.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.BOTTOM or (if (nextLeft) Gravity.START else Gravity.END)
        floatingDockWrapper.layoutParams = lp
        updateSubPanelAlignments(nextLeft)

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
        val maxBottom = (rootFrame.height - floatingDockWrapper.height).coerceAtLeast(0)
        bottomOffsetPx = (bottomOffsetPx - deltaScreenY.toInt()).coerceIn(0, maxBottom)

        val lp = floatingDockWrapper.layoutParams as FrameLayout.LayoutParams
        lp.bottomMargin = bottomOffsetPx
        floatingDockWrapper.layoutParams = lp

        getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("kb_bottom_offset", bottomOffsetPx)
            .apply()
    }
}
