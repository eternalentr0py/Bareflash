package com.bareflash.cli

import com.bareflash.usb.DebugProtocol

/** [line] is the new text of the input. [candidates] is filled only when the choice is ambiguous and nothing more can be completed. */
public class CompletionResult(public val line: String, public val candidates: List<String>)

/** What the completer may offer besides the fixed vocabulary. */
public class CompletionContext(
    public val connected: DebugProtocol?,
    /** Partition names, for flash and erase. */
    public val partitions: Collection<String> = emptyList(),
    /** Fastboot variable names, for getvar. */
    public val variables: Collection<String> = emptyList(),
    /** Built-ins and aliases, offered as the first word. */
    public val extraWords: Collection<String> = emptyList(),
)

/** The words the command line knows. */
public object CliVocabulary {
    public val ADB_VERBS: List<String> = listOf(
        "shell", "exec-out", "push", "pull", "reboot", "logcat", "uninstall",
        "devices", "get-state", "get-serialno", "version",
    )
    public val FASTBOOT_VERBS: List<String> = listOf(
        "getvar", "devices", "flash", "erase", "reboot", "reboot-bootloader", "reboot-recovery", "reboot-fastboot",
        "set_active", "oem", "flashing", "boot", "continue", "create-logical-partition", "delete-logical-partition",
        "resize-logical-partition", "--version",
    )
    public val ADB_REBOOT_TARGETS: List<String> = listOf("bootloader", "recovery", "sideload", "fastboot", "download", "edl")
    public val FASTBOOT_REBOOT_TARGETS: List<String> = listOf("bootloader", "recovery", "fastboot")
    public val FLASHING: List<String> = listOf("unlock", "lock", "unlock_critical", "lock_critical", "get_unlock_ability")
    public val LOGCAT_FLAGS: List<String> = listOf("-d", "-c", "-v", "-b", "-s", "-t", "-e", "-f")
    public val COMMON_GETVARS: List<String> = listOf(
        "all", "product", "version", "version-bootloader", "serialno", "current-slot", "slot-count", "is-userspace",
        "max-download-size", "unlocked", "secure", "variant", "slot-successful:a", "slot-successful:b",
    )

    /** Everyday device shell commands, offered as the first word while ADB is connected. */
    public val SHELL_COMMANDS: List<String> = listOf(
        "ls", "cat", "cd", "pwd", "cp", "mv", "rm", "mkdir", "touch", "chmod", "find", "grep", "head", "tail", "du", "df",
        "ps", "top", "id", "uname", "date", "uptime", "ip", "ifconfig", "ping", "getprop", "setprop", "dumpsys", "pm", "am",
        "cmd", "input", "settings", "wm", "svc", "screencap", "screenrecord", "monkey", "logcat", "dmesg", "reboot", "su",
    )
}

/**
 * Tab completion for the console: knows what comes after `adb`, after
 * `adb reboot`, after `fastboot flash`, and so on. It cannot look inside the
 * device, so remote file names are not completed.
 */
public object CliCompleter {

    public fun complete(line: String, ctx: CompletionContext): CompletionResult {
        val tokenStart = line.indexOfLast { it.isWhitespace() } + 1
        val prefix = line.substring(tokenStart)
        val head = line.substring(0, tokenStart)
        val words = head.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

        val matches = poolFor(words, ctx).filter { it.startsWith(prefix) }.distinct().sorted()
        if (matches.isEmpty()) return CompletionResult(line, emptyList())
        if (matches.size == 1) {
            val m = matches[0]
            return CompletionResult(head + m + if (m.endsWith("/") || m.endsWith("=")) "" else " ", emptyList())
        }
        val common = commonPrefix(matches)
        return if (common.length > prefix.length) CompletionResult(head + common, emptyList()) else CompletionResult(line, matches)
    }

    /** Candidates laid out in columns about [width] characters wide, the way a shell prints them. */
    public fun columns(words: List<String>, width: Int = 56): String {
        if (words.isEmpty()) return ""
        val cell = (words.maxOf { it.length } + 2).coerceAtLeast(4)
        val perRow = (width / cell).coerceAtLeast(1)
        return words.chunked(perRow).joinToString("\n") { row -> row.joinToString("") { it.padEnd(cell) }.trimEnd() }
    }

    private fun commonPrefix(words: List<String>): String {
        var p = words[0]
        for (w in words) {
            var i = 0
            while (i < p.length && i < w.length && p[i] == w[i]) i++
            p = p.substring(0, i)
            if (p.isEmpty()) break
        }
        return p
    }

    private fun poolFor(words: List<String>, ctx: CompletionContext): Collection<String> {
        if (words.isEmpty()) {
            return buildList {
                add("adb"); add("fastboot")
                addAll(ctx.extraWords)
                when (ctx.connected) {
                    DebugProtocol.ADB -> addAll(CliVocabulary.SHELL_COMMANDS)
                    DebugProtocol.FASTBOOT -> addAll(CliVocabulary.FASTBOOT_VERBS)
                    null -> Unit
                }
            }
        }
        return when (words[0]) {
            "adb" -> adbPool(words.drop(1))
            "fastboot" -> fastbootPool(words.drop(1), ctx)
            "help", "man" -> if (words.size == 1) listOf("adb", "fastboot") + CliVocabulary.ADB_VERBS + CliVocabulary.FASTBOOT_VERBS else emptyList()
            else -> if (ctx.connected == DebugProtocol.FASTBOOT && words[0] in CliVocabulary.FASTBOOT_VERBS) fastbootPool(words, ctx) else emptyList()
        }
    }

    private fun adbPool(w: List<String>): Collection<String> = when {
        w.isEmpty() -> CliVocabulary.ADB_VERBS
        w[0] == "reboot" && w.size == 1 -> CliVocabulary.ADB_REBOOT_TARGETS
        w[0] == "shell" && w.size == 1 -> CliVocabulary.SHELL_COMMANDS
        w[0] == "logcat" -> CliVocabulary.LOGCAT_FLAGS
        else -> emptyList()
    }

    private fun fastbootPool(w: List<String>, ctx: CompletionContext): Collection<String> {
        if (w.isEmpty()) return CliVocabulary.FASTBOOT_VERBS
        if (w.size != 1) return emptyList()
        return when (w[0]) {
            "getvar" -> CliVocabulary.COMMON_GETVARS + ctx.variables
            "flash", "erase", "delete-logical-partition", "resize-logical-partition" -> ctx.partitions
            "reboot" -> CliVocabulary.FASTBOOT_REBOOT_TARGETS
            "set_active" -> listOf("a", "b")
            "flashing" -> CliVocabulary.FLASHING
            else -> emptyList()
        }
    }
}
