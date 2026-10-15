package com.bareflash.app.dump

/**
 * Reads a MediaTek scatter file (the `*scatter*.txt` that ships with stock firmware).
 * It understands the common YAML-like layout: blocks that start with `- partition_index:`
 * followed by `key: value` lines.
 */
object ScatterFile {

    data class Entry(
        val name: String,
        val fileName: String,
        val startAddr: Long,
        val size: Long,
        val operation: String,
        val isDownload: Boolean,
        val region: String,
    ) {
        /** There is an image to write for this partition. */
        val hasImage: Boolean get() = fileName.isNotBlank() && !fileName.equals("NONE", true)
    }

    data class Parsed(val platform: String, val storage: String, val entries: List<Entry>)

    private fun num(s: String?): Long {
        val t = s?.trim() ?: return -1L
        return if (t.startsWith("0x", true)) t.drop(2).toLongOrNull(16) ?: -1L else t.toLongOrNull() ?: -1L
    }

    fun looksLikeScatter(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("scatter") && n.endsWith(".txt")
    }

    fun parse(text: String): Parsed {
        var platform = ""
        var storage = ""
        val entries = ArrayList<Entry>()
        var cur: MutableMap<String, String>? = null
        fun flush() {
            val m = cur ?: return
            val name = m["partition_name"] ?: return
            entries += Entry(
                name = name,
                fileName = m["file_name"].orEmpty(),
                startAddr = num(m["linear_start_addr"]).takeIf { it >= 0 } ?: num(m["physical_start_addr"]),
                size = num(m["partition_size"]),
                operation = m["operation_type"].orEmpty(),
                isDownload = m["is_download"]?.equals("true", true) ?: false,
                region = m["region"].orEmpty(),
            )
            cur = null
        }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val body = line.removePrefix("-").trim()
            val idx = body.indexOf(':')
            if (idx <= 0) continue
            val key = body.substring(0, idx).trim()
            val value = body.substring(idx + 1).trim()
            if (key == "partition_index") { flush(); cur = HashMap(); continue }
            if (key == "platform" && platform.isEmpty()) platform = value
            if (key == "storage" && storage.isEmpty() && cur == null) storage = value
            cur?.put(key, value)
        }
        flush()
        return Parsed(platform, storage, entries)
    }

    /** Partitions that should not be ticked by default: user data and the bootloader chain's first stage. */
    fun defaultSelected(e: Entry): Boolean =
        e.hasImage && e.isDownload &&
            !e.name.equals("userdata", true) && !e.name.equals("preloader", true) &&
            !e.name.equals("pgpt", true) && !e.name.equals("sgpt", true)
}
