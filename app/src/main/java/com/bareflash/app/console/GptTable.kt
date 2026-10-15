package com.bareflash.app.console

/**
 * One GPT/PMT row captured from mtkclient's `printgpt` output.
 * [length] is the partition size in bytes.
 */
data class GptPartition(
    val name: String,
    val offset: Long,
    val length: Long,
    val flags: Long = 0L,
)

/**
 * Incremental parser for the table `guid_gpt.print()` writes:
 *
 * ```
 * GPT Table:
 * -------------
 * boot:                Offset 0x0000000000080000, Length 0x0000000000400000, Flags 0x0000000000000000
 * ...
 * Total disk size:0x0000001d1c000000, sectors:0x0000000000e8e000
 * ```
 *
 * Lines arrive in arbitrary stdout chunks, so [GptParseState.ingest] is
 * fed every log fragment and only publishes a complete table.
 */
class GptParseState {
    private val lineRest = StringBuilder()
    private val acc = mutableListOf<GptPartition>()
    private var inTable = false

    /**
     * @return a newly completed table, or null if this chunk did not finish one.
     */
    fun ingest(raw: String): List<GptPartition>? {
        lineRest.append(raw)
        val text = lineRest.toString()
        val parts = text.split('\n')
        if (text.endsWith('\n')) {
            lineRest.setLength(0)
        } else {
            lineRest.setLength(0)
            lineRest.append(parts.last())
        }
        val lines = if (text.endsWith('\n')) parts else parts.dropLast(1)
        var completed: List<GptPartition>? = null
        for (line in lines) {
            val trimmed = line.trim()
            if (HEADER.containsMatchIn(trimmed)) {
                acc.clear()
                inTable = true
                continue
            }
            if (!inTable) continue
            val row = parseRow(trimmed)
            if (row != null) {
                acc += row
                continue
            }
            if (TOTAL.containsMatchIn(trimmed) || (trimmed.startsWith("-----") && acc.isNotEmpty())) {
                if (acc.isNotEmpty()) completed = acc.toList()
                inTable = false
            }
        }
        return completed
    }

    fun reset() {
        lineRest.setLength(0)
        acc.clear()
        inTable = false
    }

    companion object {
        private val HEADER = Regex("(?i)(GPT|PMT|BPI)\\s+Table:")
        private val ROW = Regex(
            """^([A-Za-z0-9_.+\-]+):\s+Offset\s+0x([0-9a-fA-F]+),\s+Length\s+0x([0-9a-fA-F]+)(?:,\s+Flags\s+0x([0-9a-fA-F]+))?"""
        )
        private val TOTAL = Regex("""(?i)Total disk size:0x([0-9a-fA-F]+)""")

        internal fun parseRow(line: String): GptPartition? {
            val m = ROW.find(line) ?: return null
            val name = m.groupValues[1]
            if (name.equals("Name", ignoreCase = true)) return null
            return GptPartition(
                name = name,
                offset = m.groupValues[2].toLongOrNull(16) ?: 0L,
                length = m.groupValues[3].toLongOrNull(16) ?: 0L,
                flags = m.groupValues.getOrNull(4)?.toLongOrNull(16) ?: 0L,
            )
        }
    }
}
