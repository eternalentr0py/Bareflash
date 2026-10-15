package com.bareflash.app.console

import android.os.StatFs
import com.bareflash.app.dump.DumpBridge
import java.io.File

/**
 * Blocks dump commands that would almost certainly run out of space at the
 * dump destination (the user-chosen SAF folder, or the app's `dumps/`
 * folder). Exact GPT sizes are used when a live table is available.
 *
 * If free space cannot be read, the command is allowed through rather than
 * failing closed.
 */
object DumpSpaceGuard {

    const val HEADROOM_BYTES = 32L * 1024 * 1024
    const val MB = 1024L * 1024
    const val GB = 1024L * MB

    sealed class Verdict {
        data object NotADump : Verdict()
        data object Ok : Verdict()
        data class Blocked(
            val available: Long,
            val required: Long,
            val exact: Boolean,
        ) : Verdict() {
            fun userMessage(): String {
                val have = DumpSpaceGuard.formatBytes(available)
                val need = DumpSpaceGuard.formatBytes(required)
                val dest = DumpBridge.displayName()
                return if (exact) {
                    "Not enough free space: needs $need, $dest has $have free."
                } else {
                    "Not enough free space: needs at least $need, $dest has $have free."
                }
            }

            fun detail(command: String, dumpsDir: File): String = buildString {
                append("Command: ").append(command).append('\n')
                append("Free: ").append(DumpSpaceGuard.formatBytes(available)).append('\n')
                append(if (exact) "Needed: " else "Needed (lower bound): ")
                append(DumpSpaceGuard.formatBytes(required)).append('\n')
                append("Dump folder: ").append(DumpBridge.displayName()).append('\n')
                if (!DumpBridge.isEnabled()) {
                    append("Path: ").append(dumpsDir.absolutePath).append('\n')
                }
                if (!exact) {
                    append("Userdata and full-flash dumps are often much larger than this lower bound.\n")
                }
            }
        }
    }

