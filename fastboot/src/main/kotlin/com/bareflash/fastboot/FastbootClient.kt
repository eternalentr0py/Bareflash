package com.bareflash.fastboot

import com.bareflash.usb.CancelToken
import com.bareflash.usb.UsbTimeoutException
import com.bareflash.usb.UsbTransport
import com.bareflash.usb.UsbTransportException
import java.util.Locale

/** What `reboot` should boot into. */
public enum class RebootTarget(internal val command: String) {
    SYSTEM("reboot"),
    BOOTLOADER("reboot-bootloader"),
    RECOVERY("reboot-recovery"),
    FASTBOOTD("reboot-fastboot"),
}

/** Bootloader lock-state commands. Unlock and lock normally wipe user data. */
public enum class FlashingCommand(internal val command: String) {
    UNLOCK("flashing unlock"),
    LOCK("flashing lock"),
    UNLOCK_CRITICAL("flashing unlock_critical"),
    LOCK_CRITICAL("flashing lock_critical"),
    GET_UNLOCK_ABILITY("flashing get_unlock_ability"),
}

/** Progress callbacks for [FastbootClient.flash]. Every method has an empty default. */
public interface FlashProgress {
    /** A piece is about to be downloaded. [pieceBytes] is its size on the wire. */
    public fun onPieceStart(index: Int, count: Int, pieceBytes: Long) {}

    /** Bytes of the current piece sent so far. */
    public fun onBytesSent(sent: Long, total: Long) {}

    /** The piece is on the device and the device is now writing it to flash. */
    public fun onWriting(index: Int, count: Int) {}
}

/**
 * Host side of the fastboot protocol over any [UsbTransport].
 *
 * One command is in flight at a time, so a client must not be shared between
 * threads. Cancelling mid-download leaves the device expecting data: close the
 * connection (or reboot the device) afterwards instead of reusing it.
 *
 * Protocol notes, from the AOSP fastboot README: commands are ASCII of at most
 * 64 bytes; the device replies INFO* then exactly one of OKAY, FAIL or DATA.
 * These details were written from the spec and tested against a fake device;
 * they have not yet been exercised on real hardware.
 */
