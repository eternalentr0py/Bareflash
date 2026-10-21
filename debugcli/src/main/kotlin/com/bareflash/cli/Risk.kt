package com.bareflash.cli

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

/** How much a command should be questioned before it runs. Ordered: later values are riskier. */
public enum class RiskLevel { SAFE, CAUTION, DANGEROUS }

/**
 * [phrase] is what the user must type to confirm a [RiskLevel.DANGEROUS]
 * command (a partition name, or the command word), so a tap is not enough.
 */
public data class Risk(val level: RiskLevel, val reason: String = "", val phrase: String? = null) {
    public companion object {
        public val SAFE: Risk = Risk(RiskLevel.SAFE)
    }
}

/**
 * Judges a command before it runs.
 *
 * Fastboot and ADB-tool commands are judged exactly. Shell text is judged by
 * a heuristic that looks at each command in a line (split on `;`, `&&`, `||`,
 * `|`, `&`, `$(...)` and backticks) and at redirections into device nodes. It
 * catches the common ways to destroy data; it is a safety net, not a proof,
 * since the device shell can do anything.
 */
public object RiskAssessor {

    private val PROTECTED_PUSH_TARGETS = listOf("/system", "/vendor", "/product", "/odm", "/system_ext", "/boot", "/dev", "/proc", "/sys", "/apex")

    public fun assess(c: DeviceCommand): Risk = when (c) {
        is AdbShell -> ShellRisk.assess(c.command)
        is AdbExecOut -> ShellRisk.assess(c.command)
        is AdbPush -> {
            val target = c.remote.trim()
            if (PROTECTED_PUSH_TARGETS.any { target == it || target.startsWith("$it/") }) {
                Risk(RiskLevel.DANGEROUS, "This writes into a protected system location.", "push")
            } else Risk.SAFE
        }
        is AdbPull, is AdbReboot, is AdbInfo, is Unsupported -> Risk.SAFE

        is FbGetvar, FbDevices, FbContinue, FbVersion, is FbReboot -> Risk.SAFE
        is FbBoot -> Risk(RiskLevel.CAUTION, "This boots an image from this phone once. Nothing is written to flash.")
        is FbSetActive -> Risk(RiskLevel.CAUTION, "This changes which slot the device boots from.")
        is FbFlash -> Risk(RiskLevel.DANGEROUS, "This overwrites the ${c.partition} partition and cannot be undone.", c.partition)
        is FbErase -> Risk(RiskLevel.DANGEROUS, "This erases the ${c.partition} partition and cannot be undone.", c.partition)
        is FbOem -> Risk(RiskLevel.DANGEROUS, "OEM commands are vendor specific and can change or wipe the device.", "oem")
        is FbFlashing -> when (c.command) {
            FlashingCommand.GET_UNLOCK_ABILITY -> Risk.SAFE
            FlashingCommand.UNLOCK, FlashingCommand.UNLOCK_CRITICAL ->
                Risk(RiskLevel.DANGEROUS, "Unlocking the bootloader erases all user data and can void the warranty.", "unlock")
            FlashingCommand.LOCK, FlashingCommand.LOCK_CRITICAL ->
                Risk(RiskLevel.DANGEROUS, "Locking the bootloader erases user data, and a device with modified software may not boot afterwards.", "lock")
        }
        is FbLogical -> when (c.op) {
            FbLogical.Op.CREATE -> Risk(RiskLevel.CAUTION, "This creates a logical partition.")
            FbLogical.Op.RESIZE -> Risk(RiskLevel.DANGEROUS, "Resizing ${c.name} can destroy the data in it.", c.name)
            FbLogical.Op.DELETE -> Risk(RiskLevel.DANGEROUS, "This deletes the ${c.name} logical partition and its data.", c.name)
        }
    }
}

internal object ShellRisk {
    private val DANGEROUS_WORDS = mapOf(
        "dd" to "dd writes raw bytes, and a wrong of= target can destroy a partition.",
        "wipe" to "wipe erases data.",
        "format" to "format erases a filesystem.",
        "blkdiscard" to "blkdiscard erases a whole block device.",
        "shred" to "shred overwrites files so they cannot be recovered.",
        "sgdisk" to "Partition table tools can make the device unbootable.",
        "gdisk" to "Partition table tools can make the device unbootable.",
        "fdisk" to "Partition table tools can make the device unbootable.",
        "sfdisk" to "Partition table tools can make the device unbootable.",
        "parted" to "Partition table tools can make the device unbootable.",
        "flash_erase" to "This erases flash memory.",
        "nandwrite" to "This writes flash memory directly.",
    )

    private val CAUTION_WORDS = mapOf(
        "rm" to "rm deletes files permanently.",
        "rmdir" to "rmdir removes folders.",
        "mv" to "mv can overwrite or move files out of place.",
        "truncate" to "truncate cuts files short.",
        "setprop" to "This changes a system property.",
        "resetprop" to "This changes a system property.",
        "mount" to "This changes how a filesystem is mounted.",
        "umount" to "This unmounts a filesystem.",
        "insmod" to "This loads a kernel module.",
        "rmmod" to "This unloads a kernel module.",
        "modprobe" to "This loads or unloads a kernel module.",
        "kill" to "This stops a process.",
        "killall" to "This stops processes.",
        "pkill" to "This stops processes.",
        "stop" to "This stops a system service.",
        "start" to "This starts a system service.",
        "svc" to "This changes a system service setting.",
    )

