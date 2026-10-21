package com.bareflash.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CommandLineTest {
    private fun words(s: String) = CommandLine.tokenize(s).map { it.text }

    @Test fun splitsOnWhitespace() {
        assertEquals(listOf("adb", "shell", "ls", "-la"), words("  adb   shell\tls -la  "))
        assertEquals(emptyList<String>(), words("   "))
    }

    @Test fun singleQuotesAreLiteral() {
        assertEquals(listOf("echo", "a \\ \"b\" \$c"), words("echo 'a \\ \"b\" \$c'"))
    }

    @Test fun doubleQuotesAllowFewEscapes() {
        assertEquals(listOf("a \"q\" \\ \$ `"), words("\"a \\\"q\\\" \\\\ \\$ \\`\""))
        assertEquals(listOf("keeps\\nthis"), words("\"keeps\\nthis\""))
    }

    @Test fun backslashEscapesOutsideQuotes() {
        assertEquals(listOf("a b", "c"), words("a\\ b c"))
    }

    @Test fun adjacentPiecesJoinIntoOneWord() {
        assertEquals(listOf("ab cd"), words("a\"b c\"d"))
        assertEquals(listOf("", "x"), words("'' x"))
    }

    @Test fun offsetsPointIntoTheOriginalLine() {
        val line = "adb shell   echo \"a  b\"   tail"
        val t = CommandLine.tokenize(line)
        assertEquals("echo \"a  b\"   tail", line.substring(t[2].start))
        assertEquals("adb", line.substring(t[0].start, t[0].end))
        assertEquals("\"a  b\"", line.substring(t[3].start, t[3].end))
    }

    private fun assertSyntaxError(s: String) {
        try { CommandLine.tokenize(s); fail("expected CommandSyntaxException for $s") } catch (_: CommandSyntaxException) { assertEquals(1, 1) }
    }

    @Test fun malformedLinesAreRejected() {
        assertSyntaxError("echo 'oops")
        assertSyntaxError("echo \"oops")
        assertSyntaxError("echo trailing\\")
    }

    @Test fun quotingRoundTripsThroughTheTokenizer() {
        val nasty = listOf("plain", "with space", "it's", "a\"b", "\$HOME", "semi;colon", "", "tab\there", "new\nline", "back\\slash", "*glob*", "é€")
        for (s in nasty) assertEquals(listOf(s), words(CommandLine.quote(s)))
        assertEquals(nasty, words(CommandLine.join(nasty)))
    }

    @Test fun plainWordsAreNotQuoted() {
        assertEquals("/sdcard/Download/a.txt", CommandLine.quote("/sdcard/Download/a.txt"))
        assertEquals("'a b'", CommandLine.quote("a b"))
        assertEquals("''", CommandLine.quote(""))
    }
}
