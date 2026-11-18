package com.tastiera45.ime

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionService
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
            text = "Tastiera Laterale Circolare"
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        container.addView(title)

        val subtitle = TextView(this).apply {
            text = "Regola dimensioni, trasparenza, lato dello schermo e motore vocale."
            textSize = 14f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, (6 * density).toInt(), 0, (18 * density).toInt())
        }
        container.addView(subtitle)

        container.addView(makeActionButton("1. Attiva Tastiera nelle Impostazioni", "#4B7BD6") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })

        container.addView(makeActionButton("2. Seleziona come tastiera attiva", "#1C1C1E") {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        })

        container.addView(makeActionButton("3. Attiva permesso Remote Cursor (Mouse)", "#1C1C1E") {
            Toast.makeText(this, "Trova 'Tastiera 45° - Remote Cursor' e attivalo", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        val sideBtn = makeActionButton(
            if (prefs.getBoolean("left_handed", false)) "Lato attuale: SINISTRO (esce da sinistra)"
            else "Lato attuale: DESTRO (esce da destra)",
            "#1C1C1E"
        ) {}
        sideBtn.setOnClickListener {
            val next = !prefs.getBoolean("left_handed", false)
            prefs.edit().putBoolean("left_handed", next).apply()
            sideBtn.text = if (next) {
                "Lato attuale: SINISTRO (esce da sinistra)"
            } else {
                "Lato attuale: DESTRO (esce da destra)"
            }
        }
        container.addView(sideBtn)

        // --- Regolazione Dimensioni Tastiera ---
        val savedScale = prefs.getFloat("kb_scale", 0.74f)
        val initialSizePct = (savedScale * 100).toInt().coerceIn(50, 95)
        val sizeLabel = makeSectionLabel("Dimensione tastiera: $initialSizePct%")
        container.addView(sizeLabel)

        val sizeSeekBar = SeekBar(this).apply {
            max = 45 // da 50% a 95%
            progress = initialSizePct - 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val pct = 50 + value
                    sizeLabel.text = "Dimensione tastiera: $pct%"
                    prefs.edit().putFloat("kb_scale", pct / 100f).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(sizeSeekBar)

        // --- Regolazione Trasparenza Tastiera ---
        val savedOpacity = prefs.getFloat("kb_alpha", 0.92f)
        val initialOpacityPct = (savedOpacity * 100).toInt().coerceIn(15, 100)
        val alphaLabel = makeSectionLabel("Opacità sfondo (trasparenza): $initialOpacityPct%")
        container.addView(alphaLabel)

        val alphaSeekBar = SeekBar(this).apply {
            max = 85 // da 15% a 100%
            progress = initialOpacityPct - 15
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val pct = 15 + value
                    alphaLabel.text = "Opacità sfondo (trasparenza): $pct%"
                    prefs.edit().putFloat("kb_alpha", pct / 100f).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(alphaSeekBar)

        // --- Regolazione Altezza dal Fondo ---
        val defaultBottomDp = 72
        val currentBottomPx = prefs.getInt("kb_bottom_offset", (defaultBottomDp * density).toInt())
        val currentBottomDp = (currentBottomPx / density).toInt().coerceIn(0, 260)
        val heightLabel = makeSectionLabel("Altezza dal fondo dello schermo: ${currentBottomDp}dp")
        container.addView(heightLabel)

        val heightSeekBar = SeekBar(this).apply {
            max = 260
            progress = currentBottomDp
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    heightLabel.text = "Altezza dal fondo dello schermo: ${value}dp"
                    prefs.edit().putInt("kb_bottom_offset", (value * density).toInt()).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(heightSeekBar)

        // --- Scelta Motore Dettatura Vocale ---
        val voiceTitle = makeSectionLabel("Motore Dettatura Vocale (Google / Samsung / Terze parti):")
        container.addView(voiceTitle)

        loadAvailableVoiceServices()

        val spinner = Spinner(this).apply {
            setBackgroundColor(Color.parseColor("#1C1C1E"))
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
                v.setBackgroundColor(Color.parseColor("#1C1C1E"))
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

        // --- Campo di prova immediato ---
        val testBox = EditText(this).apply {
            hint = "Tocca qui per provare la tastiera e il bottone ✦..."
            setHintTextColor(Color.parseColor("#6E6E73"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#141416"))
            setPadding((14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt())
        }
        val boxParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (22 * density).toInt()
        }
        container.addView(testBox, boxParams)

        scroll.addView(container)
        setContentView(scroll)
    }

    private fun makeSectionLabel(text: String): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            textSize = 14f
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
                cornerRadius = 14f * density
            }
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (10 * density).toInt()
            }
            setOnClickListener { onClick() }
        }
    }
}
