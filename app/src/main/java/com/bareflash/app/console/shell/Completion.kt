package com.bareflash.app.console.shell

/**
 * The result of pressing Tab. [line] is the new text for the input. [candidates] is filled only
 * when the choice is still ambiguous and nothing more could be completed, so the caller can list them.
 */
data class Completion(val line: String, val candidates: List<String>)

object Completer {

    /**
     * Completes the word at the end of [line].
     *
     * The first word is completed from [commandNames]; later words from [wordsFor], which gets the
     * first word of the line and returns the flags and partition names that make sense after it.
     */
    fun complete(
        line: String,
        commandNames: Collection<String>,
        wordsFor: (command: String) -> Collection<String>
    ): Completion {
        val tokenStart = line.indexOfLast { it.isWhitespace() } + 1
        val prefix = line.substring(tokenStart)
        val head = line.substring(0, tokenStart)
        val firstWord = head.trim().substringBefore(' ')
        val pool: Collection<String> = if (head.isBlank()) commandNames else wordsFor(firstWord)

        val matches = pool.filter { it.startsWith(prefix) }.distinct().sorted()
        if (matches.isEmpty()) return Completion(line, emptyList())
        if (matches.size == 1) {
            val m = matches[0]
            val tail = if (m.endsWith("/") || m.endsWith("=")) "" else " "
            return Completion(head + m + tail, emptyList())
        }
        val common = commonPrefix(matches)
        return if (common.length > prefix.length) Completion(head + common, emptyList())
        else Completion(line, matches)
    }

    fun commonPrefix(words: List<String>): String {
        if (words.isEmpty()) return ""
        var p = words[0]
        for (w in words) {
            var i = 0
            while (i < p.length && i < w.length && p[i] == w[i]) i++
            p = p.substring(0, i)
            if (p.isEmpty()) break
        }
        return p
    }

    /** Candidates laid out on lines of about [width] characters, the way a shell prints them. */
    fun columns(words: List<String>, width: Int = 56): String {
        if (words.isEmpty()) return ""
        val cell = (words.maxOf { it.length } + 2).coerceAtLeast(4)
        val perRow = (width / cell).coerceAtLeast(1)
        return words.chunked(perRow).joinToString("\n") { row -> row.joinToString("") { it.padEnd(cell) }.trimEnd() }
    }
}
