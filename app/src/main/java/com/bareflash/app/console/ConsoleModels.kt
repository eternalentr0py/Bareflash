package com.bareflash.app.console

enum class LinkState { Disconnected, Detecting, Reconnecting, Connected, Running, Error }

enum class LinkMode(val label: String?) {
    Unknown(null), Preloader("Preloader"), BootRom("BootROM"), Da("DA")
}

data class ConsoleStatus(
    val state: LinkState = LinkState.Disconnected,
    val mode: LinkMode = LinkMode.Unknown,
    val progress: Float? = null,       // 0..1 when the running command reports it
    val progressLabel: String = "",
    val message: String = ""          // short reason for Error
)

data class DeviceInfo(
    val vid: Int,
    val pid: Int,
    val hwCode: String? = null,
    val targetConfig: String? = null
)

/**
 * What the last handshake told us about the chip, parsed from mtkclient's log.
 * Kept separately from [DeviceInfo] because the device re-enumerates (and its
 * USB info is rebuilt) during normal operation, while the chip stays the same.
 */
data class TargetInfo(
    /** e.g. MT6789 */
    val cpu: String? = null,
    /** e.g. Dimensity 1080 */
    val chipset: String? = null,
    val hwCode: String? = null,
    val hwVer: String? = null,
    val swVer: String? = null,
    val blVer: String? = null,
    val bromVer: String? = null,
    val targetConfig: String? = null
)

data class ErrorInfo(val friendly: String, val detail: String, val hint: String? = null)

data class PendingCommand(
    val command: String,
    val finalCommand: String,
    /** Image / partition sizes and the typed-confirmation rule, when the command is a write or erase. */
    val check: WriteGuard.Check? = null
)

/** What kind of work a running command does; drives the island's FLASH / DUMP label. */
enum class JobKind { Flash, Dump, Erase, Control }

/**
 * Live view of the running command for the Dynamic Island. Progress is
 * whatever the engine reports through [com.bareflash.app.UiBridge];
 * [bytesTotal] stays 0 for commands that do not report any.
 */
data class JobProgress(
    val title: String,
    val kind: JobKind,
    val target: String,
    val stage: String = "",
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val speedBps: Double = 0.0,
    /** Milliseconds left, or -1 while unknown. */
    val etaMs: Long = -1L
) {
    val fraction: Float
        get() = if (bytesTotal > 0L) (bytesDone.toDouble() / bytesTotal.toDouble()).coerceIn(0.0, 1.0).toFloat() else 0f
    val measurable: Boolean get() = bytesTotal > 0L
}

/** Emitted once when a command ends, so the island can show OK / ERR briefly. */
data class JobResult(
    val id: Long,
    val title: String,
    val kind: JobKind,
    val target: String,
    /** One of ConsoleViewModel.RESULT_OK / RESULT_ERROR / RESULT_CANCELLED. */
    val outcome: String,
    val fraction: Float
)

/** Splits a final command line into island-friendly pieces. */
internal object JobInfo {
    private val dumpCmds = setOf(
        "r", "rl", "rf", "rs", "ro", "gpt", "printgpt", "footer",
        "dumpbrom", "dumpsram", "dumppreloader", "peek", "read", "readxml", "ramdump"
    )
    private val flashCmds = setOf("w", "wl", "wf", "wo", "write", "flash", "xml")
    private val eraseCmds = setOf("e", "es", "ess", "erase")
    private val partitionCmds = setOf("r", "rl", "rf", "rs", "ro", "w", "wl", "wf", "wo", "e", "es", "ess", "read", "write", "erase")

    fun describe(command: String): Triple<String, JobKind, String> {
        val t = command.trim().split(Regex("\\s+"))
        val first = t.firstOrNull().orEmpty()
        val second = t.getOrNull(1).orEmpty()
        val third = t.getOrNull(2).orEmpty()
        val kind = when {
            first == "da" && second == "rpmb" -> when (third) {
                "r" -> JobKind.Dump; "w" -> JobKind.Flash; "e" -> JobKind.Erase; else -> JobKind.Control
            }
            first == "da" && second == "dumpbrom" -> JobKind.Dump
            first in dumpCmds -> JobKind.Dump
            first in flashCmds -> JobKind.Flash
            first in eraseCmds -> JobKind.Erase
            else -> JobKind.Control
        }
        val key2 = if (t.size >= 2) "$first $second" else first
        var title = com.bareflash.app.engine.Catalogs.titleFor(key2)
        if (title == key2) title = com.bareflash.app.engine.Catalogs.titleFor(first)
        val target = if (first in partitionCmds) second.trim('"') else ""
        return Triple(title, kind, target)
    }
}

/**
 * One card after a job ends: what ran, how long, how much, how fast, where it went and
 * the SHA-256 of each image. [verify] is filled in later by a read-back check.
 */
data class JobSummary(
    val id: Long,
    val title: String,
    val kind: JobKind,
    val target: String,
    /** One of ConsoleViewModel.RESULT_OK / RESULT_ERROR / RESULT_CANCELLED. */
    val outcome: String,
    val elapsedMs: Long,
    val bytes: Long,
    val avgBps: Double,
    val destination: String?,
    /** (file, SHA-256) for each image written by the run. */
    val hashes: List<Pair<String, String>> = emptyList(),
    val verify: String? = null,
    val verifyOk: Boolean? = null,
    /** Things worth a second look in what the run produced, e.g. an image that is entirely blank. */
    val warnings: List<String> = emptyList(),
    val error: String? = null,
    /** What to try next, from [RunDoctor]; null when there is nothing specific to suggest. */
    val hint: String? = null,
    /** A dump that was cut short and can be continued from the Dumps tab. */
    val resumable: String? = null,
)
