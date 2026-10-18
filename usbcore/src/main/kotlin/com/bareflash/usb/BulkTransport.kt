package com.bareflash.usb

/**
 * The raw bulk-transfer primitives of one claimed interface.
 *
 * It mirrors Android's `UsbDeviceConnection.bulkTransfer`: each call returns
 * the number of bytes transferred, or a negative value when the transfer
 * failed or timed out. Android does not say which of the two it was, so
 * [BulkTransport] works that out. Implementations contain no logic of their
 * own; the Android app supplies one over `UsbDeviceConnection`, tests supply a
 * fake.
 */
public interface BulkBackend : AutoCloseable {
    /** Max packet size of the OUT endpoint, or 0 if unknown. */
    public val maxPacketSizeOut: Int

    /** One OUT transfer. Returns bytes sent, or a negative value on failure or timeout. */
    public fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    /** One IN transfer of up to [length] bytes. Returns bytes received, or a negative value on failure or timeout. */
    public fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    /** True while the device is still on the bus. Used to tell an unplug from a timeout. */
    public fun isDeviceAttached(): Boolean

    override fun close()
}

/**
 * Tuning for [BulkTransport].
 *
 * [chunkSize] is the largest single transfer. 16 KiB is the long-standing
 * usbfs limit and works everywhere; raise it only after measuring on real
 * hardware. [zeroLengthPacketAfterFullFinalPacket] makes a write whose length
 * is an exact multiple of the OUT max packet size end with a zero-length
 * packet, which ADB needs and fastboot does not.
 */
public class BulkTransportConfig(
    public val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    public val zeroLengthPacketAfterFullFinalPacket: Boolean = false,
) {
    init {
        require(chunkSize > 0) { "chunkSize must be positive" }
    }

    public companion object {
        public const val DEFAULT_CHUNK_SIZE: Int = 16 * 1024
    }
}

/**
 * [UsbTransport] over a [BulkBackend]: splits writes into transfers that are a
 * whole number of USB packets, and turns the backend's bare "negative" result
 * into a precise exception.
 *
 * Failure classification, in order: closed or detached means
 * [UsbDisconnectedException]; a failure that used (nearly) the whole timeout
 * means [UsbTimeoutException]; anything else (a stall or I/O error) is a plain
 * [UsbTransportException]. After a timeout in the middle of a multi-chunk
 * write the amount already sent is unknown, so a failed write must be treated
 * as having left the device mid-transfer.
 *
 * One reader and one writer at a time. [close] may be called from any thread
 * to abort a blocked transfer.
 */
public class BulkTransport(
    private val backend: BulkBackend,
    private val config: BulkTransportConfig = BulkTransportConfig(),
    private val nanoClock: () -> Long = System::nanoTime,
) : UsbTransport {

    @Volatile
    private var closed = false

    /** Largest OUT transfer: the chunk size rounded down to whole packets, but at least one packet. */
    private val writeChunk: Int = run {
        val mps = backend.maxPacketSizeOut
        if (mps <= 0) config.chunkSize else maxOf(mps, config.chunkSize / mps * mps)
    }

    override fun write(data: ByteArray, offset: Int, length: Int, timeoutMs: Int) {
        require(offset >= 0 && length >= 0 && offset <= data.size - length) { "Range out of bounds" }
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        ensureOpen()
        if (length == 0) {
            // An explicit empty write means "send a zero-length packet".
            sendOut(data, offset, 0, timeoutMs)
            return
        }
        var done = 0
        while (done < length) {
            val n = minOf(writeChunk, length - done)
            sendOut(data, offset + done, n, timeoutMs)
            done += n
        }
        val mps = backend.maxPacketSizeOut
        if (config.zeroLengthPacketAfterFullFinalPacket && mps > 0 && length % mps == 0) {
            sendOut(data, offset, 0, timeoutMs)
        }
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        require(buffer.isNotEmpty()) { "buffer must not be empty" }
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        ensureOpen()
        // Ask for no more than the caller can hold, and no more than one chunk.
        val request = minOf(buffer.size, config.chunkSize)
        val started = nanoClock()
        val got = backend.bulkIn(buffer, 0, request, timeoutMs)
        if (got < 0) throw failure("read", started, timeoutMs)
        return got
    }

    override fun close() {
        if (closed) return
        closed = true
        backend.close()
    }

    private fun sendOut(data: ByteArray, offset: Int, length: Int, timeoutMs: Int) {
        val started = nanoClock()
        val sent = backend.bulkOut(data, offset, length, timeoutMs)
        if (sent < 0) throw failure("write", started, timeoutMs)
        if (sent != length) throw UsbTransportException("Short USB write: sent $sent of $length bytes")
    }

    private fun ensureOpen() {
        if (closed) throw UsbDisconnectedException("USB connection is closed")
    }

    private fun failure(operation: String, startedNanos: Long, timeoutMs: Int): UsbTransportException {
        if (closed) return UsbDisconnectedException("USB connection was closed during $operation")
        if (!backend.isDeviceAttached()) return UsbDisconnectedException("Device was disconnected during $operation")
        val elapsedMs = (nanoClock() - startedNanos) / NANOS_PER_MILLI
        // Android reports a timeout and an I/O error identically. A failure
        // that used at least 90% of the timeout is treated as the timeout.
        return if (elapsedMs * 10 >= timeoutMs.toLong() * 9) {
            UsbTimeoutException("USB $operation timed out after $timeoutMs ms")
        } else {
            UsbTransportException("USB $operation failed after $elapsedMs ms (stall or I/O error)")
        }
    }

    private companion object {
        const val NANOS_PER_MILLI: Long = 1_000_000L
    }
}
