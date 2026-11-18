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
    }

    private data class StoredShortcut(val type: String, val label: String, val payload: String)

    private val voiceComponents = mutableListOf<ComponentName?>()
    private val voiceLabels = mutableListOf<String>()
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
            text = "Scegli tra layout ad arco concentrico dall'angolo o pop-up scolpito, spostabile ovunque sullo schermo."
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
        // SEZIONE A: STILE LAYOUT E AVVIO
        // =========================================================================
        container.addView(makeGroupTitle("STILE LAYOUT TASTIERA"))

        val layoutStyleBtn = makeActionButton(
            if (safeBool("kb_arc_mode", true))
                "Layout: AD ARCO CONCENTRICO DALL'ANGOLO"
            else
                "Layout: POP-UP SCOLPITO FLUTTUANTE",
            "#141417",
            "#FFFFFF"
        ) {}
        layoutStyleBtn.setOnClickListener {
            val next = !safeBool("kb_arc_mode", true)
            prefs.edit().putBoolean("kb_arc_mode", next).apply()
            layoutStyleBtn.text = if (next) {
                "Layout: AD ARCO CONCENTRICO DALL'ANGOLO"
            } else {
                "Layout: POP-UP SCOLPITO FLUTTUANTE"
            }
        }
        container.addView(layoutStyleBtn)

        val arcCornerBtn = makeActionButton(
            if (safeBool("kb_arc_left", false))
                "Angolo arco: SINISTRO"
            else
                "Angolo arco: DESTRO",
            "#141417",
            "#FFFFFF"
        ) {}
        arcCornerBtn.setOnClickListener {
            val next = !safeBool("kb_arc_left", false)
            prefs.edit().putBoolean("kb_arc_left", next).apply()
            arcCornerBtn.text = if (next) "Angolo arco: SINISTRO" else "Angolo arco: DESTRO"
        }
        container.addView(arcCornerBtn)

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
        // SEZIONE B: OMBRA MARCATA SOTTO LE LETTERE
        // =========================================================================
        container.addView(makeGroupTitle("OMBRA LETTERE E LEGGIBILITÀ"))

        val savedShadowStrength = safeFloat("kb_shadow_strength", 0.90f)
        val initialShadowPct = (savedShadowStrength * 100).toInt().coerceIn(0, 100)
        val shadowStrengthLabel = makeSectionLabel("Intensità ombra sotto le lettere: $initialShadowPct%")
        container.addView(shadowStrengthLabel)

        val shadowStrengthSeek = SeekBar(this).apply {
            max = 100
            progress = initialShadowPct
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    shadowStrengthLabel.text = "Intensità ombra sotto le lettere: $value%"
                    prefs.edit().putFloat("kb_shadow_strength", value / 100f).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(shadowStrengthSeek)

        val savedShadowBlur = safeFloat("kb_shadow_blur", 6.5f).toInt().coerceIn(0, 16)
        val shadowBlurLabel = makeSectionLabel("Ampiezza / sfocatura ombra: ${savedShadowBlur}dp")
        container.addView(shadowBlurLabel)

        val shadowBlurSeek = SeekBar(this).apply {
            max = 16
            progress = savedShadowBlur
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    shadowBlurLabel.text = "Ampiezza / sfocatura ombra: ${value}dp"
                    prefs.edit().putFloat("kb_shadow_blur", value.toFloat()).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(shadowBlurSeek)

        // =========================================================================
        // SEZIONE C: POSIZIONE AUTONOMA TASTO MAGICO (✦) E TASTIERA (2D)
        // =========================================================================
        container.addView(makeGroupTitle("POSIZIONE TASTO MAGICO (✦)"))

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

        container.addView(makeGroupTitle("POSIZIONE TASTIERA (ANCHE TRASCINABILE OVUNQUE)"))

        val kbXPct = (safeFloat("kb_x_pct", 1.0f) * 100).toInt().coerceIn(0, 100)
        val kbXLabel = makeSectionLabel("Posizione orizzontale tastiera: $kbXPct%")
        container.addView(kbXLabel)

        val kbXSeek = SeekBar(this).apply {
            max = 100
            progress = kbXPct
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    kbXLabel.text = "Posizione orizzontale tastiera: $value%"
                    prefs.edit().putFloat("kb_x_pct", value / 100f).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(kbXSeek)

        val kbBottomDp = (safeInt("kb_bottom_offset", (36 * density).toInt()) / density).toInt().coerceIn(0, 750)
        val kbYLabel = makeSectionLabel("Altezza dal basso tastiera: ${kbBottomDp}dp")
        container.addView(kbYLabel)

        val kbYSeek = SeekBar(this).apply {
            max = 750
            progress = kbBottomDp
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    kbYLabel.text = "Altezza dal basso tastiera: ${value}dp"
                    prefs.edit().putInt("kb_bottom_offset", (value * density).toInt()).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(kbYSeek)

        // =========================================================================
        // SEZIONE D: DIMENSIONI, INCLINAZIONE, LETTERE E SFONDO (FINO A 0%)
        // =========================================================================
        container.addView(makeGroupTitle("DIMENSIONI, INCLINAZIONE E SFONDO"))

        val savedTilt = safeFloat("kb_tilt", 0f).toInt().coerceIn(-90, 90)
        val tiltLabel = makeSectionLabel("Inclinazione / apertura: ${savedTilt}°")
        container.addView(tiltLabel)

        val tiltSeekBar = SeekBar(this).apply {
            max = 180
            progress = savedTilt + 90
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val deg = value - 90
                    tiltLabel.text = "Inclinazione / apertura: ${deg}°"
                    prefs.edit().putFloat("kb_tilt", deg.toFloat()).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(tiltSeekBar)

        val savedLetterSize = safeFloat("kb_letter_size", 1.0f)
        val initialLetterPct = (savedLetterSize * 100).toInt().coerceIn(60, 150)
        val letterLabel = makeSectionLabel("Grandezza delle lettere: $initialLetterPct%")
        container.addView(letterLabel)

        val letterSeekBar = SeekBar(this).apply {
            max = 90
            progress = initialLetterPct - 60
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val pct = 60 + value
                    letterLabel.text = "Grandezza delle lettere: $pct%"
                    prefs.edit().putFloat("kb_letter_size", pct / 100f).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(letterSeekBar)

        val savedSpacing = safeFloat("kb_key_spacing", 0.25f)
        val initialSpacingPct = (savedSpacing * 100).toInt().coerceIn(0, 100)
        val spacingLabel = makeSectionLabel("Spazio tra lettere ed elementi: $initialSpacingPct%")
        container.addView(spacingLabel)

        val spacingSeekBar = SeekBar(this).apply {
            max = 100
            progress = initialSpacingPct
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    spacingLabel.text = "Spazio tra lettere ed elementi: $value%"
                    prefs.edit().putFloat("kb_key_spacing", value / 100f).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(spacingSeekBar)

        val savedScale = safeFloat("kb_scale", 0.84f)
        val initialSizePct = (savedScale * 100).toInt().coerceIn(50, 98)
        val sizeLabel = makeSectionLabel("Dimensione generale tastiera: $initialSizePct%")
        container.addView(sizeLabel)

        val sizeSeekBar = SeekBar(this).apply {
            max = 48
            progress = initialSizePct - 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val pct = 50 + value
                    sizeLabel.text = "Dimensione generale tastiera: $pct%"
                    prefs.edit().putFloat("kb_scale", pct / 100f).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(sizeSeekBar)

        val savedOpacity = safeFloat("kb_alpha", 0.12f)
        val initialOpacityPct = (savedOpacity * 100).toInt().coerceIn(0, 100)
        val alphaLabel = makeSectionLabel("Opacità sfondo: $initialOpacityPct% (0%=Senza sfondo)")
        container.addView(alphaLabel)

        val alphaSeekBar = SeekBar(this).apply {
            max = 100
            progress = initialOpacityPct
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    alphaLabel.text = "Opacità sfondo: $value% (0%=Senza sfondo)"
                    prefs.edit().putFloat("kb_alpha", value / 100f).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(alphaSeekBar)

        // =========================================================================
        // SEZIONE E: SCORCIATOIE PERSONALIZZATE NEL MENU ✦
        // =========================================================================
        container.addView(makeGroupTitle("SCORCIATOIE NEL MENU ✦"))

        container.addView(makeActionButton("+ Assistente di Scrittura Samsung", "#141417", "#FFFFFF") {
            val current = loadShortcuts().toMutableList()
            current.add(StoredShortcut("SAMSUNG_ASSIST", "Assistente Samsung", "samsung_writing_assist"))
            saveShortcuts(current)
            refreshShortcutsList()
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
            val current = loadShortcuts().toMutableList()
            current.add(StoredShortcut("TEXT", label, textVal))
            saveShortcuts(current)
            shortcutNameInput.setText("")
            shortcutTextInput.setText("")
            refreshShortcutsList()
            Toast.makeText(this, "Scorciatoia '$label' aggiunta", Toast.LENGTH_SHORT).show()
        })

        container.addView(shortcutsListContainer)
        refreshShortcutsList()

        // --- Scelta Motore Dettatura Vocale ---
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

        // --- Campo di prova ---
        val testBox = EditText(this).apply {
            hint = "Tocca qui per provare l'arco, l'ombra e lo spostamento..."
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
                    val current = loadShortcuts().toMutableList()
                    current.add(StoredShortcut("INTENT_URI", label, uriString))
                    saveShortcuts(current)
                    refreshShortcutsList()
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
                    val current = loadShortcuts().toMutableList()
                    current.add(StoredShortcut("APP", appName, pkgName))
                    saveShortcuts(current)
                    refreshShortcutsList()
                    Toast.makeText(this, "App '$appName' aggiunta", Toast.LENGTH_SHORT).show()
                }
                .show()
        } catch (_: Throwable) {
        }
    }

    private fun loadShortcuts(): List<StoredShortcut> {
        val raw = safeString("custom_shortcuts", "") ?: ""
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

    private fun saveShortcuts(items: List<StoredShortcut>) {
        val encoded = items.joinToString("\u001E") { "${it.type}\u001F${it.label}\u001F${it.payload}" }
        prefs.edit().putString("custom_shortcuts", encoded).apply()
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
            }
            val delBtn = TextView(this).apply {
                text = "✕"
                textSize = 14f
                setTextColor(Color.parseColor("#8E8E93"))
                setPadding((12 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
                setOnClickListener {
                    val updated = loadShortcuts().toMutableList()
                    if (index in updated.indices) {
                        updated.removeAt(index)
                        saveShortcuts(updated)
                        refreshShortcutsList()
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
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, (20 * density).toInt(), 0, (4 * density).toInt())
        }
    }

    private fun makeSectionLabel(text: String): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            textSize = 13.5f
            setTextColor(Color.WHITE)
            setPadding(0, (10 * density).toInt(), 0, (4 * density).toInt())
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
