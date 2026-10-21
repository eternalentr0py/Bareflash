package com.bareflash.cli

import com.bareflash.adb.AdbCancelledException
import com.bareflash.adb.AdbException
import com.bareflash.adb.AdbStreamRejectedException
import com.bareflash.adb.AdbSyncException
import com.bareflash.adb.AdbTimeoutException
import com.bareflash.adb.AdbUnsupportedException
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
import com.bareflash.fastboot.FastbootCancelledException
import com.bareflash.fastboot.FastbootClient
import com.bareflash.fastboot.FastbootException
import com.bareflash.fastboot.FastbootFailException
import com.bareflash.fastboot.FlashProgress
import com.bareflash.fastboot.SparseFormatException
import com.bareflash.usb.CancelToken
import com.bareflash.usb.UsbTransportException
import java.io.IOException
import java.util.Locale

/** Where the runner writes. The app turns these into console lines and a progress bar. */
public interface TerminalSink {
    /** Output of the device (stdout) or of the tool. */
    public fun out(text: String)

    /** Error output of the device (stderr). */
    public fun err(text: String)

    /** A note from this tool, shown quietly. */
    public fun info(text: String)

    /** This tool reports that something failed. */
    public fun error(text: String)

    /** A transfer in progress. [done] below zero removes the progress display; [total] below zero means unknown. */
    public fun progress(label: String, done: Long, total: Long)
}

/** The connected device(s) and the shell's working directory. */
public class DeviceSession(public val adb: AdbOps?, public val fastboot: FastbootOps?) {
    /** Where shell commands run. Starts at `/`, like a fresh `adb shell`. */
    @Volatile public var cwd: String = "/"
    internal var previousCwd: String = "/"
}

/**
 * [exitCode] follows shell habit: 0 success, 1 failure, 2 misuse or not
 * connected, 130 cancelled, anything else is the device command's own code.
 * [linkLost] means the connection can no longer be trusted (device gone,
 * rebooting, or stopped mid-transfer) and the app should drop it.
 */
public class RunResult(public val exitCode: Int, public val linkLost: Boolean = false, public val note: String? = null)

