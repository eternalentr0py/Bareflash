package com.bareflash.app.data

import org.json.JSONArray
import org.json.JSONObject

/** Serialises remembered review-sheet arguments: {"r": ["boot", "boot.img"], ...}. */
internal fun Map<String, List<String>>.toArgsJson(): JSONObject = JSONObject().also { o ->
    forEach { (cmd, args) -> o.put(cmd, JSONArray(args)) }
}

/** Reads them back; anything malformed is skipped rather than failing the whole load. */
internal fun argsFromJson(o: JSONObject?): Map<String, List<String>> {
    if (o == null) return emptyMap()
    val out = LinkedHashMap<String, List<String>>()
    for (key in o.keys()) {
        val arr = o.optJSONArray(key) ?: continue
        out[key] = List(arr.length()) { arr.optString(it, "") }
    }
    return out
}
