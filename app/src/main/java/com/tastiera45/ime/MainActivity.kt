package com.tastiera45.ime

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var mainColumn: LinearLayout
    private lateinit var slidersContainer: LinearLayout
    private lateinit var shortcutsContainer: LinearLayout

    private lateinit var btnArcMode: TextView
    private lateinit var btnPopupMode: TextView
    private lateinit var btnArcRight: TextView
    private lateinit var btnArcLeft: TextView
    private lateinit var arcOnlyGroup: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refreshModeButtons()
        rebuildSliders()
        rebuildShortcutsList()
        if (Settings.canDrawOverlays(this)) {
            try { startService(Intent(this, FloatingOrbService::class.java)) } catch (_: Throwable) {}
        }
    }

    private fun buildUi() {
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()

        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#09090B"))
            isFillViewport = true
        }

        mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (24 * d).toInt(), pad, (36 * d).toInt())
        }

        // Titolo
        mainColumn.addView(TextView(this).apply {
            text = "Tastiera 45°"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        mainColumn.addView(TextView(this).apply {
            text = "Tastiera concentrica ad arco, pop-up flottante, swipe ad alta precisione e cursore rapido."
            textSize = 13f
            setTextColor(Color.parseColor("#A1A1AA"))
            setPadding(0, (4 * d).toInt(), 0, (16 * d).toInt())
        })

        // 1. SEZIONE ATTIVAZIONE E PERMESSI
        val setupCard = createCard(d)
        addSectionTitle(setupCard, "1. Attivazione e Permessi", d)

        setupCard.addView(createActionButton("Abilita Tastiera 45° nelle Impostazioni", primary = true, d = d) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }, marginBottom(8, d))

        setupCard.addView(createActionButton("Seleziona Tastiera 45° come predefinita", primary = false, d = d) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
        }, marginBottom(8, d))

        setupCard.addView(createActionButton("Attiva Tasto Magico Flottante ✦ (Overlay)", primary = false, d = d) {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                startService(Intent(this, FloatingOrbService::class.java))
                Toast.makeText(this, "Tasto Magico ✦ attivo", Toast.LENGTH_SHORT).show()
            }
        }, marginBottom(8, d))

        setupCard.addView(createActionButton("Attiva Permesso Accessibilità (Cursore Mouse)", primary = false, d = d) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        mainColumn.addView(setupCard, marginBottom(14, d))

        // 2. AREA DI PROVA IMMEDIATA
        val testCard = createCard(d)
        addSectionTitle(testCard, "2. Area di Prova Rapida", d)
        val testInput = EditText(this).apply {
            hint = "Tocca qui per provare tastiera, swipe e pressioni lunghe..."
            setHintTextColor(Color.parseColor("#71717A"))
            setTextColor(Color.WHITE)
            textSize = 15f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#18181B"))
                cornerRadius = 10f * d
                setStroke((1f * d).toInt(), Color.parseColor("#3F3F46"))
            }
            setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
        }
        testCard.addView(testInput)
        mainColumn.addView(testCard, marginBottom(14, d))

        // 3. LAYOUT E STILE TASTIERA
        val styleCard = createCard(d)
        addSectionTitle(styleCard, "3. Layout e Aspetto", d)

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnArcMode = createSegmentButton("Arco Concentrico", d) {
            prefs.edit().putBoolean("kb_arc_mode", true).apply()
            refreshModeButtons()
            rebuildSliders()
        }
        btnPopupMode = createSegmentButton("Pop-up Flottante", d) {
            prefs.edit().putBoolean("kb_arc_mode", false).apply()
            refreshModeButtons()
            rebuildSliders()
        }
        modeRow.addView(btnArcMode, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = (6 * d).toInt() })
        modeRow.addView(btnPopupMode, LinearLayout.LayoutParams(0, -2, 1f))
        styleCard.addView(modeRow, marginBottom(10, d))

        arcOnlyGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sideRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnArcRight = createSegmentButton("Arco a Destra", d) {
            prefs.edit().putBoolean("kb_arc_left", false).putFloat("kb_x_pct", 1.0f).apply()
            refreshModeButtons()
        }
        btnArcLeft = createSegmentButton("Arco a Sinistra", d) {
            prefs.edit().putBoolean("kb_arc_left", true).putFloat("kb_x_pct", 0.0f).apply()
            refreshModeButtons()
        }
        sideRow.addView(btnArcRight, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = (6 * d).toInt() })
        sideRow.addView(btnArcLeft, LinearLayout.LayoutParams(0, -2, 1f))
        arcOnlyGroup.addView(sideRow, marginBottom(10, d))
        styleCard.addView(arcOnlyGroup)

        slidersContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        styleCard.addView(slidersContainer)

        styleCard.addView(createActionButton("Ripristina valori predefiniti stile", primary = false, d = d) {
            val isArc = MenuPrefs.getBool(prefs, "kb_arc_mode", true)
            val p = if (isArc) "arc_" else "pop_"
            prefs.edit()
                .putFloat("${p}scale", if (isArc) 0.86f else 0.88f)
                .putFloat("${p}letter_size", 1.0f)
                .putFloat("${p}opacity", 0.0f)
                .putFloat("${p}tilt", 0f)
                .putFloat("arc_rotation", 0f)
                .putFloat("arc_inner_radius", 0.34f)
                .putFloat("arc_row_spacing", 0.35f)
                .putFloat("${p}key_spacing", 0.25f)
                .putFloat("${p}shadow_strength", 0.92f)
                .putFloat("${p}shadow_blur", 7.0f)
                .apply()
            rebuildSliders()
            Toast.makeText(this, "Stile ripristinato", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * d).toInt() })

        mainColumn.addView(styleCard, marginBottom(14, d))

        // 4. SCORCIATOIE RAPIDE NEL MENU ✦
        val shortcutCard = createCard(d)
        addSectionTitle(shortcutCard, "4. Scorciatoie Testo nel Tasto Magico ✦", d)

        val labelInput = EditText(this).apply {
            hint = "Nome (es. Email)"
            setHintTextColor(Color.parseColor("#71717A"))
            setTextColor(Color.WHITE)
            textSize = 14f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#18181B"))
                cornerRadius = 8f * d
                setStroke((1f * d).toInt(), Color.parseColor("#3F3F46"))
            }
            setPadding((10 * d).toInt(), (9 * d).toInt(), (10 * d).toInt(), (9 * d).toInt())
        }
        val textInput = EditText(this).apply {
            hint = "Testo da incollare (es. nome@esempio.it)"
            setHintTextColor(Color.parseColor("#71717A"))
            setTextColor(Color.WHITE)
            textSize = 14f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#18181B"))
                cornerRadius = 8f * d
                setStroke((1f * d).toInt(), Color.parseColor("#3F3F46"))
            }
            setPadding((10 * d).toInt(), (9 * d).toInt(), (10 * d).toInt(), (9 * d).toInt())
        }

        shortcutCard.addView(labelInput, marginBottom(6, d))
        shortcutCard.addView(textInput, marginBottom(8, d))
        shortcutCard.addView(createActionButton("+ Aggiungi Scorciatoia", primary = false, d = d) {
            val lbl = labelInput.text.toString().trim()
            val txt = textInput.text.toString().trim()
            if (lbl.isEmpty() || txt.isEmpty()) {
                Toast.makeText(this, "Inserisci sia il nome che il testo", Toast.LENGTH_SHORT).show()
                return@createActionButton
            }
            MenuPrefs.addTextShortcut(prefs, lbl, txt)
            labelInput.setText("")
            textInput.setText("")
            rebuildShortcutsList()
            Toast.makeText(this, "Scorciatoia '$lbl' aggiunta al menu ✦", Toast.LENGTH_SHORT).show()
        }, marginBottom(8, d))

        shortcutsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        shortcutCard.addView(shortcutsContainer)
        mainColumn.addView(shortcutCard)

        rootScroll.addView(mainColumn)
        setContentView(rootScroll)

        refreshModeButtons()
        rebuildSliders()
        rebuildShortcutsList()
    }

    private fun refreshModeButtons() {
        val isArc = MenuPrefs.getBool(prefs, "kb_arc_mode", true)
        val isLeft = MenuPrefs.getBool(prefs, "kb_arc_left", false)
        styleSegmentButton(btnArcMode, isArc)
        styleSegmentButton(btnPopupMode, !isArc)
        arcOnlyGroup.visibility = if (isArc) View.VISIBLE else View.GONE
        styleSegmentButton(btnArcRight, !isLeft)
        styleSegmentButton(btnArcLeft, isLeft)
    }

    private fun rebuildSliders() {
        slidersContainer.removeAllViews()
        val isArc = MenuPrefs.getBool(prefs, "kb_arc_mode", true)
        val p = if (isArc) "arc_" else "pop_"

        addSlider(
            slidersContainer,
            "Dimensione tastiera",
            50, 98,
            (MenuPrefs.getFloat(prefs, "${p}scale", if (isArc) 0.86f else 0.88f) * 100).toInt(),
            "%"
        ) {
            prefs.edit().putFloat("${p}scale", it / 100f).apply()
        }

        addSlider(
            slidersContainer,
            "Grandezza delle lettere",
            50, 300,
            (MenuPrefs.getFloat(prefs, "${p}letter_size", 1.0f) * 100).toInt(),
            "%"
        ) {
            prefs.edit().putFloat("${p}letter_size", it / 100f).apply()
        }

        addSlider(
            slidersContainer,
            "Opacità sfondo",
            0, 100,
            (MenuPrefs.getFloat(prefs, "${p}opacity", 0.0f) * 100).toInt(),
            "%"
        ) {
            prefs.edit().putFloat("${p}opacity", it / 100f).apply()
        }

        addSlider(
            slidersContainer,
            if (isArc) "Ampiezza ventaglio arco" else "Inclinazione tastiera",
            -90, 90,
            MenuPrefs.getFloat(prefs, "${p}tilt", 0f).toInt(),
            "°"
        ) {
            prefs.edit().putFloat("${p}tilt", it.toFloat()).apply()
        }

        if (isArc) {
            addSlider(
                slidersContainer,
                "Rotazione arco",
                -180, 180,
                MenuPrefs.getFloat(prefs, "arc_rotation", 0f).toInt(),
                "°"
            ) {
                prefs.edit().putFloat("arc_rotation", it.toFloat()).apply()
            }

            addSlider(
                slidersContainer,
                "Raggio interno arco",
                15, 72,
                (MenuPrefs.getFloat(prefs, "arc_inner_radius", 0.34f) * 100).toInt(),
                "%"
            ) {
                prefs.edit().putFloat("arc_inner_radius", it / 100f).apply()
            }

            addSlider(
                slidersContainer,
                "Spaziatura tra le righe",
                0, 100,
                (MenuPrefs.getFloat(prefs, "arc_row_spacing", 0.35f) * 100).toInt(),
                "%"
            ) {
                prefs.edit().putFloat("arc_row_spacing", it / 100f).apply()
            }
        }

        addSlider(
            slidersContainer,
            "Spaziatura tra i tasti",
            0, 100,
            (MenuPrefs.getFloat(prefs, "${p}key_spacing", 0.25f) * 100).toInt(),
            "%"
        ) {
            prefs.edit().putFloat("${p}key_spacing", it / 100f).apply()
        }

        addSlider(
            slidersContainer,
            "Intensità ombra sotto le lettere",
            0, 100,
            (MenuPrefs.getFloat(prefs, "${p}shadow_strength", 0.92f) * 100).toInt(),
            "%"
        ) {
            prefs.edit().putFloat("${p}shadow_strength", it / 100f).apply()
        }

        addSlider(
            slidersContainer,
            "Sfocatura ombra lettere",
            0, 16,
            MenuPrefs.getFloat(prefs, "${p}shadow_blur", 7.0f).toInt(),
            "dp"
        ) {
            prefs.edit().putFloat("${p}shadow_blur", it.toFloat()).apply()
        }
    }

    private fun rebuildShortcutsList() {
        val d = resources.displayMetrics.density
        shortcutsContainer.removeAllViews()
        val list = MenuPrefs.loadShortcuts(prefs)
        for (sc in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#18181B"))
                    cornerRadius = 8f * d
                }
                setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            }
            row.addView(TextView(this).apply {
                text = "${sc.label}: ${sc.payload}"
                textSize = 13f
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = "Elimina"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#F87171"))
                setPadding((8 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
                setOnClickListener {
                    MenuPrefs.removeShortcut(prefs, sc.id)
                    rebuildShortcutsList()
                }
            })
            shortcutsContainer.addView(row, marginBottom(6, d))
        }
    }

    private fun addSlider(
        container: LinearLayout,
        label: String,
        min: Int,
        max: Int,
        current: Int,
        unit: String,
        onChange: (Int) -> Unit
    ) {
        val d = resources.displayMetrics.density
        val clamped = current.coerceIn(min, max)

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.parseColor("#E4E4E7"))
        }
        val valueTv = TextView(this).apply {
            text = "$clamped$unit"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        headerRow.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f))
        headerRow.addView(valueTv)

        val seekBar = SeekBar(this).apply {
            this.max = max - min
            progress = clamped - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = min + progress
                    valueTv.text = "$v$unit"
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * d).toInt(), 0, (8 * d).toInt())
            addView(headerRow)
            addView(seekBar)
        }
        container.addView(block)
    }

    private fun createCard(d: Float): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#111114"))
                cornerRadius = 14f * d
                setStroke((1f * d).toInt(), Color.parseColor("#27272A"))
            }
            setPadding((14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
        }

    private fun addSectionTitle(card: LinearLayout, title: String, d: Float) {
        card.addView(TextView(this).apply {
            text = title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, (10 * d).toInt())
        })
    }

    private fun createActionButton(text: String, primary: Boolean, d: Float, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.BLACK else Color.WHITE)
            background = GradientDrawable().apply {
                setColor(if (primary) Color.WHITE else Color.parseColor("#1E1E24"))
                cornerRadius = 10f * d
                if (!primary) setStroke((1f * d).toInt(), Color.parseColor("#3F3F46"))
            }
            setPadding((12 * d).toInt(), (11 * d).toInt(), (12 * d).toInt(), (11 * d).toInt())
            setOnClickListener { onClick() }
        }

    private fun createSegmentButton(label: String, d: Float, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
            setOnClickListener { onClick() }
        }

    private fun styleSegmentButton(btn: TextView, selected: Boolean) {
        val d = resources.displayMetrics.density
        btn.setTextColor(if (selected) Color.BLACK else Color.WHITE)
        btn.background = GradientDrawable().apply {
            setColor(if (selected) Color.WHITE else Color.parseColor("#1E1E24"))
            cornerRadius = 9f * d
        }
    }

    private fun marginBottom(dp: Int, d: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (dp * d).toInt() }
}
