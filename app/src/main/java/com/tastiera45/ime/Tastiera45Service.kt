package com.tastiera45.ime

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Region
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.net.Uri
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
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
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

internal object MenuPrefs {
    const val NOTUNE_PKG = "dev.notune.transcribe"
    const val NOTUNE_PREF_KEY = "IME_PKG:dev.notune.transcribe"

    val BUILTIN_IDS = listOf(
        "LAUNCH_KB_45", "LAUNCH_KB_SAMSUNG", "TOGGLE_KB", "SWITCH_STYLE",
        "VOICE", "MOUSE", "CLIPBOARD", "TRANSLATE", "INCOGNITO", "SETTINGS"
    )

    data class Shortcut(val id: String, val type: String, val label: String, val payload: String)
    data class Entry(val id: String, val title: String, val enabled: Boolean)

    fun getFloat(p: SharedPreferences, k: String, d: Float) = try { p.getFloat(k, d) } catch (_: Throwable) { d }
    fun getInt(p: SharedPreferences, k: String, d: Int) = try { p.getInt(k, d) } catch (_: Throwable) { d }
    fun getBool(p: SharedPreferences, k: String, d: Boolean) = try { p.getBoolean(k, d) } catch (_: Throwable) { d }
    fun getString(p: SharedPreferences, k: String, d: String?) = try { p.getString(k, d) } catch (_: Throwable) { d }

    fun loadShortcuts(p: SharedPreferences): List<Shortcut> {
        val raw = getString(p, "custom_shortcuts_v2", "") ?: ""
        if (raw.isNotBlank()) {
            return raw.split("\u001E").mapNotNull { entry ->
                val parts = entry.split("\u001F")
                if (parts.size == 4 && parts[2].isNotBlank()) Shortcut(parts[0], parts[1], parts[2], parts[3]) else null
            }
        }
        val old = getString(p, "custom_shortcuts", "") ?: ""
        if (old.isBlank()) return emptyList()
        val migrated = old.split("\u001E").mapIndexedNotNull { idx, entry ->
            val parts = entry.split("\u001F")
            when {
                parts.size == 3 && parts[1].isNotBlank() -> Shortcut("sc_m_$idx", parts[0], parts[1], parts[2])
                parts.size == 2 && parts[0].isNotBlank() -> Shortcut("sc_m_$idx", "TEXT", parts[0], parts[1])
                else -> null
            }
        }
        if (migrated.isNotEmpty()) saveShortcuts(p, migrated)
        return migrated
    }

    fun saveShortcuts(p: SharedPreferences, list: List<Shortcut>) {
        val encoded = list.joinToString("\u001E") { "${it.id}\u001F${it.type}\u001F${it.label}\u001F${it.payload}" }
        p.edit().putString("custom_shortcuts_v2", encoded).apply()
    }

    fun getEntries(
        p: SharedPreferences,
        isKbVisible: Boolean = true,
        isArcMode: Boolean = true,
        isListening: Boolean = false,
        isMouseOpen: Boolean = false,
        isIncognito: Boolean = false,
        forSettingsScreen: Boolean = false
    ): List<Entry> {
        val shortcuts = loadShortcuts(p)
        val scMap = shortcuts.associateBy { it.id }
        val validIds = BUILTIN_IDS + shortcuts.map { it.id }

        val savedOrder = (getString(p, "orb_menu_order", "") ?: "").split(",").filter { it in validIds }
        val orderedIds = (savedOrder + validIds).distinct()
        val hiddenSet = (getString(p, "orb_menu_hidden", "") ?: "").split(",").filter { it.isNotBlank() }.toSet()

        return orderedIds.map { id ->
            val title = if (forSettingsScreen) {
                when (id) {
                    "LAUNCH_KB_45" -> "⌨  Lancia Tastiera 45° (Arco / Pop-up)"
                    "LAUNCH_KB_SAMSUNG" -> "⌨  Lancia Tastiera Samsung"
                    "TOGGLE_KB" -> "⊞  Apri / Chiudi Tastiera Attiva"
                    "SWITCH_STYLE" -> "◐  Cambia Stile (Arco / Pop-up)"
                    "VOICE" -> "◉  Dettatura Vocale (Notune Transcribe)"
                    "MOUSE" -> "⌖  Mouse (Remote Cursor)"
                    "CLIPBOARD" -> "⎘  Appunti"
                    "TRANSLATE" -> "文  Traduci"
                    "INCOGNITO" -> "◌  Modalità Incognito"
                    "SETTINGS" -> "⚙  Impostazioni App"
                    else -> {
                        val sc = scMap[id]
                        val icon = when (sc?.type) { "SAMSUNG_ASSIST" -> "✦"; "INTENT_URI" -> "↗"; "APP" -> "▣"; else -> "›" }
                        "$icon  ${sc?.label ?: id}"
                    }
                }
            } else {
                when (id) {
                    "LAUNCH_KB_45" -> "⌨   Tastiera 45°"
                    "LAUNCH_KB_SAMSUNG" -> "⌨   Tastiera Samsung"
                    "TOGGLE_KB" -> if (isKbVisible) "✕   Chiudi tastiera" else "⊞   Mostra tastiera"
                    "SWITCH_STYLE" -> if (isArcMode) "◐   Stile: Pop-up Scolpito" else "◑   Stile: Arco Concentrico"
                    "VOICE" -> if (isListening) "■   Ferma dettatura" else "◉   Dettatura"
                    "MOUSE" -> if (isMouseOpen) "✕   Chiudi mouse" else "⌖   Mouse"
                    "CLIPBOARD" -> "⎘   Appunti"
                    "TRANSLATE" -> "文   Traduci"
                    "INCOGNITO" -> if (isIncognito) "●   Incognito ON" else "◌   Incognito OFF"
                    "SETTINGS" -> "⚙   Impostazioni"
                    else -> {
                        val sc = scMap[id]
                        val icon = when (sc?.type) { "SAMSUNG_ASSIST" -> "✦"; "INTENT_URI" -> "↗"; "APP" -> "▣"; else -> "›" }
                        "$icon   ${sc?.label ?: id}"
                    }
                }
            }
            Entry(id, title, id !in hiddenSet)
        }
    }

