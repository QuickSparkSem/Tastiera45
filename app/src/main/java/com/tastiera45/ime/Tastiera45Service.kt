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

    private enum class ExtraAction {
        MOUSE, VOICE, CLIPBOARD, TRANSLATE, INCOGNITO, FLIP_SIDE, SETTINGS
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
        val savedScale = prefs.getFloat("kb_scale", 0.74f)
        val savedOpacity = prefs.getFloat("kb_alpha", 0.92f)
        bottomOffsetPx = prefs.getInt("kb_bottom_offset", (72 * density).toInt())

        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        floatingDockWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }

        // 1. Pannello a comparsa con tutte le funzioni extra (attivato dal bottone unico ✦)
        extraPanelContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6101115"))
                cornerRadius = 18f * density
                setStroke((1.5f * density).toInt(), Color.parseColor("#32353F"))
            }
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }
        val panelParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = (6 * density).toInt()
        }
        floatingDockWrapper.addView(extraPanelContainer, panelParams)

        // 2. Barra suggerimenti Swipe / stato Voce (appare solo quando serve)
        swipeBarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D9121318"))
                cornerRadius = 14f * density
            }
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
        }
        val swipeParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = (6 * density).toInt()
        }
        floatingDockWrapper.addView(swipeBarContainer, swipeParams)

        // 3. Pannello Appunti (appare quando selezionato dal menu ✦)
        clipboardPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EB101115"))
                cornerRadius = 16f * density
                setStroke((1.5f * density).toInt(), Color.parseColor("#32353F"))
            }
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
        }
        val scroll = ScrollView(this)
        clipboardListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(clipboardListLayout)
        clipboardPanel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (145 * density).toInt()))
        val clipParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = (6 * density).toInt()
        }
        floatingDockWrapper.addView(clipboardPanel, clipParams)

        // 4. Tastiera circolare laterale con bottone unico ✦
        keyboardView = AngledKeyboardView(this, this).apply {
            setLeftHanded(isLeftSide)
            setScaleFactor(savedScale)
            setOpacity(savedOpacity)
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
        val savedScale = prefs.getFloat("kb_scale", 0.74f)
        val savedOpacity = prefs.getFloat("kb_alpha", 0.92f)
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
            keyboardView.setIncognitoVisual(isIncognitoActive())
            keyboardView.setActionIcon(actionSymbol)
        }
        if (::floatingDockWrapper.isInitialized) {
            val lp = floatingDockWrapper.layoutParams as FrameLayout.LayoutParams
            lp.gravity = Gravity.BOTTOM or (if (isLeftSide) Gravity.START else Gravity.END)
            lp.bottomMargin = bottomOffsetPx
            floatingDockWrapper.layoutParams = lp
        }
        if (::extraPanelContainer.isInitialized) {
            extraPanelContainer.visibility = View.GONE
        }
        if (::swipeBarContainer.isInitialized && !isListeningVoice) {
            swipeBarContainer.visibility = View.GONE
        }
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (!::rootFrame.isInitialized || !::floatingDockWrapper.isInitialized) return

        val screenH = rootFrame.height
        if (screenH <= 0) return

        outInsets.contentTopInsets = screenH
        outInsets.visibleTopInsets = screenH
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION

        outInsets.touchableRegion.set(
            floatingDockWrapper.left,
            floatingDockWrapper.top,
            floatingDockWrapper.right,
            floatingDockWrapper.bottom
        )

        if (::remoteCursorOverlay.isInitialized && remoteCursorOverlay.visibility == View.VISIBLE) {
            outInsets.touchableRegion.op(cursorPadRect, Region.Op.UNION)
        }
    }

    // --- Gestione Pannello Funzioni Extra (dal bottone unico ✦) ---

    private fun rebuildExtraPanel() {
        val density = resources.displayMetrics.density
        extraPanelContainer.removeAllViews()
        extraOptionViews.clear()
        hoveredExtraAction = null

        val items = listOf(
            ExtraAction.MOUSE to "🖱️  Mouse (Remote Cursor)",
            ExtraAction.VOICE to if (isListeningVoice) "⏹️  Ferma dettatura vocale" else "🎙️  Dettatura vocale",
            ExtraAction.CLIPBOARD to "📋  Appunti",
            ExtraAction.TRANSLATE to "🌐  Traduci testo",
            ExtraAction.INCOGNITO to if (isIncognitoActive()) "🕵️  Incognito: ATTIVO" else "🕵️  Incognito: Spento",
            ExtraAction.FLIP_SIDE to if (keyboardView.isLeftHandedMode()) "⇄  Sposta a Destra" else "⇄  Sposta a Sinistra",
            ExtraAction.SETTINGS to "⚙️  Dimensioni e Trasparenza"
        )

        for ((action, label) in items) {
            val tv = TextView(this).apply {
                text = label
                textSize = 13.5f
                setTextColor(Color.WHITE)
                background = makeOptionBg(false, density)
                setPadding((12 * density).toInt(), (9 * density).toInt(), (12 * density).toInt(), (9 * density).toInt())
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
                bottomMargin = (4 * density).toInt()
            }
            extraPanelContainer.addView(tv, lp)
            extraOptionViews.add(action to tv)
        }
    }

    private fun makeOptionBg(highlighted: Boolean, density: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(if (highlighted) Color.parseColor("#4B7BD6") else Color.parseColor("#1C1D24"))
            cornerRadius = 11f * density
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
            view.background = makeOptionBg(isInside, density)
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
            ExtraAction.MOUSE -> activateRemoteCursor()
            ExtraAction.VOICE -> startOrStopVoiceDictation()
            ExtraAction.CLIPBOARD -> toggleClipboardPanel()
            ExtraAction.TRANSLATE -> openTranslate()
            ExtraAction.INCOGNITO -> {
                isManualIncognito = !isManualIncognito
                keyboardView.setIncognitoVisual(isIncognitoActive())
                Toast.makeText(
                    this,
                    if (isIncognitoActive()) "Modalità Incognito ATTIVA" else "Modalità Incognito disattivata",
                    Toast.LENGTH_SHORT
                ).show()
            }
            ExtraAction.FLIP_SIDE -> toggleHandSide()
            ExtraAction.SETTINGS -> {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
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
            text = if (isIncognitoActive()) {
                "🕵️ Incognito ON: appunti sospesi"
            } else {
                "📋 Appunti (tocca per incollare)"
            }
            textSize = 11.5f
            setTextColor(Color.parseColor("#8E8E93"))
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 14f
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
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#1C1C1E"))
                    setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
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
                    topMargin = (5 * density).toInt()
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
            text = "🎙️ Ascolto... (tocca per fermare)"
            textSize = 13f
            setTextColor(Color.parseColor("#60A5FA"))
            setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
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
            Toast.makeText(this, "Installa Google Traduttore o Samsung Traduttore per la traduzione rapida", Toast.LENGTH_SHORT).show()
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
