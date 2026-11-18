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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
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
            text = "Tastiera Tonda Ergonomica"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        container.addView(title)

        val subtitle = TextView(this).apply {
            text = "Personalizza tasto singolo nell'angolo, grandezza lettere, spazi, inclinazione e scorciatoie."
            textSize = 13.5f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, (6 * density).toInt(), 0, (18 * density).toInt())
        }
        container.addView(subtitle)

        container.addView(makeActionButton("1. Attiva Tastiera nelle Impostazioni", "#4B7BD6") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })

        container.addView(makeActionButton("2. Seleziona come tastiera attiva", "#16171C") {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        })

        container.addView(makeActionButton("3. Attiva permesso Remote Cursor (Mouse)", "#16171C") {
            Toast.makeText(this, "Trova 'Tastiera 45° - Remote Cursor' e attivalo", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        // --- Modalità di comparsa nell'angolo ---
        val orbModeBtn = makeActionButton(
            if (prefs.getBoolean("orb_only_start", true))
                "Avvio campo testo: SOLO TASTO ✦ NELL'ANGOLO"
            else
                "Avvio campo testo: TASTIERA TONDA SUBITO APERTA",
            "#16171C"
        ) {}
        orbModeBtn.setOnClickListener {
            val next = !prefs.getBoolean("orb_only_start", true)
            prefs.edit().putBoolean("orb_only_start", next).apply()
            orbModeBtn.text = if (next) {
                "Avvio campo testo: SOLO TASTO ✦ NELL'ANGOLO"
            } else {
                "Avvio campo testo: TASTIERA TONDA SUBITO APERTA"
            }
        }
        container.addView(orbModeBtn)

        // --- Lato Destro / Sinistro ---
        val sideBtn = makeActionButton(
            if (prefs.getBoolean("left_handed", false)) "Lato attuale: SINISTRO"
            else "Lato attuale: DESTRO",
            "#16171C"
        ) {}
        sideBtn.setOnClickListener {
            val next = !prefs.getBoolean("left_handed", false)
            prefs.edit().putBoolean("left_handed", next).apply()
            sideBtn.text = if (next) "Lato attuale: SINISTRO" else "Lato attuale: DESTRO"
        }
        container.addView(sideBtn)

        // --- Slider Inclinazione Tastiera e Tasti (-90° a +90°) ---
        val savedTilt = prefs.getFloat("kb_tilt", -28f).toInt().coerceIn(-90, 90)
        val tiltLabel = makeSectionLabel("Inclinazione tastiera e tasti: ${savedTilt}°")
        container.addView(tiltLabel)

        val tiltSeekBar = SeekBar(this).apply {
            max = 180 // da -90° a +90°
            progress = savedTilt + 90
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val deg = value - 90
                    tiltLabel.text = "Inclinazione tastiera e tasti: ${deg}°"
                    prefs.edit().putFloat("kb_tilt", deg.toFloat()).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(tiltSeekBar)

        // --- Slider Grandezza delle Lettere ---
        val savedLetterSize = prefs.getFloat("kb_letter_size", 1.0f)
        val initialLetterPct = (savedLetterSize * 100).toInt().coerceIn(60, 150)
        val letterLabel = makeSectionLabel("Grandezza delle lettere: $initialLetterPct%")
        container.addView(letterLabel)

        val letterSeekBar = SeekBar(this).apply {
            max = 90 // da 60% a 150%
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

        // --- Slider Spazio tra Lettere ed Elementi ---
        val savedSpacing = prefs.getFloat("kb_key_spacing", 0.25f)
        val initialSpacingPct = (savedSpacing * 100).toInt().coerceIn(0, 100)
        val spacingLabel = makeSectionLabel("Spazio tra lettere ed elementi: $initialSpacingPct%")
        container.addView(spacingLabel)

        val spacingSeekBar = SeekBar(this).apply {
            max = 100 // da 0% a 100%
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

        // --- Slider Dimensione Cerchio Tastiera ---
        val savedScale = prefs.getFloat("kb_scale", 0.80f)
        val initialSizePct = (savedScale * 100).toInt().coerceIn(52, 98)
        val sizeLabel = makeSectionLabel("Dimensione cerchio tastiera: $initialSizePct%")
        container.addView(sizeLabel)

        val sizeSeekBar = SeekBar(this).apply {
            max = 46 // da 52% a 98%
            progress = initialSizePct - 52
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val pct = 52 + value
                    sizeLabel.text = "Dimensione cerchio tastiera: $pct%"
                    prefs.edit().putFloat("kb_scale", pct / 100f).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(sizeSeekBar)

        // --- Slider Trasparenza Tastiera ---
        val savedOpacity = prefs.getFloat("kb_alpha", 0.92f)
        val initialOpacityPct = (savedOpacity * 100).toInt().coerceIn(15, 100)
        val alphaLabel = makeSectionLabel("Opacità sfondo: $initialOpacityPct%")
        container.addView(alphaLabel)

        val alphaSeekBar = SeekBar(this).apply {
            max = 85 // da 15% a 100%
            progress = initialOpacityPct - 15
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val pct = 15 + value
                    alphaLabel.text = "Opacità sfondo: $pct%"
                    prefs.edit().putFloat("kb_alpha", pct / 100f).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(alphaSeekBar)

        // --- Slider Altezza dal Fondo ---
        val defaultBottomDp = 42
        val currentBottomPx = prefs.getInt("kb_bottom_offset", (defaultBottomDp * density).toInt())
        val currentBottomDp = (currentBottomPx / density).toInt().coerceIn(0, 260)
        val heightLabel = makeSectionLabel("Distanza dal bordo inferiore: ${currentBottomDp}dp")
        container.addView(heightLabel)

        val heightSeekBar = SeekBar(this).apply {
            max = 260
            progress = currentBottomDp
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    heightLabel.text = "Distanza dal bordo inferiore: ${value}dp"
                    prefs.edit().putInt("kb_bottom_offset", (value * density).toInt()).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(heightSeekBar)

        // --- Sezione Tasti Personalizzati / Shortcut Maker / Samsung Assist ---
        container.addView(makeSectionLabel("Scorciatoie personalizzate nel menu ✦:"))

        // 1. Tasto rapido Assistente di Scrittura Samsung
        container.addView(makeActionButton("+ Assistente di Scrittura Samsung", "#1E222D") {
            val current = loadShortcuts().toMutableList()
            current.add(StoredShortcut("SAMSUNG_ASSIST", "Assistente Samsung", "samsung_writing_assist"))
            saveShortcuts(current)
            refreshShortcutsList()
            Toast.makeText(this, "Assistente di Scrittura Samsung aggiunto al menu ✦", Toast.LENGTH_SHORT).show()
        })

        // 2. Importa da Shortcut Maker o altre app di scorciatoie Android
        container.addView(makeActionButton("+ Scegli da Shortcut Maker / Scorciatoie App", "#1E222D") {
            try {
                val pickIntent = Intent(Intent.ACTION_CREATE_SHORTCUT)
                val chooser = Intent.createChooser(pickIntent, "Scegli scorciatoia (es. Shortcut Maker)")
                @Suppress("DEPRECATION")
                startActivityForResult(chooser, REQ_PICK_SHORTCUT)
            } catch (_: Exception) {
                Toast.makeText(this, "Nessuna app di scorciatoie (es. Shortcut Maker) trovata", Toast.LENGTH_SHORT).show()
            }
        })

        // 3. Scegli un'App installata da aprire
        container.addView(makeActionButton("+ Scegli un'App da avviare", "#1E222D") {
            showInstalledAppsDialog()
        })

        // 4. Aggiungi testo rapido
        val shortcutNameInput = EditText(this).apply {
            hint = "Nome testo rapido (es. Email, Indirizzo)"
            setHintTextColor(Color.parseColor("#6E6E73"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#141519"))
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
            setBackgroundColor(Color.parseColor("#141519"))
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

        container.addView(makeActionButton("+ Aggiungi testo rapido al menu ✦", "#1F222A") {
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
        val voiceTitle = makeSectionLabel("Motore Dettatura Vocale (Google / Samsung / Terze parti):")
        container.addView(voiceTitle)

        loadAvailableVoiceServices()

        val spinner = Spinner(this).apply {
            setBackgroundColor(Color.parseColor("#16171C"))
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
                v.setBackgroundColor(Color.parseColor("#16171C"))
                v.setPadding((14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())
                return v
            }
        }
        spinner.adapter = adapter

        val savedComp = prefs.getString("voice_service_component", null)
        val initialIdx = voiceComponents.indexOfFirst { it?.flattenToString() == savedComp }.coerceAtLeast(0)
        spinner.setSelection(initialIdx)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val comp = voiceComponents[position]
                prefs.edit().putString("voice_service_component", comp?.flattenToString()).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        container.addView(spinner)

        // --- Campo di prova ---
        val testBox = EditText(this).apply {
            hint = "Tocca qui per provare il tasto ✦ nell'angolo..."
            setHintTextColor(Color.parseColor("#6E6E73"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#141519"))
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
                Toast.makeText(this, "Scorciatoia '$label' importata nel menu ✦", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showInstalledAppsDialog() {
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
            .setTitle("Scegli un'App da aggiungere al menu ✦")
            .setItems(names) { _, which ->
                val (appName, pkgName) = resolved[which]
                val current = loadShortcuts().toMutableList()
                current.add(StoredShortcut("APP", appName, pkgName))
                saveShortcuts(current)
                refreshShortcutsList()
                Toast.makeText(this, "App '$appName' aggiunta al menu ✦", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun loadShortcuts(): List<StoredShortcut> {
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
                    setColor(Color.parseColor("#121317"))
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

    private fun makeSectionLabel(text: String): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            textSize = 13.5f
            setTextColor(Color.WHITE)
            setPadding(0, (14 * density).toInt(), 0, (6 * density).toInt())
        }
    }

    private fun loadAvailableVoiceServices() {
        voiceComponents.clear()
        voiceLabels.clear()

        voiceComponents.add(null)
        voiceLabels.add("Predefinito di sistema (Automatico)")

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
    }

    private fun makeActionButton(label: String, bgHex: String, onClick: () -> Unit): Button {
        val density = resources.displayMetrics.density
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = 12f * density
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
