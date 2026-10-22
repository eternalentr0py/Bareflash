package com.bareflash.cli

import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Decodes UTF-8 that arrives in arbitrary pieces. A character split across
 * two pieces is held back until the rest arrives; invalid bytes become U+FFFD.
 */
public class Utf8Streamer {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)

    public fun feed(data: ByteArray): String {
        val input = ByteBuffer.wrap(pending + data)
        val out = CharBuffer.allocate(input.remaining() + 2)
        decoder.decode(input, out, false)
        pending = ByteArray(input.remaining()).also { input.get(it) }
        return flipToString(out)
    }

    /** Whatever is left once no more data will come. */
    public fun finish(): String {
        val input = ByteBuffer.wrap(pending)
        val out = CharBuffer.allocate(input.remaining() + 2)
        decoder.decode(input, out, true)
        decoder.flush(out)
        pending = ByteArray(0)
        decoder.reset()
        return flipToString(out)
    }

    private companion object {
        /**
         * Calls flip() as declared on Buffer. JDK 9 added covariant overrides
         * (CharBuffer.flip() returning CharBuffer) that older Android versions
         * lack, so calling them by their JDK 9 signature crashes the app there.
         */
        fun flipToString(out: CharBuffer): String {
            val buffer: Buffer = out
            buffer.flip()
            return out.toString()
        }
    }
}

/**
 * Removes terminal control sequences (colours, cursor movement, window titles)
 * and stray control characters, so command output reads cleanly in a plain
 * text console. Keeps newlines and tabs. Carriage returns are dropped, which
 * turns CRLF into LF. State carries across calls, so a sequence split between
 * two pieces of output is still removed whole.
 */
public class AnsiFilter {
    private enum class State { NORMAL, ESC, CSI, OSC, OSC_ESC, SKIP_ONE }

    private var state = State.NORMAL
    private var sequenceLength = 0

    public fun filter(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            when (state) {
                State.NORMAL -> when {
                    c == '\u001b' -> state = State.ESC
                    c == '\n' || c == '\t' || (c >= ' ' && c != '\u007f') -> sb.append(c)
                    else -> Unit // CR, BEL, backspace and other controls
                }
                State.ESC -> {
                    sequenceLength = 0
                    state = when (c) {
                        '[' -> State.CSI
                        ']' -> State.OSC
                        '(', ')', '#' -> State.SKIP_ONE
                        else -> State.NORMAL // a two-character sequence such as ESC = or ESC >
                    }
                }
                State.CSI -> {
                    sequenceLength++
                    if (c in '@'..'~' || sequenceLength > MAX_SEQUENCE) state = State.NORMAL
                }
                State.OSC -> {
                    sequenceLength++
                    when {
                        c == '\u0007' || sequenceLength > MAX_SEQUENCE -> state = State.NORMAL
                        c == '\u001b' -> state = State.OSC_ESC
                    }
                }
                State.OSC_ESC -> state = State.NORMAL // ESC \ ends the string
                State.SKIP_ONE -> state = State.NORMAL
            }
        }
        return sb.toString()
    }

    private companion object {
        const val MAX_SEQUENCE = 4096
    }
}
