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

    private lateinit var prefs: SharedPreferences
    private lateinit var menuConfigList: LinearLayout
    private lateinit var shortcutsList: LinearLayout
    private val voiceComponents = mutableListOf<ComponentName?>(null)
    private val voiceLabels = mutableListOf("Predefinito di sistema (Automatico)")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            }
        } catch (_: Throwable) {}

        val density = resources.displayMetrics.density
        prefs = getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (32 * density).toInt(), (20 * density).toInt(), (32 * density).toInt())
        }

        container.addView(TextView(this).apply {
            text = "Tastiera Minimal & Arco"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        container.addView(TextView(this).apply {
            text = "Caratteri sospesi con ombra nera, senza contorni, regolabili separatamente per entrambi i layout."
            textSize = 13.5f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, (6 * density).toInt(), 0, (18 * density).toInt())
        })

        container.addView(actionBtn("1. Attiva Tastiera nelle Impostazioni", "#FFFFFF", "#000000") {
            try { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) } catch (_: Throwable) {}
        })
        container.addView(actionBtn("2. Seleziona come tastiera attiva") {
            try { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() } catch (_: Throwable) {}
        })
        container.addView(actionBtn("3. Attiva permesso Remote Cursor (Mouse)") {
            Toast.makeText(this, "Trova 'Tastiera 45° - Remote Cursor' e attivalo", Toast.LENGTH_LONG).show()
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Throwable) {}
        })

        // =========================================================================
        // GENERALE & AVVIO
        // =========================================================================
        container.addView(groupTitle("GENERALE & AVVIO"))

        val styleBtn = actionBtn("") {}
        fun updateStyleBtn() {
            val arc = MenuPrefs.getBool(prefs, "kb_arc_mode", true)
            styleBtn.text = if (arc) "Layout attivo: AD ARCO CONCENTRICO" else "Layout attivo: POP-UP SCOLPITO"
        }
        updateStyleBtn()
        styleBtn.setOnClickListener {
            prefs.edit().putBoolean("kb_arc_mode", !MenuPrefs.getBool(prefs, "kb_arc_mode", true)).apply()
            updateStyleBtn()
        }
        container.addView(styleBtn)

        val startBtn = actionBtn("") {}
        fun updateStartBtn() {
            val orbOnly = MenuPrefs.getBool(prefs, "orb_only_start", true)
            startBtn.text = if (orbOnly) "Avvio campo testo: SOLO TASTO MAGICO ✦" else "Avvio campo testo: TASTIERA SUBITO APERTA"
        }
        updateStartBtn()
        startBtn.setOnClickListener {
            prefs.edit().putBoolean("orb_only_start", !MenuPrefs.getBool(prefs, "orb_only_start", true)).apply()
            updateStartBtn()
        }
        container.addView(startBtn)

        // =========================================================================
        // SEZIONE 1: TASTIERA AD ARCO CONCENTRICO
        // =========================================================================
        container.addView(divider(density))
        container.addView(groupTitle("SEZIONE 1 — TASTIERA AD ARCO CONCENTRICO"))

        val cornerBtn = actionBtn("") {}
        fun updateCornerBtn() {
            val left = MenuPrefs.getBool(prefs, "arc_left_side", MenuPrefs.getBool(prefs, "kb_arc_left", false))
            cornerBtn.text = if (left) "Angolo arco: SINISTRO" else "Angolo arco: DESTRO"
        }
        updateCornerBtn()
        cornerBtn.setOnClickListener {
            val nextLeft = !MenuPrefs.getBool(prefs, "arc_left_side", MenuPrefs.getBool(prefs, "kb_arc_left", false))
            prefs.edit()
                .putBoolean("arc_left_side", nextLeft)
                .putFloat("arc_x_pct", if (nextLeft) 0.0f else 1.0f)
                .apply()
            updateCornerBtn()
        }
        container.addView(cornerBtn)

        addStyleSliders(container, "arc_", defaultX = 1.0f, defaultScale = 0.86f, tiltTitle = "Apertura / angolo arco", density = density)

        // =========================================================================
        // SEZIONE 2: TASTIERA POP-UP SCOLPITO
        // =========================================================================
        container.addView(divider(density))
        container.addView(groupTitle("SEZIONE 2 — TASTIERA POP-UP SCOLPITO"))

        addStyleSliders(container, "popup_", defaultX = 0.50f, defaultScale = 0.82f, tiltTitle = "Inclinazione pop-up", density = density)

        // =========================================================================
        // SEZIONE 3: PULSANTE MAGICO (✦) — POSIZIONE, TASTI E ORDINE
        // =========================================================================
        container.addView(divider(density))
        container.addView(groupTitle("SEZIONE 3 — PULSANTE MAGICO (✦): POSIZIONE, TASTI E ORDINE"))

        addSlider(container, "Posizione orizzontale tasto ✦", 0, 100, (MenuPrefs.getFloat(prefs, "orb_x_pct", 0.96f) * 100).toInt(), "%") {
            prefs.edit().putFloat("orb_x_pct", it / 100f).apply()
        }
        addSlider(container, "Altezza dal basso tasto ✦", 0, 750, (MenuPrefs.getInt(prefs, "orb_bottom_offset", (36 * density).toInt()) / density).toInt(), "dp") {
            prefs.edit().putInt("orb_bottom_offset", (it * density).toInt()).apply()
        }

        container.addView(label("Attiva/Disattiva (☑/☐) e riordina (▲/▼) le opzioni del menu ✦ (anche dal tasto '✎ Edit' nel menu):"))
        menuConfigList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(menuConfigList)
        refreshMenuConfig()

        // =========================================================================
        // SEZIONE 4: AGGIUNGI SCORCIATOIE AL MENU ✦
        // =========================================================================
        container.addView(divider(density))
        container.addView(groupTitle("SEZIONE 4 — AGGIUNGI SCORCIATOIE AL MENU ✦"))

        container.addView(actionBtn("+ Assistente di Scrittura Samsung") {
            addShortcut("SAMSUNG_ASSIST", "Assistente Samsung", "samsung_writing_assist")
        })
        container.addView(actionBtn("+ Scegli da Shortcut Maker / Scorciatoie") {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent.createChooser(Intent(Intent.ACTION_CREATE_SHORTCUT), "Scegli scorciatoia"), 202)
            } catch (_: Throwable) { Toast.makeText(this, "Nessuna app di scorciatoie trovata", Toast.LENGTH_SHORT).show() }
        })
        container.addView(actionBtn("+ Scegli un'App da avviare") { showAppsDialog() })

        val nameIn = inputBox("Nome testo rapido (es. Email)", density)
        val textIn = inputBox("Testo da inserire (es. mario@gmail.com)", density)
        container.addView(nameIn)
        container.addView(textIn)
        container.addView(actionBtn("+ Aggiungi testo rapido al menu ✦", "#1C1C20") {
            val l = nameIn.text.toString().trim()
            val t = textIn.text.toString()
            if (l.isEmpty() || t.isEmpty()) {
                Toast.makeText(this, "Inserisci nome e testo", Toast.LENGTH_SHORT).show()
            } else {
                addShortcut("TEXT", l, t)
                nameIn.setText(""); textIn.setText("")
            }
        })

        shortcutsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(shortcutsList)
        refreshShortcuts()

        // =========================================================================
        // SEZIONE 5: DETTATURA VOCALE E CAMPO PROVA
        // =========================================================================
        container.addView(divider(density))
        container.addView(groupTitle("DETTATURA VOCALE"))

        try {
            packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).forEach { ri ->
                ri.serviceInfo?.let { si ->
                    voiceComponents.add(ComponentName(si.packageName, si.name))
                    voiceLabels.add("${ri.loadLabel(packageManager)} (${si.packageName})")
                }
            }
        } catch (_: Throwable) {}

        val spinner = Spinner(this).apply { setBackgroundColor(Color.parseColor("#141417")) }
        spinner.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, voiceLabels) {
            override fun getView(pos: Int, v: View?, p: ViewGroup) = (super.getView(pos, v, p) as TextView).apply {
                setTextColor(Color.WHITE); setPadding((12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt())
            }
            override fun getDropDownView(pos: Int, v: View?, p: ViewGroup) = (super.getDropDownView(pos, v, p) as TextView).apply {
                setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#141417"))
                setPadding((14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())
            }
        }
        val savedVoice = MenuPrefs.getString(prefs, "voice_service_component", null)
        spinner.setSelection(voiceComponents.indexOfFirst { it?.flattenToString() == savedVoice }.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit().putString("voice_service_component", voiceComponents.getOrNull(pos)?.flattenToString()).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        container.addView(spinner)

        container.addView(inputBox("Tocca qui per provare l'arco, lo swipe, il cursore 2D e il menu ✦...", density).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = (20 * density).toInt()
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(container) })
    }

    override fun onResume() {
        super.onResume()
        if (::menuConfigList.isInitialized) refreshMenuConfig()
        if (::shortcutsList.isInitialized) refreshShortcuts()
    }

    private fun addStyleSliders(container: LinearLayout, p: String, defaultX: Float, defaultScale: Float, tiltTitle: String, density: Float) {
        addSlider(container, "Posizione orizzontale", 0, 100, (MenuPrefs.getFloat(prefs, "${p}x_pct", defaultX) * 100).toInt(), "%") {
            prefs.edit().putFloat("${p}x_pct", it / 100f).apply()
        }
        addSlider(container, "Altezza dal basso", 0, 750, (MenuPrefs.getInt(prefs, "${p}bottom_offset", (36 * density).toInt()) / density).toInt(), "dp") {
            prefs.edit().putInt("${p}bottom_offset", (it * density).toInt()).apply()
        }
        addSlider(container, "Dimensione generale", 50, 98, (MenuPrefs.getFloat(prefs, "${p}scale", defaultScale) * 100).toInt(), "%") {
            prefs.edit().putFloat("${p}scale", it / 100f).apply()
        }
        addSlider(container, tiltTitle, -90, 90, MenuPrefs.getFloat(prefs, "${p}tilt", 0f).toInt(), "°") {
            prefs.edit().putFloat("${p}tilt", it.toFloat()).apply()
        }
        addSlider(container, "Grandezza delle lettere", 60, 150, (MenuPrefs.getFloat(prefs, "${p}letter_size", 1.0f) * 100).toInt(), "%") {
            prefs.edit().putFloat("${p}letter_size", it / 100f).apply()
        }
        addSlider(container, "Spazio tra lettere", 0, 100, (MenuPrefs.getFloat(prefs, "${p}key_spacing", 0.25f) * 100).toInt(), "%") {
            prefs.edit().putFloat("${p}key_spacing", it / 100f).apply()
        }
        addSlider(container, "Intensità ombra nera sotto lettere", 0, 100, (MenuPrefs.getFloat(prefs, "${p}shadow_strength", 0.92f) * 100).toInt(), "%") {
            prefs.edit().putFloat("${p}shadow_strength", it / 100f).apply()
        }
        addSlider(container, "Ampiezza / sfocatura ombra nera", 0, 16, MenuPrefs.getFloat(prefs, "${p}shadow_blur", 7.0f).toInt(), "dp") {
            prefs.edit().putFloat("${p}shadow_blur", it.toFloat()).apply()
        }
        addSlider(container, "Opacità sfondo (0%=Senza sfondo)", 0, 100, (MenuPrefs.getFloat(prefs, "${p}alpha", 0.0f) * 100).toInt(), "%") {
            prefs.edit().putFloat("${p}alpha", it / 100f).apply()
        }
    }

    private fun addSlider(container: LinearLayout, title: String, min: Int, max: Int, current: Int, unit: String, onChange: (Int) -> Unit) {
        val clamped = current.coerceIn(min, max)
        val lbl = label("$title: $clamped$unit")
        container.addView(lbl)
        container.addView(SeekBar(this).apply {
            this.max = max - min
            progress = clamped - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val v = min + value
                    lbl.text = "$title: $v$unit"
                    onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })
    }

    private fun refreshMenuConfig() {
        val density = resources.displayMetrics.density
        menuConfigList.removeAllViews()
        val entries = MenuPrefs.getEntries(prefs, forSettingsScreen = true)

        entries.forEachIndexed { idx, entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { setColor(Color.parseColor("#121215")); cornerRadius = 10f * density }
                setPadding((10 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            }
            val toggle = { MenuPrefs.toggleVisibility(prefs, entry.id); refreshMenuConfig() }

            row.addView(TextView(this).apply {
                text = if (entry.enabled) "☑" else "☐"
                textSize = 16f
                setTextColor(if (entry.enabled) Color.WHITE else Color.parseColor("#6E6E73"))
                setPadding((4 * density).toInt(), 0, (10 * density).toInt(), 0)
                setOnClickListener { toggle() }
            })
            row.addView(TextView(this).apply {
                text = entry.title
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (entry.enabled) Color.WHITE else Color.parseColor("#6E6E73"))
                setOnClickListener { toggle() }
            }, LinearLayout.LayoutParams(0, -2, 1f))

            row.addView(TextView(this).apply {
                text = "▲"; textSize = 13f
                setTextColor(if (idx > 0) Color.WHITE else Color.parseColor("#383840"))
                setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
                setOnClickListener { if (idx > 0) { MenuPrefs.moveItem(prefs, entry.id, -1); refreshMenuConfig() } }
            })
            row.addView(TextView(this).apply {
                text = "▼"; textSize = 13f
                setTextColor(if (idx < entries.lastIndex) Color.WHITE else Color.parseColor("#383840"))
                setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
                setOnClickListener { if (idx < entries.lastIndex) { MenuPrefs.moveItem(prefs, entry.id, +1); refreshMenuConfig() } }
            })
            menuConfigList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (5 * density).toInt() })
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 202 && resultCode == RESULT_OK && data != null) {
            try {
                @Suppress("DEPRECATION")
                val intent = data.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT) ?: return
                @Suppress("DEPRECATION")
                val name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME)?.trim().orEmpty().ifEmpty { "Scorciatoia App" }
                addShortcut("INTENT_URI", name, intent.toUri(Intent.URI_INTENT_SCHEME))
            } catch (_: Throwable) {}
        }
    }

    private fun showAppsDialog() {
        try {
            val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .mapNotNull { ri -> ri.activityInfo?.packageName?.let { ri.loadLabel(packageManager).toString() to it } }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }
            if (apps.isEmpty()) return
            AlertDialog.Builder(this)
                .setTitle("Scegli un'App per il menu ✦")
                .setItems(apps.map { it.first }.toTypedArray()) { _, w -> addShortcut("APP", apps[w].first, apps[w].second) }
                .show()
        } catch (_: Throwable) {}
    }

    private fun addShortcut(type: String, label: String, payload: String) {
        val list = MenuPrefs.loadShortcuts(prefs).toMutableList()
        list.add(MenuPrefs.Shortcut("sc_${System.currentTimeMillis()}_${list.size}", type, label, payload))
        MenuPrefs.saveShortcuts(prefs, list)
        refreshShortcuts()
        refreshMenuConfig()
        Toast.makeText(this, "'$label' aggiunta al menu ✦", Toast.LENGTH_SHORT).show()
    }

    private fun refreshShortcuts() {
        val density = resources.displayMetrics.density
        shortcutsList.removeAllViews()
        MenuPrefs.loadShortcuts(prefs).forEachIndexed { idx, sc ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { setColor(Color.parseColor("#121215")); cornerRadius = 10f * density }
                setPadding((12 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            }
            val tag = when (sc.type) { "SAMSUNG_ASSIST" -> "[Samsung]"; "INTENT_URI" -> "[Shortcut]"; "APP" -> "[App]"; else -> "[Testo]" }
            row.addView(TextView(this).apply {
                text = "$tag ${sc.label}${if (sc.type == "TEXT") ": ${sc.payload}" else ""}"
                textSize = 13f; setTextColor(Color.parseColor("#D1D1D6")); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = "✕"; textSize = 14f; setTextColor(Color.parseColor("#8E8E93"))
                setPadding((12 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
                setOnClickListener {
                    val updated = MenuPrefs.loadShortcuts(prefs).toMutableList()
                    if (idx in updated.indices) {
                        val rem = updated.removeAt(idx).id
                        MenuPrefs.saveShortcuts(prefs, updated)
                        val order = (MenuPrefs.getString(prefs, "orb_menu_order", "") ?: "").split(",").filter { it.isNotBlank() && it != rem }
                        val hidden = (MenuPrefs.getString(prefs, "orb_menu_hidden", "") ?: "").split(",").filter { it.isNotBlank() && it != rem }
                        prefs.edit().putString("orb_menu_order", order.joinToString(",")).putString("orb_menu_hidden", hidden.joinToString(",")).apply()
                        refreshShortcuts(); refreshMenuConfig()
                    }
                }
            })
            shortcutsList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (6 * density).toInt() })
        }
    }

    private fun groupTitle(t: String) = TextView(this).apply {
        val d = resources.displayMetrics.density
        text = t; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        setPadding(0, (12 * d).toInt(), 0, (6 * d).toInt())
    }

    private fun label(t: String) = TextView(this).apply {
        val d = resources.displayMetrics.density
        text = t; textSize = 13f; setTextColor(Color.parseColor("#D1D1D6"))
        setPadding(0, (9 * d).toInt(), 0, (4 * d).toInt())
    }

    private fun divider(d: Float) = View(this).apply {
        setBackgroundColor(Color.parseColor("#242429"))
        layoutParams = LinearLayout.LayoutParams(-1, (1 * d).toInt().coerceAtLeast(1)).apply {
            topMargin = (18 * d).toInt(); bottomMargin = (4 * d).toInt()
        }
    }

    private fun inputBox(hintText: String, d: Float) = EditText(this).apply {
        hint = hintText; setHintTextColor(Color.parseColor("#6E6E73")); setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#121215"))
        setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * d).toInt() }
    }

    private fun actionBtn(text: String, bgHex: String = "#141417", fgHex: String = "#FFFFFF", onClick: () -> Unit) = Button(this).apply {
        val d = resources.displayMetrics.density
        this.text = text; isAllCaps = false; textSize = 14f; setTextColor(Color.parseColor(fgHex))
        background = GradientDrawable().apply {
            setColor(Color.parseColor(bgHex)); cornerRadius = 12f * d
            setStroke((1 * d).toInt(), Color.parseColor("#323236"))
        }
        setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * d).toInt() }
        setOnClickListener { onClick() }
    }
}