/** Executes [DeviceCommand]s. One command at a time; not thread-safe. */
public class CommandRunner(
    private val session: DeviceSession,
    private val files: LocalFiles,
    private val sink: TerminalSink,
    private val cancel: CancelToken,
    private val nanoClock: () -> Long = System::nanoTime,
) {
    public fun run(command: DeviceCommand): RunResult =
        try {
            when (command) {
                is AdbShell -> adbShell(command.command)
                is AdbExecOut -> adbExecOut(command)
                is AdbPush -> adbPush(command)
                is AdbPull -> adbPull(command)
                is AdbReboot -> adbReboot(command)
                is AdbInfo -> adbInfo(command)
                is FbGetvar -> fbGetvar(command.name)
                FbDevices -> fbDevices()
                is FbFlash -> fbFlash(command)
                is FbErase -> fbSimple { it.erase(command.partition) }
                is FbReboot -> fbLeaving("Rebooting (${command.target.name.lowercase()})…", "The device is restarting.") { it.reboot(command.target) }
                is FbSetActive -> fbSimple { it.setActive(command.slot) }
                is FbOem -> fbResult { it.oem(command.args) }
                is FbFlashing -> fbResult { it.flashing(command.command) }
                is FbBoot -> fbBoot(command)
                FbContinue -> fbLeaving("Continuing the boot…", "The device is booting.") { it.continueBoot() }
                FbVersion -> { sink.out("Bareflash native fastboot client\n"); OK }
                is FbLogical -> fbLogical(command)
                is Unsupported -> { sink.error("${command.tool} ${command.verb}: ${command.hint}"); RunResult(1) }
            }
        } catch (e: Exception) {
            classify(e)
        }

    // ---- errors ----------------------------------------------------------------

    private fun classify(e: Exception): RunResult = when (e) {
        is AdbCancelledException -> cancelled(lost = false)
        is FastbootCancelledException -> cancelled(lost = true)
        is AdbSyncException -> { sink.error(e.message ?: "The device reported an error."); RunResult(1) }
        is AdbStreamRejectedException, is AdbUnsupportedException -> { sink.error(e.message ?: "The device refused."); RunResult(1) }
        is AdbTimeoutException -> { sink.error("Timed out: ${e.message}"); RunResult(1) }
        is AdbException -> lost("The ADB connection failed: ${e.message}")
        is FastbootFailException -> { sink.error("FAILED (remote: '${e.message}')"); RunResult(1) }
        is SparseFormatException -> { sink.error("The image cannot be used: ${e.message}"); RunResult(1) }
        is FastbootException -> lost("The fastboot connection failed: ${e.message}")
        is UsbTransportException -> lost("The USB connection failed: ${e.message}")
        is IllegalArgumentException -> { sink.error(e.message ?: "Invalid argument."); RunResult(2) }
        is IOException -> { sink.error("File error: ${e.message}"); RunResult(1) }
        else -> throw e
    }

    private fun cancelled(lost: Boolean): RunResult {
        sink.info("Cancelled.")
        return RunResult(130, linkLost = lost, note = if (lost) "A transfer was stopped half-way, so the connection was closed." else null)
    }

    private fun lost(message: String): RunResult {
        sink.error(message)
        return RunResult(1, linkLost = true, note = message)
    }

    private fun adbOrNull(): AdbOps? {
        session.adb?.let { return it }
        sink.error(
            if (session.fastboot != null) "This device is in fastboot mode, so ADB commands are not available. Use fastboot commands, or boot Android."
            else "ADB is not connected. Connect a device on the Devices tab."
        )
        return null
    }

    private fun fastbootOrNull(): FastbootOps? {
        session.fastboot?.let { return it }
        sink.error(
            if (session.adb != null) "This device is running Android, so fastboot commands are not available. Try: adb reboot bootloader"
            else "Fastboot is not connected. Connect a device in fastboot mode on the Devices tab."
        )
        return null
    }

    // ---- adb shell -----------------------------------------------------------------

    private fun adbShell(text: String): RunResult {
        val adb = adbOrNull() ?: return NOT_CONNECTED
        if (text.isEmpty()) {
            sink.info("Every line you type here already runs in the device shell. Type help for the rest.")
            return OK
        }
        CD.matchEntire(text)?.let { m ->
            val arg = m.groupValues[1].trim()
            if (arg.none { it in CD_METACHARACTERS }) return changeDirectory(adb, arg)
        }
        val wrapped = if (session.cwd == "/") text else "cd ${CommandLine.quote(session.cwd)} && $text"
        val outDecoder = Utf8Streamer()
        val errDecoder = Utf8Streamer()
        val outFilter = AnsiFilter()
        val errFilter = AnsiFilter()
        val code = adb.runShell(
            wrapped, cancel,
            { b -> outFilter.filter(outDecoder.feed(b)).let { if (it.isNotEmpty()) sink.out(it) } },
            { b -> errFilter.filter(errDecoder.feed(b)).let { if (it.isNotEmpty()) sink.err(it) } },
        )
        outFilter.filter(outDecoder.finish()).let { if (it.isNotEmpty()) sink.out(it) }
        errFilter.filter(errDecoder.finish()).let { if (it.isNotEmpty()) sink.err(it) }
        if (code != null && code != 0) sink.info("[exit $code]")
        return RunResult(code ?: 0)
    }

    /** `cd` is handled here: every command runs in its own shell, so the directory is remembered on this side. */
    private fun changeDirectory(adb: AdbOps, arg: String): RunResult {
        when {
            arg.isEmpty() -> {
                session.previousCwd = session.cwd
                session.cwd = "/"
            }
            arg == "-" -> {
                val back = session.previousCwd
                session.previousCwd = session.cwd
                session.cwd = back
                sink.out(back + "\n")
            }
            else -> {
                val r = adb.shell("cd ${CommandLine.quote(session.cwd)} && cd $arg && pwd")
                val dir = r.stdoutText().trim().lineSequence().lastOrNull()?.trim().orEmpty()
                if ((r.exitCode == null || r.exitCode == 0) && dir.startsWith("/")) {
                    session.previousCwd = session.cwd
                    session.cwd = dir
                } else {
                    val why = (r.stderrText() + r.stdoutText()).trim().ifEmpty { "cd: $arg: No such file or directory" }
                    sink.err(why + "\n")
                    return RunResult(1)
                }
            }
        }
        return OK
    }

    private fun adbExecOut(c: AdbExecOut): RunResult {
        val adb = adbOrNull() ?: return NOT_CONNECTED
        if (c.saveTo == null) {
            val decoder = Utf8Streamer()
            val filter = AnsiFilter()
            adb.execRaw(c.command, cancel) { b -> filter.filter(decoder.feed(b)).let { if (it.isNotEmpty()) sink.out(it) } }
            filter.filter(decoder.finish()).let { if (it.isNotEmpty()) sink.out(it) }
            return OK
        }
        val target = files.resolve(c.saveTo).let { if (it.isDirectory) it.child("output.bin") else it }
        target.mkParentDirs()
        var bytes = 0L
        var ok = false
        try {
            target.openOutput().use { out -> adb.execRaw(c.command, cancel) { b -> out.write(b); bytes += b.size } }
            ok = true
        } finally {
            if (!ok) target.delete()
        }
        sink.info("Saved ${human(bytes)} to ${target.path}")
        return OK
    }

    // ---- push and pull ------------------------------------------------------------------

    private class Counters {
        var files = 0
        var skipped = 0
        var bytes = 0L
    }

    private fun adbPush(c: AdbPush): RunResult {
        val adb = adbOrNull() ?: return NOT_CONNECTED
        val src = files.resolve(c.local)
        if (!src.exists) {
            sink.error("adb: error: cannot stat '${c.local}': No such file or directory")
            return RunResult(1)
        }
        val target = absoluteRemote(c.remote)
        val started = nanoClock()
        val counters = Counters()
        if (src.isDirectory) {
            val st = adb.stat(target)
            val root = if (st != null && st.isDirectory) target.trimEnd('/') + "/" + src.name else target
            pushTree(adb, src, root, counters, 0)
        } else {
            val dest = when {
                target.endsWith("/") -> target + src.name
                adb.stat(target)?.isDirectory == true -> "$target/${src.name}"
                else -> target
            }
            counters.bytes += pushFile(adb, src, dest)
            counters.files++
        }
        summary("pushed", c.local, counters, started)
        return OK
    }

    private fun pushFile(adb: AdbOps, src: LocalFile, remote: String): Long {
        val total = src.size
        try {
            return src.openInput().use { input ->
                adb.push(input, remote, if (src.isExecutable) EXEC_MODE else FILE_MODE, src.mtimeSeconds, cancel) { done ->
                    sink.progress(src.name, done, total)
                }
            }
        } finally {
            sink.progress("", -1, -1)
        }
    }

    private fun pushTree(adb: AdbOps, dir: LocalFile, remoteDir: String, counters: Counters, depth: Int) {
        if (depth > MAX_DEPTH) {
            sink.info("Skipping ${dir.path}: folders are nested too deeply.")
            counters.skipped++
            return
        }
        val r = adb.shell("mkdir -p ${CommandLine.quote(remoteDir)}")
        if (r.exitCode != null && r.exitCode != 0) throw AdbSyncException("Cannot create $remoteDir: ${r.stderrText().trim()}")
        for (child in dir.children()) {
            if (cancel.isCancelled()) throw AdbCancelledException()
            when {
                child.isSymlink -> { sink.info("Skipping symbolic link ${child.path}"); counters.skipped++ }
                child.isDirectory -> pushTree(adb, child, "$remoteDir/${child.name}", counters, depth + 1)
                else -> { counters.bytes += pushFile(adb, child, "$remoteDir/${child.name}"); counters.files++ }
            }
        }
    }

    private fun adbPull(c: AdbPull): RunResult {
        val adb = adbOrNull() ?: return NOT_CONNECTED
        val remote = absoluteRemote(c.remote)
        val st = adb.stat(remote)
        if (st == null) {
            sink.error("adb: error: failed to stat remote object '$remote': No such file or directory")
            return RunResult(1)
        }
        val base = remote.trimEnd('/').substringAfterLast('/')
        if (base.isEmpty()) {
            sink.error("adb: error: give a path inside the root folder to pull.")
            return RunResult(2)
        }
        val local = c.local?.let { files.resolve(it) }
        val dest = when {
            local == null -> files.downloadDir.child(base)
            local.isDirectory -> local.child(base)
            else -> local
        }
        val started = nanoClock()
        val counters = Counters()
        if (st.isDirectory) {
            pullTree(adb, remote, dest, counters, 0)
        } else {
            counters.bytes += pullFile(adb, remote, dest, st.size)
            counters.files++
        }
        summary("pulled", c.remote, counters, started, dest.path)
        return OK
    }

    private fun pullFile(adb: AdbOps, remote: String, dest: LocalFile, total: Long): Long {
        dest.mkParentDirs()
        var ok = false
        try {
            val n = dest.openOutput().use { out -> adb.pull(remote, out, cancel) { done -> sink.progress(dest.name, done, total) } }
            ok = true
            return n
        } finally {
            sink.progress("", -1, -1)
            if (!ok) dest.delete()
        }
    }

    private fun pullTree(adb: AdbOps, remoteDir: String, localDir: LocalFile, counters: Counters, depth: Int) {
        if (depth > MAX_DEPTH) {
            sink.info("Skipping $remoteDir: folders are nested too deeply.")
            counters.skipped++
            return
        }
        if (!localDir.mkdirs()) throw IOException("Cannot create ${localDir.path}")
        for (e in adb.list(remoteDir)) {
            if (cancel.isCancelled()) throw AdbCancelledException()
            val path = remoteDir.trimEnd('/') + "/" + e.name
            when {
                e.stat.isDirectory -> pullTree(adb, path, localDir.child(e.name), counters, depth + 1)
                e.stat.isRegularFile -> { counters.bytes += pullFile(adb, path, localDir.child(e.name), e.stat.size); counters.files++ }
                else -> { sink.info("Skipping $path (not a regular file)"); counters.skipped++ }
            }
        }
    }

    private fun summary(verb: String, what: String, c: Counters, startedNanos: Long, dest: String? = null) {
        val seconds = ((nanoClock() - startedNanos) / 1e9).coerceAtLeast(0.001)
        val rate = String.format(Locale.ROOT, "%.1f MB/s", c.bytes / seconds / (1024 * 1024))
        val skipped = if (c.skipped > 0) ", ${c.skipped} skipped" else ""
        sink.info("$what: ${c.files} file${if (c.files == 1) "" else "s"} $verb$skipped. $rate (${human(c.bytes)} in ${String.format(Locale.ROOT, "%.2f", seconds)}s)" + (dest?.let { " -> $it" } ?: ""))
    }

    private fun absoluteRemote(path: String): String = if (path.startsWith("/")) path else session.cwd.trimEnd('/') + "/" + path

    // ---- other adb commands ------------------------------------------------------------------------

    private fun adbReboot(c: AdbReboot): RunResult {
        val adb = adbOrNull() ?: return NOT_CONNECTED
        sink.info("Rebooting (${c.target.name.lowercase()})…")
        adb.reboot(c.target)
        return RunResult(0, linkLost = true, note = "The device is restarting.")
    }

    private fun adbInfo(c: AdbInfo): RunResult {
        if (c.what == AdbInfo.Info.NO_SERVER) {
            sink.info("Nothing to do: this client talks to the device directly, so there is no adb server.")
            return OK
        }
        if (c.what == AdbInfo.Info.VERSION) {
            sink.out("Bareflash native ADB client (no adb binary is involved)\n")
            return OK
        }
        val adb = adbOrNull() ?: return NOT_CONNECTED
        when (c.what) {
            AdbInfo.Info.DEVICES -> {
                val i = adb.info
                sink.out("List of devices attached\n${i.product ?: "device"}\t${i.state}\tmodel:${i.model?.replace(' ', '_') ?: "?"} device:${i.device ?: "?"}\n")
            }
            AdbInfo.Info.GET_STATE -> sink.out(adb.info.state + "\n")
            AdbInfo.Info.GET_SERIALNO -> sink.out(adb.shell("getprop ro.serialno").stdoutText().trim().ifEmpty { "unknown" } + "\n")
        }
        return OK
    }

    // ---- fastboot -----------------------------------------------------------------------------

    private fun fbGetvar(name: String): RunResult {
        val fb = fastbootOrNull() ?: return NOT_CONNECTED
        if (name == "all") {
            fb.getvarAll().toSortedMap().forEach { (k, v) -> sink.out("$k: $v\n") }
            return OK
        }
        val value = fb.getvar(name)
        if (value == null) {
            sink.error("getvar:$name FAILED (remote: 'unknown variable')")
            return RunResult(1)
        }
        sink.out("$name: $value\n")
        return OK
    }

    private fun fbDevices(): RunResult {
        val fb = fastbootOrNull() ?: return NOT_CONNECTED
        sink.out("${fb.getvar("serialno") ?: "fastboot device"}\tfastboot\n")
        return OK
    }

    private fun fbFlash(c: FbFlash): RunResult {
        val fb = fastbootOrNull() ?: return NOT_CONNECTED
        val file = files.resolve(c.path)
        if (!file.exists || file.isDirectory) {
            sink.error("cannot load '${c.path}': No such file or directory")
            return RunResult(1)
        }
        val started = nanoClock()
        file.openByteSource().use { image ->
            fb.flash(c.partition, image, object : FlashProgress {
                override fun onPieceStart(index: Int, count: Int, pieceBytes: Long) {
                    sink.info("Sending '${c.partition}'${if (count > 1) " (${index + 1}/$count)" else ""} (${human(pieceBytes)})")
                }

                override fun onBytesSent(sent: Long, total: Long) {
                    sink.progress(c.partition, sent, total)
                }

                override fun onWriting(index: Int, count: Int) {
                    sink.progress("", -1, -1)
                    sink.info("Writing '${c.partition}'${if (count > 1) " (${index + 1}/$count)" else ""}")
                }
            })
        }
        sink.progress("", -1, -1)
        sink.info("OKAY. Total time: ${String.format(Locale.ROOT, "%.3f", (nanoClock() - started) / 1e9)}s")
        return OK
    }

    private fun fbBoot(c: FbBoot): RunResult {
        val fb = fastbootOrNull() ?: return NOT_CONNECTED
        val file = files.resolve(c.path)
        if (!file.exists || file.isDirectory) {
            sink.error("cannot load '${c.path}': No such file or directory")
            return RunResult(1)
        }
        sink.info("Booting '${file.name}' (${human(file.size)})")
        file.openByteSource().use { fb.boot(it) }
        sink.info("OKAY")
        return RunResult(0, linkLost = true, note = "The device is booting the image.")
    }

    private fun fbLogical(c: FbLogical): RunResult = fbResult {
        when (c.op) {
            FbLogical.Op.CREATE -> it.createLogicalPartition(c.name, c.sizeBytes ?: 0)
            FbLogical.Op.RESIZE -> it.resizeLogicalPartition(c.name, c.sizeBytes ?: 0)
            FbLogical.Op.DELETE -> it.deleteLogicalPartition(c.name)
        }
    }

    private fun fbSimple(block: (FastbootOps) -> Unit): RunResult {
        val fb = fastbootOrNull() ?: return NOT_CONNECTED
        block(fb)
        sink.info("OKAY")
        return OK
    }

    private fun fbResult(block: (FastbootOps) -> FastbootClient.Result): RunResult {
        val fb = fastbootOrNull() ?: return NOT_CONNECTED
        val r = block(fb)
        r.info.forEach { sink.out("(bootloader) $it\n") }
        if (r.message.isNotEmpty()) sink.out("(bootloader) ${r.message}\n")
        sink.info("OKAY")
        return OK
    }

    private fun fbLeaving(message: String, note: String, block: (FastbootOps) -> Unit): RunResult {
        val fb = fastbootOrNull() ?: return NOT_CONNECTED
        sink.info(message)
        block(fb)
        return RunResult(0, linkLost = true, note = note)
    }

    private fun human(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
        else -> String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0)
    }

    private companion object {
        val OK = RunResult(0)
        val NOT_CONNECTED = RunResult(2)
        val CD = Regex("^cd(?:\\s+(.*))?$", RegexOption.DOT_MATCHES_ALL)
        const val CD_METACHARACTERS = ";&|<>`$()\n"
        const val MAX_DEPTH = 32
        const val FILE_MODE = 0x81A4 // 0100644
        const val EXEC_MODE = 0x81ED // 0100755
    }
}
