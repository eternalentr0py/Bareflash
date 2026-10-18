package com.bareflash.fastboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FastbootVarsTest {
    @Test fun splitsAtLastSeparatorSoNamesMayContainColons() {
        val m = FastbootVars.parseAll(listOf("version: 0.5", "partition-size:boot_a: 0x4000000", "no separator", ": novalue"))
        assertEquals("0.5", m["version"])
        assertEquals("0x4000000", m["partition-size:boot_a"])
        assertEquals(2, m.size)
    }

    @Test fun stripsBootloaderPrefixAndLaterLinesWin() {
        val m = FastbootVars.parseAll(listOf("(bootloader) a: 1", "a: 2"))
        assertEquals("2", m["a"])
    }

    @Test fun parseSize() {
        assertEquals(0x10000000L, FastbootVars.parseSize("0x10000000"))
        assertEquals(65536L, FastbootVars.parseSize(" 65536 "))
        assertNull(FastbootVars.parseSize("abc"))
        assertNull(FastbootVars.parseSize("-5"))
        assertNull(FastbootVars.parseSize("0x"))
        assertNull(FastbootVars.parseSize("0xFFFFFFFFFFFFFFFFF"))
    }
}
