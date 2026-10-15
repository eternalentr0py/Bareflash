package com.bareflash.app.samsung

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every Samsung flash that was started (what, which phone, how it ended) and named sets of
 * packages to reuse. Stored in app storage; no firmware file is copied, a set only remembers
 * the files' persisted URIs.
 */
object FlashHistory {

    data class Entry(
        val at: Long,
        val packages: List<String>,
        val model: String,
        /** "success", "failed" or "stopped". */
        val outcome: String,
        val elapsedMs: Long,
        val bytes: Long,
        val message: String,
    )

    data class PackageSet(val name: String, val items: List<Pair<String, String>>)

    private const val MAX_ENTRIES = 50
    private var prefs: SharedPreferences? = null

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    private val _sets = MutableStateFlow<List<PackageSet>>(emptyList())
    val sets: StateFlow<List<PackageSet>> = _sets

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("samsung_history", Context.MODE_PRIVATE)
        prefs = p
        _entries.value = runCatching {
            val a = JSONArray(p.getString("entries", "[]"))
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Entry(
                    o.getLong("at"),
                    o.getJSONArray("packages").let { arr -> (0 until arr.length()).map { i -> arr.getString(i) } },
                    o.optString("model"), o.optString("outcome"), o.optLong("elapsed"), o.optLong("bytes"), o.optString("message"),
                )
            }
        }.getOrDefault(emptyList())
        _sets.value = runCatching {
            val a = JSONArray(p.getString("sets", "[]"))
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                PackageSet(
                    o.getString("name"),
                    o.getJSONArray("items").let { arr ->
                        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { f -> f.getString("name") to f.getString("uri") } }
                    }
                )
            }
        }.getOrDefault(emptyList())
    }

    fun record(e: Entry) {
        _entries.value = (listOf(e) + _entries.value).take(MAX_ENTRIES)
        saveEntries()
    }

    fun clearEntries() {
        _entries.value = emptyList()
        saveEntries()
    }

    fun saveSet(name: String, items: List<Pair<String, String>>) {
        val n = name.trim()
        if (n.isEmpty() || items.isEmpty()) return
        _sets.value = _sets.value.filterNot { it.name == n } + PackageSet(n, items)
        saveSets()
    }

    fun deleteSet(name: String) {
        _sets.value = _sets.value.filterNot { it.name == name }
        saveSets()
    }

    private fun saveEntries() {
        val a = JSONArray()
        _entries.value.forEach { e ->
            a.put(
                JSONObject().put("at", e.at).put("packages", JSONArray(e.packages)).put("model", e.model)
                    .put("outcome", e.outcome).put("elapsed", e.elapsedMs).put("bytes", e.bytes).put("message", e.message)
            )
        }
        prefs?.edit()?.putString("entries", a.toString())?.apply()
    }

    private fun saveSets() {
        val a = JSONArray()
        _sets.value.forEach { s ->
            a.put(
                JSONObject().put("name", s.name).put(
                    "items", JSONArray().also { arr -> s.items.forEach { (n, u) -> arr.put(JSONObject().put("name", n).put("uri", u)) } }
                )
            )
        }
        prefs?.edit()?.putString("sets", a.toString())?.apply()
    }
}