    fun toggleVisibility(p: SharedPreferences, id: String) {
        val set = (getString(p, "orb_menu_hidden", "") ?: "").split(",").filter { it.isNotBlank() }.toMutableSet()
        if (!set.add(id)) set.remove(id)
        p.edit().putString("orb_menu_hidden", set.joinToString(",")).apply()
    }

    fun moveItem(p: SharedPreferences, id: String, delta: Int) {
        val list = getEntries(p).map { it.id }.toMutableList()
        val i = list.indexOf(id)
        val target = i + delta
        if (i >= 0 && target in list.indices) {
            list.add(target, list.removeAt(i))
            p.edit().putString("orb_menu_order", list.joinToString(",")).apply()
        }
    }

    fun isPackageInstalled(ctx: Context, pkg: String): Boolean {
        return try {
            ctx.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }
}

/**
 * Ponte in-process tra il servizio IME (Tastiera45Service) e l'overlay persistente (FloatingOrbService).
 */
internal object OrbBridge {
    var activeIme: Tastiera45Service? = null
    var overlayService: FloatingOrbService? = null
    var lastSwipeCandidates: List<String> = emptyList()

    fun isOverlayActive(): Boolean = overlayService != null

    fun notifySwipeCandidates(candidates: List<String>) {
        lastSwipeCandidates = candidates
        if (overlayService != null) {
            overlayService?.showSwipeCandidates(candidates)
        } else {
            activeIme?.showLocalSwipeCandidates(candidates)
        }
    }

    fun applySwipeCandidate(word: String) {
        activeIme?.replaceLastWordWith(word)
        lastSwipeCandidates = emptyList()
    }

    fun refreshOverlayState() {
        overlayService?.refreshPositionAndVisuals()
        activeIme?.syncOrbVisibility()
    }
}

class Tastiera45Service : InputMethodService(), AngledKeyboardView.KeyboardActionListener {

    private lateinit var prefs: SharedPreferences
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
    private var isEditingMenu = false

    private var isKeyboardVisible = true
    private var isAutoIncognito = false
    private var isManualIncognito = false
    private val clipboardHistory = mutableListOf<String>()
    private var clipboardManager: ClipboardManager? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningVoice = false
    private var cursorPadRect = Rect()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var orbXPct = 0.96f
    private var orbBottomPx = 0
    private var kbXPct = 1.0f
    private var kbBottomPx = 0

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { syncClipboard() }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        mainHandler.post {
            if (::keyboardView.isInitialized && ::rootFrame.isInitialized) {
                applySavedConfig()
                applyPositions()
                rootFrame.post { applyPositions() }
            }
        }
    }

