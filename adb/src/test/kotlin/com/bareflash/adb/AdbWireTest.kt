package com.bareflash.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AdbWireTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun commandIdsAreTheLittleEndianAsciiOfTheirNames() {
        fun id(s: String) = s.toByteArray(Charsets.US_ASCII).foldIndexed(0) { i, acc, b -> acc or ((b.toInt() and 0xFF) shl (8 * i)) }
        assertEquals(id("CNXN"), AdbCommand.CNXN)
        assertEquals(id("AUTH"), AdbCommand.AUTH)
        assertEquals(id("OPEN"), AdbCommand.OPEN)
        assertEquals(id("OKAY"), AdbCommand.OKAY)
        assertEquals(id("CLSE"), AdbCommand.CLSE)
        assertEquals(id("WRTE"), AdbCommand.WRTE)
        assertEquals(id("SYNC"), AdbCommand.SYNC)
        assertEquals(id("STLS"), AdbCommand.STLS)
        assertEquals("CNXN", AdbCommand.name(AdbCommand.CNXN))
        assertEquals("0x00000001", AdbCommand.name(1))
    }

    // Reference bytes produced by the adb-shell Python library.
    @Test fun headersMatchTheReferenceImplementation() {
        val cnxn = AdbMessage(AdbCommand.CNXN, 0x01000000, 4096, "host::\u0000".toByteArray())
        assertEquals("434e584e00000001001000000700000032020000bcb1a7b1", hex(AdbWire.encodeHeader(cnxn, withChecksum = true)))
        val okay = AdbMessage(AdbCommand.OKAY, 5, 9)
        assertEquals("4f4b415905000000090000000000000000000000b0b4bea6", hex(AdbWire.encodeHeader(okay, withChecksum = true)))
    }

    @Test fun checksumIsOmittedWhenNotRequired() {
        val h = AdbWire.encodeHeader(AdbMessage(AdbCommand.WRTE, 1, 2, byteArrayOf(1, 2, 3)), withChecksum = false)
        assertEquals(0, java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(16))
    }

    @Test fun checksumTreatsBytesAsUnsigned() {
        assertEquals(255 + 255 + 1, AdbWire.checksum(byteArrayOf(-1, -1, 1)))
    }

    @Test fun decodeRoundTrips() {
        val h = AdbWire.decodeHeader(unhex("434e584e00000001001000000700000032020000bcb1a7b1"), 4096)
        assertEquals(AdbCommand.CNXN, h.command)
        assertEquals(0x01000000, h.arg0)
        assertEquals(4096, h.arg1)
        assertEquals(7, h.length)
        assertEquals(562, h.checksum)
    }

    private fun assertRejected(bytes: ByteArray, max: Int = 4096) {
        try { AdbWire.decodeHeader(bytes, max); fail("expected AdbProtocolException") } catch (_: AdbProtocolException) { assertTrue(true) }
    }

    @Test fun badHeadersAreRejected() {
        val good = unhex("434e584e00000001001000000700000032020000bcb1a7b1")
        assertRejected(good.copyOf(23))
        assertRejected(good.copyOf().also { it[20] = 0 })                      // wrong magic
        assertRejected(good, max = 6)                                          // payload longer than allowed
        assertRejected(good.copyOf().also { it[15] = 0x80.toByte() })          // negative length
    }
}
