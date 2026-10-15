package com.bareflash.app.console

import com.bareflash.app.catalog.McCatalog
import com.bareflash.app.data.ProtocolSettings
import java.io.File

/**
 * Pure command logic shared by the Console and Commands tabs:
 * destructive-command detection and Protocols default injection.
 */
object CommandRules {

    // mtk.py subcommands that read/write/erase flash.
    private val destructiveSubcommands = setOf("w", "wf", "wl", "wo", "e", "es", "ess")

    // da subcommands that write flash / rpmb / seccfg / memory
    private val destructiveDaSubcommands = setOf("seccfg", "poke", "vbmeta", "patchmodem")
    private val destructiveDaRpmb = setOf("w", "e")

    // Every top-level subcommand the catalog knows about, plus "clear"
    // (a Console-only convenience, not a real mtk.py subcommand). Derived
    // from McCatalog rather than duplicated here, so a command the catalog
    // knows about can never be rejected as "unknown" by mistake.
    private val knownFirstWords: Set<String> by lazy {
        McCatalog.all.map { it.cmd.substringBefore(' ') }.toSet() + "clear"
    }

    /**
     * A typed command is only "known" if its first word matches a real
     * subcommand. This blocks obvious typos and nonsense before anything
     * reaches Python; a valid first word with an invalid rest (e.g. an
     * unknown "da" subcommand, or a bad flag) still runs and is caught by
     * mtk.py's own argument parser — that path already reports a friendly
     * error rather than a raw traceback.
     */
    fun isKnownCommand(cmd: String): Boolean {
        val first = cmd.trim().substringBefore(' ')
        return first.isNotEmpty() && first in knownFirstWords
    }

    /**
     * Subcommands whose argparse parser inherits the shared base options
     * (--preloader, --loader, --ptype, --vid, ...). devices, script, multi
     * and fs do not, so nothing may be injected there. Keep in sync with
     * app/src/main/python/mtk.py.
     */
    val flagCapable = setOf(
        "r", "rl", "rf", "rs", "ro", "w", "wf", "wl", "wo",
        "e", "es", "ess", "footer",
        "printgpt", "gpt",
        "dumpbrom", "dumpsram", "dumppreloader",
        "payload", "brute", "stage", "plstage", "peek",
        "da", "crash", "reset", "meta", "meta2", "logs",
        "gettargetconfig"
    )

    val payloadTypes = listOf("amonet", "kamakiri", "kamakiri2", "carbonara")
    const val DEFAULT_SECTOR_SIZE = "0x200"
    private val hexPattern = Regex("^(0[xX])?[0-9a-fA-F]{1,8}$")

    fun isHexValue(v: String) = hexPattern.matches(v.trim())

    fun isDestructive(cmd: String): Boolean {
        val tokens = cmd.trim().split(Regex("\\s+"))
        if (tokens.isEmpty()) return false
        val first = tokens[0]
        if (first in destructiveSubcommands) return true
        if (first == "da") {
            val sub = tokens.getOrNull(1) ?: return false
            if (sub in destructiveDaSubcommands) return true
            if (sub == "rpmb" && tokens.getOrNull(2) in destructiveDaRpmb) return true
            if (sub == "imei" && tokens.any { it == "--write" }) return true
        }
        return false
    }

    /** [command] is what will run; [notes] lists the saved defaults added to it. */
    data class Applied(val command: String, val notes: List<String>)

    /** Wraps a path in double quotes (embedded quotes removed); shlex honours them. */
    private fun quoted(path: String): String = "\"" + path.replace("\"", "") + "\""

    private fun hasFlag(tokens: List<String>, flag: String) =
        tokens.any { it == flag || it.startsWith("$flag=") }

    /**
     * Adds the saved Protocols defaults to [cmd]. A flag already present in
     * the command always wins. Flags are placed directly after the
     * subcommand so that nested parsers (e.g. "da rpmb r") accept them.
     */
    fun applyDefaults(cmd: String, p: ProtocolSettings): Applied {
        val trimmed = cmd.trim()
        val first = trimmed.substringBefore(' ')
        if (first !in flagCapable) return Applied(cmd, emptyList())

        val rest = trimmed.substringAfter(' ', "")
        val tokens = trimmed.split(Regex("\\s+")).map { it.lowercase() }
        val inject = mutableListOf<String>()
        val notes = mutableListOf<String>()

        fun add(flag: String, note: String, value: String? = null) {
            if (hasFlag(tokens, flag)) return
            inject += flag
            if (value != null) inject += value
            notes += note
        }

        if (p.debugMode) add("--debugmode", "Debug mode")
        if (p.skipWatchdog) add("--skipwdt", "Skip watchdog initialization")
        if (p.alreadyInPreloader) add("--skipcrash", "Device already in Preloader mode")
        if (p.enforceCrash) add("--crash", "Enforce Preloader crash to BROM")
        if (p.stockDa) add("--stock", "Use stock DA")
        if (p.iotMode) add("--iot", "IoT mode (MT6261/MT2301)")
        if (p.readSocId) add("--socid", "Read SoC ID")
        if (p.payloadType in payloadTypes) add("--ptype", "Payload type: ${p.payloadType}", p.payloadType)
        if (isHexValue(p.vid)) add("--vid", "Vendor ID: ${p.vid.trim()}", p.vid.trim())
        if (isHexValue(p.pid)) add("--pid", "Product ID: ${p.pid.trim()}", p.pid.trim())
        val sector = p.sectorSize.trim()
        if (isHexValue(sector) && !sector.equals(DEFAULT_SECTOR_SIZE, true)) {
            add("--sectorsize", "Sector size: $sector", sector)
        }
        val uart = p.uartLogLevel.trim()
        if (uart.isNotEmpty() && uart.all { it.isDigit() }) {
            add("--uartloglevel", "UART log level: $uart", uart)
        }
        // Auth / cert always inject when present (independent of useCustomFiles).
        if (p.authPath.isNotBlank() && File(p.authPath).exists()) {
            add("--auth", "Auth file", quoted(p.authPath))
        }
        if (p.certPath.isNotBlank() && File(p.certPath).exists()) {
            add("--cert", "Cert file", quoted(p.certPath))
        }
        if (p.useCustomFiles) {
            if (p.preloaderPath.isNotBlank() && File(p.preloaderPath).exists()) {
                add("--preloader", "Custom preloader file", quoted(p.preloaderPath))
            }
            if (p.daPath.isNotBlank() && File(p.daPath).exists()) {
                add("--loader", "Custom DA file", quoted(p.daPath))
            }
        }

        if (inject.isEmpty()) return Applied(cmd, emptyList())
        val built = buildString {
            append(first)
            append(' ').append(inject.joinToString(" "))
            if (rest.isNotEmpty()) append(' ').append(rest)
        }
        return Applied(built, notes)
    }
}
