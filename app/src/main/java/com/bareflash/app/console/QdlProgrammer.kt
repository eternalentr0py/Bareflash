package com.bareflash.app.console

import java.io.File

/**
 * Matches a Qualcomm programmer (prog_firehose / .mbn / .elf) to the chip's hardware ID
 * printed by `chipinfo`. Firmware packages usually name the programmer after that ID,
 * so a wrong file can be spotted before it is sent. Pure logic.
 */
object QdlProgrammer {

    private val reHw = Regex("""(?i)(?:hw[_ ]?id|msm[_ ]?id)\s*[:=]\s*(?:0x)?([0-9a-f]{6,16})""")
    private val reAnyId = Regex("""(?i)(?:0x)?[0-9a-f]{16}|(?<![0-9a-f])[0-9a-f]{8}(?![0-9a-f])""")

    fun hwIdFromChipinfo(text: String): String? =
        reHw.find(text)?.groupValues?.get(1)?.lowercase()

    private fun keys(hw: String): List<String> =
        if (hw.length >= 16) listOf(hw.takeLast(16), hw.take(8)) else listOf(hw)

    fun matches(fileName: String, hw: String): Boolean {
        val n = fileName.lowercase()
        return keys(hw.lowercase()).any { it in n }
    }

    fun candidates(dir: String, hw: String): List<String> {
        if (dir.isBlank()) return emptyList()
        val files = runCatching { File(dir).listFiles() }.getOrNull() ?: return emptyList()
        return files.filter { it.isFile && matches(it.name, hw) }.map { it.name }.sorted().take(6)
    }

    /** One plain sentence about how the selected programmer relates to the chip. */
    fun advice(hw: String, programmerPath: String, candidates: List<String>): String {
        if (programmerPath.isBlank()) {
            return if (candidates.isNotEmpty()) {
                "Chip hardware ID $hw. Programmers that match it: ${candidates.joinToString(", ")}. Pick one in the Session tab."
            } else {
                "Chip hardware ID $hw. No programmer is selected; pick the one for this chip in the Session tab."
            }
        }
        val name = File(programmerPath).name
        return when {
            matches(name, hw) -> "Programmer $name matches this chip ($hw)."
            reAnyId.containsMatchIn(name) ->
                "WARNING: $name carries a different hardware ID than this chip ($hw). It is probably the wrong programmer." +
                    (if (candidates.isNotEmpty()) " Matching files: ${candidates.joinToString(", ")}." else "")
            else -> "Chip hardware ID $hw. The name of $name carries no hardware ID, so it could not be matched."
        }
    }
}
