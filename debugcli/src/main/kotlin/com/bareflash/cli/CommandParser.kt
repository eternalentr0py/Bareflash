package com.bareflash.cli

import com.bareflash.adb.AdbRebootTarget
import com.bareflash.cli.DeviceCommand.AdbExecOut
import com.bareflash.cli.DeviceCommand.AdbInfo
import com.bareflash.cli.DeviceCommand.AdbPull
import com.bareflash.cli.DeviceCommand.AdbPush
import com.bareflash.cli.DeviceCommand.AdbReboot
import com.bareflash.cli.DeviceCommand.AdbShell
import com.bareflash.cli.DeviceCommand.FbBoot
import com.bareflash.cli.DeviceCommand.FbContinue
import com.bareflash.cli.DeviceCommand.FbDevices
import com.bareflash.cli.DeviceCommand.FbErase
import com.bareflash.cli.DeviceCommand.FbFlash
import com.bareflash.cli.DeviceCommand.FbFlashing
import com.bareflash.cli.DeviceCommand.FbGetvar
import com.bareflash.cli.DeviceCommand.FbLogical
import com.bareflash.cli.DeviceCommand.FbOem
import com.bareflash.cli.DeviceCommand.FbReboot
import com.bareflash.cli.DeviceCommand.FbSetActive
import com.bareflash.cli.DeviceCommand.FbVersion
import com.bareflash.cli.DeviceCommand.Unsupported
import com.bareflash.fastboot.FlashingCommand
import com.bareflash.fastboot.RebootTarget
import com.bareflash.usb.DebugProtocol

public sealed interface ParseResult {
    public data class Ok(val command: DeviceCommand) : ParseResult
    public data class Error(val message: String) : ParseResult
    public data object Empty : ParseResult
}

/**
 * Turns a typed line into a [DeviceCommand].
 *
 * A line may start with `adb` or `fastboot`, as on a desktop. Without that
 * prefix it is taken to be for the connected device: a shell command when ADB
 * is connected, a fastboot command when fastboot is.
 */
public object CommandParser {

    public fun parse(line: String, connected: DebugProtocol?): ParseResult {
        val tokens = try {
            CommandLine.tokenize(line)
        } catch (e: CommandSyntaxException) {
            return ParseResult.Error(e.message ?: "Malformed line")
        }
        if (tokens.isEmpty()) return ParseResult.Empty
        return when (tokens[0].text) {
            "adb" -> parseAdb(line, tokens)
            "fastboot" -> parseFastboot(line, tokens, 1)
            else -> when (connected) {
                DebugProtocol.ADB -> ParseResult.Ok(AdbShell(line.trim()))
                DebugProtocol.FASTBOOT -> parseFastboot(line, tokens, 0)
                null -> ParseResult.Error("Not connected. Connect a device on the Devices tab, or type help.")
            }
        }
    }

    private fun ok(c: DeviceCommand): ParseResult = ParseResult.Ok(c)
    private fun err(m: String): ParseResult = ParseResult.Error(m)

    // ---- adb -----------------------------------------------------------------

    private val ADB_TARGETS = mapOf(
        "bootloader" to AdbRebootTarget.BOOTLOADER,
        "recovery" to AdbRebootTarget.RECOVERY,
        "sideload" to AdbRebootTarget.SIDELOAD,
        "fastboot" to AdbRebootTarget.FASTBOOTD,
        "download" to AdbRebootTarget.DOWNLOAD,
        "edl" to AdbRebootTarget.EDL,
    )

    private val ADB_UNSUPPORTED = mapOf(
        "install" to "not built in yet. Copy the APK over and run: adb push app.apk /sdcard/app.apk, then adb shell pm install /sdcard/app.apk",
        "install-multiple" to "not built in yet. Push the APKs and use adb shell pm install-create / install-write / install-commit.",
        "root" to "this client does not restart adbd. On a rootable build use: adb shell su -c <command>",
        "unroot" to "this client does not restart adbd.",
        "remount" to "not supported. Use: adb shell su -c 'mount -o rw,remount /'",
        "forward" to "port forwarding needs the network, and this app has no network access.",
        "reverse" to "port forwarding needs the network, and this app has no network access.",
        "tcpip" to "wireless debugging needs the network, and this app has no network access.",
        "connect" to "wireless debugging needs the network, and this app has no network access.",
        "disconnect" to "use Disconnect on the Devices tab.",
        "pair" to "wireless debugging needs the network, and this app has no network access.",
        "usb" to "the device is already connected over USB.",
        "bugreport" to "not built in yet. Try: adb shell bugreportz, then adb pull the file it names.",
        "sideload" to "not supported yet.",
        "backup" to "not supported (deprecated by Android).",
        "restore" to "not supported (deprecated by Android).",
    )

