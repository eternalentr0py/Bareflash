package com.bareflash.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextToolsTest {

    @Test fun utf8SplitAcrossPiecesIsHeldBackUntilComplete() {
        val bytes = "a€b😀c".toByteArray(Charsets.UTF_8)
        // Feed one byte at a time: every split point of every character is exercised.
        val d = Utf8Streamer()
        val sb = StringBuilder()
        for (b in bytes) sb.append(d.feed(byteArrayOf(b)))
        sb.append(d.finish())
        assertEquals("a€b😀c", sb.toString())
    }

    @Test fun utf8InvalidBytesBecomeReplacementCharacters() {
        val d = Utf8Streamer()
        val out = d.feed(byteArrayOf(0x61, 0xFF.toByte(), 0x62)) + d.finish()
        assertEquals("a\uFFFDb", out)
    }

    @Test fun utf8TruncatedTailIsFlushedAtTheEnd() {
        val d = Utf8Streamer()
        assertEquals("x", d.feed(byteArrayOf(0x78, 0xE2.toByte(), 0x82.toByte())))
        assertEquals("\uFFFD", d.finish())
        assertEquals("ok", d.feed("ok".toByteArray()) + d.finish()) // reusable afterwards
    }

    @Test fun ansiColoursAndCursorMovesAreRemoved() {
        val f = AnsiFilter()
        assertEquals("red plain", f.filter("\u001B[31mred\u001B[0m plain"))
        assertEquals("ab", f.filter("a\u001B[2K\u001B[1;1Hb"))
        assertEquals("title kept", f.filter("\u001B]0;window title\u0007title kept"))
        assertEquals("x y", f.filter("x\u001B]0;t\u001B\\ y"))
        assertEquals("ab", f.filter("a\u001B(Bb"))
        assertEquals("ab", f.filter("a\u001B=b"))
    }

    @Test fun ansiSequencesSplitAcrossCallsAreStillRemoved() {
        val f = AnsiFilter()
        val sb = StringBuilder()
        for (piece in listOf("one\u001B", "[", "3", "1", "mtwo\u001B]0;ti", "tle\u0007", "three")) sb.append(f.filter(piece))
        assertEquals("onetwothree", sb.toString())
    }

    @Test fun carriageReturnsAndControlsAreDropped() {
        val f = AnsiFilter()
        assertEquals("a\nb\tc", f.filter("a\r\nb\tc\u0007\u0008\u007f"))
    }

    @Test fun anUnterminatedSequenceCannotSwallowTheRestForever() {
        val f = AnsiFilter()
        f.filter("\u001B[" + "9".repeat(5000))
        assertEquals("back to normal", f.filter("back to normal"))
        f.filter("\u001B]" + "x".repeat(5000))
        assertEquals("fine", f.filter("fine"))
    }

    @Test fun unicodeSurvivesTheFilter() {
        assertEquals("héllo €😀", AnsiFilter().filter("héllo €😀"))
    }

    // ---- ConsoleBuffer ----

    @Test fun bufferSplitsOnNewlinesAndKeepsTheTail() {
        val b = ConsoleBuffer()
        b.append(LineKind.OUTPUT, "one\ntw")
        b.append(LineKind.OUTPUT, "o\nthree")
        assertEquals(listOf("one", "two", "three"), b.snapshot().map { it.text })
        b.append(LineKind.OUTPUT, "\n")
        assertEquals(listOf("one", "two", "three"), b.snapshot().map { it.text })
    }

    @Test fun aChangeOfKindFinishesTheLine() {
        val b = ConsoleBuffer()
        b.append(LineKind.OUTPUT, "no newline")
        b.append(LineKind.INFO, "note\n")
        val s = b.snapshot()
        assertEquals(listOf("no newline", "note"), s.map { it.text })
        assertEquals(listOf(LineKind.OUTPUT, LineKind.INFO), s.map { it.kind })
    }

    @Test fun appendLineAlwaysFinishesTheLine() {
        val b = ConsoleBuffer()
        b.append(LineKind.OUTPUT, "partial")
        b.appendLine(LineKind.COMMAND, "adb shell ls")
        assertEquals(listOf("partial", "adb shell ls"), b.snapshot().map { it.text })
        assertEquals(LineKind.COMMAND, b.snapshot().last().kind)
    }

    @Test fun veryLongLinesAreWrapped() {
        val b = ConsoleBuffer()
        b.append(LineKind.OUTPUT, "x".repeat(ConsoleBuffer.MAX_LINE_LENGTH * 2 + 5))
        val s = b.snapshot()
        assertEquals(3, s.size)
        assertTrue(s.all { it.text.length <= ConsoleBuffer.MAX_LINE_LENGTH })
    }

    @Test fun oldLinesAreDroppedBeyondTheLimit() {
        val b = ConsoleBuffer(maxLines = 5)
        for (i in 1..20) b.appendLine(LineKind.OUTPUT, "line $i")
        assertEquals((16..20).map { "line $it" }, b.snapshot().map { it.text })
    }

    @Test fun clearAndPlainText() {
        val b = ConsoleBuffer()
        b.appendLine(LineKind.OUTPUT, "a")
        b.append(LineKind.ERROR, "b")
        assertEquals("a\nb", b.plainText())
        b.clear()
        assertTrue(b.snapshot().isEmpty())
        b.append(LineKind.OUTPUT, "")
        assertFalse(b.snapshot().isNotEmpty())
    }
}
