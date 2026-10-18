package com.bareflash.fastboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FastbootResponseTest {
    private fun parse(s: String) = s.toByteArray(Charsets.UTF_8).let { FastbootResponse.parse(it, it.size) }

    @Test fun okayCarriesText() { assertEquals("1.0", (parse("OKAY1.0") as FastbootResponse.Okay).message) }
    @Test fun okayMayBeEmpty() { assertEquals("", (parse("OKAY") as FastbootResponse.Okay).message) }
    @Test fun infoAndFail() {
        assertEquals("hello", (parse("INFOhello") as FastbootResponse.Info).message)
        assertEquals("bad", (parse("FAILbad") as FastbootResponse.Fail).message)
    }
    @Test fun dataParsesHexLength() {
        assertEquals(0x1000L, (parse("DATA00001000") as FastbootResponse.Data).length)
        assertEquals(0xFFFFFFFFL, (parse("DATAffffffff") as FastbootResponse.Data).length)
    }

    private fun assertProtocolError(s: String) {
        try { parse(s); fail("expected failure for '$s'") } catch (_: FastbootProtocolException) { assertTrue(true) }
    }

    @Test fun malformedPacketsAreRejected() {
        assertProtocolError("")
        assertProtocolError("OKA")
        assertProtocolError("WHAT")
        assertProtocolError("DATA")
        assertProtocolError("DATA1000")          // too short
        assertProtocolError("DATA0000100000")    // too long
        assertProtocolError("DATAzzzzzzzz")      // not hex
        assertProtocolError("DATA-0000001")      // sign is not hex
    }

    @Test fun onlyTheFirstLengthBytesAreRead() {
        val buf = "OKAYabcdef".toByteArray()
        assertEquals("ab", (FastbootResponse.parse(buf, 6) as FastbootResponse.Okay).message)
    }
}
