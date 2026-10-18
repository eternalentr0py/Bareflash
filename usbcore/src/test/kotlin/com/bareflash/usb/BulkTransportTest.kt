package com.bareflash.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BulkTransportTest {

    private fun transport(
        backend: FakeBulkBackend,
        chunk: Int = BulkTransportConfig.DEFAULT_CHUNK_SIZE,
        zlp: Boolean = false,
    ) = BulkTransport(backend, BulkTransportConfig(chunk, zlp), backend.clock)

    private fun bytes(n: Int) = ByteArray(n) { (it * 31 + 7).toByte() }

    // ---- writes ------------------------------------------------------------

    @Test fun writeIsSplitIntoWholePacketChunks() {
        val b = FakeBulkBackend(maxPacketSizeOut = 512)
        val data = bytes(40_000)
        transport(b, chunk = 16_000).write(data, 0, data.size, 1000)
        // 16000 rounds down to 31 packets = 15872 bytes.
        assertEquals(listOf(15_872, 15_872, 8_256), b.outCalls.map { it.length })
        assertArrayEquals(data, b.written())
    }

    @Test fun writeHonoursOffsetAndLength() {
        val b = FakeBulkBackend()
        val data = bytes(100)
        transport(b).write(data, 10, 50, 1000)
        assertArrayEquals(data.copyOfRange(10, 60), b.written())
    }

    @Test fun chunkSmallerThanOnePacketStillSendsWholePackets() {
        val b = FakeBulkBackend(maxPacketSizeOut = 512)
        transport(b, chunk = 100).write(bytes(1300), 0, 1300, 1000)
        assertEquals(listOf(512, 512, 276), b.outCalls.map { it.length })
    }

    @Test fun unknownPacketSizeUsesChunkSizeAsIs() {
        val b = FakeBulkBackend(maxPacketSizeOut = 0)
        transport(b, chunk = 1000).write(bytes(2500), 0, 2500, 1000)
        assertEquals(listOf(1000, 1000, 500), b.outCalls.map { it.length })
    }

    @Test fun emptyWriteSendsOneZeroLengthPacket() {
        val b = FakeBulkBackend()
        transport(b).write(ByteArray(0), 0, 0, 1000)
        assertEquals(listOf(0), b.outCalls.map { it.length })
    }

    @Test fun zeroLengthPacketFollowsExactMultipleOfPacketSizeWhenEnabled() {
        val b = FakeBulkBackend(maxPacketSizeOut = 512)
        transport(b, zlp = true).write(bytes(1024), 0, 1024, 1000)
        assertEquals(listOf(1024, 0), b.outCalls.map { it.length })
    }

    @Test fun noZeroLengthPacketForPartialFinalPacketOrWhenDisabled() {
        val on = FakeBulkBackend(maxPacketSizeOut = 512)
        transport(on, zlp = true).write(bytes(1000), 0, 1000, 1000)
        assertEquals(listOf(1000), on.outCalls.map { it.length })

        val off = FakeBulkBackend(maxPacketSizeOut = 512)
        transport(off, zlp = false).write(bytes(1024), 0, 1024, 1000)
        assertEquals(listOf(1024), off.outCalls.map { it.length })
    }

    @Test fun zeroLengthPacketAfterMultiChunkWrite() {
        val b = FakeBulkBackend(maxPacketSizeOut = 512)
        transport(b, chunk = 1024, zlp = true).write(bytes(2048), 0, 2048, 1000)
        assertEquals(listOf(1024, 1024, 0), b.outCalls.map { it.length })
    }

    @Test fun shortWriteIsAnError() {
        val b = FakeBulkBackend()
        b.reportedOut = 10
        try {
            transport(b).write(bytes(100), 0, 100, 1000)
            fail("expected an exception")
        } catch (e: UsbTransportException) {
            assertFalse(e is UsbTimeoutException)
            assertFalse(e is UsbDisconnectedException)
        }
    }

    // ---- failure classification -------------------------------------------

    @Test fun failureUsingTheWholeTimeoutIsATimeout() {
        val b = FakeBulkBackend()
        b.failNext = true
        b.transferNanos = 1_000_000_000L
        try {
            transport(b).write(bytes(10), 0, 10, 1000)
            fail("expected a timeout")
        } catch (_: UsbTimeoutException) {
        }
    }

    @Test fun quickFailureIsAStallNotATimeout() {
        val b = FakeBulkBackend()
        b.failNext = true
        b.transferNanos = 5_000_000L
        try {
            transport(b).read(ByteArray(64), 1000)
            fail("expected an exception")
        } catch (e: UsbTransportException) {
            assertFalse(e is UsbTimeoutException)
            assertFalse(e is UsbDisconnectedException)
        }
    }

    @Test fun failureAfterUnplugIsDisconnected() {
        val b = FakeBulkBackend()
        b.failNext = true
        b.attached = false
        try {
            transport(b).read(ByteArray(64), 500)
            fail("expected an exception")
        } catch (_: UsbDisconnectedException) {
        }
    }

    @Test fun timeoutBoundaryIsNinetyPercent() {
        val atEdge = FakeBulkBackend()
        atEdge.failNext = true
        atEdge.transferNanos = 900_000_000L
        try { transport(atEdge).read(ByteArray(8), 1000); fail() } catch (_: UsbTimeoutException) { }

        val below = FakeBulkBackend()
        below.failNext = true
        below.transferNanos = 899_000_000L
        try {
            transport(below).read(ByteArray(8), 1000)
            fail()
        } catch (e: UsbTransportException) {
            assertFalse(e is UsbTimeoutException)
        }
    }

    // ---- reads -------------------------------------------------------------

    @Test fun readReturnsOneTransfer() {
        val b = FakeBulkBackend()
        b.incoming.addLast(byteArrayOf(1, 2, 3))
        val buf = ByteArray(64)
        val n = transport(b).read(buf, 500)
        assertEquals(3, n)
        assertArrayEquals(byteArrayOf(1, 2, 3), buf.copyOf(3))
    }

    @Test fun readReturnsZeroForZeroLengthPacket() {
        val b = FakeBulkBackend()
        b.incoming.addLast(ByteArray(0))
        assertEquals(0, transport(b).read(ByteArray(64), 500))
    }

    @Test fun readRequestIsCappedToChunkAndBufferSize() {
        val b = FakeBulkBackend()
        b.incoming.addLast(ByteArray(1)); b.incoming.addLast(ByteArray(1))
        val t = transport(b, chunk = 4096)
        t.read(ByteArray(100_000), 500)
        t.read(ByteArray(100), 500)
        assertEquals(listOf(4096, 100), b.inRequests)
    }

    @Test fun readOverflowSurfacesAsAnError() {
        val b = FakeBulkBackend()
        b.incoming.addLast(ByteArray(64))
        try {
            transport(b).read(ByteArray(16), 500)
            fail("expected an exception")
        } catch (_: UsbTransportException) {
        }
    }

    // ---- lifecycle and arguments ------------------------------------------

    @Test fun closeClosesBackendOnceAndBlocksFurtherUse() {
        val b = FakeBulkBackend()
        val t = transport(b)
        t.close()
        t.close()
        assertEquals(1, b.closeCount)
        try { t.write(bytes(1), 0, 1, 100); fail() } catch (_: UsbDisconnectedException) { }
        try { t.read(ByteArray(8), 100); fail() } catch (_: UsbDisconnectedException) { }
        assertTrue(b.outCalls.isEmpty())
        assertTrue(b.inRequests.isEmpty())
    }

    @Test fun badArgumentsAreRejectedWithoutTouchingTheDevice() {
        val b = FakeBulkBackend()
        val t = transport(b)
        val data = bytes(10)
        val bad = listOf<() -> Unit>(
            { t.write(data, -1, 1, 100) },
            { t.write(data, 0, 11, 100) },
            { t.write(data, 5, 6, 100) },
            { t.write(data, 0, -1, 100) },
            { t.write(data, 0, 1, 0) },
            { t.read(ByteArray(0), 100) },
            { t.read(ByteArray(8), 0) },
        )
        for (call in bad) {
            try { call(); fail("expected IllegalArgumentException") } catch (_: IllegalArgumentException) { }
        }
        assertTrue(b.outCalls.isEmpty())
        assertTrue(b.inRequests.isEmpty())
    }

    @Test fun nonPositiveChunkSizeIsRejected() {
        try { BulkTransportConfig(chunkSize = 0); fail() } catch (_: IllegalArgumentException) { }
    }
}
