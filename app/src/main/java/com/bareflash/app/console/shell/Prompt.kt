package com.bareflash.app.console.shell

/**
 * The prompt shown in front of every command, written like a bash PS1.
 *
 *   \u  user name        \h  host name       \w  working directory (always ~)
 *   \m  engine (mtk, qdl, odin)              \$  $ (or # for the user root)
 *   \\  a backslash
 *
 * Anything else is printed as typed, so "❯ " or "[bareflash] " work as they are.
 */
enum class PromptPreset(val label: String, val ps1: String) {
    BASH("Bash", "\\u@\\h:\\w\\$ "),
    BASH_MODE("Bash with engine", "\\u@\\h:~/\\m\\$ "),
    TERMUX("Termux", "\\w \\$ "),
    SIMPLE("Plain", "\\$ "),
    ROOT("Root", "# "),
    ARROW("Arrow", "\u276F "),
    ENGINE("Engine", "\\m \u276F "),
    CUSTOM("Custom", "")
}

object Prompt {
    const val MAX_LENGTH = 60

    fun format(ps1: String, user: String, host: String, mode: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < ps1.length) {
            val c = ps1[i]
            if (c == '\\' && i + 1 < ps1.length) {
                when (val n = ps1[i + 1]) {
                    'u' -> sb.append(user)
                    'h' -> sb.append(host)
                    'w' -> sb.append('~')
                    'm' -> sb.append(mode)
                    '$' -> sb.append(if (user == "root") '#' else '$')
                    '\\' -> sb.append('\\')
                    else -> sb.append(c).append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        // One line only, and short enough to leave room to type.
        val oneLine = sb.toString().replace('\n', ' ')
        return if (oneLine.length > MAX_LENGTH) oneLine.take(MAX_LENGTH) else oneLine
    }

    /** User and host names are shown inside the prompt, so keep them to safe, short characters. */
    fun cleanName(raw: String, fallback: String): String {
        val kept = raw.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }.take(24)
        return kept.ifEmpty { fallback }
    }
}
