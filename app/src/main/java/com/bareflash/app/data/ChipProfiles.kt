package com.bareflash.app.data

import org.json.JSONObject

/**
 * Protocol settings remembered per chip (keyed by hardware code). When a chip with a saved
 * profile is detected, its settings are applied again, so reconnecting a known phone does
 * not mean opening Settings.
 */
object ChipProfiles {
    private const val KEY = "chip_profiles"

    private fun norm(hw: String): String =
        hw.trim().removePrefix("0x").removePrefix("0X").trimStart('0').lowercase().ifEmpty { "0" }

    private fun all(): JSONObject = runCatching { JSONObject(Extras.prefs()?.getString(KEY, null) ?: "{}") }.getOrDefault(JSONObject())

    fun has(hw: String): Boolean = all().has(norm(hw))

    fun save(hw: String, p: ProtocolSettings) {
        val o = all()
        o.put(norm(hw), JSONObject().apply {
            put("debugMode", p.debugMode); put("skipWatchdog", p.skipWatchdog)
            put("alreadyInPreloader", p.alreadyInPreloader); put("readSocId", p.readSocId)
            put("enforceCrash", p.enforceCrash); put("stockDa", p.stockDa); put("iotMode", p.iotMode)
            put("payloadType", p.payloadType); put("vid", p.vid); put("pid", p.pid)
            put("sectorSize", p.sectorSize); put("uartLogLevel", p.uartLogLevel)
        })
        Extras.prefs()?.edit()?.putString(KEY, o.toString())?.apply()
    }

    fun forget(hw: String) {
        val o = all()
        o.remove(norm(hw))
        Extras.prefs()?.edit()?.putString(KEY, o.toString())?.apply()
    }

    /** The saved settings laid over [cur] (file paths are never part of a profile), or null when none is saved. */
    fun applyTo(hw: String, cur: ProtocolSettings): ProtocolSettings? {
        val j = all().optJSONObject(norm(hw)) ?: return null
        return cur.copy(
            debugMode = j.optBoolean("debugMode", cur.debugMode),
            skipWatchdog = j.optBoolean("skipWatchdog", cur.skipWatchdog),
            alreadyInPreloader = j.optBoolean("alreadyInPreloader", cur.alreadyInPreloader),
            readSocId = j.optBoolean("readSocId", cur.readSocId),
            enforceCrash = j.optBoolean("enforceCrash", cur.enforceCrash),
            stockDa = j.optBoolean("stockDa", cur.stockDa),
            iotMode = j.optBoolean("iotMode", cur.iotMode),
            payloadType = j.optString("payloadType", cur.payloadType),
            vid = j.optString("vid", cur.vid),
            pid = j.optString("pid", cur.pid),
            sectorSize = j.optString("sectorSize", cur.sectorSize).ifBlank { "0x200" },
            uartLogLevel = j.optString("uartLogLevel", cur.uartLogLevel),
        )
    }
}
