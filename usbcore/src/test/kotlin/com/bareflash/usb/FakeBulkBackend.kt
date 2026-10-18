package com.bareflash.usb

/**
 * A scriptable [BulkBackend] that behaves like Android's UsbDeviceConnection:
 * transfers either complete in full or return -1, with no hint why. A fake
 * clock lets tests make a failure look like a timeout or like a quick stall.
 */
class FakeBulkBackend(
    override val maxPacketSizeOut: Int = 512,
) : BulkBackend {
    class OutCall(val offset: Int, val length: Int, val data: ByteArray)

    val outCalls = ArrayList<OutCall>()
    val inRequests = ArrayList<Int>()
    val incoming = ArrayDeque<ByteArray>()

    var attached = true
    var closeCount = 0

    /** Nanoseconds the fake clock advances during each transfer. */
    var transferNanos = 0L

    /** If set, the next transfer fails with -1 after consuming [transferNanos]. */
    var failNext = false

    /** If non-null, OUT transfers report this many bytes instead of the requested length. */
    var reportedOut: Int? = null

    var nowNanos = 0L
    val clock: () -> Long = { nowNanos }

    override fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        nowNanos += transferNanos
        if (failNext) { failNext = false; return -1 }
        outCalls += OutCall(offset, length, buffer.copyOfRange(offset, offset + length))
        return reportedOut ?: length
    }

    override fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        nowNanos += transferNanos
        inRequests += length
        if (failNext) { failNext = false; return -1 }
        val packet = incoming.removeFirstOrNull() ?: return -1
        // A device that sends more than was asked for overflows the transfer.
        if (packet.size > length) return -1
        packet.copyInto(buffer, offset)
        return packet.size
    }

    override fun isDeviceAttached(): Boolean = attached

    override fun close() { closeCount++ }

    /** Everything written so far, joined. */
    fun written(): ByteArray = outCalls.fold(ByteArray(0)) { acc, c -> acc + c.data }
}
