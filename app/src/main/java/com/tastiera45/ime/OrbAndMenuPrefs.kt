package com.tastiera45.ime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
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
 * Activity trasparente istantanea per aprire il selettore nativo Android
 * dei metodi di immissione anche quando la tastiera non è in primo piano.
 */
class ImePickerActivity : Activity() {
    private var shown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !shown) {
            shown = true
            try {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showInputMethodPicker()
            } catch (_: Throwable) {
            }
            window.decorView.postDelayed({ finish() }, 250L)
        }
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
        DefaultEntry("SWITCH_STYLE", "Cambia Arco / Pop-up", true),
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
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
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
        isArcMode: Boolean,
        isListening: Boolean,
        isMouseOpen: Boolean,
        isIncognito: Boolean
    ): List<MenuEntry> {
        val shortcuts = loadShortcuts(p)
        val allIds = defaultEntries.map { it.id } + shortcuts.map { it.id }

        val savedOrder = (p.getString("menu_order", "") ?: "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

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
                "SWITCH_STYLE" -> if (isArcMode) "Passa a Pop-up" else "Passa a Arco"
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
            isArcMode = true,
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
