package com.bareflash.adb

import com.bareflash.usb.UsbTimeoutException
import com.bareflash.usb.UsbTransport

/**
 * Reads and writes whole ADB messages over a [UsbTransport].
 *
 * The reader keeps its own buffer, so it works whether the transport hands
 * back exactly one USB transfer per read (real hardware: a header, then a
 * payload that may arrive in several pieces, with zero-length packets in
 * between) or an arbitrary slice of a byte stream (tests, TCP).
 *
 * One reader thread and any number of writer threads: writes are serialized.
 */
internal class MessageChannel(
    private val transport: UsbTransport,
    private val maxPayload: Int,
    private val writeTimeoutMs: Int,
    private val nanoClock: () -> Long = System::nanoTime,
) {
    private val writeLock = Any()
    private val scratch = ByteArray(SCRATCH_SIZE)
    private var bufPos = 0
    private var bufLimit = 0

    /** True until the peer proves it speaks a version that drops checksums. */
    @Volatile
    var sendChecksums: Boolean = true

    /** Verify the checksum of incoming messages. Off until negotiation says it is needed. */
    @Volatile
    var verifyChecksums: Boolean = false

    @Volatile
    var stopped: Boolean = false

    fun send(message: AdbMessage) {
        synchronized(writeLock) {
            val header = AdbWire.encodeHeader(message, sendChecksums)
            transport.write(header, 0, header.size, writeTimeoutMs)
            if (message.data.isNotEmpty()) transport.write(message.data, 0, message.data.size, writeTimeoutMs)
        }
    }

    /**
     * Reads one message. The header wait ends at [headerDeadlineNanos]
     * (use [NO_DEADLINE] to wait for as long as the channel stays open); once a
     * header has arrived the payload must follow within [payloadTimeoutMs].
     */
    fun receive(headerDeadlineNanos: Long, payloadTimeoutMs: Long): AdbMessage {
        val headerBytes = ByteArray(AdbWire.HEADER_SIZE)
        readFully(headerBytes, headerBytes.size, headerDeadlineNanos)
        val h = AdbWire.decodeHeader(headerBytes, maxPayload)
        val data = ByteArray(h.length)
        if (h.length > 0) readFully(data, h.length, nanoClock() + payloadTimeoutMs * NANOS_PER_MS)
        if (verifyChecksums && AdbWire.checksum(data) != h.checksum) throw AdbProtocolException("Bad payload checksum")
        return AdbMessage(h.command, h.arg0, h.arg1, data)
    }

    private fun readFully(dst: ByteArray, length: Int, deadlineNanos: Long) {
        var done = 0
        var emptyReads = 0
        while (done < length) {
            if (bufPos < bufLimit) {
                val n = minOf(length - done, bufLimit - bufPos)
                System.arraycopy(scratch, bufPos, dst, done, n)
                bufPos += n
                done += n
                continue
            }
            if (stopped) throw AdbClosedException("Connection closed")
            val remainingMs = if (deadlineNanos == NO_DEADLINE) POLL_MS else (deadlineNanos - nanoClock()) / NANOS_PER_MS
            if (remainingMs <= 0) throw AdbTimeoutException("Timed out waiting for the device")
            val n = try {
                transport.read(scratch, minOf(remainingMs, POLL_MS).toInt())
            } catch (_: UsbTimeoutException) {
                continue
            }
            if (n == 0) {
                // A zero-length packet ends a transfer whose size was a whole number of packets.
                if (++emptyReads > MAX_EMPTY_READS) throw AdbProtocolException("Device sent only empty packets")
                continue
            }
            emptyReads = 0
            bufPos = 0
            bufLimit = n
        }
    }

    companion object {
        const val NO_DEADLINE = Long.MIN_VALUE
        private const val SCRATCH_SIZE = 16 * 1024
        private const val POLL_MS = 250L
        private const val NANOS_PER_MS = 1_000_000L
        private const val MAX_EMPTY_READS = 64
    }
}
