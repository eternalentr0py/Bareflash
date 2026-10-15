package com.bareflash.app.data

import com.bareflash.app.engine.EngineMode
import com.bareflash.app.log.AppLog
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for user settings, command history and pinned
 * commands, stored in the "settings" preference file.
 */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state

    private val _history = MutableStateFlow(loadList("command_history"))
    val history: StateFlow<List<String>> = _history

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        _state.value = next
        save(next)
    }

    // ---- history ------------------------------------------------------

    fun recordCommand(mode: EngineMode, cmd: String) {
        val key = key(mode, cmd)
        val next = (listOf(key) + _history.value.filter { !same(it, mode, cmd) }).take(MAX_HISTORY)
        _history.value = next
        saveList("command_history", next)
    }

    fun clearHistory(mode: EngineMode? = null) {
        val next = if (mode == null) emptyList() else _history.value.filter { split(it).first != mode.id }
        _history.value = next
        saveList("command_history", next)
    }

    /** Commands recorded for [mode]. Entries saved before this split count as MediaTek. */
    fun commandsFor(mode: EngineMode, raw: List<String> = _history.value): List<String> =
        raw.mapNotNull { e -> split(e).let { (m, c) -> if (m == mode.id) c else null } }

    fun togglePinned(mode: EngineMode, cmd: String) = update { s ->
        val present = s.pinned.any { same(it, mode, cmd) }
        val next = if (present) s.pinned.filterNot { same(it, mode, cmd) } else s.pinned + key(mode, cmd)
        s.copy(pinned = next)
    }

    fun pinsFor(mode: EngineMode, pinned: List<String>): List<String> =
        pinned.mapNotNull { e -> split(e).let { (m, c) -> if (m == mode.id) c else null } }

    private fun key(mode: EngineMode, cmd: String) = mode.id + SEP + cmd

    private fun split(raw: String): Pair<String, String> {
        val i = raw.indexOf(SEP)
        return if (i <= 0) EngineMode.MTK.id to raw else raw.substring(0, i) to raw.substring(i + 1)
    }

    private fun same(raw: String, mode: EngineMode, cmd: String): Boolean {
        val (m, c) = split(raw)
        return m == mode.id && c == cmd
    }

    // ---- export / import ----------------------------------------------

    /**
     * Settings backup. File paths are excluded: they point at app-private
     * copies that do not survive a reinstall.
     *
     * Format 2 dropped the dead terminal repeat-filter fields and the keep-awake
     * switch (commands always keep the device awake). Format 1 files still import;
     * those keys are ignored.
     */
    fun exportJson(): String {
        val s = _state.value
        return JSONObject().apply {
            put("format", FORMAT)
            put("appearance", JSONObject().apply {
                put("themeMode", s.appearance.themeMode.name)
                put("oled", s.appearance.oled)
                put("preset", s.appearance.preset)
                put("accent", s.appearance.accent)
            })
            put("island", s.island.toJson())
            put("terminal", s.terminal.toJson())
            put("look", s.look.toJson())
            put("accessibility", s.accessibility.toJson())
            put("protocols", JSONObject().apply {
                val p = s.protocols
                put("debugMode", p.debugMode)
                put("skipWatchdog", p.skipWatchdog)
                put("alreadyInPreloader", p.alreadyInPreloader)
                put("readSocId", p.readSocId)
                put("useCustomFiles", p.useCustomFiles)
                put("enforceCrash", p.enforceCrash)
                put("stockDa", p.stockDa)
                put("iotMode", p.iotMode)
                put("payloadType", p.payloadType)
                put("vid", p.vid)
                put("pid", p.pid)
                put("sectorSize", p.sectorSize)
                put("uartLogLevel", p.uartLogLevel)
            })
            put("pinned", JSONArray(s.pinned))
            put("lastReviewArgs", s.lastReviewArgs.toArgsJson())
            put("general", JSONObject().apply {
                put("lastDumpPartition", s.general.lastDumpPartition)
                put("lastFlashPartition", s.general.lastFlashPartition)
                put("vibrateOnCommandEnd", s.general.vibrateOnCommandEnd)
                put("soundOnCommandEnd", s.general.soundOnCommandEnd)
                // dumpFolderUri is a device-specific SAF grant and is not portable.
            })
        }.toString(2)
    }

    /** Returns null on success, or a user-readable error message. */
    fun importJson(json: String): String? {
        return try {
            val o = JSONObject(json)
            if (o.optInt("format", -1) !in 1..FORMAT) return "This file is not a valid settings backup."
            update { cur ->
                val a = o.optJSONObject("appearance")
                val p = o.optJSONObject("protocols")
                cur.copy(
                    appearance = AppearanceSettings(
                        themeMode = runCatching { ThemeMode.valueOf(a?.optString("themeMode") ?: "") }.getOrDefault(cur.appearance.themeMode),
                        oled = a?.optBoolean("oled", cur.appearance.oled) ?: cur.appearance.oled,
                        preset = a?.optString("preset", cur.appearance.preset) ?: cur.appearance.preset,
                        accent = a?.optString("accent", cur.appearance.accent) ?: cur.appearance.accent
                    ),
                    island = islandFromJson(o.optJSONObject("island"), cur.island),
                    terminal = terminalFromJson(o.optJSONObject("terminal"), cur.terminal),
                    look = if (o.has("look")) lookFromJson(o.optJSONObject("look"), cur.look) else cur.look,
                    accessibility = accessibilityFromJson(o.optJSONObject("accessibility"), cur.accessibility),
                    protocols = cur.protocols.copy(
                        debugMode = p?.optBoolean("debugMode", false) ?: false,
                        skipWatchdog = p?.optBoolean("skipWatchdog", false) ?: false,
                        alreadyInPreloader = p?.optBoolean("alreadyInPreloader", false) ?: false,
                        readSocId = p?.optBoolean("readSocId", false) ?: false,
                        useCustomFiles = p?.optBoolean("useCustomFiles", false) ?: false,
                        enforceCrash = p?.optBoolean("enforceCrash", false) ?: false,
                        stockDa = p?.optBoolean("stockDa", false) ?: false,
                        iotMode = p?.optBoolean("iotMode", false) ?: false,
                        payloadType = p?.optString("payloadType", "") ?: "",
                        vid = p?.optString("vid", "") ?: "",
                        pid = p?.optString("pid", "") ?: "",
                        sectorSize = (p?.optString("sectorSize", "0x200") ?: "0x200").ifBlank { "0x200" },
                        uartLogLevel = p?.optString("uartLogLevel", "") ?: "",
                        // Paths stay local; only flags are portable across devices.
                    ),
                    pinned = o.optJSONArray("pinned")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: cur.pinned,
                    lastReviewArgs = if (o.has("lastReviewArgs")) argsFromJson(o.optJSONObject("lastReviewArgs")) else cur.lastReviewArgs,
                    general = run {
                        val g = o.optJSONObject("general")
                        GeneralSettings(
                            lastDumpPartition = g?.optString(
                                "lastDumpPartition",
                                cur.general.lastDumpPartition
                            ) ?: cur.general.lastDumpPartition,
                            lastFlashPartition = g?.optString(
                                "lastFlashPartition",
                                cur.general.lastFlashPartition
                            ) ?: cur.general.lastFlashPartition,
                            vibrateOnCommandEnd = g?.optBoolean(
                                "vibrateOnCommandEnd",
                                cur.general.vibrateOnCommandEnd
                            ) ?: cur.general.vibrateOnCommandEnd,
                            soundOnCommandEnd = g?.optBoolean(
                                "soundOnCommandEnd",
                                cur.general.soundOnCommandEnd
                            ) ?: cur.general.soundOnCommandEnd,
                            dumpFolderUri = cur.general.dumpFolderUri,
                            dumpFolderName = cur.general.dumpFolderName,
                        )
                    }
                )
            }
            null
        } catch (e: Exception) {
            AppLog.e("settings", "Settings backup import failed", e)
            "This file could not be read as a settings backup."
        }
    }

    // ---- persistence --------------------------------------------------

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            appearance = AppearanceSettings(
                themeMode = runCatching { ThemeMode.valueOf(prefs.getString("theme_mode", "") ?: "") }.getOrDefault(ThemeMode.SYSTEM),
                oled = prefs.getBoolean("theme_pure_black", d.appearance.oled),
                preset = prefs.getString("theme_preset", d.appearance.preset) ?: d.appearance.preset,
                accent = prefs.getString("theme_accent", d.appearance.accent) ?: d.appearance.accent
            ),
            protocols = ProtocolSettings(
                debugMode = prefs.getBoolean("p_debug", false),
                skipWatchdog = prefs.getBoolean("p_skipwdt", false),
                alreadyInPreloader = prefs.getBoolean("p_skipcrash", false),
                readSocId = prefs.getBoolean("p_socid", false),
                useCustomFiles = prefs.getBoolean("p_use_files", false),
                enforceCrash = prefs.getBoolean("p_crash", false),
                stockDa = prefs.getBoolean("p_stock", false),
                iotMode = prefs.getBoolean("p_iot", false),
                payloadType = prefs.getString("p_ptype", "") ?: "",
                vid = prefs.getString("p_vid", "") ?: "",
                pid = prefs.getString("p_pid", "") ?: "",
                sectorSize = prefs.getString("p_sectorsize", "0x200") ?: "0x200",
                uartLogLevel = prefs.getString("p_uartlog", "") ?: "",
                                preloaderPath = prefs.getString("preloader_path", "") ?: "",
                preloaderName = prefs.getString("preloader_name", "") ?: "",
                daPath = prefs.getString("loader_path", "") ?: "",
                daName = prefs.getString("loader_name", "") ?: "",
                authPath = prefs.getString("auth_path", "") ?: "",
                authName = prefs.getString("auth_name", "") ?: "",
                certPath = prefs.getString("cert_path", "") ?: "",
                certName = prefs.getString("cert_name", "") ?: "",
            ),
            general = GeneralSettings(
                lastDumpPartition = prefs.getString("last_dump_partition", "") ?: "",
                lastFlashPartition = prefs.getString("last_flash_partition", "") ?: "",
                vibrateOnCommandEnd = prefs.getBoolean("vibrate_on_command_end", true),
                soundOnCommandEnd = prefs.getBoolean("sound_on_command_end", false),
                dumpFolderUri = prefs.getString("dump_folder_uri", "") ?: "",
                dumpFolderName = prefs.getString("dump_folder_name", "") ?: "",
            ),
            island = prefs.getString(ISLAND_KEY, null)?.let { raw ->
                runCatching { islandFromJson(JSONObject(raw), d.island) }.getOrNull()
            } ?: d.island,
            terminal = prefs.getString(TERMINAL_KEY, null)?.let { raw ->
                runCatching { terminalFromJson(JSONObject(raw), d.terminal) }.getOrNull()
            } ?: d.terminal,
            look = prefs.getString(LOOK_KEY, null)?.let { raw ->
                runCatching { lookFromJson(JSONObject(raw), d.look) }.getOrNull()
            } ?: d.look,
            accessibility = prefs.getString(A11Y_KEY, null)?.let { raw ->
                runCatching { accessibilityFromJson(JSONObject(raw), d.accessibility) }.getOrNull()
            } ?: d.accessibility,
            pinned = if (prefs.contains("pinned")) loadList("pinned") else AppSettings.DEFAULT_PINNED,
            lastReviewArgs = prefs.getString("last_review_args", null)?.let { raw ->
                runCatching { argsFromJson(JSONObject(raw)) }.getOrNull()
            } ?: emptyMap()
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .remove("dedup_enabled")
            .remove("dedup_window_ms")
            .remove("keep_awake")
            .putString("theme_mode", s.appearance.themeMode.name)
            .putBoolean("theme_pure_black", s.appearance.oled)
            .putString("theme_preset", s.appearance.preset)
            .putString("theme_accent", s.appearance.accent)
            .putString(ISLAND_KEY, s.island.toJson().toString())
            .putString(TERMINAL_KEY, s.terminal.toJson().toString())
            .putString(LOOK_KEY, s.look.toJson().toString())
            .putString(A11Y_KEY, s.accessibility.toJson().toString())
            .putBoolean("p_debug", s.protocols.debugMode)
            .putBoolean("p_skipwdt", s.protocols.skipWatchdog)
            .putBoolean("p_skipcrash", s.protocols.alreadyInPreloader)
            .putBoolean("p_socid", s.protocols.readSocId)
            .putBoolean("p_use_files", s.protocols.useCustomFiles)
            .putBoolean("p_crash", s.protocols.enforceCrash)
            .putBoolean("p_stock", s.protocols.stockDa)
            .putBoolean("p_iot", s.protocols.iotMode)
            .putString("p_ptype", s.protocols.payloadType)
            .putString("p_vid", s.protocols.vid)
            .putString("p_pid", s.protocols.pid)
            .putString("p_sectorsize", s.protocols.sectorSize)
            .putString("p_uartlog", s.protocols.uartLogLevel)
            .putString("preloader_path", s.protocols.preloaderPath)
            .putString("preloader_name", s.protocols.preloaderName)
            .putString("loader_path", s.protocols.daPath)
            .putString("loader_name", s.protocols.daName)
            .putString("auth_path", s.protocols.authPath)
            .putString("auth_name", s.protocols.authName)
            .putString("cert_path", s.protocols.certPath)
            .putString("cert_name", s.protocols.certName)
            .putString("last_dump_partition", s.general.lastDumpPartition)
            .putString("last_flash_partition", s.general.lastFlashPartition)
            .putBoolean("vibrate_on_command_end", s.general.vibrateOnCommandEnd)
            .putBoolean("sound_on_command_end", s.general.soundOnCommandEnd)
            .putString("dump_folder_uri", s.general.dumpFolderUri)
            .putString("dump_folder_name", s.general.dumpFolderName)
            .putString("pinned", JSONArray(s.pinned).toString())
            .putString("last_review_args", s.lastReviewArgs.toArgsJson().toString())
            .apply()
    }

    private fun loadList(key: String): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun saveList(key: String, list: List<String>) {
        prefs.edit().putString(key, JSONArray(list).toString()).apply()
    }

    private companion object {
        const val MAX_HISTORY = 50
        const val FORMAT = 2
        private const val SEP = "\u001f"
        /** Same name the web preview persists its island blob under. */
        const val ISLAND_KEY = "night-circuit-island-v1"
        const val LOOK_KEY = "bareflash-look-v1"
        const val TERMINAL_KEY = "bareflash-terminal-v1"
        const val A11Y_KEY = "bareflash-a11y-v1"
    }
}
