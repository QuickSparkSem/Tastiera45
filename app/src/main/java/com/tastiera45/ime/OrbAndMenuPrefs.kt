package com.tastiera45.ime

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import org.json.JSONArray
import org.json.JSONObject

data class MenuEntry(
    val id: String,
    val title: String,
    val enabled: Boolean
)

data class CustomShortcut(
    val id: String,
    val label: String,
    val type: String,
    val payload: String
)

object OrbBridge {
    var activeIme: Tastiera45Service? = null
    var overlayService: FloatingOrbService? = null

    fun applySwipeCandidate(word: String) {
        activeIme?.replaceLastWordWith(word)
    }
}

/**
 * Activity trasparente dedicata all'apertura del selettore nativo Android
 * dei metodi di immissione da qualsiasi schermata o tastiera (inclusa Samsung).
 * Resta attiva finché l'utente non sceglie una tastiera o chiude la finestra.
 */
class ImePickerActivity : Activity() {
    private var pickerOpened = false
    private val handler = Handler(Looper.getMainLooper())
    private val safetyFinish = Runnable {
        if (!isFinishing) {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        handler.postDelayed(safetyFinish, 15000L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        if (!pickerOpened) {
            pickerOpened = true
            handler.postDelayed({
                try {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showInputMethodPicker()
                } catch (_: Throwable) {
                    finish()
                }
            }, 110L)
        } else {
            handler.removeCallbacks(safetyFinish)
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

object MenuPrefs {
    const val NOTUNE_PKG = "dev.notune.transcribe"

    private data class DefaultEntry(val id: String, val baseTitle: String, val defaultEnabled: Boolean)

    private val defaultEntries = listOf(
        DefaultEntry("PICK_IME", "Scegli Tastiera (Menu Android)", true),
        DefaultEntry("VOICE", "Dettatura Vocale (Compatta)", true),
        DefaultEntry("LAUNCH_KB_45", "Tastiera 45°", true),
        DefaultEntry("LAUNCH_KB_SAMSUNG", "Tastiera Samsung", true),
        DefaultEntry("TOGGLE_KB", "Mostra / Nascondi Tastiera", true),
        DefaultEntry("MOUSE", "Cursore Mouse", true),
        DefaultEntry("CLIPBOARD", "Appunti", true),
        DefaultEntry("TRANSLATE", "Traduci", true),
        DefaultEntry("INCOGNITO", "Modalità Incognito", true),
        DefaultEntry("SETTINGS", "Impostazioni Tastiera 45°", true)
    )

    fun openSystemImePicker(context: Context) {
        val ime = OrbBridge.activeIme
        if (ime != null) {
            ime.showAndroidImePicker()
            return
        }
        try {
            context.startActivity(
                Intent(context, ImePickerActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                }
            )
        } catch (_: Throwable) {
            try {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showInputMethodPicker()
            } catch (_: Throwable) {
            }
        }
    }

    fun switchToImeOrPicker(
        context: Context,
        targetPackageMatch: (String) -> Boolean,
        preferCompactSubtype: Boolean = false
    ) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val targetImi = imm?.enabledInputMethodList?.firstOrNull { targetPackageMatch(it.packageName) }

        var chosenSubtype: InputMethodSubtype? = null
        if (targetImi != null && preferCompactSubtype) {
            for (i in 0 until targetImi.subtypeCount) {
                val st = targetImi.getSubtypeAt(i)
                val label = st.getDisplayName(
                    context,
                    targetImi.packageName,
                    targetImi.serviceInfo.applicationInfo
                ).toString()
                val meta = "${st.mode} ${st.extraValue} $label".lowercase()
                if (st.mode.equals("voice", ignoreCase = true) ||
                    meta.contains("compact") || meta.contains("compatta") ||
                    meta.contains("mini") || meta.contains("float") ||
                    meta.contains("overlay") || meta.contains("pill") || meta.contains("bar")
                ) {
                    chosenSubtype = st
                    break
                }
            }
        }

        val activeIme = OrbBridge.activeIme
        if (activeIme != null && targetImi != null) {
            try {
                if (chosenSubtype != null) {
                    activeIme.switchInputMethod(targetImi.id, chosenSubtype)
                } else {
                    activeIme.switchInputMethod(targetImi.id)
                }
                return
            } catch (_: Throwable) {
            }
        }

        // Se un'altra tastiera (es. Samsung) è attiva, proviamo cambio diretto se permesso ADB presente,
        // altrimenti apriamo subito il selettore nativo Android tramite ImePickerActivity.
        if (targetImi != null &&
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.DEFAULT_INPUT_METHOD,
                    targetImi.id
                )
                return
            } catch (_: Throwable) {
            }
        }

        openSystemImePicker(context)
    }

    fun getBool(p: SharedPreferences, key: String, def: Boolean): Boolean =
        if (p.contains(key)) p.getBoolean(key, def) else def

    fun getInt(p: SharedPreferences, key: String, def: Int): Int {
        if (!p.contains(key)) return def
        return when (val v = p.all[key]) {
            is Int -> v
            is Long -> v.toInt()
            is Float -> v.toInt()
            is Double -> v.toInt()
            is String -> v.toIntOrNull() ?: def
            else -> def
        }
    }

    fun getFloat(p: SharedPreferences, key: String, def: Float): Float {
        if (!p.contains(key)) return def
        return when (val v = p.all[key]) {
            is Float -> v
            is Double -> v.toFloat()
            is Int -> v.toFloat()
            is Long -> v.toFloat()
            is String -> v.toFloatOrNull() ?: def
            else -> def
        }
    }

    fun getString(p: SharedPreferences, key: String, def: String?): String? {
        if (!p.contains(key)) return def
        val v = p.all[key] ?: return def
        return v.toString()
    }

    fun loadShortcuts(p: SharedPreferences): List<CustomShortcut> {
        val raw = p.getString("custom_shortcuts_json", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<CustomShortcut>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                val label = obj.optString("label").takeIf { it.isNotBlank() } ?: continue
                val type = obj.optString("type", "TEXT")
                val payload = obj.optString("payload")
                out.add(CustomShortcut(id, label, type, payload))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun saveShortcuts(p: SharedPreferences, list: List<CustomShortcut>) {
        val arr = JSONArray()
        for (sc in list) {
            arr.put(JSONObject().apply {
                put("id", sc.id)
                put("label", sc.label)
                put("type", sc.type)
                put("payload", sc.payload)
            })
        }
        p.edit().putString("custom_shortcuts_json", arr.toString()).apply()
    }

    fun addTextShortcut(p: SharedPreferences, label: String, text: String) {
        val current = loadShortcuts(p).toMutableList()
        val id = "SC_${System.currentTimeMillis()}"
        current.add(CustomShortcut(id = id, label = label, type = "TEXT", payload = text))
        saveShortcuts(p, current)
    }

    fun removeShortcut(p: SharedPreferences, id: String) {
        val updated = loadShortcuts(p).filterNot { it.id == id }
        saveShortcuts(p, updated)
    }

    fun getEntries(
        p: SharedPreferences,
        isKbVisible: Boolean,
        isListening: Boolean,
        isMouseOpen: Boolean,
        isIncognito: Boolean
    ): List<MenuEntry> {
        val shortcuts = loadShortcuts(p)
        val allIds = defaultEntries.map { it.id } + shortcuts.map { it.id }

        val savedOrder = (p.getString("menu_order", "") ?: "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "SWITCH_STYLE" }

        val orderedIds = LinkedHashSet<String>()
        for (id in savedOrder) {
            if (id in allIds) orderedIds.add(id)
        }
        for (id in allIds) {
            orderedIds.add(id)
        }

        val disabledSet = (p.getString("menu_disabled", "") ?: "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        val defaultMap = defaultEntries.associateBy { it.id }
        val shortcutMap = shortcuts.associateBy { it.id }

        return orderedIds.mapNotNull { id ->
            val def = defaultMap[id]
            val sc = shortcutMap[id]
            if (def == null && sc == null) return@mapNotNull null

            val title = when (id) {
                "TOGGLE_KB" -> if (isKbVisible) "Nascondi Tastiera" else "Mostra Tastiera"
                "VOICE" -> if (isListening) "Stop Dettatura" else "Dettatura Vocale (Compatta)"
                "MOUSE" -> if (isMouseOpen) "Chiudi Cursore" else "Cursore Mouse"
                "INCOGNITO" -> if (isIncognito) "Esci da Incognito" else "Modalità Incognito"
                else -> def?.baseTitle ?: "⚡ ${sc!!.label}"
            }

            val enabled = if (p.contains("menu_disabled")) {
                id !in disabledSet
            } else {
                def?.defaultEnabled ?: true
            }

            MenuEntry(id = id, title = title, enabled = enabled)
        }
    }

    fun toggleVisibility(p: SharedPreferences, id: String) {
        val disabled = (p.getString("menu_disabled", "") ?: "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableSet()

        if (id in disabled) disabled.remove(id) else disabled.add(id)
        p.edit().putString("menu_disabled", disabled.joinToString(",")).apply()
    }

    fun moveItem(p: SharedPreferences, id: String, delta: Int) {
        val current = getEntries(
            p = p,
            isKbVisible = false,
            isListening = false,
            isMouseOpen = false,
            isIncognito = false
        ).map { it.id }.toMutableList()

        val idx = current.indexOf(id)
        if (idx < 0) return
        val target = (idx + delta).coerceIn(0, current.lastIndex)
        if (idx == target) return

        val item = current.removeAt(idx)
        current.add(target, item)
        p.edit().putString("menu_order", current.joinToString(",")).apply()
    }
}
