package com.bareflash.app.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GptParseStateTest {

    private val table = """
        GPT Table:
        -------------
        boot:                Offset 0x0000000000080000, Length 0x0000000000400000, Flags 0x0000000000000000
        userdata:            Offset 0x0000000010000000, Length 0x0000000100000000, Flags 0x0000000000000004
        Total disk size:0x0000001d1c000000, sectors:0x0000000000e8e000
    """.trimIndent() + "\n"

    private val expected = listOf(
        GptPartition("boot", 0x80000L, 0x400000L, 0L),
        GptPartition("userdata", 0x10000000L, 0x100000000L, 4L),
    )

    @Test
    fun parseRowReadsOffsetLengthAndFlags() {
        val p = GptParseState.parseRow("boot:  Offset 0x80000, Length 0x400000, Flags 0x4")
        assertEquals(GptPartition("boot", 0x80000L, 0x400000L, 4L), p)
    }

    @Test
    fun parseRowFlagsAreOptional() {
        val p = GptParseState.parseRow("lk: Offset 0x10, Length 0x20")
        assertEquals(GptPartition("lk", 0x10L, 0x20L, 0L), p)
    }

    @Test
    fun parseRowRejectsLinesThatAreNotRows() {
        assertNull(GptParseState.parseRow(""))
        assertNull(GptParseState.parseRow("GPT Table:"))
        assertNull(GptParseState.parseRow("-------------"))
        assertNull(GptParseState.parseRow("Total disk size:0x10, sectors:0x20"))
        assertNull(GptParseState.parseRow("Name:  Offset 0x1, Length 0x2"))
    }

    @Test
    fun ingestPublishesTheTableWhenItIsComplete() {
        assertEquals(expected, GptParseState().ingest(table))
    }

    @Test
    fun ingestHandlesOutputSplitAtArbitraryPoints() {
        val state = GptParseState()
        val completed = mutableListOf<List<GptPartition>>()
        table.chunked(7).forEach { piece -> state.ingest(piece)?.let { completed += it } }
        assertEquals(listOf(expected), completed)
    }

    @Test
    fun ingestDoesNotPublishAHalfFinishedTable() {
        val state = GptParseState()
        val half = "GPT Table:\n-------------\nboot: Offset 0x1, Length 0x2\n"
        assertNull(state.ingest(half))
    }

    @Test
    fun aNewHeaderReplacesThePreviousTable() {
        val state = GptParseState()
        assertEquals(expected, state.ingest(table))
        val second = "PMT Table:\n-------------\nlk: Offset 0x10, Length 0x20, Flags 0x0\n" +
            "Total disk size:0x30, sectors:0x1\n"
        assertEquals(listOf(GptPartition("lk", 0x10L, 0x20L, 0L)), state.ingest(second))
    }

    @Test
    fun resetDiscardsAPartialTable() {
        val state = GptParseState()
        state.ingest("GPT Table:\n-------------\nboot: Offset 0x1, Length 0x2\n")
        state.reset()
        // Without the header the rest of a table must be ignored.
        assertNull(state.ingest("lk: Offset 0x1, Length 0x2\nTotal disk size:0x10, sectors:0x1\n"))
    }
}
