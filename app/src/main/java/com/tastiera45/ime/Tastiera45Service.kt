package com.tastiera45.ime

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot

class Tastiera45Service : InputMethodService(), AngledKeyboardView.KeyboardActionListener {

    private lateinit var prefs: SharedPreferences
    private lateinit var rootContainer: FrameLayout
    private lateinit var keyboardView: AngledKeyboardView
    private lateinit var orbView: InternalOrbView
    private lateinit var extraPanelContainer: LinearLayout
    private lateinit var swipeBarContainer: LinearLayout

    private var remoteCursorOverlay: RemoteCursorOverlay? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private var isKeyboard45Visible = true
    private var manualIncognito = false
    private var autoIncognito = false
    private var isListeningVoice = false
    private var isEditingMenu = false
    private var isClipboardMode = false
    private var hoveredMenuId: String? = null
    private val menuOptionViews = mutableListOf<Pair<String, TextView>>()

    private val handler = Handler(Looper.getMainLooper())

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.post {
            if (::keyboardView.isInitialized) {
                applyKeyboardStyle()
                positionFloatingViews()
                syncOrbVisibility()
            }
        }
    }

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (isIncognito()) return@OnPrimaryClipChangedListener
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@OnPrimaryClipChangedListener
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() && text.length <= 2000) {
                val existing = (MenuPrefs.getString(prefs, "saved_clipboard", "") ?: "")
                    .split("\u001E")
                    .filter { it.isNotEmpty() && it != text }
                val updated = (listOf(text) + existing).take(25)
                prefs.edit().putString("saved_clipboard", updated.joinToString("\u001E")).apply()
            }
        } catch (_: Throwable) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.addPrimaryClipChangedListener(clipboardListener)
        } catch (_: Throwable) {
        }
        OrbBridge.activeIme = this
        startOverlayOrbIfPermitted()
    }

    override fun onDestroy() {
        if (OrbBridge.activeIme === this) OrbBridge.activeIme = null
        handler.removeCallbacksAndMessages(null)
        stopVoiceDictation()
        try { prefs.unregisterOnSharedPreferenceChangeListener(prefListener) } catch (_: Throwable) {}
        try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.removePrimaryClipChangedListener(clipboardListener)
        } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun startOverlayOrbIfPermitted() {
        if (Settings.canDrawOverlays(this) && OrbBridge.overlayService == null) {
            try {
                startService(Intent(this, FloatingOrbService::class.java))
            } catch (_: Throwable) {
            }
        }
    }

    override fun onCreateInputView(): View {
        val density = resources.displayMetrics.density
        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        keyboardView = AngledKeyboardView(this, this)
        rootContainer.addView(
            keyboardView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            )
        )

        swipeBarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F208080A"))
                cornerRadius = 12f * density
                setStroke((1f * density).toInt(), Color.parseColor("#484852"))
            }
            setPadding((8 * density).toInt(), (5 * density).toInt(), (8 * density).toInt(), (5 * density).toInt())
        }
        rootContainer.addView(
            swipeBarContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            )
        )

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
        rootContainer.addView(
            extraPanelContainer,
            FrameLayout.LayoutParams(
                (240 * density).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            )
        )

        val orbSize = (46 * density).toInt()
        orbView = InternalOrbView(this)
        rootContainer.addView(
            orbView,
            FrameLayout.LayoutParams(orbSize, orbSize, Gravity.BOTTOM or Gravity.START)
        )

        applyKeyboardStyle()
        syncOrbVisibility()
        rootContainer.post { positionFloatingViews() }
        return rootContainer
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        updateEditorFlags(attribute)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        updateEditorFlags(info)
        startOverlayOrbIfPermitted()
        isKeyboard45Visible = true
        if (::keyboardView.isInitialized) {
            keyboardView.visibility = View.VISIBLE
            applyKeyboardStyle()
            syncOrbVisibility()
            rootContainer.post { positionFloatingViews() }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        stopVoiceDictation()
        if (::extraPanelContainer.isInitialized) extraPanelContainer.visibility = View.GONE
        if (::swipeBarContainer.isInitialized) swipeBarContainer.visibility = View.GONE
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val decorH = window?.window?.decorView?.height ?: resources.displayMetrics.heightPixels
        outInsets.contentTopInsets = decorH
        outInsets.visibleTopInsets = decorH
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION

        val region = outInsets.touchableRegion
        region.setEmpty()

        if (!::rootContainer.isInitialized) return
        val loc = IntArray(2)

        if (::keyboardView.isInitialized && keyboardView.visibility == View.VISIBLE) {
            keyboardView.getLocationInWindow(loc)
            keyboardView.appendContourToRegion(region, loc[0], loc[1])
        }

        if (::orbView.isInitialized && orbView.visibility == View.VISIBLE) {
            orbView.getLocationInWindow(loc)
            region.union(Rect(loc[0], loc[1], loc[0] + orbView.width, loc[1] + orbView.height))
        }

        if (::extraPanelContainer.isInitialized && extraPanelContainer.visibility == View.VISIBLE) {
            extraPanelContainer.getLocationInWindow(loc)
            region.union(Rect(loc[0], loc[1], loc[0] + extraPanelContainer.width, loc[1] + extraPanelContainer.height))
        }

        if (::swipeBarContainer.isInitialized && swipeBarContainer.visibility == View.VISIBLE) {
            swipeBarContainer.getLocationInWindow(loc)
            region.union(Rect(loc[0], loc[1], loc[0] + swipeBarContainer.width, loc[1] + swipeBarContainer.height))
        }

        remoteCursorOverlay?.let { overlay ->
            region.union(overlay.getPuckBounds())
        }
    }

    private fun updateEditorFlags(info: EditorInfo?) {
        if (info == null) return
        val noLearn = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val isPassword = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        autoIncognito = noLearn || isPassword

        val action = when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> "SEARCH"
            EditorInfo.IME_ACTION_SEND -> "SEND"
            EditorInfo.IME_ACTION_DONE -> "DONE"
            EditorInfo.IME_ACTION_NEXT -> "NEXT"
            else -> "ENTER"
        }
        if (::keyboardView.isInitialized) {
            keyboardView.setActionType(action)
        }
        if (::orbView.isInitialized) orbView.invalidate()
    }

    private fun applyKeyboardStyle() {
        if (!::keyboardView.isInitialized) return
        val isArc = MenuPrefs.getBool(prefs, "kb_arc_mode", true)
        val isArcLeft = MenuPrefs.getBool(prefs, "kb_arc_left", false)
        val p = if (isArc) "arc_" else "pop_"

        keyboardView.applyStyle(
            arcMode = isArc,
            arcLeft = isArcLeft,
            scale = MenuPrefs.getFloat(prefs, "${p}scale", if (isArc) 0.86f else 0.88f),
            opacity = MenuPrefs.getFloat(prefs, "${p}opacity", 0.0f),
            tiltDeg = MenuPrefs.getFloat(prefs, "${p}tilt", 0f),
            arcRotDeg = MenuPrefs.getFloat(prefs, "arc_rotation", 0f),
            innerRadPct = MenuPrefs.getFloat(prefs, "arc_inner_radius", 0.34f),
            rowSpacing = MenuPrefs.getFloat(prefs, "arc_row_spacing", 0.35f),
            letterScale = MenuPrefs.getFloat(prefs, "${p}letter_size", 1.0f),
            spacingScale = MenuPrefs.getFloat(prefs, "${p}key_spacing", 0.25f),
            shStrength = MenuPrefs.getFloat(prefs, "${p}shadow_strength", 0.92f),
            shBlur = MenuPrefs.getFloat(prefs, "${p}shadow_blur", 7.0f)
        )
    }

    private fun positionFloatingViews() {
        if (!::rootContainer.isInitialized) return
        val density = resources.displayMetrics.density
        val sw = rootContainer.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val sh = rootContainer.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

        if (::keyboardView.isInitialized) {
            keyboardView.measure(
                View.MeasureSpec.makeMeasureSpec(sw, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.AT_MOST)
            )
            val kw = keyboardView.measuredWidth
            val kh = keyboardView.measuredHeight
            val isArc = keyboardView.isArcLayoutMode()
            val isArcLeft = MenuPrefs.getBool(prefs, "kb_arc_left", false)
            val defaultXPct = if (isArc) (if (isArcLeft) 0.0f else 1.0f) else 0.5f
            val xPct = MenuPrefs.getFloat(prefs, "kb_x_pct", defaultXPct).coerceIn(0f, 1f)
            val bottomOff = MenuPrefs.getInt(prefs, "kb_bottom_offset", 0)
                .coerceIn(0, (sh - kh).coerceAtLeast(0))

            (keyboardView.layoutParams as? FrameLayout.LayoutParams)?.apply {
                leftMargin = ((sw - kw).coerceAtLeast(0) * xPct).toInt()
                bottomMargin = bottomOff
            }
            keyboardView.requestLayout()
        }

        if (::orbView.isInitialized) {
            val orbSize = orbView.layoutParams.width
            val orbXPct = MenuPrefs.getFloat(prefs, "orb_x_pct", 0.96f).coerceIn(0f, 1f)
            val orbBottom = MenuPrefs.getInt(prefs, "orb_bottom_offset", (36 * density).toInt())
                .coerceIn(0, (sh - orbSize).coerceAtLeast(0))
            val orbLeft = ((sw - orbSize).coerceAtLeast(0) * orbXPct).toInt()

            (orbView.layoutParams as? FrameLayout.LayoutParams)?.apply {
                leftMargin = orbLeft
                bottomMargin = orbBottom
            }
            orbView.requestLayout()

            val edge = (6 * density).toInt()
            if (::swipeBarContainer.isInitialized && swipeBarContainer.visibility == View.VISIBLE) {
                swipeBarContainer.measure(
                    View.MeasureSpec.makeMeasureSpec((sw * 0.86f).toInt(), View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val sW = swipeBarContainer.measuredWidth
                val sH = swipeBarContainer.measuredHeight
                (swipeBarContainer.layoutParams as? FrameLayout.LayoutParams)?.apply {
                    leftMargin = (orbLeft + orbSize / 2 - sW / 2).coerceIn(edge, (sw - sW - edge).coerceAtLeast(edge))
                    bottomMargin = (orbBottom + orbSize + (8 * density).toInt()).coerceIn(edge, (sh - sH - edge).coerceAtLeast(edge))
                }
                swipeBarContainer.requestLayout()
            }

            if (::extraPanelContainer.isInitialized && extraPanelContainer.visibility == View.VISIBLE) {
                extraPanelContainer.measure(
                    View.MeasureSpec.makeMeasureSpec((240 * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.AT_MOST)
                )
                val mW = extraPanelContainer.measuredWidth
                val mH = extraPanelContainer.measuredHeight
                (extraPanelContainer.layoutParams as? FrameLayout.LayoutParams)?.apply {
                    leftMargin = if (orbLeft + mW <= sw - edge) orbLeft else (orbLeft + orbSize - mW).coerceIn(edge, (sw - mW - edge).coerceAtLeast(edge))
                    bottomMargin = (orbBottom + orbSize + (8 * density).toInt()).coerceIn(edge, (sh - mH - edge).coerceAtLeast(edge))
                }
                extraPanelContainer.requestLayout()
            }
        }
    }

    // =========================================================================
    // API PUBBLICHE PER ORB BRIDGE & FLOATING ORB SERVICE
    // =========================================================================

    fun syncOrbVisibility() {
        if (!::orbView.isInitialized) return
        val hasGlobalOverlay = OrbBridge.overlayService != null
        orbView.visibility = if (hasGlobalOverlay) View.GONE else View.VISIBLE
        if (hasGlobalOverlay && ::extraPanelContainer.isInitialized) {
            extraPanelContainer.visibility = View.GONE
        }
        orbView.invalidate()
    }

    fun isIncognito(): Boolean = manualIncognito || autoIncognito

    fun isKeyboardShown(): Boolean = isKeyboard45Visible && ::keyboardView.isInitialized && keyboardView.visibility == View.VISIBLE

    fun setKeyboard45Visible(visible: Boolean) {
        isKeyboard45Visible = visible
        if (::keyboardView.isInitialized) {
            keyboardView.visibility = if (visible) View.VISIBLE else View.GONE
            if (visible) {
                applyKeyboardStyle()
                positionFloatingViews()
            }
        }
    }

    fun toggleManualIncognito() {
        manualIncognito = !manualIncognito
        if (::orbView.isInitialized) orbView.invalidate()
        OrbBridge.overlayService?.refreshPositionAndVisuals()
        Toast.makeText(
            this,
            if (manualIncognito) "Modalità Incognito attivata" else "Modalità Incognito disattivata",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun switchToSamsungKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val samsungId = imm.enabledInputMethodList.firstOrNull {
            it.packageName.contains("honeyboard", ignoreCase = true) ||
                    it.packageName.contains("samsung", ignoreCase = true)
        }?.id

        if (samsungId != null) {
            try {
                switchInputMethod(samsungId)
                return
            } catch (_: Throwable) {
            }
        }
        if (!switchToNextInputMethod(false)) {
            try { imm.showInputMethodPicker() } catch (_: Throwable) {}
        }
    }

    fun showAndroidImePicker() {
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showInputMethodPicker()
        } catch (_: Throwable) {
        }
    }

    fun switchToNotuneVoice() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (imm != null) {
            val notuneImi = imm.enabledInputMethodList.firstOrNull {
                it.packageName == MenuPrefs.NOTUNE_PKG ||
                        it.packageName.contains("notune", ignoreCase = true) ||
                        it.packageName.contains("transcribe", ignoreCase = true)
            }
            if (notuneImi != null) {
                var compactSubtype: InputMethodSubtype? = null
                for (i in 0 until notuneImi.subtypeCount) {
                    val st = notuneImi.getSubtypeAt(i)
                    val label = st.getDisplayName(
                        this,
                        notuneImi.packageName,
                        notuneImi.serviceInfo.applicationInfo
                    ).toString()
                    val meta = "${st.mode} ${st.extraValue} $label".lowercase()
                    if (meta.contains("compact") || meta.contains("compatta") ||
                        meta.contains("mini") || meta.contains("float") ||
                        meta.contains("overlay") || meta.contains("pill") || meta.contains("bar")
                    ) {
                        compactSubtype = st
                        break
                    }
                }
                try {
                    if (compactSubtype != null) {
                        switchInputMethod(notuneImi.id, compactSubtype)
                    } else {
                        switchInputMethod(notuneImi.id)
                    }
                    return
                } catch (_: Throwable) {
                }
            }
        }
        startVoiceDictation()
    }

    fun replaceLastWordWith(word: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(40, 0)?.toString().orEmpty()
        val trimmed = before.trimEnd(' ')
        val trailingSpaces = before.length - trimmed.length
        val lastWord = trimmed.substringAfterLast(' ').substringAfterLast('\n')
        if (lastWord.isNotEmpty()) {
            ic.deleteSurroundingText(lastWord.length + trailingSpaces, 0)
            ic.commitText("$word ", 1)
            if (::keyboardView.isInitialized) {
                keyboardView.learnWord(word, isIncognito())
            }
        }
    }

    fun openProcessTextChooser(title: String, initialText: String?) {
        val selected = initialText ?: currentInputConnection?.getSelectedText(0)?.toString().orEmpty()
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, selected)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(Intent.createChooser(intent, title).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Throwable) {
            Toast.makeText(this, "Nessuna app di traduzione disponibile", Toast.LENGTH_SHORT).show()
        }
    }

    fun runCustomShortcut(id: String) {
        val sc = MenuPrefs.loadShortcuts(prefs).firstOrNull { it.id == id } ?: return
        when (sc.type) {
            "TEXT" -> onCommitText(sc.payload)
            "INTENT_URI" -> try {
                startActivity(Intent.parseUri(sc.payload, Intent.URI_INTENT_SCHEME).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Throwable) {}
            "APP" -> try {
                packageManager.getLaunchIntentForPackage(sc.payload)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }?.let { startActivity(it) }
            } catch (_: Throwable) {}
        }
    }

    // =========================================================================
    // DETTATURA VOCALE (Fallback integrato)
    // =========================================================================

    fun startVoiceDictation() {
        if (isListeningVoice) {
            stopVoiceDictation()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Concedi il permesso Microfono nell'app Tastiera 45°", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            } catch (_: Throwable) {}
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Riconoscimento vocale non disponibile", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListeningVoice = true
                        showVoiceStatusBar("🎙 In ascolto...")
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        showVoiceStatusBar("⏳ Elaborazione...")
                    }
                    override fun onError(error: Int) {
                        stopVoiceDictation()
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spoken = matches?.firstOrNull()?.trim().orEmpty()
                        if (spoken.isNotEmpty()) {
                            onCommitText("$spoken ")
                        }
                        stopVoiceDictation()
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!partial.isNullOrBlank()) {
                            showVoiceStatusBar("🎙 $partial")
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            speechRecognizer?.startListening(intent)
        } catch (_: Throwable) {
            stopVoiceDictation()
        }
    }

    private fun stopVoiceDictation() {
        isListeningVoice = false
        try { speechRecognizer?.stopListening() } catch (_: Throwable) {}
        try { speechRecognizer?.destroy() } catch (_: Throwable) {}
        speechRecognizer = null
        if (::swipeBarContainer.isInitialized) {
            swipeBarContainer.visibility = View.GONE
        }
    }

    private fun showVoiceStatusBar(status: String) {
        if (!::swipeBarContainer.isInitialized) return
        val density = resources.displayMetrics.density
        swipeBarContainer.removeAllViews()
        swipeBarContainer.addView(TextView(this).apply {
            text = status
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding((6 * density).toInt(), (2 * density).toInt(), (8 * density).toInt(), (2 * density).toInt())
        })
        swipeBarContainer.addView(TextView(this).apply {
            text = "✕"
            textSize = 12f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding((8 * density).toInt(), (2 * density).toInt(), (4 * density).toInt(), (2 * density).toInt())
            setOnClickListener { stopVoiceDictation() }
        })
        swipeBarContainer.visibility = View.VISIBLE
        positionFloatingViews()
    }

    // =========================================================================
    // CALLBACK TASTIERA (KeyboardActionListener)
    // =========================================================================

    override fun onCommitText(text: String) {
        if (::extraPanelContainer.isInitialized && extraPanelContainer.visibility == View.VISIBLE && !isEditingMenu) {
            extraPanelContainer.visibility = View.GONE
        }
        if (!isListeningVoice && ::swipeBarContainer.isInitialized && swipeBarContainer.visibility == View.VISIBLE) {
            swipeBarContainer.visibility = View.GONE
        }
        OrbBridge.overlayService?.hidePopupsOnTyping()

        val ic = currentInputConnection ?: return
        if (text == " " && ::keyboardView.isInitialized) {
            val before = ic.getTextBeforeCursor(32, 0)?.toString().orEmpty()
            val lastWord = before.substringAfterLast(' ').substringAfterLast('\n')
            if (lastWord.length >= 2) {
                keyboardView.learnWord(lastWord, isIncognito())
            }
        } else if (text.trim().length >= 2 && text.endsWith(" ") && ::keyboardView.isInitialized) {
            keyboardView.learnWord(text.trim(), isIncognito())
        }
        ic.commitText(text, 1)
    }

    override fun onDeleteChar() {
        if (::extraPanelContainer.isInitialized && extraPanelContainer.visibility == View.VISIBLE && !isEditingMenu) {
            extraPanelContainer.visibility = View.GONE
        }
        OrbBridge.overlayService?.hidePopupsOnTyping()
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
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
    }

    override fun onSwitchIme() {
        if (!switchToNextInputMethod(false)) {
            try {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
            } catch (_: Throwable) {
            }
        }
    }

    override fun onMoveTextCursor(deltaXSteps: Int, deltaYSteps: Int) {
        val ic = currentInputConnection ?: return
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        if (extracted != null && extracted.text != null) {
            val fullText = extracted.text.toString()
            var pos = extracted.selectionEnd.coerceIn(0, fullText.length)
            if (deltaXSteps != 0) {
                pos = (pos + deltaXSteps).coerceIn(0, fullText.length)
            }
            if (deltaYSteps != 0) {
                val lines = fullText.split('\n')
                var charCount = 0
                var curLine = 0
                var curCol = 0
                for (i in lines.indices) {
                    val lineLen = lines[i].length
                    if (pos <= charCount + lineLen) {
                        curLine = i
                        curCol = pos - charCount
                        break
                    }
                    charCount += lineLen + 1
                }
                val targetLine = (curLine + deltaYSteps).coerceIn(0, lines.lastIndex)
                var newPos = 0
                for (i in 0 until targetLine) newPos += lines[i].length + 1
                newPos += curCol.coerceAtMost(lines[targetLine].length)
                pos = newPos.coerceIn(0, fullText.length)
            }
            ic.setSelection(pos, pos)
        } else if (deltaXSteps != 0) {
            val before = ic.getTextBeforeCursor(64, 0)?.length ?: 0
            val after = ic.getTextAfterCursor(64, 0)?.length ?: 0
            val shift = deltaXSteps.coerceIn(-before, after)
            if (shift != 0) {
                val newCursor = before + shift
                ic.setSelection(newCursor, newCursor)
            }
        }
    }

    override fun onSwipeWordDecoded(candidates: List<String>) {
        if (candidates.size <= 1) return
        val overlay = OrbBridge.overlayService
        if (overlay != null) {
            overlay.showSwipeCandidates(candidates)
            return
        }
        if (!::swipeBarContainer.isInitialized) return
        val density = resources.displayMetrics.density
        swipeBarContainer.removeAllViews()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        candidates.forEachIndexed { idx, word ->
            row.addView(TextView(this).apply {
                text = word
                textSize = 13f
                typeface = if (idx == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(Color.WHITE)
                setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt())
                setOnClickListener {
                    replaceLastWordWith(word)
                    swipeBarContainer.visibility = View.GONE
                }
            })
        }
        row.addView(TextView(this).apply {
            text = "✕"
            textSize = 11.5f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding((8 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
            setOnClickListener { swipeBarContainer.visibility = View.GONE }
        })
        swipeBarContainer.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
        swipeBarContainer.visibility = View.VISIBLE
        positionFloatingViews()
    }

    override fun onDragFloatingKeyboard(deltaScreenX: Float, deltaScreenY: Float) {
        if (!::keyboardView.isInitialized || !::rootContainer.isInitialized) return
        val sw = rootContainer.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val sh = rootContainer.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val lp = keyboardView.layoutParams as? FrameLayout.LayoutParams ?: return
        val maxLeft = (sw - keyboardView.width).coerceAtLeast(1)
        val maxBottom = (sh - keyboardView.height).coerceAtLeast(0)

        val newLeft = (lp.leftMargin + deltaScreenX.toInt()).coerceIn(0, maxLeft)
        val newBottom = (lp.bottomMargin - deltaScreenY.toInt()).coerceIn(0, maxBottom)
        lp.leftMargin = newLeft
        lp.bottomMargin = newBottom
        keyboardView.requestLayout()

        prefs.edit()
            .putFloat("kb_x_pct", (newLeft.toFloat() / maxLeft).coerceIn(0f, 1f))
            .putInt("kb_bottom_offset", newBottom)
            .apply()
    }

    // =========================================================================
    // TASTO MAGICO INTERNO (Fallback quando l'overlay globale è disattivo)
    // =========================================================================

    private inner class InternalOrbView(context: Context) : View(context) {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startLeft = 0
        private var startBottom = 0
        private var held = false
        private var dragged = false

        private val holdTask = Runnable {
            held = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            openInternalMenu()
        }

        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EE08080A") }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = (minOf(width, height) / 2f) - 2f
            if (r <= 0f) return
            val incog = isIncognito()
            border.color = when {
                isEditingMenu -> Color.parseColor("#D0BCFF")
                incog -> Color.WHITE
                else -> Color.parseColor("#606068")
            }
            border.strokeWidth = if (incog || isEditingMenu) 3.2f else 2.0f
            canvas.drawCircle(cx, cy, r, bg)
            canvas.drawCircle(cx, cy, r, border)
            text.textSize = r * 0.85f
            canvas.drawText("✦", cx, cy - (text.descent() + text.ascent()) / 2f, text)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val density = resources.displayMetrics.density
            val lp = layoutParams as? FrameLayout.LayoutParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    startLeft = lp.leftMargin; startBottom = lp.bottomMargin
                    held = false; dragged = false
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (!isEditingMenu) handler.postDelayed(holdTask, 150L)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isEditingMenu) {
                        val sw = rootContainer.width.coerceAtLeast(320)
                        val sh = rootContainer.height.coerceAtLeast(320)
                        val maxL = (sw - width).coerceAtLeast(1)
                        val maxB = (sh - height).coerceAtLeast(0)
                        lp.leftMargin = (startLeft + (event.rawX - downRawX).toInt()).coerceIn(0, maxL)
                        lp.bottomMargin = (startBottom - (event.rawY - downRawY).toInt()).coerceIn(0, maxB)
                        requestLayout()
                        positionFloatingViews()
                        return true
                    }
                    if (!dragged && hypot(event.rawX - downRawX, event.rawY - downRawY) > 8f * density) {
                        dragged = true
                        handler.removeCallbacks(holdTask)
                        openInternalMenu()
                    }
                    if (dragged || held) updateInternalHover(event.rawX, event.rawY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(holdTask)
                    if (isEditingMenu) {
                        val sw = rootContainer.width.coerceAtLeast(320)
                        val maxL = (sw - width).coerceAtLeast(1)
                        prefs.edit()
                            .putFloat("orb_x_pct", (lp.leftMargin.toFloat() / maxL).coerceIn(0f, 1f))
                            .putInt("orb_bottom_offset", lp.bottomMargin)
                            .apply()
                        return true
                    }
                    if (dragged || held) {
                        updateInternalHover(event.rawX, event.rawY)
                        val chosen = hoveredMenuId
                        closeInternalMenu()
                        if (chosen != null) executeInternalAction(chosen)
                    } else {
                        if (extraPanelContainer.visibility == View.VISIBLE) closeInternalMenu() else openInternalMenu()
                    }
                }
            }
            return true
        }
    }

    private fun openInternalMenu() {
        isEditingMenu = false
        isClipboardMode = false
        rebuildInternalMenu()
        extraPanelContainer.visibility = View.VISIBLE
        positionFloatingViews()
    }

    private fun closeInternalMenu() {
        isEditingMenu = false
        isClipboardMode = false
        hoveredMenuId = null
        menuOptionViews.clear()
        extraPanelContainer.visibility = View.GONE
        orbView.invalidate()
    }

    private fun rebuildInternalMenu() {
        val density = resources.displayMetrics.density
        extraPanelContainer.removeAllViews()
        menuOptionViews.clear()
        hoveredMenuId = null

        if (isClipboardMode) {
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding((6 * density).toInt(), (2 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            }
            header.addView(TextView(this).apply {
                text = "Appunti"; textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#8E8E93"))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(TextView(this).apply {
                text = "✕"; textSize = 13f; setTextColor(Color.WHITE)
                setPadding((8 * density).toInt(), (2 * density).toInt(), (4 * density).toInt(), (2 * density).toInt())
                setOnClickListener { closeInternalMenu() }
            })
            extraPanelContainer.addView(header)

            val items = (MenuPrefs.getString(prefs, "saved_clipboard", "") ?: "").split("\u001E").filter { it.isNotEmpty() }
            if (items.isEmpty()) {
                extraPanelContainer.addView(TextView(this).apply {
                    text = "Nessun testo negli appunti."; textSize = 12.5f; setTextColor(Color.WHITE)
                    setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
                })
            } else {
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                for (item in items) {
                    col.addView(TextView(this).apply {
                        text = item; maxLines = 2; textSize = 12.5f; setTextColor(Color.WHITE)
                        setBackgroundColor(Color.parseColor("#16161A"))
                        setPadding((8 * density).toInt(), (7 * density).toInt(), (8 * density).toInt(), (7 * density).toInt())
                        setOnClickListener {
                            onCommitText(item)
                            closeInternalMenu()
                        }
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (4 * density).toInt() })
                }
                extraPanelContainer.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(-1, (150 * density).toInt()))
            }
            return
        }

        val entries = MenuPrefs.getEntries(
            p = prefs,
            isKbVisible = isKeyboardShown(),
            isArcMode = MenuPrefs.getBool(prefs, "kb_arc_mode", true),
            isListening = isListeningVoice,
            isMouseOpen = remoteCursorOverlay != null,
            isIncognito = isIncognito()
        )

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * density).toInt(), (3 * density).toInt(), (4 * density).toInt(), (5 * density).toInt())
        }
        header.addView(TextView(this).apply {
            text = if (isEditingMenu) "Modifica / Sposta ✦" else "Menu ✦"
            textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#8E8E93"))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = if (isEditingMenu) "✓ Fine" else "✎ Edit"
            textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isEditingMenu) Color.BLACK else Color.WHITE)
            background = GradientDrawable().apply {
                setColor(if (isEditingMenu) Color.WHITE else Color.parseColor("#242429"))
                cornerRadius = 7f * density
            }
            setPadding((8 * density).toInt(), (3 * density).toInt(), (8 * density).toInt(), (3 * density).toInt())
            setOnClickListener {
                isEditingMenu = !isEditingMenu
                orbView.invalidate()
                rebuildInternalMenu()
                positionFloatingViews()
            }
        })
        extraPanelContainer.addView(header)

        if (isEditingMenu) {
            val listCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            entries.forEachIndexed { idx, entry ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply { setColor(Color.parseColor("#141418")); cornerRadius = 8f * density }
                    setPadding((6 * density).toInt(), (5 * density).toInt(), (4 * density).toInt(), (5 * density).toInt())
                }
                val toggle: (View) -> Unit = {
                    MenuPrefs.toggleVisibility(prefs, entry.id)
                    rebuildInternalMenu()
                }
                row.addView(TextView(this).apply {
                    text = if (entry.enabled) "☑" else "☐"; textSize = 15f
                    setTextColor(if (entry.enabled) Color.WHITE else Color.parseColor("#6E6E73"))
                    setPadding((4 * density).toInt(), 0, (6 * density).toInt(), 0)
                    setOnClickListener(toggle)
                })
                row.addView(TextView(this).apply {
                    text = entry.title; textSize = 12f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    setTextColor(if (entry.enabled) Color.WHITE else Color.parseColor("#6E6E73"))
                    setOnClickListener(toggle)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(TextView(this).apply {
                    text = "▲"; textSize = 11.5f
                    setTextColor(if (idx > 0) Color.WHITE else Color.parseColor("#3A3A40"))
                    setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
                    setOnClickListener { if (idx > 0) { MenuPrefs.moveItem(prefs, entry.id, -1); rebuildInternalMenu() } }
                })
                row.addView(TextView(this).apply {
                    text = "▼"; textSize = 11.5f
                    setTextColor(if (idx < entries.lastIndex) Color.WHITE else Color.parseColor("#3A3A40"))
                    setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
                    setOnClickListener { if (idx < entries.lastIndex) { MenuPrefs.moveItem(prefs, entry.id, +1); rebuildInternalMenu() } }
                })
                listCol.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (3 * density).toInt() })
            }
            extraPanelContainer.addView(ScrollView(this).apply { addView(listCol) }, LinearLayout.LayoutParams(-1, (240 * density).toInt()))
        } else {
            val visible = entries.filter { it.enabled }
            visible.forEachIndexed { idx, entry ->
                val tv = TextView(this).apply {
                    text = entry.title; textSize = 13f; setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = 8f * density }
                    setPadding((10 * density).toInt(), (7 * density).toInt(), (10 * density).toInt(), (7 * density).toInt())
                    setOnClickListener {
                        closeInternalMenu()
                        executeInternalAction(entry.id)
                    }
                }
                extraPanelContainer.addView(tv, LinearLayout.LayoutParams(-1, -2).apply { if (idx < visible.lastIndex) bottomMargin = (2 * density).toInt() })
                menuOptionViews.add(entry.id to tv)
            }
        }
    }

    private fun updateInternalHover(rawX: Float, rawY: Float) {
        if (extraPanelContainer.visibility != View.VISIBLE || isEditingMenu) return
        val density = resources.displayMetrics.density
        val loc = IntArray(2)
        var match: String? = null
        for ((id, view) in menuOptionViews) {
            view.getLocationOnScreen(loc)
            val inside = rawX in loc[0].toFloat()..(loc[0] + view.width).toFloat() &&
                    rawY in loc[1].toFloat()..(loc[1] + view.height).toFloat()
            if (inside) match = id
            view.background = GradientDrawable().apply { setColor(if (inside) Color.WHITE else Color.TRANSPARENT); cornerRadius = 8f * density }
            view.setTextColor(if (inside) Color.BLACK else Color.WHITE)
        }
        if (match != null && match != hoveredMenuId) orbView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        hoveredMenuId = match
    }

    private fun executeInternalAction(id: String) {
        when (id) {
            "PICK_IME" -> showAndroidImePicker()
            "LAUNCH_KB_45" -> setKeyboard45Visible(true)
            "LAUNCH_KB_SAMSUNG" -> switchToSamsungKeyboard()
            "TOGGLE_KB" -> setKeyboard45Visible(!isKeyboardShown())
            "SWITCH_STYLE" -> {
                val nextArc = !MenuPrefs.getBool(prefs, "kb_arc_mode", true)
                prefs.edit().putBoolean("kb_arc_mode", nextArc).apply()
                setKeyboard45Visible(true)
            }
            "VOICE" -> switchToNotuneVoice()
            "MOUSE" -> toggleInternalCursor()
            "CLIPBOARD" -> {
                isClipboardMode = true
                rebuildInternalMenu()
                extraPanelContainer.visibility = View.VISIBLE
                positionFloatingViews()
            }
            "TRANSLATE" -> openProcessTextChooser("Traduci testo", null)
            "INCOGNITO" -> toggleManualIncognito()
            "SETTINGS" -> try {
                startActivity(Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            } catch (_: Throwable) {}
            else -> runCustomShortcut(id)
        }
    }

    private fun toggleInternalCursor() {
        val existing = remoteCursorOverlay
        if (existing != null) {
            rootContainer.removeView(existing)
            remoteCursorOverlay = null
            return
        }
        val overlay = RemoteCursorOverlay(
            context = this,
            onClose = {
                remoteCursorOverlay?.let { rootContainer.removeView(it) }
                remoteCursorOverlay = null
            },
            onRequestOpenAccessibility = {
                Toast.makeText(this, "Attiva 'Tastiera 45° - Remote Cursor' in Accessibilità", Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                } catch (_: Throwable) {}
            },
            onTouchableRegionChanged = {}
        )
        rootContainer.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        overlay.resetPositions(MenuPrefs.getFloat(prefs, "orb_x_pct", 0.96f) < 0.5f)
        remoteCursorOverlay = overlay
    }
}
