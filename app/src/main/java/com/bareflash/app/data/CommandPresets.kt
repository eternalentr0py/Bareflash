package com.bareflash.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Named, fully filled-in commands the user saved from the review sheet. Running one goes
 * through the same checks and confirmation as typing it. Files attached from outside the
 * dump folder are remembered by name only, are remembered by their saved permission; if it is gone the preset says which file is missing.
 */
object CommandPresets {
    data class Preset(
        val name: String,
        val command: String,
        val mode: String,
        /** Files attached from outside the dump folder, as (name, persisted uri). */
        val attachments: List<Pair<String, String>> = emptyList(),
    )

    private const val PREFS = "command_presets"
    private var ctx: Context? = null
    private val _items = MutableStateFlow<List<Preset>>(emptyList())
    val items: StateFlow<List<Preset>> = _items

    fun init(app: Context) {
        ctx = app.applicationContext
        val raw = ctx!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("items", null) ?: return
        _items.value = runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                val att = o.optJSONArray("files")
                Preset(
                    o.getString("name"), o.getString("command"), o.getString("mode"),
                    if (att == null) emptyList() else (0 until att.length()).map {
                        val f = att.getJSONObject(it); f.getString("name") to f.getString("uri")
                    }
                )
            }
        }.getOrDefault(emptyList())
    }

    fun add(name: String, command: String, mode: String, attachments: List<Pair<String, String>> = emptyList()) {
        val n = name.trim()
        if (n.isEmpty() || command.isBlank()) return
        _items.value = _items.value.filterNot { it.name == n && it.mode == mode } + Preset(n, command.trim(), mode, attachments)
        save()
    }

    fun remove(p: Preset) {
        _items.value = _items.value - p
        save()
    }

    private fun save() {
        val a = JSONArray()
        _items.value.forEach { a.put(
            JSONObject().put("name", it.name).put("command", it.command).put("mode", it.mode).put(
                "files", JSONArray().also { arr -> it.attachments.forEach { (n, u) -> arr.put(JSONObject().put("name", n).put("uri", u)) } }
            )
        ) }
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString("items", a.toString())?.apply()
    }
}
