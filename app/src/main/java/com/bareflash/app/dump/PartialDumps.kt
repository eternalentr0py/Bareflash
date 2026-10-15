package com.bareflash.app.dump

import android.content.Context
import android.content.SharedPreferences
import com.bareflash.app.console.DumpSpaceGuard
import com.bareflash.app.console.GptPartition
import org.json.JSONObject

/**
 * Dumps that were started and have not finished. A record is written when a
 * single-file read begins and removed when it succeeds, so anything left over
 * (cable pulled, crash, Stop) can be continued from where the file ends.
 */
object PartialDumps {
    private const val PREFS = "partial_dumps"
    private const val KEY = "rec:"

    /**
     * [rel] file under the dump folder; [baseOffset] where the data starts in flash;
     * [total] bytes the read will produce (0 when not known yet).
     */
    data class Record(
        val rel: String,
        val command: String,
        val baseOffset: Long,
        val total: Long,
        val startedAt: Long,
        /** The command that read several files at once ("r a,b,c ..." or "rl <folder>"); blank for a single file. */
        val group: String = "",
    )

    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun put(r: Record) {
        val j = JSONObject()
            .put("rel", r.rel).put("command", r.command)
            .put("base", r.baseOffset).put("total", r.total).put("at", r.startedAt).put("group", r.group)
        prefs?.edit()?.putString(KEY + r.rel, j.toString())?.apply()
    }

    fun get(rel: String): Record? = parse(prefs?.getString(KEY + rel, null))

    fun remove(rel: String) { prefs?.edit()?.remove(KEY + rel)?.apply() }

    fun all(): Map<String, Record> {
        val p = prefs ?: return emptyMap()
        val out = LinkedHashMap<String, Record>()
        for ((k, v) in p.all) {
            if (!k.startsWith(KEY)) continue
            parse(v as? String)?.let { out[it.rel] = it }
        }
        return out
    }

    private fun parse(raw: String?): Record? = runCatching {
        val j = JSONObject(raw ?: return null)
        Record(j.getString("rel"), j.getString("command"), j.getLong("base"), j.getLong("total"), j.getLong("at"), j.optString("group", ""))
    }.getOrNull()

    /**
     * The record for a read command that writes exactly one file: `r <partition> <file>`
     * (needs the partition table) or `rf <file>`. Anything else cannot be resumed.
     */
    fun trackable(command: String, gpt: List<GptPartition>): Record? {
        val t = DumpSpaceGuard.tokenize(command)
        val pos = DumpSpaceGuard.positionals(t)
        val now = System.currentTimeMillis()
        return when (t.firstOrNull()) {
            "r" -> {
                val names = pos.getOrNull(0)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                val files = pos.getOrNull(1)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                if (names == null || files == null || names.size != 1 || files.size != 1) return null
                val part = gpt.firstOrNull { it.name.equals(names[0], ignoreCase = true) } ?: return null
                val rel = relOf(files[0]) ?: return null
                Record(rel, command, part.offset, part.length, now)
            }
            "rf" -> {
                val rel = relOf(pos.getOrNull(0) ?: return null) ?: return null
                Record(rel, command, 0L, 0L, now)
            }
            else -> null
        }
    }

    /** Every record that belongs to one multi-file read. */
    fun inGroup(group: String): List<Record> = all().values.filter { it.group == group && group.isNotEmpty() }

    /**
     * One record per file for a read that writes several: `r a,b,c x,y,z` and `rl <folder>`
     * (once the partition table is known). A single-file `r` or `rf` still goes through [trackable].
     */
    fun trackableAll(command: String, gpt: List<GptPartition>): List<Record> {
        val t = DumpSpaceGuard.tokenize(command)
        val pos = DumpSpaceGuard.positionals(t)
        val now = System.currentTimeMillis()
        when (t.firstOrNull()) {
            "r" -> {
                val names = pos.getOrNull(0)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return emptyList()
                val files = pos.getOrNull(1)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return emptyList()
                if (names.size < 2 || names.size != files.size) return emptyList()
                val out = ArrayList<Record>()
                for (i in names.indices) {
                    val part = gpt.firstOrNull { it.name.equals(names[i], ignoreCase = true) } ?: return emptyList()
                    val rel = relOf(files[i]) ?: return emptyList()
                    out += Record(rel, command, part.offset, part.length, now, command)
                }
                return out
            }
            "rl" -> {
                val dir = relOf(pos.getOrNull(0) ?: return emptyList()) ?: return emptyList()
                if (gpt.isEmpty()) return emptyList()
                return gpt.filter { it.length > 0L }.map { Record("$dir/${it.name}.bin", command, it.offset, it.length, now, command) }
            }
        }
        return emptyList()
    }

    private fun relOf(arg: String): String? {
        val a = arg.trim().trim('"').removePrefix("dumps/")
        return if (a.isBlank() || a.startsWith("/")) null else a
    }
}
