package com.bareflash.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One event from a shell v2 session. */
public sealed interface ShellEvent {
    public class Stdout(public val data: ByteArray) : ShellEvent
    public class Stderr(public val data: ByteArray) : ShellEvent
    public class Exit(public val code: Int) : ShellEvent
}

/** Everything a finished command produced. [exitCode] is null when the device cannot report it (legacy shell). */
public class ShellResult(
    public val exitCode: Int?,
    public val stdout: ByteArray,
    public val stderr: ByteArray,
    /** True if output hit the size limit and the command was cut off. */
    public val truncated: Boolean,
) {
    public fun stdoutText(): String = String(stdout, Charsets.UTF_8)
    public fun stderrText(): String = String(stderr, Charsets.UTF_8)
}

/**
 * A shell v2 session: stdout, stderr and the exit code arrive as separate,
 * length-prefixed packets (1 byte id + 4 byte little-endian length + data),
 * and input, stdin-close and window-size changes can be sent back.
 */
public class AdbShellSession internal constructor(
    private val stream: AdbStream,
    private val maxPacket: Int,
) : AutoCloseable {

    private val header = ByteArray(HEADER_SIZE)

    /**
     * Next event, or null when the stream ended without an exit packet.
     * Packets with an unknown id are skipped. Throws [AdbTimeoutException]
     * if nothing arrives within [timeoutMs].
     */
    public fun readEvent(timeoutMs: Long): ShellEvent? {
        while (true) {
            val got = stream.readFully(header, 0, HEADER_SIZE, timeoutMs)
            if (got == 0) return null
            if (got < HEADER_SIZE) throw AdbProtocolException("Shell stream ended inside a packet header")
            val id = header[0].toInt() and 0xFF
            val length = ByteBuffer.wrap(header, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (length < 0 || length > MAX_INCOMING_PACKET) throw AdbProtocolException("Shell packet length $length out of range")
            val payload = ByteArray(length)
            if (stream.readFully(payload, 0, length, timeoutMs) < length) {
                throw AdbProtocolException("Shell stream ended inside a packet")
            }
            when (id) {
                ID_STDOUT -> return ShellEvent.Stdout(payload)
                ID_STDERR -> return ShellEvent.Stderr(payload)
                ID_EXIT -> {
                    if (length < 1) throw AdbProtocolException("Empty exit packet")
                    return ShellEvent.Exit(payload[0].toInt() and 0xFF)
                }
                else -> continue
            }
        }
    }

    public fun writeStdin(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        require(offset >= 0 && length >= 0 && offset <= data.size - length) { "Range out of bounds" }
        var done = 0
        while (done < length) {
            val n = minOf(length - done, maxPacket)
            writePacket(ID_STDIN, data.copyOfRange(offset + done, offset + done + n))
            done += n
        }
    }

    /** Tells the command its input has ended (like Ctrl-D on a pipe). */
    public fun closeStdin(): Unit = writePacket(ID_CLOSE_STDIN, ByteArray(0))

    /** Reports a new terminal size to a command running on a pty. */
    public fun resize(rows: Int, cols: Int, xPixels: Int = 0, yPixels: Int = 0) {
        require(rows in 1..9999 && cols in 1..9999) { "Invalid size" }
        writePacket(ID_WINDOW_SIZE, "${rows}x$cols,${xPixels}x$yPixels".toByteArray(Charsets.US_ASCII))
    }

    override fun close(): Unit = stream.close()

    private fun writePacket(id: Int, data: ByteArray) {
        val packet = ByteBuffer.allocate(HEADER_SIZE + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByte()).putInt(data.size).put(data).array()
        stream.write(packet)
    }

    internal companion object {
        const val ID_STDIN = 0
        const val ID_STDOUT = 1
        const val ID_STDERR = 2
        const val ID_EXIT = 3
        const val ID_CLOSE_STDIN = 4
        const val ID_WINDOW_SIZE = 5
        const val HEADER_SIZE = 5
        const val MAX_INCOMING_PACKET = 1 shl 20
    }
}

private const val DEFAULT_MAX_OUTPUT = 16 * 1024 * 1024
private const val DEFAULT_SHELL_TIMEOUT_MS = 30_000L

/**
 * Opens a shell v2 session. With [pty] true the command runs on a
 * pseudo-terminal (stdout and stderr merge, as in an interactive shell); an
 * empty [command] starts the login shell. Throws [AdbUnsupportedException] if
 * the device has no shell v2.
 */
public fun AdbConnection.openShell(command: String = "", pty: Boolean = false, term: String = "xterm-256color"): AdbShellSession {
    if (!supports("shell_v2")) throw AdbUnsupportedException("Device does not support shell v2")
    require('\u0000' !in command) { "Invalid command" }
    require(term.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "Invalid TERM" }
    val mode = if (pty) "pty,TERM=$term" else "raw"
    val stream = open("shell,v2,$mode:$command")
    return AdbShellSession(stream, minOf(maxPayload - AdbShellSession.HEADER_SIZE, 64 * 1024))
}

/** Opens a plain (legacy) shell stream: raw bytes, stdout and stderr merged, no exit code. */
public fun AdbConnection.openLegacyShell(command: String = ""): AdbStream {
    require('\u0000' !in command) { "Invalid command" }
    return open("shell:$command")
}

/**
 * Runs [command] and collects its output. Uses shell v2 when the device has
 * it (separate stderr and a real exit code), otherwise the legacy shell.
 * Output beyond [maxOutputBytes] is dropped and the command is stopped.
 */
public fun AdbConnection.shell(
    command: String,
    timeoutMs: Long = DEFAULT_SHELL_TIMEOUT_MS,
    maxOutputBytes: Int = DEFAULT_MAX_OUTPUT,
): ShellResult {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    fun left(): Long = (deadline - System.nanoTime()) / 1_000_000

    if (!supports("shell_v2")) {
        openLegacyShell(command).use { s ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                if (left() <= 0) throw AdbTimeoutException("Command timed out")
                val n = s.read(buf, 0, buf.size, left())
                if (n < 0) return ShellResult(null, out.toByteArray(), ByteArray(0), false)
                if (out.size() + n > maxOutputBytes) return ShellResult(null, out.toByteArray(), ByteArray(0), true)
                out.write(buf, 0, n)
            }
        }
    }
    openShell(command).use { session ->
        val out = java.io.ByteArrayOutputStream()
        val err = java.io.ByteArrayOutputStream()
        var exit: Int? = null
        while (exit == null) {
            if (left() <= 0) throw AdbTimeoutException("Command timed out")
            when (val e = session.readEvent(left()) ?: break) {
                is ShellEvent.Stdout -> {
                    if (out.size() + err.size() + e.data.size > maxOutputBytes) return ShellResult(null, out.toByteArray(), err.toByteArray(), true)
                    out.write(e.data)
                }
                is ShellEvent.Stderr -> {
                    if (out.size() + err.size() + e.data.size > maxOutputBytes) return ShellResult(null, out.toByteArray(), err.toByteArray(), true)
                    err.write(e.data)
                }
                is ShellEvent.Exit -> exit = e.code
            }
        }
        return ShellResult(exit, out.toByteArray(), err.toByteArray(), false)
    }
}

