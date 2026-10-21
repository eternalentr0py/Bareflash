package com.bareflash.cli

import com.bareflash.adb.AdbConnection
import com.bareflash.fakes.FakeAdbUsbTransport
import com.bareflash.fakes.FakeAdbdCore
import com.bareflash.fakes.FakeFastbootDevice
import com.bareflash.fakes.FakeFs
import com.bareflash.fakes.ShellOutcome
import com.bareflash.fakes.TestKeys
import com.bareflash.fastboot.FastbootClient
import java.io.File
import java.nio.file.Files
import java.util.Random

class RecordingSink : TerminalSink {
    val outText = StringBuilder()
    val errText = StringBuilder()
    val infos = ArrayList<String>()
    val errors = ArrayList<String>()
    val progress = ArrayList<Triple<String, Long, Long>>()
    var onProgress: (Long, Long) -> Unit = { _, _ -> }

    override fun out(text: String) { outText.append(text) }
    override fun err(text: String) { errText.append(text) }
    override fun info(text: String) { infos += text }
    override fun error(text: String) { errors += text }
    override fun progress(label: String, done: Long, total: Long) {
        progress += Triple(label, done, total)
        onProgress(done, total)
    }

    fun everything(): String = outText.toString() + errText + infos.joinToString("\n") + errors.joinToString("\n")
}

/** Emulates just enough of a device shell for the console's own helper commands. */
fun shellEmulation(fs: FakeFs, seen: MutableList<String>): (String) -> ShellOutcome = { cmd ->
    seen += cmd
    val cd = Regex("""^cd (\S+) && cd (.+) && pwd$""").matchEntire(cmd)
    when {
        cd != null -> {
            val base = cd.groupValues[1].trim('\'')
            val target = cd.groupValues[2].trim().trim('\'', '"')
            val p = if (target.startsWith("/")) target else java.nio.file.Paths.get(base, target).normalize().toString()
            if (p in fs.dirs) ShellOutcome("$p\n".toByteArray())
            else ShellOutcome(stderr = "cd: $target: No such file or directory\n".toByteArray(), exit = 1)
        }
        cmd.startsWith("mkdir -p ") -> {
            fs.dirs += CommandLine.tokenize(cmd)[2].text
            ShellOutcome()
        }
        cmd == "getprop ro.serialno" -> ShellOutcome("SN123\n".toByteArray())
        else -> ShellOutcome(stdout = "ran: $cmd\n".toByteArray())
    }
}

/** A runner wired to a fake adbd and/or a fake fastboot device, with a scratch home folder. */
class RunnerHarness(withAdb: Boolean = true, withFastboot: Boolean = false) : AutoCloseable {
    val home: File = Files.createTempDirectory("cli-home").toFile().also { File(it, "Download").mkdirs() }
    val core = FakeAdbdCore()
    val fbDevice = FakeFastbootDevice()
    val sink = RecordingSink()
    val seenShell = ArrayList<String>()
    @Volatile var cancelled = false

    val adbTransport = FakeAdbUsbTransport(core)
    val connection: AdbConnection? = if (withAdb) {
        core.shellHandler = shellEmulation(core.fs, seenShell)
        core.fs.put("/sdcard/Docs/readme.txt", "docs".toByteArray())
        AdbConnection.connect(adbTransport, TestKeys.pair)
    } else null
    private val fastbootClient: FastbootClient? = if (withFastboot) FastbootClient(fbDevice, cancel = { cancelled }) else null

    val session = DeviceSession(connection?.let { NativeAdbOps(it) }, fastbootClient?.let { NativeFastbootOps(it) })
    val files = JavaLocalFiles(home, File(home, "Download"))
    val runner = CommandRunner(session, files, sink, { cancelled })

    fun run(c: DeviceCommand): RunResult = runner.run(c)

    fun file(path: String): File = File(home, path)

    fun write(path: String, bytes: ByteArray): File = file(path).also { it.parentFile.mkdirs(); it.writeBytes(bytes) }

    override fun close() {
        connection?.close()
        fbDevice.close()
        home.deleteRecursively()
    }
}

fun bytes(n: Int, seed: Long = 1): ByteArray = ByteArray(n).also { Random(seed).nextBytes(it) }
