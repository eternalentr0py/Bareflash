package com.bareflash.app.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveFormatTest {

    private val mib = 1024L * 1024L
    private val gib = 1024L * mib

    @Test
    fun bytesPicksTheUnit() {
        assertEquals("0 B", LiveFormat.bytes(0L))
        assertEquals("1023 B", LiveFormat.bytes(1023L))
        assertEquals("2 KiB", LiveFormat.bytes(2048L))
        assertEquals("5.0 MiB", LiveFormat.bytes(5L * mib))
        assertEquals("50 MiB", LiveFormat.bytes(50L * mib))
        assertEquals("3.00 GiB", LiveFormat.bytes(3L * gib))
    }

    @Test
    fun speedIsBlankWhenNothingIsMoving() {
        assertEquals("", LiveFormat.speed(0.0))
        assertEquals("", LiveFormat.speed(0.5))
    }

    @Test
    fun speedSwitchesBetweenKibAndMib() {
        assertEquals("2.0 MiB/s", LiveFormat.speed(2.0 * mib))
        assertEquals("512 KiB/s", LiveFormat.speed(512.0 * 1024.0))
    }

    @Test
    fun etaReadsNaturally() {
        assertEquals("", LiveFormat.eta(-1L))
        assertEquals("0 s left", LiveFormat.eta(0L))
        assertEquals("40 s left", LiveFormat.eta(40_000L))
        assertEquals("59 s left", LiveFormat.eta(59_499L))
        assertEquals("1 min left", LiveFormat.eta(60_000L))
        assertEquals("5 min left", LiveFormat.eta(300_000L))
        assertEquals("1 h 12 min left", LiveFormat.eta(4_320_000L))
        assertEquals("1 h 0 min left", LiveFormat.eta(3_600_000L))
    }

    @Test
    fun elapsedIsCompact() {
        assertEquals("0s", LiveFormat.elapsed(-5L))
        assertEquals("4s", LiveFormat.elapsed(4_000L))
        assertEquals("59s", LiveFormat.elapsed(59_999L))
        assertEquals("1m 00s", LiveFormat.elapsed(60_000L))
        assertEquals("4m 12s", LiveFormat.elapsed(252_000L))
        assertEquals("1h 03m", LiveFormat.elapsed(3_780_000L))
    }

    @Test
    fun everyKindHasAVerbAndAPastVerb() {
        for (kind in LiveKind.values()) {
            assertTrue(kind.name, LiveFormat.verb(kind).isNotBlank())
            assertTrue(kind.name, LiveFormat.pastVerb(kind).isNotBlank())
        }
    }
}
