package com.bareflash.fakes

import com.bareflash.adb.AdbCommand
import com.bareflash.adb.AdbMessage
import com.bareflash.adb.AdbProtocol

import com.bareflash.usb.UsbDisconnectedException
import com.bareflash.usb.UsbTimeoutException
import com.bareflash.usb.UsbTransport
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Presents a [FakeAdbdCore] as a USB link: the host's writes are reassembled
 * into messages (the way adbd reads a header, then a payload), and replies
 * come back as separate header and payload "transfers", optionally split into
 * small pieces and followed by zero-length packets, as real hardware does.
 */
class FakeAdbUsbTransport(
    val core: FakeAdbdCore,
    private val transferSize: Int = 16 * 1024,
    private val zeroLengthPackets: Boolean = false,
    private val packetSize: Int = 512,
) : UsbTransport {

    private val inbound = LinkedBlockingQueue<ByteArray>()
    private var assembling = ByteArray(0)
    private var leftover: ByteArray? = null

    @Volatile var closed = false
    @Volatile var detached = false

    val hostMessages = ArrayList<AdbMessage>()
    @Volatile var closeCount = 0

    private val poison = ByteArray(0)

    override fun write(data: ByteArray, offset: Int, length: Int, timeoutMs: Int) {
        if (closed || detached) throw UsbDisconnectedException("fake link is down")
        synchronized(this) {
            assembling += data.copyOfRange(offset, offset + length)
            while (assembling.size >= FakeWire.HEADER_SIZE) {
                val h = FakeWire.decodeHeader(assembling.copyOf(FakeWire.HEADER_SIZE))
                if (assembling.size < FakeWire.HEADER_SIZE + h.length) break
                val payload = assembling.copyOfRange(FakeWire.HEADER_SIZE, FakeWire.HEADER_SIZE + h.length)
                assembling = assembling.copyOfRange(FakeWire.HEADER_SIZE + h.length, assembling.size)
                if (core.version < AdbProtocol.VERSION_SKIP_CHECKSUM && h.command != AdbCommand.CNXN) {
                    check(FakeWire.checksum(payload) == h.checksum) { "host sent a bad checksum" }
                }
                val m = AdbMessage(h.command, h.arg0, h.arg1, payload)
                hostMessages += m
                for (r in core.onMessage(m)) emit(r)
            }
        }
    }

    private fun emit(m: AdbMessage) {
        inbound.add(FakeWire.encodeHeader(m, core.version < AdbProtocol.VERSION_SKIP_CHECKSUM))
        var i = 0
        while (i < m.data.size) {
            val n = minOf(transferSize, m.data.size - i)
            inbound.add(m.data.copyOfRange(i, i + n))
            i += n
        }
        if (zeroLengthPackets && m.data.isNotEmpty() && m.data.size % packetSize == 0) inbound.add(ByteArray(0))
    }

    /** Puts arbitrary bytes on the wire, for corruption tests. */
    fun injectRaw(bytes: ByteArray) { inbound.add(bytes) }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (closed || detached) throw UsbDisconnectedException("fake link is down")
        val item = leftover ?: inbound.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            ?: throw UsbTimeoutException("fake read timeout")
        leftover = null
        if (item === poison) throw UsbDisconnectedException("fake link closed")
        if (item.size > buffer.size) {
            System.arraycopy(item, 0, buffer, 0, buffer.size)
            leftover = item.copyOfRange(buffer.size, item.size)
            return buffer.size
        }
        System.arraycopy(item, 0, buffer, 0, item.size)
        return item.size
    }

    /** Simulates pulling the cable. */
    fun detach() { detached = true; inbound.add(poison) }

    override fun close() {
        closed = true
        closeCount++
        inbound.add(poison)
    }
}
