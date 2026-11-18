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

    private lateinit var rootFrame: FrameLayout
    private lateinit var floatingDockWrapper: LinearLayout
    private lateinit var topBarContainer: LinearLayout
    private lateinit var keyboardView: AngledKeyboardView
    private lateinit var clipboardPanel: LinearLayout
    private lateinit var clipboardListLayout: LinearLayout
    private lateinit var remoteCursorOverlay: RemoteCursorOverlay

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
        // Sollevata dal fondo di default così esce dal lato come tastiera mobile
        bottomOffsetPx = prefs.getInt("kb_bottom_offset", (72 * density).toInt())

        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        floatingDockWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = 22f * density
                setStroke((1.5f * density).toInt(), Color.parseColor("#2C2C32"))
            }
            setPadding((2 * density).toInt(), (4 * density).toInt(), (2 * density).toInt(), (4 * density).toInt())
        }

        // Barra compatta superiore del modulo mobile: Incognito, Voce, Appunti, Traduci, Swipe
        topBarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
        }
        rebuildTopStrip(emptyList())
        floatingDockWrapper.addView(
            topBarContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (34 * density).toInt())
        )

        // Pannello Appunti integrato
        clipboardPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E0E11"))
            visibility = View.GONE
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
        }
        val scroll = ScrollView(this)
        clipboardListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(clipboardListLayout)
        clipboardPanel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (140 * density).toInt()))
        floatingDockWrapper.addView(clipboardPanel)

        // Tastiera circolare laterale
        keyboardView = AngledKeyboardView(this, this).apply {
            setLeftHanded(isLeftSide)
            setScaleFactor(savedScale)
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

        // Overlay a tutto schermo per il Remote Cursor (Mouse)
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
            keyboardView.setActionIcon(actionSymbol)
        }
        if (::topBarContainer.isInitialized) {
            rebuildTopStrip(emptyList())
        }
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (!::rootFrame.isInitialized || !::floatingDockWrapper.isInitialized) return

        val screenH = rootFrame.height
        if (screenH <= 0) return

        // Non comprime l'app sottostante e lascia tutto il resto dello schermo libero e toccabile!
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

    private fun rebuildTopStrip(swipeSuggestions: List<String>) {
        val density = resources.displayMetrics.density
        topBarContainer.removeAllViews()

        val incBadge = TextView(this).apply {
            text = if (isIncognitoActive()) "🕵️ ON" else "🕵️"
            textSize = 11.5f
            setTextColor(if (isIncognitoActive()) Color.parseColor("#60A5FA") else Color.parseColor("#8E8E93"))
            setPadding((6 * density).toInt(), (3 * density).toInt(), (6 * density).toInt(), (3 * density).toInt())
            setOnClickListener {
                isManualIncognito = !isManualIncognito
                rebuildTopStrip(swipeSuggestions)
            }
        }
        topBarContainer.addView(incBadge)

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        if (isListeningVoice) {
            row.addView(makeChip("🎙️ Stop", true) {
                stopVoiceDictation()
            })
        } else if (swipeSuggestions.isNotEmpty()) {
            for (word in swipeSuggestions) {
                row.addView(makeChip(word, false) {
                    val ic = currentInputConnection ?: return@makeChip
                    val before = ic.getTextBeforeCursor(40, 0)?.toString() ?: ""
                    val trimmed = before.trimEnd()
                    val lastSpace = trimmed.lastIndexOf(' ')
                    val charsToDelete = before.length - (lastSpace + 1)
                    if (charsToDelete > 0) {
                        ic.deleteSurroundingText(charsToDelete, 0)
                    }
                    ic.commitText("$word ", 1)
                    rebuildTopStrip(emptyList())
                })
            }
        } else {
            row.addView(makeChip("🎙️", false) { onStartVoiceDictation() })
            row.addView(makeChip("📋", false) { onOpenClipboard() })
            row.addView(makeChip("🌐", false) { onOpenTranslate() })
            row.addView(makeChip("🖱️", false) { onActivateRemoteCursor() })
        }

        scroll.addView(row)
        topBarContainer.addView(
            scroll,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
    }

    private fun makeChip(label: String, active: Boolean, onClick: () -> Unit): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(if (active) Color.parseColor("#60A5FA") else Color.parseColor("#E5E5EA"))
            setPadding((8 * density).toInt(), (3 * density).toInt(), (8 * density).toInt(), (3 * density).toInt())
            setOnClickListener { onClick() }
        }
    }

    override fun onCommitText(text: String) {
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

    override fun onOpenClipboard() {
        if (clipboardPanel.visibility == View.VISIBLE) {
            clipboardPanel.visibility = View.GONE
            return
        }
        val density = resources.displayMetrics.density
        clipboardListLayout.removeAllViews()

        val header = TextView(this).apply {
            text = if (isIncognitoActive()) {
                "🕵️ Incognito ON: registrazione sospesa"
            } else {
                "📋 Appunti (tocca per incollare)"
            }
            textSize = 11.5f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, 0, 0, (6 * density).toInt())
        }
        clipboardListLayout.addView(header)

        if (clipboardHistory.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Nessun appunto salvato."
                textSize = 12.5f
                setTextColor(Color.WHITE)
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
                    }
                }
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (5 * density).toInt()
                }
                clipboardListLayout.addView(tv, lp)
            }
        }
        clipboardPanel.visibility = View.VISIBLE
    }

    override fun onStartVoiceDictation() {
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
                    rebuildTopStrip(emptyList())
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    isListeningVoice = false
                    rebuildTopStrip(emptyList())
                    Toast.makeText(this@Tastiera45Service, "Dettatura terminata ($error)", Toast.LENGTH_SHORT).show()
                }
                override fun onResults(results: Bundle?) {
                    isListeningVoice = false
                    rebuildTopStrip(emptyList())
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

    private fun stopVoiceDictation() {
        isListeningVoice = false
        speechRecognizer?.stopListening()
        rebuildTopStrip(emptyList())
    }

    override fun onOpenVoicePicker() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    override fun onOpenTranslate() {
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

    override fun onActivateRemoteCursor() {
        remoteCursorOverlay.resetPositions(keyboardView.isLeftHandedMode())
        remoteCursorOverlay.visibility = View.VISIBLE
        rootFrame.requestLayout()
    }

    private fun hideRemoteCursor() {
        remoteCursorOverlay.visibility = View.GONE
        rootFrame.requestLayout()
    }

    override fun onToggleHandSide() {
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
        rebuildTopStrip(candidates)
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

    override fun onResizeScaleChanged(newScale: Float) {
        getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)
            .edit()
            .putFloat("kb_scale", newScale)
            .apply()
        floatingDockWrapper.requestLayout()
    }
}
