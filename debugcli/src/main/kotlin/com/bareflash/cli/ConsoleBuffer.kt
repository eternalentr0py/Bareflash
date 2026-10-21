package com.bareflash.cli

public enum class LineKind { COMMAND, OUTPUT, ERROR_OUTPUT, INFO, ERROR }

public class ConsoleLine(public val kind: LineKind, public val text: String)

/**
 * The scrollback of the console. Text arrives in pieces that need not end on a
 * line boundary; a line is finished when a newline arrives, when the kind of
 * text changes, or when it grows past [MAX_LINE_LENGTH] (so one huge line of
 * output cannot freeze the screen). Old lines are dropped beyond [maxLines].
 *
 * Safe to use from several threads.
 */
public class ConsoleBuffer(private val maxLines: Int = 2000) {
    private val lines = ArrayDeque<ConsoleLine>()
    private var partialKind = LineKind.OUTPUT
    private val partial = StringBuilder()

    @Synchronized
    public fun append(kind: LineKind, text: String) {
        if (text.isEmpty()) return
        if (partial.isNotEmpty() && partialKind != kind) flushPartial()
        partialKind = kind
        for (c in text) {
            if (c == '\n') {
                flushPartial()
            } else {
                partial.append(c)
                if (partial.length >= MAX_LINE_LENGTH) flushPartial()
            }
        }
    }

    /** A finished line of [kind], however the previous text ended. */
    @Synchronized
    public fun appendLine(kind: LineKind, text: String) {
        if (partial.isNotEmpty()) flushPartial()
        partialKind = kind
        append(kind, text + "\n")
    }

    @Synchronized
    public fun snapshot(): List<ConsoleLine> =
        if (partial.isEmpty()) lines.toList() else lines.toList() + ConsoleLine(partialKind, partial.toString())

    @Synchronized
    public fun clear() {
        lines.clear()
        partial.setLength(0)
    }

    @Synchronized
    public fun plainText(): String = snapshot().joinToString("\n") { it.text }

    private fun flushPartial() {
        lines.addLast(ConsoleLine(partialKind, partial.toString()))
        partial.setLength(0)
        while (lines.size > maxLines) lines.removeFirst()
    }

    public companion object {
        public const val MAX_LINE_LENGTH: Int = 2000
    }
}