    private val NOOP_VERBS = mapOf(
        "start-server" to AdbInfo.Info.NO_SERVER,
        "kill-server" to AdbInfo.Info.NO_SERVER,
        "wait-for-device" to AdbInfo.Info.NO_SERVER,
        "devices" to AdbInfo.Info.DEVICES,
        "get-state" to AdbInfo.Info.GET_STATE,
        "get-serialno" to AdbInfo.Info.GET_SERIALNO,
        "version" to AdbInfo.Info.VERSION,
        "--version" to AdbInfo.Info.VERSION,
    )

    private val SHELL_FLAGS = setOf("-t", "-T", "-x", "-n")
    private val PUSH_IGNORED = setOf("--sync", "-n", "-z", "-Z")
    private val PULL_IGNORED = setOf("-a", "-z", "-Z")
    private val REDIRECT = Regex("^(.*\\S)\\s+>\\s*(.+?)\\s*$", RegexOption.DOT_MATCHES_ALL)

    private fun parseAdb(line: String, tokens: List<Token>): ParseResult {
        var i = 1
        // Global options that only make sense with several devices or servers: accepted and ignored.
        while (i < tokens.size) {
            val t = tokens[i].text
            if (t == "-d" || t == "-e") i++
            else if (t == "-s" && i + 1 < tokens.size) i += 2
            else break
        }
        val verb = tokens.getOrNull(i)?.text ?: return err("usage: adb <command>. Type help adb to see the commands.")
        val rest = tokens.drop(i + 1)

        NOOP_VERBS[verb]?.let { return ok(AdbInfo(it)) }
        ADB_UNSUPPORTED[verb]?.let { return ok(Unsupported("adb", verb, it)) }

        return when (verb) {
            "shell" -> {
                var j = i + 1
                while (j < tokens.size && tokens[j].text in SHELL_FLAGS) j++
                ok(AdbShell(if (j >= tokens.size) "" else line.substring(tokens[j].start).trim()))
            }
            "exec-out" -> {
                if (rest.isEmpty()) return err("usage: adb exec-out <command> [> file]")
                val raw = line.substring(rest.first().start).trim()
                val m = REDIRECT.matchEntire(raw)
                if (m == null) ok(AdbExecOut(raw, null)) else {
                    val target = try { CommandLine.tokenize(m.groupValues[2]).singleOrNull()?.text } catch (e: CommandSyntaxException) { null }
                        ?: return err("adb exec-out: cannot read the file name after '>'")
                    ok(AdbExecOut(m.groupValues[1], target))
                }
            }
            "push" -> {
                val (pos, bad) = split(rest, PUSH_IGNORED)
                if (bad != null) return err("adb push: option '$bad' is not supported")
                if (pos.size != 2) return err("usage: adb push <local> <remote>")
                ok(AdbPush(pos[0], pos[1]))
            }
            "pull" -> {
                val (pos, bad) = split(rest, PULL_IGNORED)
                if (bad != null) return err("adb pull: option '$bad' is not supported")
                if (pos.isEmpty() || pos.size > 2) return err("usage: adb pull <remote> [local]")
                ok(AdbPull(pos[0], pos.getOrNull(1)))
            }
            "reboot" -> {
                if (rest.size > 1) return err("usage: adb reboot [bootloader|recovery|sideload|fastboot|download|edl]")
                if (rest.isEmpty()) return ok(AdbReboot(AdbRebootTarget.SYSTEM))
                val target = ADB_TARGETS[rest[0].text]
                    ?: return err("adb reboot: unknown target '${rest[0].text}'. Use bootloader, recovery, sideload, fastboot, download or edl.")
                ok(AdbReboot(target))
            }
            "logcat" -> ok(AdbShell(if (rest.isEmpty()) "logcat" else "logcat " + line.substring(rest.first().start).trim()))
            "uninstall" -> {
                val (pos, bad) = split(rest, setOf("-k"))
                if (bad != null) return err("adb uninstall: option '$bad' is not supported")
                if (pos.size != 1) return err("usage: adb uninstall <package>")
                ok(AdbShell("pm uninstall " + CommandLine.quote(pos[0])))
            }
            else -> err("adb: unknown command '$verb'. Type help adb to see the commands.")
        }
    }

    /** Positional arguments, plus the first option that is neither ignorable nor a lone "-". */
    private fun split(rest: List<Token>, ignorable: Set<String>): Pair<List<String>, String?> {
        val pos = ArrayList<String>()
        var bad: String? = null
        for (t in rest) {
            val s = t.text
            when {
                s in ignorable -> Unit
                s.length > 1 && s.startsWith("-") -> if (bad == null) bad = s
                else -> pos += s
            }
        }
        return pos to bad
    }

    // ---- fastboot ------------------------------------------------------------

