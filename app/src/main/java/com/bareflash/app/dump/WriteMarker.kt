package com.bareflash.app.dump

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * "A write is in progress" flag, saved before a destructive command starts and
 * cleared only when it succeeds. If the cable is pulled, the app dies or the user
 * presses Stop, the flag is still there next time and the app says so: a
 * half-written partition is the one situation where the next step matters most.
 */
object WriteMarker {
    private const val PREFS = "write_marker"

    data class Record(
        /** MTK Client / QDL / Samsung. */
        val engine: String,
        /** What was being written, for the banner: "boot", "flash", "3 Samsung packages". */
        val target: String,
        /** The command as the user ran it (re-run uses this). Blank when it cannot be re-run. */
        val command: String,
        val startedAt: Long,
        /** "running" while it runs; "error" / "stopped" once the run ended without success. */
        val state: String,
    ) {
        val canRerun: Boolean get() = command.isNotBlank()
    }

    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun current(): Record? = runCatching {
        val j = JSONObject(prefs?.getString("rec", null) ?: return null)
        Record(
            j.getString("engine"), j.getString("target"), j.optString("command", ""),
            j.getLong("at"), j.optString("state", "running")
        )
    }.getOrNull()

    fun begin(engine: String, target: String, command: String): Record {
        val r = Record(engine, target, command, System.currentTimeMillis(), "running")
        save(r)
        return r
    }

    fun end(state: String): Record? {
        val r = current()?.copy(state = state) ?: return null
        save(r)
        return r
    }

    fun clear() { prefs?.edit()?.remove("rec")?.apply() }

    private fun save(r: Record) {
        val j = JSONObject().put("engine", r.engine).put("target", r.target)
            .put("command", r.command).put("at", r.startedAt).put("state", r.state)
        prefs?.edit()?.putString("rec", j.toString())?.apply()
    }
}
