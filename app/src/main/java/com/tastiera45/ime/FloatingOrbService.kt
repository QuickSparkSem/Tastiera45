package com.tastiera45.ime

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot

class FloatingOrbService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var orbView: GlobalOrbView
    private lateinit var orbParams: WindowManager.LayoutParams

    private var menuContainer: LinearLayout? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private val menuOptionViews = mutableListOf<Pair<String, TextView>>()
    private var hoveredId: String? = null
    private var isEditingMenu = false
    private var isClipboardMode = false

    private var swipeContainer: LinearLayout? = null
    private var swipeParams: WindowManager.LayoutParams? = null

    private var cursorOverlay: RemoteCursorOverlay? = null
    private var cursorPointerParams: WindowManager.LayoutParams? = null
    private var cursorPadParams: WindowManager.LayoutParams? = null

    private val handler = Handler(Looper.getMainLooper())
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.post { refreshPositionAndVisuals() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        val density = resources.displayMetrics.density
        val orbSize = (46 * density).toInt()
        orbView = GlobalOrbView(this)
        orbParams = WindowManager.LayoutParams(
            orbSize, orbSize,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
        }

        wm.addView(orbView, orbParams)
        OrbBridge.overlayService = this
        OrbBridge.activeIme?.syncOrbVisibility()
        refreshPositionAndVisuals()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshPositionAndVisuals()
        return START_STICKY
    }

    override fun onDestroy() {
        if (OrbBridge.overlayService === this) OrbBridge.overlayService = null
        OrbBridge.activeIme?.syncOrbVisibility()
        handler.removeCallbacksAndMessages(null)
        try { prefs.unregisterOnSharedPreferenceChangeListener(prefListener) } catch (_: Throwable) {}
        removeMenuWindow()
        removeSwipeWindow()
        removeCursorWindows()
        try { if (::orbView.isInitialized) wm.removeView(orbView) } catch (_: Throwable) {}
        super.onDestroy()
    }

    fun refreshPositionAndVisuals() {
        if (!::orbView.isInitialized) return
        val density = resources.displayMetrics.density
        val sw = resources.displayMetrics.widthPixels.coerceAtLeast(320)
        val sh = resources.displayMetrics.heightPixels.coerceAtLeast(320)
        val orbXPct = MenuPrefs.getFloat(prefs, "orb_x_pct", 0.96f).coerceIn(0f, 1f)
        val orbBottom = MenuPrefs.getInt(prefs, "orb_bottom_offset", (36 * density).toInt()).coerceIn(0, (sh - orbParams.height).coerceAtLeast(0))

        orbParams.x = ((sw - orbParams.width).coerceAtLeast(0) * orbXPct).toInt()
        orbParams.y = orbBottom
        try { wm.updateViewLayout(orbView, orbParams) } catch (_: Throwable) {}
        orbView.invalidate()

        updatePopupPositions()
    }

    private fun updatePopupPositions() {
        val density = resources.displayMetrics.density
        val sw = resources.displayMetrics.widthPixels.coerceAtLeast(320)
        val sh = resources.displayMetrics.heightPixels.coerceAtLeast(320)
        val edge = (6 * density).toInt()

        swipeContainer?.let { sc ->
            val sp = swipeParams ?: return@let
            sc.measure(
                View.MeasureSpec.makeMeasureSpec((sw * 0.86f).toInt(), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val sW = sc.measuredWidth
            val sH = sc.measuredHeight
            sp.x = (orbParams.x + orbParams.width / 2 - sW / 2).coerceIn(edge, (sw - sW - edge).coerceAtLeast(edge))
            val above = orbParams.y + orbParams.height + (8 * density).toInt()
            sp.y = if (above + sH <= sh - edge) above else (orbParams.y - sH - (8 * density).toInt()).coerceAtLeast(edge)
            try { wm.updateViewLayout(sc, sp) } catch (_: Throwable) {}
        }

        menuContainer?.let { mc ->
            val mp = menuParams ?: return@let
            mc.measure(
                View.MeasureSpec.makeMeasureSpec(sw, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.AT_MOST)
            )
            val mW = mc.measuredWidth.coerceAtLeast((240 * density).toInt())
            val mH = mc.measuredHeight
            mp.x = if (orbParams.x + mW <= sw - edge) orbParams.x else (orbParams.x + orbParams.width - mW).coerceIn(edge, (sw - mW - edge).coerceAtLeast(edge))
            val swipeH = swipeContainer?.measuredHeight?.plus((8 * density).toInt()) ?: 0
            val above = orbParams.y + orbParams.height + (8 * density).toInt() + swipeH
            mp.y = if (above + mH <= sh - edge) above else (orbParams.y - mH - (8 * density).toInt()).coerceAtLeast(edge)
            try { wm.updateViewLayout(mc, mp) } catch (_: Throwable) {}
        }
    }

    fun hidePopupsOnTyping() {
        if (!isEditingMenu) removeMenuWindow()
        removeSwipeWindow()
    }

    // --- Tasto Magico Globale ✦ ---

    private inner class GlobalOrbView(context: Context) : View(context) {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var held = false
        private var dragged = false

        private val holdTask = Runnable {
            held = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            openMenu()
        }

        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EE08080A") }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val r = (minOf(width, height) / 2f) - 2f
            if (r <= 0f) return
            val incog = OrbBridge.activeIme?.isIncognito() == true
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
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    startX = orbParams.x; startY = orbParams.y
                    held = false; dragged = false
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (!isEditingMenu) handler.postDelayed(holdTask, 150L)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isEditingMenu) {
                        // In modalità Edit puoi trascinare direttamente il pallino ✦ ovunque sullo schermo
                        val sw = resources.displayMetrics.widthPixels.coerceAtLeast(320)
                        val sh = resources.displayMetrics.heightPixels.coerceAtLeast(320)
                        val maxL = (sw - orbParams.width).coerceAtLeast(1)
                        val maxB = (sh - orbParams.height).coerceAtLeast(0)
                        orbParams.x = (startX + (event.rawX - downRawX).toInt()).coerceIn(0, maxL)
                        orbParams.y = (startY - (event.rawY - downRawY).toInt()).coerceIn(0, maxB)
                        try { wm.updateViewLayout(orbView, orbParams) } catch (_: Throwable) {}
                        updatePopupPositions()
                        return true
                    }
                    if (!dragged && hypot(event.rawX - downRawX, event.rawY - downRawY) > 8f * density) {
                        dragged = true
                        handler.removeCallbacks(holdTask)
                        openMenu()
                    }
                    if (dragged || held) updateHover(event.rawX, event.rawY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(holdTask)
                    if (isEditingMenu) {
                        val sw = resources.displayMetrics.widthPixels.coerceAtLeast(320)
                        val maxL = (sw - orbParams.width).coerceAtLeast(1)
                        prefs.edit()
                            .putFloat("orb_x_pct", (orbParams.x.toFloat() / maxL).coerceIn(0f, 1f))
                            .putInt("orb_bottom_offset", orbParams.y)
                            .apply()
                        return true
                    }
                    if (dragged || held) {
                        updateHover(event.rawX, event.rawY)
                        val chosen = hoveredId
                        removeMenuWindow()
                        if (chosen != null) runAction(chosen)
                    } else {
                        if (menuContainer != null) removeMenuWindow() else openMenu()
                    }
                }
            }
            return true
        }
    }

    // --- Menu ✦ Globale ---

    private fun openMenu() {
        isEditingMenu = false
        isClipboardMode = false
        showMenuWindow()
    }

    private fun removeMenuWindow() {
        isEditingMenu = false
        isClipboardMode = false
        hoveredId = null
        menuOptionViews.clear()
        menuContainer?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        menuContainer = null
        menuParams = null
        orbView.invalidate()
    }

    private fun showMenuWindow() {
        val density = resources.displayMetrics.density
        if (menuContainer == null) {
            menuContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F208080A"))
                    cornerRadius = 14f * density
                    setStroke((1f * density).toInt(), Color.parseColor("#383840"))
                }
                setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
            }
            menuParams = WindowManager.LayoutParams(
                (240 * density).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            }
            wm.addView(menuContainer, menuParams)
        }
        rebuildMenuContent()
        orbView.invalidate()
        updatePopupPositions()
    }

    private fun rebuildMenuContent() {
        val container = menuContainer ?: return
        val density = resources.displayMetrics.density
        container.removeAllViews()
        menuOptionViews.clear()
        hoveredId = null

        if (isClipboardMode) {
            buildClipboardContent(container, density)
            return
        }

        val ime = OrbBridge.activeIme
        val entries = MenuPrefs.getEntries(
            p = prefs,
            isKbVisible = ime?.isKeyboardShown() ?: false,
            isArcMode = MenuPrefs.getBool(prefs, "kb_arc_mode", true),
            isListening = false,
            isMouseOpen = cursorOverlay != null,
            isIncognito = ime?.isIncognito() ?: false
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
            setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                isEditingMenu = !isEditingMenu
                orbView.invalidate()
                rebuildMenuContent()
                updatePopupPositions()
            }
        })
        container.addView(header)

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
                    rebuildMenuContent()
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
                    setOnClickListener { v -> if (idx > 0) { v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); MenuPrefs.moveItem(prefs, entry.id, -1); rebuildMenuContent() } }
                })
                row.addView(TextView(this).apply {
                    text = "▼"; textSize = 11.5f
                    setTextColor(if (idx < entries.lastIndex) Color.WHITE else Color.parseColor("#3A3A40"))
                    setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
                    setOnClickListener { v -> if (idx < entries.lastIndex) { v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); MenuPrefs.moveItem(prefs, entry.id, +1); rebuildMenuContent() } }
                })
                listCol.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (3 * density).toInt() })
            }
            container.addView(ScrollView(this).apply { addView(listCol) }, LinearLayout.LayoutParams(-1, (250 * density).toInt()))
        } else {
            val visible = entries.filter { it.enabled }
            visible.forEachIndexed { idx, entry ->
                val tv = TextView(this).apply {
                    text = entry.title; textSize = 13f; setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = 8f * density }
                    setPadding((10 * density).toInt(), (7 * density).toInt(), (10 * density).toInt(), (7 * density).toInt())
                    setOnClickListener {
                        removeMenuWindow()
                        runAction(entry.id)
                    }
                }
                container.addView(tv, LinearLayout.LayoutParams(-1, -2).apply { if (idx < visible.lastIndex) bottomMargin = (2 * density).toInt() })
                menuOptionViews.add(entry.id to tv)
            }
        }
    }

    private fun buildClipboardContent(container: LinearLayout, density: Float) {
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
            setOnClickListener { removeMenuWindow() }
        })
        container.addView(header)

        val raw = MenuPrefs.getString(prefs, "saved_clipboard", "") ?: ""
        val items = raw.split("\u001E").filter { it.isNotEmpty() }
        if (items.isEmpty()) {
            container.addView(TextView(this).apply {
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
                        OrbBridge.activeIme?.currentInputConnection?.commitText(item, 1)
                        try { (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("text", item)) } catch (_: Throwable) {}
                        removeMenuWindow()
                    }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (4 * density).toInt() })
            }
            container.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(-1, (150 * density).toInt()))
        }
    }

    private fun updateHover(rawX: Float, rawY: Float) {
        if (menuContainer == null || isEditingMenu) return
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
        if (match != null && match != hoveredId) orbView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        hoveredId = match
    }

    private fun runAction(id: String) {
        val ime = OrbBridge.activeIme
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

        when (id) {
            "LAUNCH_KB_45" -> {
                if (ime != null) {
                    ime.setKeyboard45Visible(true)
                    @Suppress("DEPRECATION")
                    try { imm?.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0) } catch (_: Throwable) {}
                } else {
                    try { imm?.showInputMethodPicker() } catch (_: Throwable) {}
                }
            }
            "LAUNCH_KB_SAMSUNG" -> {
                if (ime != null) {
                    ime.switchToSamsungKeyboard()
                } else {
                    @Suppress("DEPRECATION")
                    try { imm?.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0) } catch (_: Throwable) {}
                }
            }
            "TOGGLE_KB" -> {
                if (ime != null) {
                    ime.setKeyboard45Visible(!ime.isKeyboardShown())
                } else {
                    @Suppress("DEPRECATION")
                    try { imm?.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0) } catch (_: Throwable) {}
                }
            }
            "SWITCH_STYLE" -> {
                val nextArc = !MenuPrefs.getBool(prefs, "kb_arc_mode", true)
                prefs.edit().putBoolean("kb_arc_mode", nextArc).apply()
                if (ime != null) {
                    ime.setKeyboard45Visible(true)
                } else {
                    try { imm?.showInputMethodPicker() } catch (_: Throwable) {}
                }
            }
            "VOICE" -> {
                if (ime != null) {
                    ime.startVoiceDictation()
                } else {
                    // Anche fuori dalla tastiera, avvia direttamente Notune Transcribe (dev.notune.transcribe)
                    val pkg = MenuPrefs.NOTUNE_PKG
                    try {
                        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            setPackage(pkg)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (packageManager.resolveActivity(i, 0) != null) {
                            startActivity(i)
                        } else {
                            packageManager.getLaunchIntentForPackage(pkg)?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }?.let { startActivity(it) }
                                ?: Toast.makeText(this, "Apri un campo di testo o installa Notune Transcribe", Toast.LENGTH_SHORT).show()
                        }
                    } catch (_: Throwable) {}
                }
            }
            "MOUSE" -> toggleGlobalCursor()
            "CLIPBOARD" -> {
                isClipboardMode = true
                showMenuWindow()
            }
            "TRANSLATE" -> {
                if (ime != null) {
                    ime.openProcessTextChooser("Traduci testo", null)
                } else {
                    val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_PROCESS_TEXT, "")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try { startActivity(Intent.createChooser(intent, "Traduci").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (_: Throwable) {}
                }
            }
            "INCOGNITO" -> ime?.toggleManualIncognito()
            "SETTINGS" -> try {
                startActivity(Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            } catch (_: Throwable) {}
            else -> {
                if (ime != null) {
                    ime.runCustomShortcut(id)
                } else {
                    val sc = MenuPrefs.loadShortcuts(prefs).firstOrNull { it.id == id } ?: return
                    when (sc.type) {
                        "INTENT_URI" -> try { startActivity(Intent.parseUri(sc.payload, Intent.URI_INTENT_SCHEME).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (_: Throwable) {}
                        "APP" -> try { packageManager.getLaunchIntentForPackage(sc.payload)?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }?.let { startActivity(it) } } catch (_: Throwable) {}
                        "TEXT" -> {
                            try { (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("text", sc.payload)) } catch (_: Throwable) {}
                            Toast.makeText(this, "'${sc.label}' copiato negli appunti", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    // --- Barra Suggerimenti Swipe sopra ✦ ---

    fun showSwipeCandidates(candidates: List<String>) {
        if (candidates.size <= 1) {
            removeSwipeWindow()
            return
        }
        val density = resources.displayMetrics.density
        if (swipeContainer == null) {
            swipeContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F208080A"))
                    cornerRadius = 12f * density
                    setStroke((1f * density).toInt(), Color.parseColor("#484852"))
                }
                setPadding((8 * density).toInt(), (5 * density).toInt(), (8 * density).toInt(), (5 * density).toInt())
            }
            swipeParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            }
            wm.addView(swipeContainer, swipeParams)
        }

        val sc = swipeContainer ?: return
        sc.removeAllViews()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        candidates.forEachIndexed { idx, word ->
            row.addView(TextView(this).apply {
                text = word; textSize = 13f
                typeface = if (idx == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(Color.WHITE)
                setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt())
                setOnClickListener {
                    OrbBridge.applySwipeCandidate(word)
                    removeSwipeWindow()
                }
            })
        }
        row.addView(TextView(this).apply {
            text = "✕"; textSize = 11.5f; setTextColor(Color.parseColor("#8E8E93"))
            setPadding((8 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
            setOnClickListener { removeSwipeWindow() }
        })
        sc.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        updatePopupPositions()
    }

    private fun removeSwipeWindow() {
        swipeContainer?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        swipeContainer = null
        swipeParams = null
    }

    // --- Mouse Globale (Material Design 3) su ogni schermata ---

    private fun toggleGlobalCursor() {
        if (cursorOverlay != null) {
            removeCursorWindows()
            return
        }
        val overlay = RemoteCursorOverlay(
            context = this,
            onClose = { removeCursorWindows() },
            onRequestOpenAccessibility = {
                Toast.makeText(this, "Attiva 'Tastiera 45° - Remote Cursor' in Accessibilità", Toast.LENGTH_LONG).show()
                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (_: Throwable) {}
            },
            onTouchableRegionChanged = { rect ->
                cursorPadParams?.let { p ->
                    p.x = rect.left
                    p.y = rect.top
                    p.width = rect.width().coerceAtLeast(1)
                    p.height = rect.height().coerceAtLeast(1)
                    cursorOverlay?.let { ov ->
                        try { wm.updateViewLayout(ov.detachTrackpadForWindow(), p) } catch (_: Throwable) {}
                    }
                }
            }
        )
        overlay.resetPositions(MenuPrefs.getFloat(prefs, "orb_x_pct", 0.96f) < 0.5f)
        cursorOverlay = overlay

        // 1. Finestra full-screen NON toccabile per disegnare puntatore + ripple ovunque
        cursorPointerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(overlay, cursorPointerParams)

        // 2. Finestra compatta toccabile per il solo trackpad Material Design
        val padView = overlay.detachTrackpadForWindow()
        val rect = overlay.getPadBounds()
        cursorPadParams = WindowManager.LayoutParams(
            rect.width().coerceAtLeast(1),
            rect.height().coerceAtLeast(1),
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = rect.left
            y = rect.top
        }
        wm.addView(padView, cursorPadParams)
    }

    private fun removeCursorWindows() {
        cursorOverlay?.let { ov ->
            try { wm.removeView(ov.detachTrackpadForWindow()) } catch (_: Throwable) {}
            try { wm.removeView(ov) } catch (_: Throwable) {}
        }
        cursorOverlay = null
        cursorPointerParams = null
        cursorPadParams = null
    }
}