public class FastbootClient(
    private val transport: UsbTransport,
    private val cancel: CancelToken = CancelToken.NEVER,
    private val log: (String) -> Unit = {},
    private val nanoClock: () -> Long = System::nanoTime,
) {
    /** Outcome of a successful command: the OKAY text and any INFO lines that preceded it. */
    public class Result(public val message: String, public val info: List<String>)

    private sealed interface Reply {
        class Okay(val message: String, val info: List<String>) : Reply
        class Data(val length: Long) : Reply
    }

    private val readBuffer = ByteArray(RESPONSE_BUFFER)

    // ---- Commands -----------------------------------------------------------

    public fun command(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result {
        send(command)
        return when (val r = awaitReply(timeoutMs)) {
            is Reply.Okay -> Result(r.message, r.info)
            is Reply.Data -> throw FastbootProtocolException("Unexpected DATA response to '$command'")
        }
    }

    /** Value of [name], or null if the device answers FAIL (unknown variable). */
    public fun getvarOrNull(name: String): String? {
        requireToken(name, "variable name")
        return try {
            command("getvar:$name").message
        } catch (_: FastbootFailException) {
            null
        }
    }

    public fun getvar(name: String): String =
        getvarOrNull(name) ?: throw FastbootFailException("Unknown variable: $name")

    /** Every variable the bootloader reports, via `getvar:all`. */
    public fun getvarAll(): Map<String, String> =
        FastbootVars.parseAll(command("getvar:all", LONG_TIMEOUT_MS).info)

    /** The device's max-download-size, or null if it does not say. */
    public fun maxDownloadSize(): Long? = getvarOrNull("max-download-size")?.let(FastbootVars::parseSize)?.takeIf { it > 0 }

    /** Sends [payload] to the device's download buffer. Does not flash it. */
    public fun download(payload: Payload, onBytesSent: ((sent: Long, total: Long) -> Unit)? = null) {
        val size = payload.size
        require(size in 1..MAX_DOWNLOAD) { "Download size out of range: $size" }
        send(String.format(Locale.ROOT, "download:%08x", size))
        val reply = awaitReply(DEFAULT_TIMEOUT_MS)
        if (reply !is Reply.Data) throw FastbootProtocolException("Expected DATA after download command")
        if (reply.length != size) {
            throw FastbootProtocolException("Device accepted ${reply.length} bytes, expected $size")
        }
        var sent = 0L
        payload.writeTo({ buf, off, len ->
            writeToDevice(buf, off, len)
            sent += len
            onBytesSent?.invoke(sent, size)
        }, cancel)
        when (awaitReply(POST_DOWNLOAD_TIMEOUT_MS)) {
            is Reply.Okay -> Unit
            is Reply.Data -> throw FastbootProtocolException("Unexpected DATA after download")
        }
    }

    /**
     * Flashes [image] to [partition]. If the image is larger than the device's
     * max-download-size it is sent as several sparse pieces. If the device does
     * not report a limit and [maxDownloadSize] is null, a conservative 64 MiB
     * is assumed.
     */
    public fun flash(
        partition: String,
        image: ByteSource,
        maxDownloadSize: Long? = null,
        progress: FlashProgress? = null,
    ) {
        requirePartition(partition)
        val limit = maxDownloadSize ?: maxDownloadSize() ?: FALLBACK_MAX_DOWNLOAD.also {
            log("Device did not report max-download-size; assuming $it bytes")
        }
        val pieces = FlashPlanner.plan(image, limit)
        for ((i, piece) in pieces.withIndex()) {
            progress?.onPieceStart(i, pieces.size, piece.size)
            download(piece) { sent, total -> progress?.onBytesSent(sent, total) }
            progress?.onWriting(i, pieces.size)
            command("flash:$partition", LONG_TIMEOUT_MS)
        }
    }

    /** Downloads [image] and boots it once, without writing it to flash. */
    public fun boot(image: ByteSource) {
        download(image.asPayload())
        command("boot", LONG_TIMEOUT_MS)
    }

    public fun erase(partition: String) {
        requirePartition(partition)
        command("erase:$partition", LONG_TIMEOUT_MS)
    }

    /** Selects the active slot, `a` or `b`. */
    public fun setActive(slot: String) {
        require(slot.length == 1 && slot[0] in 'a'..'z') { "Invalid slot: $slot" }
        command("set_active:$slot")
    }

    /**
     * Reboots. Many devices drop the USB link before or right after answering,
     * or never answer at all, so a transport failure or a short silence after
     * the command was sent is treated as success. A FAIL from the device is
     * still reported.
     */
    public fun reboot(target: RebootTarget = RebootTarget.SYSTEM, answerTimeoutMs: Long = REBOOT_TIMEOUT_MS) {
        try {
            command(target.command, answerTimeoutMs)
        } catch (e: UsbTransportException) {
            log("Link dropped during ${target.command}: ${e.message}")
        } catch (_: FastbootTimeoutException) {
            log("No answer to ${target.command}; assuming the device is rebooting")
        }
    }

    /** Continues the normal boot. Same disconnect tolerance as [reboot]. */
    public fun continueBoot(answerTimeoutMs: Long = REBOOT_TIMEOUT_MS) {
        try {
            command("continue", answerTimeoutMs)
        } catch (e: UsbTransportException) {
            log("Link dropped during continue: ${e.message}")
        } catch (_: FastbootTimeoutException) {
            log("No answer to continue; assuming the device is booting")
        }
    }

    public fun flashing(cmd: FlashingCommand): Result = command(cmd.command, LONG_TIMEOUT_MS)

    /** `oem <args>`; [args] is passed through as typed, so the caller owns what it means. */
    public fun oem(args: String): Result {
        require(args.isNotBlank()) { "oem needs an argument" }
        return command("oem $args", LONG_TIMEOUT_MS)
    }

    public fun createLogicalPartition(name: String, sizeBytes: Long): Result {
        requirePartition(name)
        require(sizeBytes >= 0) { "Negative size" }
        return command("create-logical-partition:$name:$sizeBytes")
    }

    public fun deleteLogicalPartition(name: String): Result {
        requirePartition(name)
        return command("delete-logical-partition:$name")
    }

    public fun resizeLogicalPartition(name: String, sizeBytes: Long): Result {
        requirePartition(name)
        require(sizeBytes >= 0) { "Negative size" }
        return command("resize-logical-partition:$name:$sizeBytes")
    }

    // ---- Wire handling ------------------------------------------------------

    private fun send(command: String) {
        require(command.length <= MAX_COMMAND_LENGTH) { "Command longer than $MAX_COMMAND_LENGTH bytes" }
        require(command.all { it.code in 0x20..0x7E }) { "Command must be printable ASCII" }
        log("> $command")
        val bytes = command.toByteArray(Charsets.US_ASCII)
        writeToDevice(bytes, 0, bytes.size)
    }

    private fun writeToDevice(buf: ByteArray, off: Int, len: Int) {
        if (cancel.isCancelled()) throw FastbootCancelledException()
        transport.write(buf, off, len, WRITE_TIMEOUT_MS)
    }

    /**
     * Reads until OKAY or DATA. INFO packets are collected and each one
     * restarts the timeout, since devices send them to show they are alive.
     * FAIL becomes [FastbootFailException].
     */
    private fun awaitReply(timeoutMs: Long): Reply {
        val info = ArrayList<String>()
        var deadline = nanoClock() + timeoutMs * NANOS_PER_MS
        while (true) {
            if (cancel.isCancelled()) throw FastbootCancelledException()
            val remainingMs = (deadline - nanoClock()) / NANOS_PER_MS
            if (remainingMs <= 0) throw FastbootTimeoutException("No response from device in ${timeoutMs}ms")
            val n = try {
                transport.read(readBuffer, minOf(remainingMs, POLL_MS).toInt())
            } catch (_: UsbTimeoutException) {
                continue
            }
            if (n == 0) continue // zero-length packet
            when (val r = FastbootResponse.parse(readBuffer, n)) {
                is FastbootResponse.Info -> {
                    log("(info) ${r.message}")
                    info += r.message
                    deadline = nanoClock() + timeoutMs * NANOS_PER_MS
                }
                is FastbootResponse.Okay -> { log("OKAY ${r.message}"); return Reply.Okay(r.message, info) }
                is FastbootResponse.Fail -> { log("FAIL ${r.message}"); throw FastbootFailException(r.message) }
                is FastbootResponse.Data -> return Reply.Data(r.length)
            }
        }
    }

    private fun requirePartition(name: String) =
        require(PARTITION_NAME.matches(name)) { "Invalid partition name: $name" }

    private fun requireToken(value: String, what: String) =
        require(value.isNotEmpty() && value.all { it.code in 0x21..0x7E }) { "Invalid $what" }

    public companion object {
        public const val MAX_COMMAND_LENGTH: Int = 64
        public const val DEFAULT_TIMEOUT_MS: Long = 15_000
        public const val LONG_TIMEOUT_MS: Long = 10 * 60_000
        public const val REBOOT_TIMEOUT_MS: Long = 5_000
        public const val FALLBACK_MAX_DOWNLOAD: Long = 64L * 1024 * 1024

        private const val POST_DOWNLOAD_TIMEOUT_MS = 60_000L
        private const val WRITE_TIMEOUT_MS = 30_000
        private const val POLL_MS = 500L
        private const val RESPONSE_BUFFER = 512
        private const val MAX_DOWNLOAD = 0xFFFFFFFFL
        private const val NANOS_PER_MS = 1_000_000L
        private val PARTITION_NAME = Regex("^[A-Za-z0-9._-]{1,48}$")
    }
}