    private val FB_TARGETS = mapOf(
        "bootloader" to RebootTarget.BOOTLOADER,
        "recovery" to RebootTarget.RECOVERY,
        "fastboot" to RebootTarget.FASTBOOTD,
        "fastbootd" to RebootTarget.FASTBOOTD,
        "system" to RebootTarget.SYSTEM,
    )

    private val FLASHING = mapOf(
        "unlock" to FlashingCommand.UNLOCK,
        "lock" to FlashingCommand.LOCK,
        "unlock_critical" to FlashingCommand.UNLOCK_CRITICAL,
        "lock_critical" to FlashingCommand.LOCK_CRITICAL,
        "get_unlock_ability" to FlashingCommand.GET_UNLOCK_ABILITY,
    )

    private val FB_UNSUPPORTED = mapOf(
        "format" to "creating filesystems happens on the host in desktop fastboot and is not built in. Use: fastboot erase userdata",
        "-w" to "wiping with host-side formatting is not built in. Use: fastboot erase userdata (and cache if the device has it)",
        "update" to "zip bundles are not supported yet. Flash the images one by one.",
        "flashall" to "zip bundles are not supported yet. Flash the images one by one.",
        "fetch" to "not supported yet.",
        "stage" to "not supported yet.",
        "get_staged" to "not supported yet.",
        "snapshot-update" to "not supported yet.",
        "gsi" to "not supported yet.",
        "wipe-super" to "not supported yet.",
    )

    private fun parseFastboot(line: String, tokens: List<Token>, from: Int): ParseResult {
        val verb = tokens.getOrNull(from)?.text ?: return err("usage: fastboot <command>. Type help fastboot to see the commands.")
        val rest = tokens.drop(from + 1)
        val args = rest.map { it.text }

        FB_UNSUPPORTED[verb]?.let { return ok(Unsupported("fastboot", verb, it)) }

        fun need(n: Int, usage: String): ParseResult? =
            if (args.size != n) err("usage: fastboot $usage") else null

        return when {
            verb == "getvar" -> need(1, "getvar <name | all>") ?: ok(FbGetvar(args[0]))
            verb == "devices" -> ok(FbDevices)
            verb == "flash" -> need(2, "flash <partition> <file>") ?: ok(FbFlash(args[0], args[1]))
            verb == "erase" -> need(1, "erase <partition>") ?: ok(FbErase(args[0]))
            verb == "reboot" -> when {
                args.size > 1 -> err("usage: fastboot reboot [bootloader|recovery|fastboot]")
                args.isEmpty() -> ok(FbReboot(RebootTarget.SYSTEM))
                else -> FB_TARGETS[args[0]]?.let { ok(FbReboot(it)) }
                    ?: err("fastboot reboot: unknown target '${args[0]}'. Use bootloader, recovery or fastboot.")
            }
            verb == "reboot-bootloader" -> need(0, "reboot-bootloader") ?: ok(FbReboot(RebootTarget.BOOTLOADER))
            verb == "reboot-recovery" -> need(0, "reboot-recovery") ?: ok(FbReboot(RebootTarget.RECOVERY))
            verb == "reboot-fastboot" -> need(0, "reboot-fastboot") ?: ok(FbReboot(RebootTarget.FASTBOOTD))
            verb == "set_active" -> need(1, "set_active <a|b>") ?: ok(FbSetActive(args[0]))
            verb.startsWith("--set-active=") -> ok(FbSetActive(verb.removePrefix("--set-active=")))
            verb == "oem" -> if (rest.isEmpty()) err("usage: fastboot oem <command>") else ok(FbOem(line.substring(rest.first().start).trim()))
            verb == "flashing" -> {
                if (args.size != 1) return err("usage: fastboot flashing <unlock|lock|unlock_critical|lock_critical|get_unlock_ability>")
                FLASHING[args[0]]?.let { ok(FbFlashing(it)) }
                    ?: err("fastboot flashing: unknown subcommand '${args[0]}'.")
            }
            verb == "boot" -> need(1, "boot <image>") ?: ok(FbBoot(args[0]))
            verb == "continue" -> ok(FbContinue)
            verb == "--version" || verb == "version" -> ok(FbVersion)
            verb == "create-logical-partition" || verb == "resize-logical-partition" -> {
                if (args.size != 2) return err("usage: fastboot $verb <name> <size-in-bytes>")
                val size = args[1].toLongOrNull()?.takeIf { it >= 0 } ?: return err("fastboot $verb: '${args[1]}' is not a size in bytes")
                ok(FbLogical(if (verb.startsWith("create")) FbLogical.Op.CREATE else FbLogical.Op.RESIZE, args[0], size))
            }
            verb == "delete-logical-partition" -> need(1, "delete-logical-partition <name>") ?: ok(FbLogical(FbLogical.Op.DELETE, args[0], null))
            else -> err("fastboot: unknown command '$verb'. Type help fastboot to see the commands.")
        }
    }
}
