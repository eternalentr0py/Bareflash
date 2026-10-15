package com.bareflash.app.console.shell

/** The text of the input line and where the cursor sits in it. */
data class Edit(val text: String, val cursor: Int)

/** Readline-style editing of one line. Every function is pure: text in, text out. */
object LineEdit {

    /** Ctrl+U: delete from the start of the line to the cursor. */
    fun killToStart(text: String, cursor: Int): Edit {
        val c = cursor.coerceIn(0, text.length)
        return Edit(text.substring(c), 0)
    }

    /** Ctrl+K: delete from the cursor to the end of the line. */
    fun killToEnd(text: String, cursor: Int): Edit {
        val c = cursor.coerceIn(0, text.length)
        return Edit(text.substring(0, c), c)
    }

    /** Ctrl+W: delete the word before the cursor (and the spaces just before it). */
    fun deleteWordBack(text: String, cursor: Int): Edit {
        val c = cursor.coerceIn(0, text.length)
        var i = c
        while (i > 0 && text[i - 1].isWhitespace()) i--
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        return Edit(text.substring(0, i) + text.substring(c), i)
    }

    /** Puts [s] at the cursor, replacing the selection [selStart, selEnd) when there is one. */
    fun insert(text: String, selStart: Int, selEnd: Int, s: String): Edit {
        val a = minOf(selStart, selEnd).coerceIn(0, text.length)
        val b = maxOf(selStart, selEnd).coerceIn(0, text.length)
        return Edit(text.substring(0, a) + s + text.substring(b), a + s.length)
    }

    fun moveBy(text: String, cursor: Int, delta: Int): Edit =
        Edit(text, (cursor + delta).coerceIn(0, text.length))
}

/** What a Ctrl+letter does. Both a hardware keyboard and the on-screen Ctrl key use this. */
enum class CtrlAction { Interrupt, ClearScreen, KillToStart, KillToEnd, DeleteWord, Home, End, None }

object Ctrl {
    fun forChar(c: Char): CtrlAction = when (c.lowercaseChar()) {
        'c' -> CtrlAction.Interrupt
        'l' -> CtrlAction.ClearScreen
        'u' -> CtrlAction.KillToStart
        'k' -> CtrlAction.KillToEnd
        'w' -> CtrlAction.DeleteWord
        'a' -> CtrlAction.Home
        'e' -> CtrlAction.End
        else -> CtrlAction.None
    }
}

/**
 * Up / Down through earlier commands, like bash. [history] is newest first.
 * The line you were typing is kept and comes back when you press Down past the newest one.
 */
class HistoryNav {
    private var index = -1
    private var draft = ""
    private var prefix: String? = null

    val browsing: Boolean get() = index >= 0

    fun reset() {
        index = -1
        draft = ""
        prefix = null
    }

    /** The older command to show, or null when there is nothing older. */
    fun up(current: String, history: List<String>, prefixSearch: Boolean): String? {
        if (history.isEmpty()) return null
        if (index < 0) {
            draft = current
            prefix = if (prefixSearch && current.isNotEmpty()) current else null
        }
        var i = index + 1
        while (i < history.size) {
            val p = prefix
            if (p == null || history[i].startsWith(p)) {
                index = i
                return history[i]
            }
            i++
        }
        return null
    }

    /** The newer command to show (the draft once past the newest), or null when not browsing. */
    fun down(history: List<String>): String? {
        if (index < 0) return null
        var i = index - 1
        while (i >= 0) {
            val p = prefix
            if (p == null || history[i].startsWith(p)) {
                index = i
                return history[i]
            }
            i--
        }
        index = -1
        return draft
    }
}

/** Grey completion of the line from the most recent matching command, accepted with Right or End. */
object Suggest {
    /** The text that would finish [input], or null. [history] is newest first. */
    fun forInput(input: String, history: List<String>): String? {
        if (input.isBlank() || input.contains('\n')) return null
        val hit = history.firstOrNull { it.length > input.length && it.startsWith(input) } ?: return null
        return hit.substring(input.length)
    }
}