    fun isIncognito() = isAutoIncognito || isManualIncognito
    fun isKeyboardShown() = isKeyboardVisible

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreate() {
        super.onCreate()
        OrbBridge.activeIme = this
        prefs = getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        // Avvia automaticamente l'overlay persistente su tutte le schermate se il permesso è attivo
        if (Settings.canDrawOverlays(this)) {
            try { startService(Intent(this, FloatingOrbService::class.java)) } catch (_: Throwable) {}
        }

        val raw = MenuPrefs.getString(prefs, "saved_clipboard", "") ?: ""
        if (raw.isNotEmpty()) clipboardHistory.addAll(raw.split("\u001E").filter { it.isNotEmpty() })

        try {
            clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboardManager?.addPrimaryClipChangedListener(clipListener)
            syncClipboard()
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        if (OrbBridge.activeIme === this) OrbBridge.activeIme = null
        mainHandler.removeCallbacksAndMessages(null)
        try { prefs.unregisterOnSharedPreferenceChangeListener(prefListener) } catch (_: Throwable) {}
        try { clipboardManager?.removePrimaryClipChangedListener(clipListener) } catch (_: Throwable) {}
        try { speechRecognizer?.destroy() } catch (_: Throwable) {}
        super.onDestroy()
    }

    fun syncOrbVisibility() {
        if (!::cornerOrbView.isInitialized) return
        // Se l'overlay di sistema è attivo su tutte le schermate, nascondi il duplicato interno
        cornerOrbView.visibility = if (OrbBridge.isOverlayActive()) View.GONE else View.VISIBLE
        if (::rootFrame.isInitialized) rootFrame.requestLayout()
    }

    private fun syncClipboard() {
        if (isIncognito()) return
        try {
            val clip = clipboardManager?.primaryClip ?: return
            var added = false
            for (i in (clip.itemCount - 1) downTo 0) {
                val text = clip.getItemAt(i)?.coerceToText(this)?.toString()?.trim()
                if (!text.isNullOrEmpty() && clipboardHistory.firstOrNull() != text) {
                    clipboardHistory.remove(text)
                    clipboardHistory.add(0, text)
                    added = true
                }
            }
            while (clipboardHistory.size > 30) clipboardHistory.removeAt(clipboardHistory.lastIndex)
            if (added) prefs.edit().putString("saved_clipboard", clipboardHistory.joinToString("\u001E")).apply()
        } catch (_: Throwable) {
        }
    }

    private fun applySavedConfig() {
        val density = resources.displayMetrics.density
        orbXPct = MenuPrefs.getFloat(prefs, "orb_x_pct", 0.96f)
        orbBottomPx = MenuPrefs.getInt(prefs, "orb_bottom_offset", (36 * density).toInt())

        val isArc = MenuPrefs.getBool(prefs, "kb_arc_mode", true)
        val isLeft = MenuPrefs.getBool(prefs, "arc_left_side", MenuPrefs.getBool(prefs, "kb_arc_left", false))
        val p = if (isArc) "arc_" else "popup_"

        kbXPct = MenuPrefs.getFloat(prefs, "${p}x_pct", if (isArc) (if (isLeft) 0.0f else 1.0f) else 0.50f)
        kbBottomPx = MenuPrefs.getInt(prefs, "${p}bottom_offset", (36 * density).toInt())

        keyboardView.applyStyle(
            arcMode = isArc,
            arcLeft = isLeft,
            scale = MenuPrefs.getFloat(prefs, "${p}scale", if (isArc) 0.86f else 0.82f),
            opacity = MenuPrefs.getFloat(prefs, "${p}alpha", 0.0f),
            tiltDeg = MenuPrefs.getFloat(prefs, "${p}tilt", 0f),
            arcRotDeg = if (isArc) MenuPrefs.getFloat(prefs, "arc_rotation", 0f) else 0f,
            innerRadPct = if (isArc) MenuPrefs.getFloat(prefs, "arc_inner_radius", 0.34f) else 0.34f,
            rowSpacing = if (isArc) MenuPrefs.getFloat(prefs, "arc_row_spacing", 0.35f) else 0.35f,
            letterScale = MenuPrefs.getFloat(prefs, "${p}letter_size", 1.0f),
            spacingScale = MenuPrefs.getFloat(prefs, "${p}key_spacing", 0.25f),
            shStrength = MenuPrefs.getFloat(prefs, "${p}shadow_strength", 0.92f),
            shBlur = MenuPrefs.getFloat(prefs, "${p}shadow_blur", 7.0f)
        )
    }

    private fun darkBoxDrawable(density: Float, radiusDp: Float = 14f, strokeHex: String = "#383840") =
        GradientDrawable().apply {
            setColor(Color.parseColor("#F208080A"))
            cornerRadius = radiusDp * density
            setStroke((1f * density).toInt(), Color.parseColor(strokeHex))
        }

    override fun onCreateInputView(): View {
        val density = resources.displayMetrics.density
        isKeyboardVisible = !MenuPrefs.getBool(prefs, "orb_only_start", false)

        rootFrame = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val h = resources.displayMetrics.heightPixels.coerceAtLeast(320)
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            }
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
                if ((r - l) != (oldR - oldL) || (b - t) != (oldB - oldT)) post { applyPositions() }
            }
        }

        // 1. Tastiera
        keyboardView = AngledKeyboardView(this, this)
        applySavedConfig()
        keyboardView.visibility = if (isKeyboardVisible) View.VISIBLE else View.GONE
        rootFrame.addView(keyboardView, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))

        // 2. Menu ✦ e Appunti (fallback quando overlay globale non è attivo)
        popupColumnWrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        extraPanelContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = darkBoxDrawable(density)
            setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
        }
        popupColumnWrapper.addView(extraPanelContainer, LinearLayout.LayoutParams((240 * density).toInt(), -2).apply {
            bottomMargin = (6 * density).toInt()
        })

        clipboardPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = darkBoxDrawable(density)
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
        }
        clipboardListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clipboardPanel.addView(ScrollView(this).apply { addView(clipboardListLayout) }, LinearLayout.LayoutParams(-1, (145 * density).toInt()))
        popupColumnWrapper.addView(clipboardPanel, LinearLayout.LayoutParams((240 * density).toInt(), -2).apply {
            bottomMargin = (6 * density).toInt()
        })
        rootFrame.addView(popupColumnWrapper, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))

        // 3. Suggerimenti Swipe (fallback interno)
        swipeBarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = darkBoxDrawable(density, 12f, "#484852")
            setPadding((8 * density).toInt(), (5 * density).toInt(), (8 * density).toInt(), (5 * density).toInt())
        }
        rootFrame.addView(swipeBarContainer, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))

        // 4. Tasto Magico ✦ (fallback interno se overlay globale non attivo)
        val orbSize = (46 * density).toInt()
        cornerOrbView = CornerOrbView(this).apply { setIncognitoVisual(isIncognito()) }
        rootFrame.addView(cornerOrbView, FrameLayout.LayoutParams(orbSize, orbSize, Gravity.BOTTOM or Gravity.START))
        syncOrbVisibility()

        // 5. Mouse Overlay (fallback interno)
        remoteCursorOverlay = RemoteCursorOverlay(
            context = this,
            onClose = { remoteCursorOverlay.visibility = View.GONE; rootFrame.requestLayout() },
            onRequestOpenAccessibility = {
                Toast.makeText(this, "Attiva 'Tastiera 45° - Remote Cursor' in Accessibilità", Toast.LENGTH_LONG).show()
                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (_: Throwable) {}
            },
            onTouchableRegionChanged = { rect -> cursorPadRect = rect; rootFrame.requestLayout() }
        ).apply { visibility = View.GONE }
        rootFrame.addView(remoteCursorOverlay, FrameLayout.LayoutParams(-1, -1))

        return rootFrame
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        OrbBridge.activeIme = this
        // Quando si clicca su un campo di testo, apri la tastiera e mantieni il pulsante visibile
        isKeyboardVisible = !MenuPrefs.getBool(prefs, "orb_only_start", false)
        isEditingMenu = false

        val imeOptions = info?.imeOptions ?: 0
        val variation = (info?.inputType ?: 0) and InputType.TYPE_MASK_VARIATION
        isAutoIncognito = (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0 ||
                variation in listOf(
                    InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                    InputType.TYPE_NUMBER_VARIATION_PASSWORD
                )
        syncClipboard()
        OrbBridge.refreshOverlayState()

        val actionName = when (imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> "SEARCH"
            EditorInfo.IME_ACTION_SEND -> "SEND"
            EditorInfo.IME_ACTION_NEXT -> "NEXT"
            EditorInfo.IME_ACTION_DONE -> "DONE"
            else -> "RETURN"
        }

        if (::keyboardView.isInitialized) {
            applySavedConfig()
            keyboardView.setActionType(actionName)
            keyboardView.visibility = if (isKeyboardVisible) View.VISIBLE else View.GONE
        }
        if (::cornerOrbView.isInitialized) cornerOrbView.setIncognitoVisual(isIncognito())
        if (::extraPanelContainer.isInitialized) extraPanelContainer.visibility = View.GONE
        if (::swipeBarContainer.isInitialized && !isListeningVoice) swipeBarContainer.visibility = View.GONE
        if (::rootFrame.isInitialized) {
            rootFrame.requestLayout()
            rootFrame.post { applyPositions() }
        }
    }

    private fun applyPositions() {
        if (!::rootFrame.isInitialized || rootFrame.width <= 0 || rootFrame.height <= 0) return
        val density = resources.displayMetrics.density
        val sw = rootFrame.width
        val sh = rootFrame.height
        val edge = (6 * density).toInt()

        // 1. Tasto Magico ✦
        val orbW = cornerOrbView.measuredWidth.takeIf { it > 0 } ?: (46 * density).toInt()
        val orbH = cornerOrbView.measuredHeight.takeIf { it > 0 } ?: (46 * density).toInt()
        val orbLeft = ((sw - orbW).coerceAtLeast(0) * orbXPct.coerceIn(0f, 1f)).toInt()
        val orbBottom = orbBottomPx.coerceIn(0, (sh - orbH).coerceAtLeast(0))
        (cornerOrbView.layoutParams as? FrameLayout.LayoutParams)?.apply {
            leftMargin = orbLeft; bottomMargin = orbBottom
        }

        // 2. Tastiera
        val kbW = keyboardView.measuredWidth.takeIf { it > 0 } ?: (sw * 0.7f).toInt()
        val kbH = keyboardView.measuredHeight.takeIf { it > 0 } ?: (sw * 0.4f).toInt()
        val kbLeft = ((sw - kbW).coerceAtLeast(0) * kbXPct.coerceIn(0f, 1f)).toInt()
        val kbBottom = kbBottomPx.coerceIn(0, (sh - kbH).coerceAtLeast(0))
        (keyboardView.layoutParams as? FrameLayout.LayoutParams)?.apply {
            leftMargin = kbLeft; bottomMargin = kbBottom
        }

        // 3. Barra suggerimenti swipe
        if (swipeBarContainer.visibility == View.VISIBLE) {
            swipeBarContainer.measure(
                View.MeasureSpec.makeMeasureSpec((sw * 0.86f).toInt(), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val sW = swipeBarContainer.measuredWidth
            val sH = swipeBarContainer.measuredHeight
            val sLeft = (orbLeft + orbW / 2 - sW / 2).coerceIn(edge, (sw - sW - edge).coerceAtLeast(edge))
            val above = orbBottom + orbH + (8 * density).toInt()
            val sBottom = if (above + sH <= sh - edge) above else (orbBottom - sH - (8 * density).toInt()).coerceAtLeast(edge)
            (swipeBarContainer.layoutParams as? FrameLayout.LayoutParams)?.apply {
                leftMargin = sLeft; bottomMargin = sBottom
            }
        }

        // 4. Menu ✦ e Appunti
        popupColumnWrapper.measure(
            View.MeasureSpec.makeMeasureSpec(sw, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.AT_MOST)
        )
        val pW = popupColumnWrapper.measuredWidth.coerceAtLeast((240 * density).toInt())
        val pH = popupColumnWrapper.measuredHeight
        val pLeft = if (orbLeft + pW <= sw - edge) orbLeft else (orbLeft + orbW - pW).coerceIn(edge, (sw - pW - edge).coerceAtLeast(edge))
        val swipeSpace = if (swipeBarContainer.visibility == View.VISIBLE) swipeBarContainer.measuredHeight + (8 * density).toInt() else 0
        val pAbove = orbBottom + orbH + (8 * density).toInt() + swipeSpace
        val pBottom = if (pAbove + pH <= sh - edge) pAbove else (orbBottom - pH - (8 * density).toInt()).coerceAtLeast(edge)
        (popupColumnWrapper.layoutParams as? FrameLayout.LayoutParams)?.apply {
            leftMargin = pLeft; bottomMargin = pBottom
        }

        rootFrame.requestLayout()
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

        fun addRect(v: View, parentLeft: Int = 0, parentTop: Int = 0, pad: Int = 0) {
            if (v.visibility == View.VISIBLE && v.width > 0 && v.height > 0) {
                outInsets.touchableRegion.op(
                    Rect(parentLeft + v.left - pad, parentTop + v.top - pad, parentLeft + v.right + pad, parentTop + v.bottom + pad),
                    Region.Op.UNION
                )
            }
        }

        if (cornerOrbView.visibility == View.VISIBLE) addRect(cornerOrbView, pad = 8)
        if (keyboardView.visibility == View.VISIBLE && keyboardView.width > 0) {
            keyboardView.appendContourToRegion(outInsets.touchableRegion, keyboardView.left, keyboardView.top)
        }
        addRect(swipeBarContainer)
        addRect(extraPanelContainer, popupColumnWrapper.left, popupColumnWrapper.top)
        addRect(clipboardPanel, popupColumnWrapper.left, popupColumnWrapper.top)

        if (::remoteCursorOverlay.isInitialized && remoteCursorOverlay.visibility == View.VISIBLE && !cursorPadRect.isEmpty) {
            outInsets.touchableRegion.op(cursorPadRect, Region.Op.UNION)
        }
    }

    // --- Azioni richiamabili anche dal Pulsante Magico Globale ---

    fun setKeyboard45Visible(visible: Boolean) {
        isKeyboardVisible = visible
        if (::keyboardView.isInitialized) {
            keyboardView.visibility = if (visible) View.VISIBLE else View.GONE
            applyPositions()
        }
    }

    fun switchToSamsungKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val enabled = imm.enabledInputMethodList
        val samsungIme = enabled.firstOrNull {
            it.packageName.contains("samsung", ignoreCase = true) ||
                    it.packageName.contains("honeyboard", ignoreCase = true)
        }
        if (samsungIme != null) {
            try {
                switchInputMethod(samsungIme.id)
                return
            } catch (_: Throwable) {
            }
        }
        try { imm.showInputMethodPicker() } catch (_: Throwable) {}
    }

    fun startVoiceDictation() {
        val saved = MenuPrefs.getString(prefs, "voice_service_component", MenuPrefs.NOTUNE_PREF_KEY)
            ?: MenuPrefs.NOTUNE_PREF_KEY

        // 1. Caso Tastiera Vocale / App dedicata (es. Notune Transcribe dev.notune.transcribe)
        if (saved.startsWith("IME_PKG:") || saved.startsWith("IME_ID:")) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            val targetPkg = if (saved.startsWith("IME_PKG:")) saved.removePrefix("IME_PKG:") else ""
            val targetId = if (saved.startsWith("IME_ID:")) saved.removePrefix("IME_ID:") else ""

            val enabledIme = imm?.enabledInputMethodList?.firstOrNull {
                (targetId.isNotEmpty() && it.id == targetId) || (targetPkg.isNotEmpty() && it.packageName == targetPkg)
            }
            if (enabledIme != null) {
                try {
                    switchInputMethod(enabledIme.id)
                    return
                } catch (_: Throwable) {
                }
            }

            val installedIme = imm?.inputMethodList?.firstOrNull {
                (targetId.isNotEmpty() && it.id == targetId) || (targetPkg.isNotEmpty() && it.packageName == targetPkg)
            }
            if (installedIme != null) {
                Toast.makeText(this, "Attiva '${installedIme.loadLabel(packageManager)}' nelle impostazioni tastiera", Toast.LENGTH_LONG).show()
                try { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (_: Throwable) {}
                return
            }

            val pkg = targetPkg.ifEmpty { MenuPrefs.NOTUNE_PKG }
            if (!MenuPrefs.isPackageInstalled(this, pkg)) {
                Toast.makeText(this, "Installa Notune Transcribe dal Play Store", Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                } catch (_: Throwable) {}
                return
            }

            // Fallback Activity dell'app vocale
            try {
                val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    setPackage(pkg)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (packageManager.resolveActivity(speechIntent, 0) != null) {
                    startActivity(speechIntent)
                    return
                }
                packageManager.getLaunchIntentForPackage(pkg)?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }?.let {
                    startActivity(it)
                    return
                }
            } catch (_: Throwable) {
            }
        }

        // 2. Caso RecognitionService standard (o fallback)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Consenti il microfono nell'app Tastiera 45°", Toast.LENGTH_LONG).show()
            try { startActivity(Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (_: Throwable) {}
            return
        }

        if (isListeningVoice) {
            isListeningVoice = false
            try { speechRecognizer?.stopListening() } catch (_: Throwable) {}
            swipeBarContainer.visibility = View.GONE
            rootFrame.requestLayout()
            return
        }

        val density = resources.displayMetrics.density
        val comp = if (saved.contains("/")) ComponentName.unflattenFromString(saved) else null
        try {
            speechRecognizer?.destroy()
            speechRecognizer = if (comp != null) SpeechRecognizer.createSpeechRecognizer(this, comp) else SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {
                    isListeningVoice = true
                    showVoiceStatus("◉  Ascolto... (tocca per fermare)", density)
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(e: Int) {
                    isListeningVoice = false
                    swipeBarContainer.visibility = View.GONE
                    rootFrame.requestLayout()
                    Toast.makeText(this@Tastiera45Service, "Errore dettatura ($e). Verifica il motore nelle impostazioni.", Toast.LENGTH_SHORT).show()
                }
                override fun onResults(r: Bundle?) {
                    isListeningVoice = false
                    swipeBarContainer.visibility = View.GONE
                    rootFrame.requestLayout()
                    r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                        currentInputConnection?.commitText("$it ", 1)
                    }
                }
                override fun onPartialResults(p: Bundle?) {
                    val partial = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!partial.isNullOrBlank()) showVoiceStatus("◉  $partial", density)
                }
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            speechRecognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            })
        } catch (_: Throwable) {
            Toast.makeText(this, "Impossibile avviare il servizio vocale selezionato.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showVoiceStatus(msg: String, density: Float) {
        swipeBarContainer.removeAllViews()
        swipeBarContainer.addView(TextView(this).apply {
            text = msg; textSize = 12.5f; setTextColor(Color.WHITE)
            setPadding((6 * density).toInt(), (3 * density).toInt(), (6 * density).toInt(), (3 * density).toInt())
            setOnClickListener { startVoiceDictation() }
        })
        swipeBarContainer.visibility = View.VISIBLE
        applyPositions()
    }

    // --- Tasto Magico (✦) interno (usato solo se l'overlay globale non è attivo) ---

    private inner class CornerOrbView(context: Context) : View(context) {
        private var incognito = false
        private var downX = 0f
        private var downY = 0f
        private var held = false
        private var dragged = false
        private val h = Handler(Looper.getMainLooper())
        private val holdTask = Runnable {
            held = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            openMenu()
        }

        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EE08080A") }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }

        fun setIncognitoVisual(active: Boolean) { incognito = active; invalidate() }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val r = (minOf(width, height) / 2f) - 2f
            if (r <= 0f) return
            border.color = if (incognito) Color.WHITE else Color.parseColor("#606068")
            border.strokeWidth = if (incognito) 3.2f else 2.0f
            canvas.drawCircle(cx, cy, r, bg)
            canvas.drawCircle(cx, cy, r, border)
            text.textSize = r * 0.85f
            canvas.drawText("✦", cx, cy - (text.descent() + text.ascent()) / 2f, text)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val density = resources.displayMetrics.density
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    held = false; dragged = false
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (!isEditingMenu) h.postDelayed(holdTask, 150L)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isEditingMenu) return true
                    if (!dragged && hypot(event.rawX - downX, event.rawY - downY) > 8f * density) {
                        dragged = true
                        h.removeCallbacks(holdTask)
                        openMenu()
                    }
                    if (dragged || held) updateHover(event.rawX, event.rawY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    h.removeCallbacks(holdTask)
                    if (!isEditingMenu && (dragged || held)) {
                        updateHover(event.rawX, event.rawY)
                        val chosen = hoveredExtraId
                        extraPanelContainer.visibility = View.GONE
                        hoveredExtraId = null
                        rootFrame.requestLayout()
                        if (chosen != null) runMenuAction(chosen)
                    } else {
                        if (extraPanelContainer.visibility == View.VISIBLE) {
                            isEditingMenu = false
                            extraPanelContainer.visibility = View.GONE
                            rootFrame.requestLayout()
                        } else {
                            openMenu()
                        }
                    }
                }
            }
            return true
        }
    }

    private fun openMenu() {
        isEditingMenu = false
        rebuildMenu()
        extraPanelContainer.visibility = View.VISIBLE
        applyPositions()
    }

    private fun rebuildMenu() {
        val density = resources.displayMetrics.density
        extraPanelContainer.removeAllViews()
        extraOptionViews.clear()
        hoveredExtraId = null

        val entries = MenuPrefs.getEntries(
            p = prefs,
            isKbVisible = isKeyboardVisible,
            isArcMode = keyboardView.isArcLayoutMode(),
            isListening = isListeningVoice,
            isMouseOpen = remoteCursorOverlay.visibility == View.VISIBLE,
            isIncognito = isIncognito()
        )

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * density).toInt(), (3 * density).toInt(), (4 * density).toInt(), (5 * density).toInt())
        }
        header.addView(TextView(this).apply {
            text = if (isEditingMenu) "Modifica menu ✦" else "Menu ✦"
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
            setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                isEditingMenu = !isEditingMenu
                rebuildMenu()
                applyPositions()
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
                val toggle: (View) -> Unit = { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    MenuPrefs.toggleVisibility(prefs, entry.id)
                    rebuildMenu()
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
                    setOnClickListener { v -> if (idx > 0) { v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); MenuPrefs.moveItem(prefs, entry.id, -1); rebuildMenu() } }
                })
                row.addView(TextView(this).apply {
                    text = "▼"; textSize = 11.5f
                    setTextColor(if (idx < entries.lastIndex) Color.WHITE else Color.parseColor("#3A3A40"))
                    setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
                    setOnClickListener { v -> if (idx < entries.lastIndex) { v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); MenuPrefs.moveItem(prefs, entry.id, +1); rebuildMenu() } }
                })
                listCol.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (3 * density).toInt() })
            }
            extraPanelContainer.addView(ScrollView(this).apply { addView(listCol) }, LinearLayout.LayoutParams(-1, (250 * density).toInt()))
        } else {
            val visible = entries.filter { it.enabled }
            visible.forEachIndexed { idx, entry ->
                val tv = TextView(this).apply {
                    text = entry.title; textSize = 13f; setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = 8f * density }
                    setPadding((10 * density).toInt(), (7 * density).toInt(), (10 * density).toInt(), (7 * density).toInt())
                    setOnClickListener {
                        extraPanelContainer.visibility = View.GONE
                        rootFrame.requestLayout()
                        runMenuAction(entry.id)
                    }
                }
                extraPanelContainer.addView(tv, LinearLayout.LayoutParams(-1, -2).apply { if (idx < visible.lastIndex) bottomMargin = (2 * density).toInt() })
                extraOptionViews.add(entry.id to tv)
            }
        }
    }

    private fun updateHover(rawX: Float, rawY: Float) {
        if (extraPanelContainer.visibility != View.VISIBLE || isEditingMenu) return
        val density = resources.displayMetrics.density
        val loc = IntArray(2)
        var match: String? = null
        for ((id, view) in extraOptionViews) {
            view.getLocationOnScreen(loc)
            val inside = rawX in loc[0].toFloat()..(loc[0] + view.width).toFloat() &&
                    rawY in loc[1].toFloat()..(loc[1] + view.height).toFloat()
            if (inside) match = id
            view.background = GradientDrawable().apply { setColor(if (inside) Color.WHITE else Color.TRANSPARENT); cornerRadius = 8f * density }
            view.setTextColor(if (inside) Color.BLACK else Color.WHITE)
        }
        if (match != null && match != hoveredExtraId) cornerOrbView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        hoveredExtraId = match
    }

    fun runMenuAction(id: String) {
        when (id) {
            "LAUNCH_KB_45" -> setKeyboard45Visible(true)
            "LAUNCH_KB_SAMSUNG" -> switchToSamsungKeyboard()
            "TOGGLE_KB" -> setKeyboard45Visible(!isKeyboardVisible)
            "SWITCH_STYLE" -> {
                prefs.edit().putBoolean("kb_arc_mode", !keyboardView.isArcLayoutMode()).apply()
                applySavedConfig()
                setKeyboard45Visible(true)
            }
            "VOICE" -> startVoiceDictation()
            "MOUSE" -> {
                if (remoteCursorOverlay.visibility == View.VISIBLE) {
                    remoteCursorOverlay.visibility = View.GONE
                } else {
                    remoteCursorOverlay.resetPositions(orbXPct < 0.5f)
                    remoteCursorOverlay.visibility = View.VISIBLE
                }
                rootFrame.requestLayout()
            }
            "CLIPBOARD" -> toggleClipboard()
            "TRANSLATE" -> openProcessTextChooser("Traduci testo", null)
            "INCOGNITO" -> toggleManualIncognito()
            "SETTINGS" -> try {
                startActivity(Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            } catch (_: Throwable) {}
            else -> runCustomShortcut(id)
        }
    }

    fun toggleManualIncognito() {
        isManualIncognito = !isManualIncognito
        if (::cornerOrbView.isInitialized) cornerOrbView.setIncognitoVisual(isIncognito())
        OrbBridge.refreshOverlayState()
        if (!isIncognito()) syncClipboard()
        Toast.makeText(this, if (isIncognito()) "Incognito ON" else "Incognito OFF", Toast.LENGTH_SHORT).show()
    }

    fun runCustomShortcut(id: String) {
        val sc = MenuPrefs.loadShortcuts(prefs).firstOrNull { it.id == id } ?: return
        when (sc.type) {
            "SAMSUNG_ASSIST" -> {
                for (pkg in listOf("com.samsung.android.honeyboard", "com.samsung.android.smartsuggestions")) {
                    if (openProcessTextChooser("Assistente", pkg)) return
                }
                openProcessTextChooser("Assistente di scrittura", null)
            }
            "INTENT_URI" -> try {
                startActivity(Intent.parseUri(sc.payload, Intent.URI_INTENT_SCHEME).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            } catch (_: Throwable) { Toast.makeText(this, "Impossibile avviare '${sc.label}'", Toast.LENGTH_SHORT).show() }
            "APP" -> try {
                packageManager.getLaunchIntentForPackage(sc.payload)?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }?.let { startActivity(it) }
            } catch (_: Throwable) {}
            else -> currentInputConnection?.commitText(sc.payload, 1)
        }
    }

    fun openProcessTextChooser(title: String, pkg: String?): Boolean {
        val ic = currentInputConnection
        val text = ic?.getSelectedText(0)?.toString()?.takeIf { it.isNotEmpty() }
            ?: ic?.getTextBeforeCursor(300, 0)?.toString().orEmpty()
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pkg != null) setPackage(pkg)
        }
        return try {
            if (pkg != null) {
                if (packageManager.resolveActivity(intent, 0) == null) return false
                startActivity(intent)
            } else {
                startActivity(Intent.createChooser(intent, title).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun onCommitText(text: String) {
        if (extraPanelContainer.visibility == View.VISIBLE && !isEditingMenu) extraPanelContainer.visibility = View.GONE
        if (!isListeningVoice && swipeBarContainer.visibility == View.VISIBLE) swipeBarContainer.visibility = View.GONE
        OrbBridge.overlayService?.hidePopupsOnTyping()
        currentInputConnection?.commitText(text, 1)
    }

    override fun onDeleteChar() {
        val ic = currentInputConnection ?: return
        if (!ic.getSelectedText(0).isNullOrEmpty()) ic.commitText("", 1) else ic.deleteSurroundingText(1, 0)
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
        try { (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker() } catch (_: Throwable) {}
    }

    private fun toggleClipboard() {
        if (clipboardPanel.visibility == View.VISIBLE) {
            clipboardPanel.visibility = View.GONE
            rootFrame.requestLayout()
            return
        }
        syncClipboard()
        val density = resources.displayMetrics.density
        clipboardListLayout.removeAllViews()

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = if (isIncognito()) "Incognito ON" else "Appunti"; textSize = 11.5f; setTextColor(Color.parseColor("#8E8E93"))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (clipboardHistory.isNotEmpty()) {
            header.addView(TextView(this).apply {
                text = "Svuota"; textSize = 11.5f; setTextColor(Color.parseColor("#D1D1D6"))
                setPadding((8 * density).toInt(), (2 * density).toInt(), (8 * density).toInt(), (2 * density).toInt())
                setOnClickListener { clipboardHistory.clear(); prefs.edit().remove("saved_clipboard").apply(); toggleClipboard() }
            })
        }
        header.addView(TextView(this).apply {
            text = "✕"; textSize = 13f; setTextColor(Color.WHITE)
            setPadding((6 * density).toInt(), (2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt())
            setOnClickListener { clipboardPanel.visibility = View.GONE; rootFrame.requestLayout() }
        })
        clipboardListLayout.addView(header)

        if (clipboardHistory.isEmpty()) {
            clipboardListLayout.addView(TextView(this).apply {
                text = "Nessun testo negli appunti."; textSize = 12.5f; setTextColor(Color.WHITE)
                setPadding(0, (6 * density).toInt(), 0, (4 * density).toInt())
            })
        } else {
            for (item in clipboardHistory) {
                clipboardListLayout.addView(TextView(this).apply {
                    text = item; maxLines = 2; textSize = 12.5f; setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#16161A"))
                    setPadding((8 * density).toInt(), (7 * density).toInt(), (8 * density).toInt(), (7 * density).toInt())
                    setOnClickListener {
                        currentInputConnection?.commitText(item, 1)
                        if (!isIncognito()) try { clipboardManager?.setPrimaryClip(ClipData.newPlainText("text", item)) } catch (_: Throwable) {}
                        clipboardPanel.visibility = View.GONE; rootFrame.requestLayout()
                    }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (4 * density).toInt() })
            }
        }
        clipboardPanel.visibility = View.VISIBLE
        applyPositions()
    }

    override fun onMoveTextCursor(deltaXSteps: Int, deltaYSteps: Int) {
        val ic = currentInputConnection ?: return
        if (deltaXSteps != 0) {
            val code = if (deltaXSteps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            repeat(abs(deltaXSteps).coerceAtMost(12)) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
        }
        if (deltaYSteps != 0) {
            val code = if (deltaYSteps < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
            repeat(abs(deltaYSteps).coerceAtMost(8)) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
        }
    }

    override fun onSwipeWordDecoded(candidates: List<String>) {
        OrbBridge.notifySwipeCandidates(candidates)
    }

    fun replaceLastWordWith(word: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(40, 0)?.toString().orEmpty()
        val del = before.length - (before.trimEnd().lastIndexOf(' ') + 1)
        if (del > 0) ic.deleteSurroundingText(del, 0)
        ic.commitText("$word ", 1)
    }

    fun showLocalSwipeCandidates(candidates: List<String>) {
        if (candidates.size <= 1) {
            swipeBarContainer.visibility = View.GONE
            return
        }
        val density = resources.displayMetrics.density
        swipeBarContainer.removeAllViews()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

        candidates.forEachIndexed { idx, word ->
            row.addView(TextView(this).apply {
                text = word; textSize = 13f
                typeface = if (idx == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(Color.WHITE)
                setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt())
                setOnClickListener {
                    replaceLastWordWith(word)
                    swipeBarContainer.visibility = View.GONE
                    rootFrame.requestLayout()
                }
            })
        }
        row.addView(TextView(this).apply {
            text = "✕"; textSize = 11.5f; setTextColor(Color.parseColor("#8E8E93"))
            setPadding((8 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
            setOnClickListener { swipeBarContainer.visibility = View.GONE; rootFrame.requestLayout() }
        })

        swipeBarContainer.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        swipeBarContainer.visibility = View.VISIBLE
        applyPositions()
    }

    override fun onDragFloatingKeyboard(deltaScreenX: Float, deltaScreenY: Float) {
        val sw = rootFrame.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val sh = rootFrame.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        if (sw <= 0 || sh <= 0) return

        val maxL = (sw - keyboardView.width).coerceAtLeast(1)
        val maxB = (sh - keyboardView.height).coerceAtLeast(0)
        kbXPct = (((maxL * kbXPct).toInt() + deltaScreenX.toInt()).coerceIn(0, maxL).toFloat() / maxL).coerceIn(0f, 1f)
        kbBottomPx = (kbBottomPx - deltaScreenY.toInt()).coerceIn(0, maxB)
        applyPositions()

        val p = if (keyboardView.isArcLayoutMode()) "arc_" else "popup_"
        prefs.edit().putFloat("${p}x_pct", kbXPct).putInt("${p}bottom_offset", kbBottomPx).apply()
    }
}