    fun availableBytes(dir: File): Long {
        val fromDest = runCatching { DumpBridge.availableBytes() }.getOrDefault(-1L)
        if (fromDest >= 0L) return fromDest
        runCatching { if (!dir.exists()) dir.mkdirs() }
        return try {
            StatFs(dir.absolutePath).availableBytes
        } catch (_: Exception) {
            -1L
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes / 1024.0
        var i = 0
        while (value >= 1024 && i < units.size - 1) {
            value /= 1024.0
            i++
        }
        val digits = if (value >= 10) 0 else 1
        return String.format("%.${digits}f %s", value, units[i])
    }

    fun check(command: String, dumpsDir: File, sectorSizeHex: String = "0x200", gpt: List<GptPartition> = emptyList()): Verdict {
        val tokens = tokenize(command)
        if (tokens.isEmpty() || !isDump(tokens)) return Verdict.NotADump
        val available = availableBytes(dumpsDir)
        if (available < 0L) return Verdict.Ok
        val sectorSize = parseByteCount(flagValue(tokens, "--sectorsize") ?: sectorSizeHex)
            ?: 0x200L
        val estimate = estimateBytes(tokens, sectorSize, gpt) ?: return Verdict.Ok
        val required = estimate.bytes + HEADROOM_BYTES
        return if (available < required) {
            Verdict.Blocked(available, required, exact = estimate.exact)
        } else {
            Verdict.Ok
        }
    }

    /** What [checkQdl] found: a verdict, plus a line to log when nothing could be proven. */
    data class QdlCheck(val verdict: Verdict, val advisory: String?)

    /**
     * Free-space check for QDL `read`, `readxml` and `ramdump`. Blocks only when the
     * length is known (sector range, or `<read>` entries in the XML) and does not fit.
     * An unknown length or unreadable free space is reported, not silently passed.
     *
     * @param resolve turns a file argument into a readable file (XML for readxml), or null.
     */
    fun checkQdl(command: String, dumpsDir: File, storage: String, resolve: (String) -> File?): QdlCheck {
        val t = tokenize(command)
        val verb = t.firstOrNull()
        if (verb !in setOf("read", "readxml", "ramdump")) return QdlCheck(Verdict.NotADump, null)
        val dest = DumpBridge.displayName()
        val available = availableBytes(dumpsDir)
        if (available < 0L) {
            return QdlCheck(Verdict.Ok, "Free space in $dest could not be read, so it was not checked.")
        }
        val sector = if (storage == "ufs" || storage == "nvme") 4096L else 512L
        val bytes: Long? = when (verb) {
            "read" -> Regex("^(?:\\d+/)?\\d+\\+(\\d+)$").find(t.getOrNull(1).orEmpty())
                ?.groupValues?.get(1)?.toLongOrNull()?.let { it * sector }
            "readxml" -> xmlReadBytes(t.getOrNull(1).orEmpty(), sector, resolve)
            else -> null
        }
        if (bytes == null) {
            return QdlCheck(
                Verdict.Ok,
                "The size of this read is not known up front. ${formatBytes(available)} free in $dest; keep an eye on it."
            )
        }
        val required = bytes + HEADROOM_BYTES
        return if (available < required) QdlCheck(Verdict.Blocked(available, required, exact = true), null)
        else QdlCheck(Verdict.Ok, null)
    }

    private fun xmlReadBytes(arg: String, defaultSector: Long, resolve: (String) -> File?): Long? {
        var total = 0L
        var found = false
        for (name in arg.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val text = runCatching { resolve(name)?.takeIf { it.isFile && it.length() < 8L * MB }?.readText() }.getOrNull()
                ?: return null
            for (m in Regex("<read\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(text)) {
                val tag = m.value
                val count = Regex("num_partition_sectors\\s*=\\s*\"(\\d+)\"").find(tag)?.groupValues?.get(1)?.toLongOrNull() ?: continue
                val size = Regex("SECTOR_SIZE_IN_BYTES\\s*=\\s*\"(\\d+)\"").find(tag)?.groupValues?.get(1)?.toLongOrNull() ?: defaultSector
                total += count * size
                found = true
            }
        }
        return if (found) total else null
    }

    /**
     * The biggest single file a dump command will create, and whether that size is exact.
     * Null when it is not a dump or nothing is known. Used for the FAT32 4 GiB limit.
     */
    internal fun largestFile(command: String, sectorSizeHex: String, gpt: List<GptPartition>): Pair<Long, Boolean>? {
        val t = tokenize(command)
        if (t.isEmpty() || !isDump(t)) return null
        val sector = parseByteCount(flagValue(t, "--sectorsize") ?: sectorSizeHex) ?: 0x200L
        return when (t[0]) {
            "r" -> {
                val names = positionals(t).getOrNull(0)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: return null
                if (names.isEmpty()) return null
                val allKnown = names.all { n -> gpt.any { it.name.equals(n, true) } }
                names.maxOf { estimatePartition(it, gpt) } to allKnown
            }
            "rl" -> if (gpt.isNotEmpty()) gpt.maxOf { it.length } to true else null
            else -> estimateBytes(t, sector, gpt)?.let { it.bytes to it.exact }
        }
    }

    internal fun isDump(tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return false
        return when (tokens[0]) {
            "r", "rl", "rf", "rs", "ro", "footer", "gpt",
            "dumpbrom", "dumpsram", "dumppreloader" -> true
            "peek" -> flagValue(tokens, "--filename") != null
            "da" -> when (tokens.getOrNull(1)) {
                "dumpbrom", "memdump", "memdram" -> true
                "rpmb" -> tokens.getOrNull(2) == "r"
                "peek" -> flagValue(tokens, "--filename") != null
                else -> false
            }
            else -> false
        }
    }

    private data class Estimate(val bytes: Long, val exact: Boolean)

    private fun estimateBytes(tokens: List<String>, sectorSize: Long, gpt: List<GptPartition>): Estimate? {
        flagValue(tokens, "--length")?.let { raw ->
            parseByteCount(raw)?.let { return Estimate(it, exact = true) }
        }
        val pos = positionals(tokens)
        return when (tokens[0]) {
            "ro" -> parseByteCount(pos.getOrNull(1) ?: "")?.let { Estimate(it, exact = true) }
            "rs" -> parseByteCount(pos.getOrNull(1) ?: "")?.let { count ->
                Estimate(count * sectorSize, exact = true)
            }
            "r" -> {
                val names = pos.getOrNull(0)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: return Estimate(512L * MB, exact = false)
                val allKnown = names.all { n -> gpt.any { it.name.equals(n, true) } }
                Estimate(names.sumOf { estimatePartition(it, gpt) }, exact = allKnown && names.isNotEmpty())
            }
            "rl", "rf" -> Estimate(8L * GB, exact = false)
            "footer", "gpt",
            "dumpbrom", "dumppreloader" -> Estimate(16L * MB, exact = false)
            "dumpsram" -> Estimate(64L * MB, exact = false)
            "peek" -> parseByteCount(pos.getOrNull(1) ?: "")?.let { Estimate(it, exact = true) }
                ?: Estimate(16L * MB, exact = false)
            "da" -> when (tokens.getOrNull(1)) {
                "rpmb" -> Estimate(16L * MB, exact = false)
                "dumpbrom" -> Estimate(16L * MB, exact = false)
                "memdump", "memdram" -> Estimate(8L * GB, exact = false)
                "peek" -> parseByteCount(pos.getOrNull(1) ?: "")?.let { Estimate(it, exact = true) }
                    ?: Estimate(16L * MB, exact = false)
                else -> null
            }
            else -> null
        }
    }

    private fun estimatePartition(name: String, gpt: List<GptPartition>): Long {
        gpt.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it.length }
        val base = name.lowercase().removeSuffix("_a").removeSuffix("_b")
        return when (base) {
            "userdata", "user_data" -> 8L * GB
            "super" -> 4L * GB
            "system", "vendor", "product", "system_ext" -> 2L * GB
            "cache" -> 1L * GB
            "boot", "recovery", "init_boot", "vendor_boot", "dtbo" -> 64L * MB
            "vbmeta", "vbmeta_system", "vbmeta_vendor" -> 8L * MB
            "preloader", "lk", "lk2", "logo", "misc", "para", "frp",
            "seccfg", "metadata", "nvram", "nvdata", "md1img",
            "protect1", "protect2" -> 64L * MB
            else -> 512L * MB
        }
    }

    internal fun tokenize(cmd: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuote = false
        for (ch in cmd.trim()) {
            when {
                ch == '"' -> inQuote = !inQuote
                ch.isWhitespace() && !inQuote -> {
                    if (cur.isNotEmpty()) {
                        out += cur.toString()
                        cur.clear()
                    }
                }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    internal fun flagValue(tokens: List<String>, flag: String): String? {
        val idx = tokens.indexOfFirst { it == flag || it.startsWith("$flag=") }
        if (idx < 0) return null
        val t = tokens[idx]
        if (t.startsWith("$flag=")) return t.substringAfter('=')
        return tokens.getOrNull(idx + 1)?.takeUnless { it.startsWith("-") }
    }

    internal fun positionals(tokens: List<String>): List<String> {
        val skip = when {
            tokens.getOrNull(0) == "da" && tokens.getOrNull(1) == "rpmb" -> 3
            tokens.getOrNull(0) == "da" -> 2
            else -> 1
        }
        val booleanFlags = setOf(
            "--debugmode", "--skipwdt", "--skipcrash", "--socid",
            "--debug", "--disable_internal_flash"
        )
        val pos = mutableListOf<String>()
        var i = skip
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t.startsWith("--") && t.contains('=') -> i++
                t.startsWith("-") && t != "-" -> {
                    if (t !in booleanFlags && i + 1 < tokens.size && !tokens[i + 1].startsWith("-")) {
                        i += 2
                    } else {
                        i++
                    }
                }
                else -> {
                    pos += t
                    i++
                }
            }
        }
        return pos
    }

    internal fun parseByteCount(raw: String): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return try {
            if (s.startsWith("0x") || s.startsWith("0X")) s.substring(2).toLong(16)
            else s.toLong()
        } catch (_: NumberFormatException) {
            null
        }
    }
}
