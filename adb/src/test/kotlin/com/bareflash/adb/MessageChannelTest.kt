package com.bareflash.adb

import com.bareflash.usb.UsbTimeoutException
import com.bareflash.usb.UsbTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** A transport that replays fixed reads, one scripted result at a time. */
private class Scripted(private val reads: MutableList<ByteArray?>) : UsbTransport {
    val written = ArrayList<ByteArray>()
    val readTimeouts = ArrayList<Int>()
    override fun write(data: ByteArray, offset: Int, length: Int, timeoutMs: Int) { written += data.copyOfRange(offset, offset + length) }
    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        readTimeouts += timeoutMs
        val next = if (reads.isEmpty()) null else reads.removeAt(0)
        if (next == null) { Thread.sleep(2); throw UsbTimeoutException("t") }
        System.arraycopy(next, 0, buffer, 0, next.size)
        return next.size
    }
    override fun close() {}
}

class MessageChannelTest {
    private fun wire(m: AdbMessage) = AdbWire.encodeHeader(m, false) + m.data

    @Test fun reassemblesAMessageDeliveredOneByteAtATime() {
        val m = AdbMessage(AdbCommand.WRTE, 3, 4, pattern(200))
        val reads = wire(m).map { byteArrayOf(it) }.toMutableList<ByteArray?>()
        val got = MessageChannel(Scripted(reads), 4096, 1000).receive(System.nanoTime() + 5_000_000_000, 5_000)
        assertEquals(AdbCommand.WRTE, got.command)
        assertArrayEquals(m.data, got.data)
    }

    @Test fun twoMessagesInOneReadAreSplitCorrectly() {
        val a = AdbMessage(AdbCommand.OKAY, 1, 2)
        val b = AdbMessage(AdbCommand.WRTE, 1, 2, byteArrayOf(9, 8, 7))
        val ch = MessageChannel(Scripted(mutableListOf(wire(a) + wire(b))), 4096, 1000)
        assertEquals(AdbCommand.OKAY, ch.receive(MessageChannel.NO_DEADLINE, 1000).command)
        assertArrayEquals(byteArrayOf(9, 8, 7), ch.receive(MessageChannel.NO_DEADLINE, 1000).data)
    }

    @Test fun zeroLengthPacketsBetweenPiecesAreSkipped() {
        val m = AdbMessage(AdbCommand.WRTE, 1, 2, pattern(10))
        val w = wire(m)
        val ch = MessageChannel(Scripted(mutableListOf(w.copyOf(24), ByteArray(0), w.copyOfRange(24, 30), ByteArray(0), w.copyOfRange(30, w.size))), 4096, 1000)
        assertArrayEquals(m.data, ch.receive(MessageChannel.NO_DEADLINE, 1000).data)
    }

    @Test fun endlessEmptyPacketsAreAProtocolError() {
        val ch = MessageChannel(Scripted(MutableList(200) { ByteArray(0) }), 4096, 1000)
        assertThrows<AdbProtocolException> { ch.receive(MessageChannel.NO_DEADLINE, 1000) }
    }

    @Test fun checksumsAreVerifiedOnlyWhenRequired() {
        val m = AdbMessage(AdbCommand.WRTE, 1, 2, byteArrayOf(1, 2, 3))
        val bad = AdbWire.encodeHeader(m, true).also { it[16] = (it[16] + 1).toByte() } + m.data
        val lenient = MessageChannel(Scripted(mutableListOf(bad)), 4096, 1000)
        assertArrayEquals(m.data, lenient.receive(MessageChannel.NO_DEADLINE, 1000).data)
        val strict = MessageChannel(Scripted(mutableListOf(bad)), 4096, 1000).also { it.verifyChecksums = true }
        assertThrows<AdbProtocolException> { strict.receive(MessageChannel.NO_DEADLINE, 1000) }
    }

    @Test fun oversizedPayloadsAreRejectedBeforeAllocation() {
        val ch = MessageChannel(Scripted(mutableListOf(AdbWire.encodeHeader(AdbMessage(AdbCommand.WRTE, 1, 2, ByteArray(10)), false))), 8, 1000)
        assertThrows<AdbProtocolException> { ch.receive(MessageChannel.NO_DEADLINE, 1000) }
    }

    @Test fun headerWaitHonoursItsDeadline() {
        val ch = MessageChannel(Scripted(mutableListOf()), 4096, 1000)
        assertThrows<AdbTimeoutException> { ch.receive(System.nanoTime() + 50_000_000, 1000) }
    }

    @Test fun sendWritesHeaderThenPayloadAsSeparateTransfers() {
        val t = Scripted(mutableListOf())
        val ch = MessageChannel(t, 4096, 1000)
        ch.send(AdbMessage(AdbCommand.WRTE, 1, 2, byteArrayOf(5, 6)))
        ch.send(AdbMessage(AdbCommand.OKAY, 1, 2))
        assertEquals(listOf(24, 2, 24), t.written.map { it.size })
    }

    @Test fun anIdleHeaderWaitCanBeCancelled() {
        val ch = MessageChannel(Scripted(mutableListOf()), 4096, 1000)
        var polls = 0
        assertThrows<AdbCancelledException> { ch.receive(MessageChannel.NO_DEADLINE, 1000) { ++polls > 3 } }
    }

    @Test fun headerWaitsUseShortTimeoutsAndPayloadWaitsLongOnes() {
        val m = AdbMessage(AdbCommand.WRTE, 1, 2, pattern(50))
        val t = Scripted(mutableListOf(wire(m).copyOf(24), null, null, wire(m).copyOfRange(24, 74)))
        val got = MessageChannel(t, 4096, 1000).receive(MessageChannel.NO_DEADLINE, 10_000)
        assertArrayEquals(m.data, got.data)
        // Reads 1: header (idle poll). Reads 2-3: payload stalls (long poll). Read 4: payload arrives.
        assertEquals(listOf(250, 2000, 2000, 2000), t.readTimeouts)
    }
}
