package com.tastiera45.ime

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionService
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_PICK_SHORTCUT = 202
        private val BUILTIN_IDS = listOf(
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

    private val voiceComponents = mutableListOf<ComponentName?>()
    private val voiceLabels = mutableListOf<String>()
    private lateinit var orbMenuConfigContainer: LinearLayout
    private lateinit var shortcutsListContainer: LinearLayout
    private lateinit var prefs: SharedPreferences

    private fun safeFloat(key: String, def: Float): Float =
        try { prefs.getFloat(key, def) } catch (_: Throwable) { def }

    private fun safeInt(key: String, def: Int): Int =
        try { prefs.getInt(key, def) } catch (_: Throwable) { def }

    private fun safeBool(key: String, def: Boolean): Boolean =
        try { prefs.getBoolean(key, def) } catch (_: Throwable) { def }

    private fun safeString(key: String, def: String?): String? =
        try { prefs.getString(key, def) } catch (_: Throwable) { def }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            }
        } catch (_: Throwable) {
        }

        val density = resources.displayMetrics.density
        prefs = getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (32 * density).toInt(), (20 * density).toInt(), (32 * density).toInt())
        }

        val title = TextView(this).apply {
            text = "Tastiera Minimal & Arco"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        container.addView(title)

        val subtitle = TextView(this).apply {
            text = "Caratteri sospesi con ombra nera profonda, senza contorni, regolabili separatamente per entrambi i layout."
            textSize = 13.5f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, (6 * density).toInt(), 0, (18 * density).toInt())
        }
        container.addView(subtitle)

        container.addView(makeActionButton("1. Attiva Tastiera nelle Impostazioni", "#FFFFFF", "#000000") {
            try {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            } catch (_: Throwable) {
            }
        })

        container.addView(makeActionButton("2. Seleziona come tastiera attiva", "#141417", "#FFFFFF") {
            try {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            } catch (_: Throwable) {
            }
        })

        container.addView(makeActionButton("3. Attiva permesso Remote Cursor (Mouse)", "#141417", "#FFFFFF") {
            Toast.makeText(this, "Trova 'Tastiera 45° - Remote Cursor' e attivalo", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Throwable) {
            }
        })

        // =========================================================================
        // GENERALE: STILE ATTIVO E AVVIO
        // =========================================================================
        container.addView(makeGroupTitle("GENERALE & AVVIO"))

        val layoutStyleBtn = makeActionButton(
            if (safeBool("kb_arc_mode", true))
                "Layout attivo: AD ARCO CONCENTRICO"
            else
                "Layout attivo: POP-UP SCOLPITO",
            "#141417",
            "#FFFFFF"
        ) {}
        layoutStyleBtn.setOnClickListener {
            val next = !safeBool("kb_arc_mode", true)
            prefs.edit().putBoolean("kb_arc_mode", next).apply()
            layoutStyleBtn.text = if (next) {
                "Layout attivo: AD ARCO CONCENTRICO"
            } else {
                "Layout attivo: POP-UP SCOLPITO"
            }
        }
        container.addView(layoutStyleBtn)

        val orbModeBtn = makeActionButton(
            if (safeBool("orb_only_start", true))
                "Avvio campo testo: SOLO TASTO MAGICO ✦"
            else
                "Avvio campo testo: TASTIERA SUBITO APERTA",
            "#141417",
            "#FFFFFF"
        ) {}
        orbModeBtn.setOnClickListener {
            val next = !safeBool("orb_only_start", true)
            prefs.edit().putBoolean("orb_only_start", next).apply()
            orbModeBtn.text = if (next) {
                "Avvio campo testo: SOLO TASTO MAGICO ✦"
            } else {
                "Avvio campo testo: TASTIERA SUBITO APERTA"
            }
        }
        container.addView(orbModeBtn)

        // =========================================================================
        // SEZIONE 1: TASTIERA AD ARCO CONCENTRICO (DALL'ANGOLO)
        // =========================================================================
        container.addView(makeDivider(density))
        container.addView(makeGroupTitle("SEZIONE 1 — TASTIERA AD ARCO CONCENTRICO"))

        val arcCornerBtn = makeActionButton(
            if (safeBool("arc_left_side", safeBool("kb_arc_left", false)))
                "Angolo arco: SINISTRO"
            else
                "Angolo arco: DESTRO",
            "#141417",
            "#FFFFFF"
        ) {}
        arcCornerBtn.setOnClickListener {
            val next = !safeBool("arc_left_side", safeBool("kb_arc_left", false))
            prefs.edit().putBoolean("arc_left_side", next).apply()
            arcCornerBtn.text = if (next) "Angolo arco: SINISTRO" else "Angolo arco: DESTRO"
        }
        container.addView(arcCornerBtn)

        addKeyboardStyleSliders(
            container = container,
            prefix = "arc_",
            defaultXPct = 1.0f,
            defaultScale = 0.86f,
            tiltLabelPrefix = "Apertura / inclinazione arco",
            density = density
        )

        // =========================================================================
        // SEZIONE 2: TASTIERA POP-UP SCOLPITO FLUTTUANTE
        // =========================================================================
        container.addView(makeDivider(density))
        container.addView(makeGroupTitle("SEZIONE 2 — TASTIERA POP-UP SCOLPITO"))

        addKeyboardStyleSliders(
            container = container,
            prefix = "popup_",
            defaultXPct = 0.50f,
            defaultScale = 0.82f,
            tiltLabelPrefix = "Inclinazione pop-up",
            density = density
        )

        // =========================================================================
        // SEZIONE 3: POSIZIONE E TASTI DEL PULSANTE MAGICO (✦)
        // =========================================================================
        container.addView(makeDivider(density))
        container.addView(makeGroupTitle("SEZIONE 3 — PULSANTE MAGICO (✦): POSIZIONE, TASTI E ORDINE"))

        val orbXPct = (safeFloat("orb_x_pct", 0.96f) * 100).toInt().coerceIn(0, 100)
        val orbXLabel = makeSectionLabel("Posizione orizzontale tasto ✦: $orbXPct%")
        container.addView(orbXLabel)

        val orbXSeek = SeekBar(this).apply {
            max = 100
            progress = orbXPct
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    orbXLabel.text = "Posizione orizzontale tasto ✦: $value%"
                    prefs.edit().putFloat("orb_x_pct", value / 100f).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(orbXSeek)

        val orbBottomDp = (safeInt("orb_bottom_offset", (36 * density).toInt()) / density).toInt().coerceIn(0, 750)
        val orbYLabel = makeSectionLabel("Altezza dal basso tasto ✦: ${orbBottomDp}dp")
        container.addView(orbYLabel)

        val orbYSeek = SeekBar(this).apply {
            max = 750
            progress = orbBottomDp
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    orbYLabel.text = "Altezza dal basso tasto ✦: ${value}dp"
                    prefs.edit().putInt("orb_bottom_offset", (value * density).toInt()).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(orbYSeek)

        val orbMenuHint = makeSectionLabel("Attiva/Disattiva (☑/☐) e riordina (▲/▼) tutte le opzioni del menu ✦ (modificabili anche dal tasto '✎ Edit' nel menu stesso):")
        container.addView(orbMenuHint)

        orbMenuConfigContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(orbMenuConfigContainer)
        refreshOrbMenuConfigList()

        // =========================================================================
        // SEZIONE 4: AGGIUNGI SCORCIATOIE AL MENU ✦
        // =========================================================================
        container.addView(makeDivider(density))
        container.addView(makeGroupTitle("SEZIONE 4 — AGGIUNGI SCORCIATOIE AL MENU ✦"))

        container.addView(makeActionButton("+ Assistente di Scrittura Samsung", "#141417", "#FFFFFF") {
            addNewShortcut("SAMSUNG_ASSIST", "Assistente Samsung", "samsung_writing_assist")
            Toast.makeText(this, "Assistente Samsung aggiunto al menu ✦", Toast.LENGTH_SHORT).show()
        })

        container.addView(makeActionButton("+ Scegli da Shortcut Maker / Scorciatoie", "#141417", "#FFFFFF") {
            try {
                val pickIntent = Intent(Intent.ACTION_CREATE_SHORTCUT)
                val chooser = Intent.createChooser(pickIntent, "Scegli scorciatoia (es. Shortcut Maker)")
                @Suppress("DEPRECATION")
                startActivityForResult(chooser, REQ_PICK_SHORTCUT)
            } catch (_: Throwable) {
                Toast.makeText(this, "Nessuna app di scorciatoie trovata", Toast.LENGTH_SHORT).show()
            }
        })

        container.addView(makeActionButton("+ Scegli un'App da avviare", "#141417", "#FFFFFF") {
            showInstalledAppsDialog()
        })

        val shortcutNameInput = EditText(this).apply {
            hint = "Nome testo rapido (es. Email, Saluto)"
            setHintTextColor(Color.parseColor("#6E6E73"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#121215"))
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
        }
        val nameParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (6 * density).toInt()
            bottomMargin = (8 * density).toInt()
        }
        container.addView(shortcutNameInput, nameParams)

        val shortcutTextInput = EditText(this).apply {
            hint = "Testo da inserire (es. mario@gmail.com)"
            setHintTextColor(Color.parseColor("#6E6E73"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#121215"))
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
        }
        val textParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = (8 * density).toInt()
        }
        container.addView(shortcutTextInput, textParams)

        shortcutsListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        container.addView(makeActionButton("+ Aggiungi testo rapido al menu ✦", "#1C1C20", "#FFFFFF") {
            val label = shortcutNameInput.text.toString().trim()
            val textVal = shortcutTextInput.text.toString()
            if (label.isEmpty() || textVal.isEmpty()) {
                Toast.makeText(this, "Inserisci sia il nome sia il testo", Toast.LENGTH_SHORT).show()
                return@makeActionButton
            }
            addNewShortcut("TEXT", label, textVal)
            shortcutNameInput.setText("")
            shortcutTextInput.setText("")
            Toast.makeText(this, "Scorciatoia '$label' aggiunta", Toast.LENGTH_SHORT).show()
        })

        container.addView(shortcutsListContainer)
        refreshShortcutsList()

        // =========================================================================
        // SEZIONE 5: DETTATURA VOCALE E CAMPO PROVA
        // =========================================================================
        container.addView(makeDivider(density))
        container.addView(makeGroupTitle("DETTATURA VOCALE"))
        val voiceTitle = makeSectionLabel("Motore Dettatura Vocale (Google / Samsung / Terze parti):")
        container.addView(voiceTitle)

        loadAvailableVoiceServices()

        val spinner = Spinner(this).apply {
            setBackgroundColor(Color.parseColor("#141417"))
        }
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, voiceLabels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(Color.WHITE)
                v.setPadding((12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt())
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setTextColor(Color.WHITE)
                v.setBackgroundColor(Color.parseColor("#141417"))
                v.setPadding((14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())
                return v
            }
        }
        spinner.adapter = adapter

        val savedComp = safeString("voice_service_component", null)
        val initialIdx = voiceComponents.indexOfFirst { it?.flattenToString() == savedComp }.coerceAtLeast(0)
        spinner.setSelection(initialIdx)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val comp = voiceComponents.getOrNull(position)
                prefs.edit().putString("voice_service_component", comp?.flattenToString()).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        container.addView(spinner)

        val testBox = EditText(this).apply {
            hint = "Tocca qui per provare l'arco, lo swipe, il cursore 2D e il menu ✦..."
            setHintTextColor(Color.parseColor("#6E6E73"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#121215"))
            setPadding((14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt())
        }
        val boxParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (20 * density).toInt()
        }
        container.addView(testBox, boxParams)

        scroll.addView(container)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        if (::orbMenuConfigContainer.isInitialized) {
            refreshOrbMenuConfigList()
        }
        if (::shortcutsListContainer.isInitialized) {
            refreshShortcutsList()
        }
    }

    // =========================================================================
    // LISTA CONTROLLO TASTI E ORDINE DEL MENU ✦
    // =========================================================================

    private fun refreshOrbMenuConfigList() {
        val density = resources.displayMetrics.density
        orbMenuConfigContainer.removeAllViews()

        val shortcuts = loadShortcuts()
        val shortcutMap = shortcuts.associateBy { it.id }
        val allValidIds = mutableListOf<String>().apply {
            addAll(BUILTIN_IDS)
            addAll(shortcuts.map { it.id })
        }

        val savedOrder = (safeString("orb_menu_order", "") ?: "").split(",").filter { it.isNotBlank() }
        val orderedIds = mutableListOf<String>()
        for (id in savedOrder) {
            if (id in allValidIds && id !in orderedIds) orderedIds.add(id)
        }
        for (id in allValidIds) {
            if (id !in orderedIds) orderedIds.add(id)
        }

        val hiddenSet = (safeString("orb_menu_hidden", "") ?: "")
            .split(",")
            .filter { it.isNotBlank() }
            .toMutableSet()

        for ((index, id) in orderedIds.withIndex()) {
            val isEnabled = id !in hiddenSet
            val labelText = when (id) {
                "TOGGLE_KB" -> "⊞  Apri / Chiudi Tastiera"
                "SWITCH_STYLE" -> "◐  Cambia Stile (Arco / Pop-up)"
                "VOICE" -> "◉  Dettatura Vocale"
                "MOUSE" -> "⌖  Mouse (Remote Cursor)"
                "CLIPBOARD" -> "⎘  Appunti"
                "TRANSLATE" -> "文  Traduci"
                "INCOGNITO" -> "◌  Modalità Incognito"
                "SETTINGS" -> "⚙  Impostazioni App"
                else -> {
                    val sc = shortcutMap[id]
                    val prefix = when (sc?.type) {
                        "SAMSUNG_ASSIST" -> "✦"
                        "INTENT_URI" -> "↗"
                        "APP" -> "▣"
                        else -> "›"
                    }
                    "$prefix  ${sc?.label ?: id}"
                }
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#121215"))
                    cornerRadius = 10f * density
                }
                setPadding((10 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            }

            val checkTv = TextView(this).apply {
                text = if (isEnabled) "☑" else "☐"
                textSize = 16f
                setTextColor(if (isEnabled) Color.WHITE else Color.parseColor("#6E6E73"))
                setPadding((4 * density).toInt(), 0, (10 * density).toInt(), 0)
                setOnClickListener {
                    if (id in hiddenSet) hiddenSet.remove(id) else hiddenSet.add(id)
                    prefs.edit().putString("orb_menu_hidden", hiddenSet.joinToString(",")).apply()
                    refreshOrbMenuConfigList()
                }
            }

            val nameTv = TextView(this).apply {
                text = labelText
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (isEnabled) Color.WHITE else Color.parseColor("#6E6E73"))
                setOnClickListener {
                    if (id in hiddenSet) hiddenSet.remove(id) else hiddenSet.add(id)
                    prefs.edit().putString("orb_menu_hidden", hiddenSet.joinToString(",")).apply()
                    refreshOrbMenuConfigList()
                }
            }

            val upBtn = TextView(this).apply {
                text = "▲"
                textSize = 13f
                setTextColor(if (index > 0) Color.WHITE else Color.parseColor("#383840"))
                setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
                setOnClickListener {
                    if (index > 0) {
                        val copy = orderedIds.toMutableList()
                        val moved = copy.removeAt(index)
                        copy.add(index - 1, moved)
                        prefs.edit().putString("orb_menu_order", copy.joinToString(",")).apply()
                        refreshOrbMenuConfigList()
                    }
                }
            }

            val downBtn = TextView(this).apply {
                text = "▼"
                textSize = 13f
                setTextColor(if (index < orderedIds.lastIndex) Color.WHITE else Color.parseColor("#383840"))
                setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
                setOnClickListener {
                    if (index < orderedIds.lastIndex) {
                        val copy = orderedIds.toMutableList()
                        val moved = copy.removeAt(index)
                        copy.add(index + 1, moved)
                        prefs.edit().putString("orb_menu_order", copy.joinToString(",")).apply()
                        refreshOrbMenuConfigList()
                    }
                }
            }

            row.addView(checkTv)
            row.addView(nameTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(upBtn)
            row.addView(downBtn)

            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (5 * density).toInt()
            }
            orbMenuConfigContainer.addView(row, lp)
        }
    }

    /**
     * Genera l'intero set identico di 9 regolazioni per ciascuno dei due stili di tastiera.
     */
    private fun addKeyboardStyleSliders(
        container: LinearLayout,
        prefix: String,
        defaultXPct: Float,
        defaultScale: Float,
        tiltLabelPrefix: String,
        density: Float
    ) {
        val savedXPct = (safeFloat("${prefix}x_pct", defaultXPct) * 100).toInt().coerceIn(0, 100)
        val xLabel = makeSectionLabel("Posizione orizzontale: $savedXPct%")
        container.addView(xLabel)
        container.addView(SeekBar(this).apply {
            max = 100
            progress = savedXPct
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    xLabel.text = "Posizione orizzontale: $value%"
                    prefs.edit().putFloat("${prefix}x_pct", value / 100f).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        val savedBottomDp = (safeInt("${prefix}bottom_offset", (36 * density).toInt()) / density).toInt().coerceIn(0, 750)
        val yLabel = makeSectionLabel("Altezza dal basso: ${savedBottomDp}dp")
        container.addView(yLabel)
        container.addView(SeekBar(this).apply {
            max = 750
            progress = savedBottomDp
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    yLabel.text = "Altezza dal basso: ${value}dp"
                    prefs.edit().putInt("${prefix}bottom_offset", (value * density).toInt()).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        val savedScale = safeFloat("${prefix}scale", defaultScale)
        val initialSizePct = (savedScale * 100).toInt().coerceIn(50, 98)
        val sizeLabel = makeSectionLabel("Dimensione generale: $initialSizePct%")
        container.addView(sizeLabel)
        container.addView(SeekBar(this).apply {
            max = 48
            progress = initialSizePct - 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val pct = 50 + value
                    sizeLabel.text = "Dimensione generale: $pct%"
                    prefs.edit().putFloat("${prefix}scale", pct / 100f).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        val savedTilt = safeFloat("${prefix}tilt", 0f).toInt().coerceIn(-90, 90)
        val tiltLabel = makeSectionLabel("$tiltLabelPrefix: ${savedTilt}°")
        container.addView(tiltLabel)
        container.addView(SeekBar(this).apply {
            max = 180
            progress = savedTilt + 90
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val deg = value - 90
                    tiltLabel.text = "$tiltLabelPrefix: ${deg}°"
                    prefs.edit().putFloat("${prefix}tilt", deg.toFloat()).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        val savedLetterSize = safeFloat("${prefix}letter_size", 1.0f)
        val initialLetterPct = (savedLetterSize * 100).toInt().coerceIn(60, 150)
        val letterLabel = makeSectionLabel("Grandezza delle lettere: $initialLetterPct%")
        container.addView(letterLabel)
        container.addView(SeekBar(this).apply {
            max = 90
            progress = initialLetterPct - 60
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val pct = 60 + value
                    letterLabel.text = "Grandezza delle lettere: $pct%"
                    prefs.edit().putFloat("${prefix}letter_size", pct / 100f).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        val savedSpacing = safeFloat("${prefix}key_spacing", 0.25f)
        val initialSpacingPct = (savedSpacing * 100).toInt().coerceIn(0, 100)
        val spacingLabel = makeSectionLabel("Spazio tra lettere: $initialSpacingPct%")
        container.addView(spacingLabel)
        container.addView(SeekBar(this).apply {
            max = 100
            progress = initialSpacingPct
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    spacingLabel.text = "Spazio tra lettere: $value%"
                    prefs.edit().putFloat("${prefix}key_spacing", value / 100f).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        val savedShadowStrength = safeFloat("${prefix}shadow_strength", 0.92f)
        val initialShadowPct = (savedShadowStrength * 100).toInt().coerceIn(0, 100)
        val shadowStrengthLabel = makeSectionLabel("Intensità ombra nera sotto lettere: $initialShadowPct%")
        container.addView(shadowStrengthLabel)
        container.addView(SeekBar(this).apply {
            max = 100
            progress = initialShadowPct
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    shadowStrengthLabel.text = "Intensità ombra nera sotto lettere: $value%"
                    prefs.edit().putFloat("${prefix}shadow_strength", value / 100f).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        val savedShadowBlur = safeFloat("${prefix}shadow_blur", 7.0f).toInt().coerceIn(0, 16)
        val shadowBlurLabel = makeSectionLabel("Ampiezza / sfocatura ombra nera: ${savedShadowBlur}dp")
        container.addView(shadowBlurLabel)
        container.addView(SeekBar(this).apply {
            max = 16
            progress = savedShadowBlur
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    shadowBlurLabel.text = "Ampiezza / sfocatura ombra nera: ${value}dp"
                    prefs.edit().putFloat("${prefix}shadow_blur", value.toFloat()).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        val savedOpacity = safeFloat("${prefix}alpha", 0.0f)
        val initialOpacityPct = (savedOpacity * 100).toInt().coerceIn(0, 100)
        val alphaLabel = makeSectionLabel("Opacità sfondo: $initialOpacityPct% (0%=Senza sfondo)")
        container.addView(alphaLabel)
        container.addView(SeekBar(this).apply {
            max = 100
            progress = initialOpacityPct
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    alphaLabel.text = "Opacità sfondo: $value% (0%=Senza sfondo)"
                    prefs.edit().putFloat("${prefix}alpha", value / 100f).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })
    }

    private fun makeDivider(density: Float): View {
        return View(this).apply {
            setBackgroundColor(Color.parseColor("#242429"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (1 * density).toInt().coerceAtLeast(1)
            ).apply {
                topMargin = (18 * density).toInt()
                bottomMargin = (4 * density).toInt()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_SHORTCUT && resultCode == RESULT_OK && data != null) {
            try {
                @Suppress("DEPRECATION")
                val shortcutIntent = data.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT)
                @Suppress("DEPRECATION")
                val shortcutName = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME)?.trim().orEmpty()
                if (shortcutIntent != null) {
                    val label = if (shortcutName.isNotEmpty()) shortcutName else "Scorciatoia App"
                    val uriString = shortcutIntent.toUri(Intent.URI_INTENT_SCHEME)
                    addNewShortcut("INTENT_URI", label, uriString)
                    Toast.makeText(this, "Scorciatoia '$label' aggiunta", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Throwable) {
                Toast.makeText(this, "Errore durante l'importazione della scorciatoia", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showInstalledAppsDialog() {
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolved = packageManager.queryIntentActivities(mainIntent, 0)
                .mapNotNull { ri ->
                    val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                    val label = ri.loadLabel(packageManager).toString()
                    label to pkg
                }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }

            if (resolved.isEmpty()) {
                Toast.makeText(this, "Nessuna app trovata", Toast.LENGTH_SHORT).show()
                return
            }

            val names = resolved.map { it.first }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Scegli un'App per il menu ✦")
                .setItems(names) { _, which ->
                    val (appName, pkgName) = resolved[which]
                    addNewShortcut("APP", appName, pkgName)
                    Toast.makeText(this, "App '$appName' aggiunta", Toast.LENGTH_SHORT).show()
                }
                .show()
        } catch (_: Throwable) {
        }
    }

    private fun addNewShortcut(type: String, label: String, payload: String) {
        val current = loadShortcuts().toMutableList()
        val id = "sc_${System.currentTimeMillis()}_${current.size}"
        current.add(StoredShortcut(id, type, label, payload))
        saveShortcuts(current)
        refreshShortcutsList()
        refreshOrbMenuConfigList()
    }

    private fun loadShortcuts(): List<StoredShortcut> {
        val rawV2 = safeString("custom_shortcuts_v2", "") ?: ""
        if (rawV2.isNotBlank()) {
            return rawV2.split("\u001E").mapNotNull { entry ->
                val parts = entry.split("\u001F")
                if (parts.size == 4 && parts[2].isNotBlank()) {
                    StoredShortcut(parts[0], parts[1], parts[2], parts[3])
                } else null
            }
        }
        val rawOld = safeString("custom_shortcuts", "") ?: ""
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
            saveShortcuts(migrated)
        }
        return migrated
    }

    private fun saveShortcuts(items: List<StoredShortcut>) {
        val encoded = items.joinToString("\u001E") { "${it.id}\u001F${it.type}\u001F${it.label}\u001F${it.payload}" }
        prefs.edit().putString("custom_shortcuts_v2", encoded).apply()
    }

    private fun refreshShortcutsList() {
        val density = resources.displayMetrics.density
        shortcutsListContainer.removeAllViews()
        val list = loadShortcuts()

        for ((index, item) in list.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#121215"))
                    cornerRadius = 10f * density
                }
                setPadding((12 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            }

            val badge = when (item.type) {
                "SAMSUNG_ASSIST" -> "[Samsung]"
                "INTENT_URI" -> "[Shortcut]"
                "APP" -> "[App]"
                else -> "[Testo]"
            }
            val detail = if (item.type == "TEXT") ": ${item.payload}" else ""
            val infoText = TextView(this).apply {
                text = "$badge ${item.label}$detail"
                textSize = 13f
                setTextColor(Color.parseColor("#D1D1D6"))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val delBtn = TextView(this).apply {
                text = "✕"
                textSize = 14f
                setTextColor(Color.parseColor("#8E8E93"))
                setPadding((12 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
                setOnClickListener {
                    val updated = loadShortcuts().toMutableList()
                    if (index in updated.indices) {
                        val removedId = updated[index].id
                        updated.removeAt(index)
                        saveShortcuts(updated)

                        val order = (safeString("orb_menu_order", "") ?: "")
                            .split(",")
                            .filter { it.isNotBlank() && it != removedId }
                        val hidden = (safeString("orb_menu_hidden", "") ?: "")
                            .split(",")
                            .filter { it.isNotBlank() && it != removedId }
                        prefs.edit()
                            .putString("orb_menu_order", order.joinToString(","))
                            .putString("orb_menu_hidden", hidden.joinToString(","))
                            .apply()

                        refreshShortcutsList()
                        refreshOrbMenuConfigList()
                    }
                }
            }

            row.addView(infoText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(delBtn)

            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (6 * density).toInt()
            }
            shortcutsListContainer.addView(row, lp)
        }
    }

    private fun makeGroupTitle(text: String): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, (12 * density).toInt(), 0, (6 * density).toInt())
        }
    }

    private fun makeSectionLabel(text: String): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.parseColor("#D1D1D6"))
            setPadding(0, (9 * density).toInt(), 0, (4 * density).toInt())
        }
    }

    private fun loadAvailableVoiceServices() {
        voiceComponents.clear()
        voiceLabels.clear()

        voiceComponents.add(null)
        voiceLabels.add("Predefinito di sistema (Automatico)")

        try {
            val services = packageManager.queryIntentServices(
                Intent(RecognitionService.SERVICE_INTERFACE),
                0
            )
            for (ri in services) {
                val si = ri.serviceInfo ?: continue
                val cn = ComponentName(si.packageName, si.name)
                val appLabel = ri.loadLabel(packageManager).toString()
                voiceComponents.add(cn)
                voiceLabels.add("$appLabel (${si.packageName})")
            }
        } catch (_: Throwable) {
        }
    }

    private fun makeActionButton(label: String, bgHex: String, textHex: String, onClick: () -> Unit): Button {
        val density = resources.displayMetrics.density
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.parseColor(textHex))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = 12f * density
                setStroke((1f * density).toInt(), Color.parseColor("#323236"))
            }
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
            setOnClickListener { onClick() }
        }
    }
}