/**
 * Runs [command] with `exec:`: a raw, binary-safe stdout with no pty and no
 * exit code. Suited to commands that print binary data, such as `screencap -p`.
 */
public fun AdbConnection.exec(command: String, timeoutMs: Long = DEFAULT_SHELL_TIMEOUT_MS, maxOutputBytes: Int = DEFAULT_MAX_OUTPUT): ByteArray {
    require('\u0000' !in command) { "Invalid command" }
    open("exec:$command").use { return it.readAll(maxOutputBytes, timeoutMs) }
}

/** What to reboot into. */
public enum class AdbRebootTarget(internal val service: String) {
    SYSTEM("reboot:"),
    BOOTLOADER("reboot:bootloader"),
    RECOVERY("reboot:recovery"),
    SIDELOAD("reboot:sideload"),
    FASTBOOTD("reboot:fastboot"),

    /** Samsung download (Odin) mode. Only some devices honour this. */
    DOWNLOAD("reboot:download"),

    /** Qualcomm emergency download mode. Only some devices honour this. */
    EDL("reboot:edl"),
}

/**
 * Asks the device to reboot. The device normally drops the USB link at once,
 * so the stream ending, or the link failing, counts as success.
 */
public fun AdbConnection.reboot(target: AdbRebootTarget = AdbRebootTarget.SYSTEM) {
    try {
        open(target.service).use { it.readAll(4096, 5_000) }
    } catch (_: AdbClosedException) {
        // The link went down with the device: expected.
    } catch (_: AdbTimeoutException) {
        // No answer before the device went away: expected.
    }
}
