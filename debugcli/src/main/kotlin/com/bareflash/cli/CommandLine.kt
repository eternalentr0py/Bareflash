package com.bareflash.cli

/** The typed line is malformed (for example an unterminated quote). */
public class CommandSyntaxException(message: String) : Exception(message)

/** One word of a line, with where it sat in the original text so the raw remainder can be recovered. */
public class Token(public val text: String, public val start: Int, public val end: Int)

/**
 * Splits a typed line into words the way a POSIX shell does at its simplest:
 * whitespace separates words; `'...'` is literal; inside `"..."` a backslash
 * escapes only `"`, `\`, `$` and the backtick; outside quotes a backslash
 * escapes the next character. Nothing is expanded or executed.
 */
public object CommandLine {
    private const val NONE = '\u0000'

    public fun tokenize(line: String): List<Token> {
        val out = ArrayList<Token>()
        val cur = StringBuilder()
        var start = -1
        var quote = NONE
        var i = 0
        fun finish(end: Int) {
            if (start >= 0) {
                out += Token(cur.toString(), start, end)
                cur.setLength(0)
                start = -1
            }
        }
        while (i < line.length) {
            val c = line[i]
            when {
                quote == '\'' -> if (c == '\'') quote = NONE else cur.append(c)
                quote == '"' -> when {
                    c == '"' -> quote = NONE
                    c == '\\' && i + 1 < line.length && line[i + 1] in "\"\\$`" -> { cur.append(line[i + 1]); i++ }
                    else -> cur.append(c)
                }
                c == '\'' || c == '"' -> { if (start < 0) start = i; quote = c }
                c == '\\' -> {
                    if (i + 1 >= line.length) throw CommandSyntaxException("A line cannot end with a backslash")
                    if (start < 0) start = i
                    cur.append(line[i + 1])
                    i++
                }
                c.isWhitespace() -> finish(i)
                else -> { if (start < 0) start = i; cur.append(c) }
            }
            i++
        }
        if (quote != NONE) throw CommandSyntaxException("Unterminated ${if (quote == '\'') "single" else "double"} quote")
        finish(line.length)
        return out
    }

    private val SAFE = Regex("[A-Za-z0-9_@%+=:,./-]+")

    /** [arg] as one shell word: unchanged if it is plainly safe, otherwise single-quoted. */
    public fun quote(arg: String): String =
        if (SAFE.matches(arg)) arg else "'" + arg.replace("'", "'\\''") + "'"

    public fun join(args: List<String>): String = args.joinToString(" ") { quote(it) }
}
