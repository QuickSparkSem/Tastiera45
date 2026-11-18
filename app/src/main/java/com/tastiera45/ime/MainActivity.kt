package com.tastiera45.ime

import android.Manifest
import android.app.Activity
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

    private val voiceComponents = mutableListOf<ComponentName?>()
    private val voiceLabels = mutableListOf<String>()
    private lateinit var shortcutsListContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }

        val density = resources.displayMetrics.density
        val prefs = getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)

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
            text = "Regola inclinazione, dimensione, trasparenza e aggiungi tasti scorciatoia personalizzati."
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

        // --- Slider Inclinazione Tastiera e Tasti ---
        val savedTilt = prefs.getFloat("kb_tilt", -28f).toInt().coerceIn(-45, 45)
        val tiltLabel = makeSectionLabel("Inclinazione tastiera e tasti: ${savedTilt}°")
        container.addView(tiltLabel)

        val tiltSeekBar = SeekBar(this).apply {
            max = 90 // da -45° a +45°
            progress = savedTilt + 45
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val deg = value - 45
                    tiltLabel.text = "Inclinazione tastiera e tasti: ${deg}°"
                    prefs.edit().putFloat("kb_tilt", deg.toFloat()).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(tiltSeekBar)

        // --- Slider Dimensione Tastiera ---
        val savedScale = prefs.getFloat("kb_scale", 0.78f)
        val initialSizePct = (savedScale * 100).toInt().coerceIn(52, 95)
        val sizeLabel = makeSectionLabel("Dimensione cerchio: $initialSizePct%")
        container.addView(sizeLabel)

        val sizeSeekBar = SeekBar(this).apply {
            max = 43 // da 52% a 95%
            progress = initialSizePct - 52
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val pct = 52 + value
                    sizeLabel.text = "Dimensione cerchio: $pct%"
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
        val defaultBottomDp = 56
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

        // --- Sezione Tasti Personalizzati / Scorciatoie ---
        container.addView(makeSectionLabel("Tasti personalizzati (compaiono nel menu ✦):"))

        val shortcutNameInput = EditText(this).apply {
            hint = "Nome tasto (es. Email, Saluto)"
            setHintTextColor(Color.parseColor("#6E6E73"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#141519"))
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
        }
        val nameParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
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

        container.addView(makeActionButton("+ Aggiungi scorciatoia al menu ✦", "#1F222A") {
            val label = shortcutNameInput.text.toString().trim()
            val textVal = shortcutTextInput.text.toString()
            if (label.isEmpty() || textVal.isEmpty()) {
                Toast.makeText(this, "Inserisci sia il nome sia il testo", Toast.LENGTH_SHORT).show()
                return@makeActionButton
            }
            val current = loadShortcuts(prefs).toMutableList()
            current.add(label to textVal)
            saveShortcuts(prefs, current)
            shortcutNameInput.setText("")
            shortcutTextInput.setText("")
            refreshShortcutsList(prefs)
            Toast.makeText(this, "Scorciatoia '$label' aggiunta", Toast.LENGTH_SHORT).show()
        })

        container.addView(shortcutsListContainer)
        refreshShortcutsList(prefs)

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
            hint = "Tocca qui per provare la tastiera tonda inclinabile..."
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

    private fun loadShortcuts(prefs: SharedPreferences): List<Pair<String, String>> {
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

    private fun saveShortcuts(prefs: SharedPreferences, items: List<Pair<String, String>>) {
        val encoded = items.joinToString("\u001E") { "${it.first}\u001F${it.second}" }
        prefs.edit().putString("custom_shortcuts", encoded).apply()
    }

    private fun refreshShortcutsList(prefs: SharedPreferences) {
        val density = resources.displayMetrics.density
        shortcutsListContainer.removeAllViews()
        val list = loadShortcuts(prefs)

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

            val infoText = TextView(this).apply {
                text = "› ${item.first}: ${item.second}"
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
                    val updated = loadShortcuts(prefs).toMutableList()
                    if (index in updated.indices) {
                        updated.removeAt(index)
                        saveShortcuts(prefs, updated)
                        refreshShortcutsList(prefs)
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
