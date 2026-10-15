package com.bareflash.app.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The free-space check decides whether a dump is allowed to start, so its size
 * estimates matter: too low lets a long read fail near the end, too high blocks
 * a dump that would have fit. Only the pure helpers are tested here.
 */
class DumpSpaceGuardTest {

    private val gpt = listOf(
        GptPartition("boot", 0L, 0x400000L),
        GptPartition("userdata", 0L, 0x100000000L),
    )

    @Test
    fun parseByteCountAcceptsDecimalAndHex() {
        assertEquals(4096L, DumpSpaceGuard.parseByteCount("4096"))
        assertEquals(0x200L, DumpSpaceGuard.parseByteCount("0x200"))
        assertEquals(0x200L, DumpSpaceGuard.parseByteCount("0X200"))
        assertEquals(16L, DumpSpaceGuard.parseByteCount("  0x10 "))
    }

    @Test
    fun parseByteCountRejectsGarbage() {
        assertNull(DumpSpaceGuard.parseByteCount(""))
        assertNull(DumpSpaceGuard.parseByteCount("abc"))
        assertNull(DumpSpaceGuard.parseByteCount("0xZZ"))
        assertNull(DumpSpaceGuard.parseByteCount("0x"))
        assertNull(DumpSpaceGuard.parseByteCount("12MB"))
    }

    @Test
    fun tokenizeHonoursQuotes() {
        assertEquals(listOf("r", "boot", "a b"), DumpSpaceGuard.tokenize("r boot  \"a b\" "))
        assertEquals(emptyList<String>(), DumpSpaceGuard.tokenize("   "))
    }

    @Test
    fun flagValueReadsBothSpellings() {
        assertEquals("0x10", DumpSpaceGuard.flagValue(listOf("rs", "0", "8", "--length", "0x10"), "--length"))
        assertEquals("5", DumpSpaceGuard.flagValue(listOf("x", "--length=5"), "--length"))
        assertNull(DumpSpaceGuard.flagValue(listOf("x", "--length", "--other"), "--length"))
        assertNull(DumpSpaceGuard.flagValue(listOf("x", "y"), "--length"))
    }

    @Test
    fun readsAreDumpsButWritesAndErasesAreNot() {
        for (cmd in listOf("r boot", "rl", "rf", "rs 0 8", "ro 0 8", "footer", "gpt", "dumpbrom", "dumpsram", "dumppreloader")) {
            assertTrue(cmd, DumpSpaceGuard.isDump(DumpSpaceGuard.tokenize(cmd)))
        }
        for (cmd in listOf("w boot boot.img", "e boot", "wl dir", "reset", "printgpt", "")) {
            assertFalse(cmd, DumpSpaceGuard.isDump(DumpSpaceGuard.tokenize(cmd)))
        }
    }

    @Test
    fun peekIsADumpOnlyWhenItWritesAFile() {
        assertFalse(DumpSpaceGuard.isDump(DumpSpaceGuard.tokenize("peek 0x1000 16")))
        assertTrue(DumpSpaceGuard.isDump(DumpSpaceGuard.tokenize("peek 0x1000 16 --filename out.bin")))
    }

    @Test
    fun daSubcommandsAreClassified() {
        assertTrue(DumpSpaceGuard.isDump(DumpSpaceGuard.tokenize("da memdump")))
        assertTrue(DumpSpaceGuard.isDump(DumpSpaceGuard.tokenize("da rpmb r")))
        assertFalse(DumpSpaceGuard.isDump(DumpSpaceGuard.tokenize("da rpmb w")))
        assertFalse(DumpSpaceGuard.isDump(DumpSpaceGuard.tokenize("da seccfg unlock")))
    }

    @Test
    fun largestFileUsesTheExactGptSizeWhenEveryNameIsKnown() {
        assertEquals(0x100000000L to true, DumpSpaceGuard.largestFile("r boot,userdata", "0x200", gpt))
        assertEquals(0x400000L to true, DumpSpaceGuard.largestFile("r BOOT", "0x200", gpt))
    }

    @Test
    fun largestFileFallsBackToAnInexactGuessWithoutAGptTable() {
        assertEquals(8L * DumpSpaceGuard.GB to false, DumpSpaceGuard.largestFile("r userdata", "0x200", emptyList()))
        assertEquals(64L * DumpSpaceGuard.MB to false, DumpSpaceGuard.largestFile("r boot_a", "0x200", emptyList()))
    }

    @Test
    fun largestFileForSectorAndByteRangesIsExact() {
        assertEquals(0x100L * 0x200L to true, DumpSpaceGuard.largestFile("rs 0 0x100", "0x200", emptyList()))
        assertEquals(0x100L * 4096L to true, DumpSpaceGuard.largestFile("rs 0 0x100 --sectorsize 4096", "0x200", emptyList()))
        assertEquals(0x1000L to true, DumpSpaceGuard.largestFile("ro 0x0 0x1000", "0x200", emptyList()))
    }

    @Test
    fun largestFileOfWholeFlashNeedsAGptTable() {
        assertNull(DumpSpaceGuard.largestFile("rl", "0x200", emptyList()))
        assertEquals(0x100000000L to true, DumpSpaceGuard.largestFile("rl", "0x200", gpt))
    }

    @Test
    fun largestFileIsNullForCommandsThatDoNotDump() {
        assertNull(DumpSpaceGuard.largestFile("w boot boot.img", "0x200", gpt))
        assertNull(DumpSpaceGuard.largestFile("", "0x200", gpt))
    }

    @Test
    fun formatBytesPicksTheRightUnit() {
        val old = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("0 B", DumpSpaceGuard.formatBytes(0L))
            assertEquals("1023 B", DumpSpaceGuard.formatBytes(1023L))
            assertEquals("1.0 KB", DumpSpaceGuard.formatBytes(1024L))
            assertEquals("1.5 KB", DumpSpaceGuard.formatBytes(1536L))
            assertEquals("10 KB", DumpSpaceGuard.formatBytes(10L * 1024L))
            assertEquals("5.0 MB", DumpSpaceGuard.formatBytes(5L * DumpSpaceGuard.MB))
            assertEquals("1.0 GB", DumpSpaceGuard.formatBytes(DumpSpaceGuard.GB))
            assertEquals("1.0 TB", DumpSpaceGuard.formatBytes(1024L * DumpSpaceGuard.GB))
            assertEquals("2048 TB", DumpSpaceGuard.formatBytes(2048L * 1024L * DumpSpaceGuard.GB))
        } finally {
            Locale.setDefault(old)
        }
    }
}