    private val PM_CAUTION = setOf("uninstall", "clear", "disable", "disable-user", "hide", "suspend", "reset-permissions", "revoke")
    private val WRAPPERS = setOf("su", "sudo", "nohup", "exec", "env", "busybox", "toybox", "command", "time", "nice", "setsid", "xargs", "eval")
    private val SHELLS = setOf("sh", "bash", "ash", "mksh", "dash")
    private val HARMLESS_DEVICES = setOf("/dev/null", "/dev/zero", "/dev/full", "/dev/stdout", "/dev/stderr", "/dev/stdin")
    private val REDIRECT = Regex(">>?\\s*(/(?:dev|proc|sys)/[^\\s;&|)]*)")
    private val ENV_ASSIGN = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*")

    fun assess(text: String, depth: Int = 0): Risk {
        var best = Risk.SAFE
        fun consider(r: Risk) { if (r.level > best.level) best = r }
        for (segment in segments(text)) consider(assessSegment(segment, depth))
        for (m in REDIRECT.findAll(text)) {
            val target = m.groupValues[1]
            if (target in HARMLESS_DEVICES || target.startsWith("/dev/tty") || target.startsWith("/dev/pts/")) continue
            consider(
                if (target.startsWith("/dev/")) Risk(RiskLevel.DANGEROUS, "Redirecting output into $target can overwrite a device.", "redirect")
                else Risk(RiskLevel.CAUTION, "Writing to $target changes kernel or hardware settings.")
            )
        }
        return best
    }

    /** Splits on command separators outside single and double quotes; `$(`, `)` and backticks also separate. */
    internal fun segments(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote = '\u0000'
        for (c in text) {
            if (quote != '\u0000') {
                cur.append(c)
                if (c == quote) quote = '\u0000'
                continue
            }
            when (c) {
                '\'', '"' -> { quote = c; cur.append(c) }
                ';', '&', '|', '\n', '(', ')', '`' -> { out += cur.toString(); cur.setLength(0) }
                else -> cur.append(c)
            }
        }
        out += cur.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun words(segment: String): List<String> =
        try { CommandLine.tokenize(segment).map { it.text } } catch (_: CommandSyntaxException) { segment.split(Regex("\\s+")).filter { it.isNotEmpty() } }

    private fun assessSegment(segment: String, depth: Int): Risk {
        var w = words(segment)
        // Drop leading variable assignments and wrapper commands to reach the real command.
        while (w.isNotEmpty()) {
            val first = w[0]
            val base = first.substringAfterLast('/')
            if (ENV_ASSIGN.matches(first)) {
                w = w.drop(1)
            } else if (base in WRAPPERS) {
                w = w.drop(1)
                while (w.isNotEmpty() && (w[0].startsWith("-") || w[0].toIntOrNull() != null)) {
                    // `su -c "<command>"`: the quoted text is itself a command line.
                    if (w[0] == "-c" && w.size > 1 && depth < 4) return assess(w[1], depth + 1)
                    w = w.drop(1)
                }
            } else if (base in SHELLS && w.size > 2 && w[1] == "-c" && depth < 4) {
                return assess(w[2], depth + 1)
            } else break
        }
        if (w.isEmpty()) return Risk.SAFE
        val cmd = w[0].substringAfterLast('/')
        val args = w.drop(1)

        if (cmd == "rm") {
            val recursive = args.any { it.startsWith("--recursive") || it.startsWith("--force") || (it.startsWith("-") && !it.startsWith("--") && it.any { ch -> ch == 'r' || ch == 'R' || ch == 'f' }) }
            return if (recursive) Risk(RiskLevel.DANGEROUS, "rm with -r or -f can delete whole folders without asking.", "rm")
            else Risk(RiskLevel.CAUTION, CAUTION_WORDS.getValue("rm"))
        }
        if (cmd == "recovery" && args.any { it.contains("wipe") }) {
            return Risk(RiskLevel.DANGEROUS, "This wipes the device from recovery.", "recovery")
        }
        if (cmd.startsWith("mkfs") || cmd.startsWith("mke2fs") || cmd == "make_ext4fs" || cmd.startsWith("newfs")) {
            return Risk(RiskLevel.DANGEROUS, "Creating a filesystem erases whatever was on the target.", cmd)
        }
        DANGEROUS_WORDS[cmd]?.let { return Risk(RiskLevel.DANGEROUS, it, cmd) }
        if (cmd == "pm" || cmd == "cmd") {
            val sub = args.firstOrNull { !it.startsWith("-") }
            val action = if (cmd == "cmd" && sub == "package") args.dropWhile { it != "package" }.drop(1).firstOrNull { !it.startsWith("-") } else sub
            if ((cmd == "pm" || sub == "package") && action in PM_CAUTION) {
                return Risk(RiskLevel.CAUTION, "This removes, disables or resets an app.")
            }
            return Risk.SAFE
        }
        if (cmd == "settings" && args.firstOrNull() in setOf("put", "delete")) return Risk(RiskLevel.CAUTION, "This changes a system setting.")
        if (cmd == "wm" && args.size >= 2 && args[0] in setOf("size", "density")) return Risk(RiskLevel.CAUTION, "This changes the display configuration.")
        if ((cmd == "chmod" || cmd == "chown" || cmd == "chcon") && args.any { it == "-R" || it == "--recursive" }) {
            return Risk(RiskLevel.CAUTION, "A recursive permission change touches many files.")
        }
        CAUTION_WORDS[cmd]?.let { return Risk(RiskLevel.CAUTION, it) }
        return Risk.SAFE
    }
}
