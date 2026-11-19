package com.tastiera45.ime

import android.Manifest
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
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var menuOrderContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)

        val d = resources.displayMetrics.density
        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A0D"))
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (24 * d).toInt(), (16 * d).toInt(), (36 * d).toInt())
        }

        col.addView(TextView(this).apply {
            text = "Tastiera 45°"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        col.addView(TextView(this).apply {
            text = "Tastiera concentrica flottante • Tasto magico ✦"
            textSize = 13f
            setTextColor(Color.parseColor("#9E9EA6"))
            setPadding(0, (2 * d).toInt(), 0, (16 * d).toInt())
        })

        // --- SEZIONE 1: ATTIVAZIONE E PERMESSI ---
        col.addView(sectionTitle("1. Attivazione e Permessi"))
        col.addView(actionButton("Attiva Tastiera 45° nelle Impostazioni") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        col.addView(actionButton("Seleziona Tastiera Attiva") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
        })
        col.addView(actionButton("Permesso Tasto Magico Flottante (Overlay)") {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                startService(Intent(this, FloatingOrbService::class.java))
                Toast.makeText(this, "Overlay già attivo", Toast.LENGTH_SHORT).show()
            }
        })
        col.addView(actionButton("Permesso Cursore Mouse (Accessibilità)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(actionButton("Permesso Microfono (Dettatura)") {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            } else {
                Toast.makeText(this, "Permesso microfono già concesso", Toast.LENGTH_SHORT).show()
            }
        })

        // --- SEZIONE 2: TASTIERA CONCENTRICA ---
        col.addView(sectionTitle("2. Tastiera Concentrica"))

        col.addView(switchRow(
            title = "Arco a Sinistra (Mano Sinistra)",
            checked = MenuPrefs.getBool(prefs, "kb_arc_left", false)
        ) { isLeft ->
            prefs.edit()
                .putBoolean("kb_arc_left", isLeft)
                .putFloat("kb_x_pct", if (isLeft) 0.0f else 1.0f)
                .apply()
        })

        col.addView(sliderRow("Dimensione Arco", 50, 98, (MenuPrefs.getFloat(prefs, "arc_scale", 0.86f) * 100).roundToInt()) { v ->
            prefs.edit().putFloat("arc_scale", v / 100f).apply()
        })
        col.addView(sliderRow("Raggio Interno Impugnatura", 15, 70, (MenuPrefs.getFloat(prefs, "arc_inner_radius", 0.34f) * 100).roundToInt()) { v ->
            prefs.edit().putFloat("arc_inner_radius", v / 100f).apply()
        })
        col.addView(sliderRow("Rotazione Arco (°)", -45, 45, MenuPrefs.getFloat(prefs, "arc_rotation", 0f).roundToInt()) { v ->
            prefs.edit().putFloat("arc_rotation", v.toFloat()).apply()
        })
        col.addView(sliderRow("Inclinazione Ventaglio (°)", -45, 45, MenuPrefs.getFloat(prefs, "arc_tilt", 0f).roundToInt()) { v ->
            prefs.edit().putFloat("arc_tilt", v.toFloat()).apply()
        })
        col.addView(sliderRow("Dimensione Lettere", 60, 200, (MenuPrefs.getFloat(prefs, "arc_letter_size", 1.0f) * 100).roundToInt()) { v ->
            prefs.edit().putFloat("arc_letter_size", v / 100f).apply()
        })
        col.addView(sliderRow("Spaziatura Righe", 0, 100, (MenuPrefs.getFloat(prefs, "arc_row_spacing", 0.35f) * 100).roundToInt()) { v ->
            prefs.edit().putFloat("arc_row_spacing", v / 100f).apply()
        })
        col.addView(sliderRow("Spaziatura Tasti", 0, 100, (MenuPrefs.getFloat(prefs, "arc_key_spacing", 0.25f) * 100).roundToInt()) { v ->
            prefs.edit().putFloat("arc_key_spacing", v / 100f).apply()
        })
        col.addView(sliderRow("Opacità Sfondo Arco", 0, 100, (MenuPrefs.getFloat(prefs, "arc_opacity", 0f) * 100).roundToInt()) { v ->
            prefs.edit().putFloat("arc_opacity", v / 100f).apply()
        })
        col.addView(sliderRow("Intensità Ombra Sotto Caratteri", 0, 100, (MenuPrefs.getFloat(prefs, "arc_shadow_strength", 0.92f) * 100).roundToInt()) { v ->
            prefs.edit().putFloat("arc_shadow_strength", v / 100f).apply()
        })
        col.addView(actionButton("Reimposta Posizione Tastiera Concentrica") {
            val isLeft = MenuPrefs.getBool(prefs, "kb_arc_left", false)
            prefs.edit()
                .putFloat("kb_x_pct", if (isLeft) 0.0f else 1.0f)
                .putInt("kb_bottom_offset", 0)
                .apply()
            Toast.makeText(this, "Posizione tastiera reimpostata", Toast.LENGTH_SHORT).show()
        })

        // --- SEZIONE 3: SCORCIATOIE TESTO NEL MENU ✦ ---
        col.addView(sectionTitle("3. Aggiungi Scorciatoia Rapida nel Menu ✦"))
        val labelInput = EditText(this).apply {
            hint = "Nome pulsante (es. Email)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6E6E73"))
            setBackgroundColor(Color.parseColor("#16161C"))
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        }
        val textInput = EditText(this).apply {
            hint = "Testo da incollare (es. nome@email.it)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6E6E73"))
            setBackgroundColor(Color.parseColor("#16161C"))
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        }
        col.addView(labelInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * d).toInt() })
        col.addView(textInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * d).toInt() })
        col.addView(actionButton("+ Aggiungi al Menu ✦") {
            val lbl = labelInput.text.toString().trim()
            val txt = textInput.text.toString()
            if (lbl.isNotEmpty() && txt.isNotEmpty()) {
                MenuPrefs.addTextShortcut(prefs, lbl, txt)
                labelInput.setText("")
                textInput.setText("")
                rebuildMenuOrderList()
                Toast.makeText(this, "Scorciatoia aggiunta", Toast.LENGTH_SHORT).show()
            }
        })

        // --- SEZIONE 4: ORDINE E VISIBILITÀ MENU ✦ ---
        col.addView(sectionTitle("4. Ordine e Visibilità Menu ✦"))
        menuOrderContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(menuOrderContainer)
        rebuildMenuOrderList()

        rootScroll.addView(col)
        setContentView(rootScroll)

        if (Settings.canDrawOverlays(this)) {
            try { startService(Intent(this, FloatingOrbService::class.java)) } catch (_: Throwable) {}
        }
    }

    private fun rebuildMenuOrderList() {
        val d = resources.displayMetrics.density
        menuOrderContainer.removeAllViews()
        val entries = MenuPrefs.getEntries(
            p = prefs,
            isKbVisible = true,
            isListening = false,
            isMouseOpen = false,
            isIncognito = false
        )
        entries.forEachIndexed { idx, entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#15151A"))
                    cornerRadius = 10f * d
                }
                setPadding((10 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            }
            row.addView(TextView(this).apply {
                text = if (entry.enabled) "☑" else "☐"
                textSize = 16f
                setTextColor(if (entry.enabled) Color.WHITE else Color.parseColor("#6E6E73"))
                setPadding(0, 0, (10 * d).toInt(), 0)
                setOnClickListener {
                    MenuPrefs.toggleVisibility(prefs, entry.id)
                    rebuildMenuOrderList()
                }
            })
            row.addView(TextView(this).apply {
                text = entry.title
                textSize = 13.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (entry.enabled) Color.WHITE else Color.parseColor("#6E6E73"))
            }, LinearLayout.LayoutParams(0, -2, 1f))

            if (entry.id.startsWith("SC_")) {
                row.addView(TextView(this).apply {
                    text = "🗑"
                    textSize = 14f
                    setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
                    setOnClickListener {
                        MenuPrefs.removeShortcut(prefs, entry.id)
                        rebuildMenuOrderList()
                    }
                })
            }

            row.addView(TextView(this).apply {
                text = "▲"
                textSize = 13f
                setTextColor(if (idx > 0) Color.WHITE else Color.parseColor("#3A3A40"))
                setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
                setOnClickListener {
                    if (idx > 0) {
                        MenuPrefs.moveItem(prefs, entry.id, -1)
                        rebuildMenuOrderList()
                    }
                }
            })
            row.addView(TextView(this).apply {
                text = "▼"
                textSize = 13f
                setTextColor(if (idx < entries.lastIndex) Color.WHITE else Color.parseColor("#3A3A40"))
                setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
                setOnClickListener {
                    if (idx < entries.lastIndex) {
                        MenuPrefs.moveItem(prefs, entry.id, +1)
                        rebuildMenuOrderList()
                    }
                }
            })
            menuOrderContainer.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (6 * d).toInt() })
        }
    }

    private fun sectionTitle(title: String): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            text = title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#D0BCFF"))
            setPadding(0, (18 * d).toInt(), 0, (8 * d).toInt())
        }
    }

    private fun actionButton(label: String, onClick: () -> Unit): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1C23"))
                cornerRadius = 10f * d
                setStroke((1f * d).toInt(), Color.parseColor("#34343E"))
            }
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * d).toInt() }
            setOnClickListener { onClick() }
        }
    }

    private fun switchRow(title: String, checked: Boolean, onChanged: (Boolean) -> Unit): LinearLayout {
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#15151A"))
                cornerRadius = 10f * d
            }
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * d).toInt() }

            addView(TextView(context).apply {
                text = title
                textSize = 14f
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, -2, 1f))

            addView(Switch(context).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
            })
        }
    }

    private fun sliderRow(title: String, min: Int, max: Int, current: Int, onChanged: (Int) -> Unit): LinearLayout {
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#15151A"))
                cornerRadius = 10f * d
            }
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * d).toInt() }

            val valueLabel = TextView(context).apply {
                text = "$title: $current"
                textSize = 13f
                setTextColor(Color.WHITE)
            }
            addView(valueLabel)

            addView(SeekBar(context).apply {
                this.max = max - min
                progress = (current - min).coerceIn(0, max - min)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        val v = min + p
                        valueLabel.text = "$title: $v"
                        if (fromUser) onChanged(v)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            })
        }
    }
}
