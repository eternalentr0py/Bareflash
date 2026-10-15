package com.bareflash.app.console.shell

import com.bareflash.app.catalog.McCommand

/** What the help text needs to know about one device command. */
data class CommandDoc(
    val name: String,
    val title: String,
    val description: String,
    val usage: String,
    val unsupported: String?
) {
    companion object {
        fun from(c: McCommand): CommandDoc {
            val parts = c.args.map { a ->
                val core = if (a.flag != null) "${a.flag} <${a.label.lowercase()}>" else "<${a.label.lowercase()}>"
                if (a.optional || a.flag != null && a.default.isNotEmpty()) "[$core]" else core
            }
            return CommandDoc(c.cmd, c.title, c.description, (listOf(c.cmd) + parts).joinToString(" "), c.unsupported)
        }
    }
}

/** What a built-in command can see. */
class ShellEnv(
    val user: String,
    val host: String,
    val modeId: String,
    val modeLabel: String,
    /** Commands typed in this engine, newest first. */
    val history: List<String>,
    val aliases: Map<String, String>,
    val commands: List<CommandDoc>
)

/** What to do with a line once the shell has looked at it. */
sealed class ShellResult {
    /** Not a built-in: send this to the device engine. */
    data class Run(val command: String) : ShellResult()

    /** A built-in that prints [text] (no trailing newline needed). */
    data class Print(val text: String) : ShellResult()

    data object ClearScreen : ShellResult()
    data class ClearHistory(val text: String) : ShellResult()
    data class SetAlias(val name: String, val value: String, val text: String) : ShellResult()
    data class RemoveAlias(val name: String, val text: String) : ShellResult()
}

object Shell {
    val BUILTINS = listOf(
        "help", "history", "alias", "unalias", "echo", "type", "whoami", "hostname", "pwd", "clear"
    )

    private val aliasName = Regex("[A-Za-z0-9_.:-]{1,24}")

    fun isValidAliasName(name: String) = aliasName.matches(name)

    /**
     * Looks at one typed line. History references (!!, !5, !-2, !pri) and aliases are expanded
     * first, then built-ins are handled here and anything else is returned as [ShellResult.Run].
     * [expandedFrom] is set by the caller to show the line after expansion when it differs.
     */
    fun run(rawLine: String, env: ShellEnv): ShellResult {
        val line = rawLine.trim()
        if (line.isEmpty()) return ShellResult.Print("")

        val afterHistory = when (val h = HistoryExpansion.expand(line, env.history)) {
            is HistoryExpansion.Result.Expanded -> h.line
            is HistoryExpansion.Result.Failed -> return ShellResult.Print(h.message)
            HistoryExpansion.Result.Unchanged -> line
        }
        val expanded = Aliases.expand(afterHistory, env.aliases)

        val name = expanded.substringBefore(' ')
        val rest = expanded.substring(name.length).trim()
        return when (name) {
            "help", "man" -> ShellResult.Print(help(rest, env))
            "history" -> history(rest, env)
            "alias" -> alias(rest, env)
            "unalias" -> unalias(rest, env)
            "echo" -> ShellResult.Print(unquote(rest))
            "type", "which" -> ShellResult.Print(typeOf(rest, env))
            "whoami" -> ShellResult.Print(env.user)
            "hostname" -> ShellResult.Print(env.host)
            "pwd" -> ShellResult.Print("/home/${env.user}")
            "clear" -> ShellResult.ClearScreen
            else -> ShellResult.Run(expanded)
        }
    }

    // ---- help ---------------------------------------------------------------

    private fun help(arg: String, env: ShellEnv): String {
        if (arg.isNotEmpty()) {
            val word = arg.substringBefore(' ')
            val doc = env.commands.firstOrNull { it.name == word }
            if (doc != null) {
                return buildString {
                    append(doc.name).append(" - ").append(doc.title).append('\n')
                    if (doc.description.isNotBlank()) append(doc.description.trim()).append('\n')
                    append("usage: ").append(doc.usage)
                    if (doc.unsupported != null) append("\nNot available here: ").append(doc.unsupported)
                }
            }
            val builtin = BUILTIN_HELP.firstOrNull { it.first == word }
            if (builtin != null) return "${builtin.first}: ${builtin.second}"
            val target = env.aliases[word]
            if (target != null) return "$word is an alias for '$target'."
            return "help: no help for '$word'. Type help to see what is available."
        }
        return buildString {
            append("Bareflash shell. Built-ins run here; everything else is sent to the device (${env.modeLabel}).\n\n")
            append("Built-ins\n")
            BUILTIN_HELP.forEach { (n, d) -> append("  ").append(n.padEnd(10)).append(d).append('\n') }
            append("\nKeys\n")
            append("  Tab        complete a command, flag or partition name\n")
            append("  Up, Down   earlier commands (!! repeats the last one)\n")
            append("  Ctrl+C     stop the running command, or drop the line\n")
            append("  Ctrl+L     clear the screen     Ctrl+U  clear the line\n")
            append('\n')
            if (env.commands.isEmpty()) append("No device commands in this mode.")
            else append("${env.commands.size} device commands. Press Tab on an empty line to list them, or help <command> to read about one.")
        }
    }

    private val BUILTIN_HELP = listOf(
        "help" to "help [command]: list built-ins, or describe a device command",
        "history" to "history [n | -c]: show earlier commands, or clear them",
        "alias" to "alias [name=command]: list shortcuts, or define one",
        "unalias" to "unalias name: remove a shortcut",
        "echo" to "echo text: print text",
        "type" to "type name: say whether a name is a built-in, an alias or a device command",
        "clear" to "clear: clear the screen",
        "whoami" to "whoami: print the user name shown in the prompt",
        "hostname" to "hostname: print the host name shown in the prompt",
        "pwd" to "pwd: print the working directory"
    )

    // ---- history ------------------------------------------------------------

    private fun history(arg: String, env: ShellEnv): ShellResult {
        if (arg == "-c") return ShellResult.ClearHistory("History cleared.")
        val oldestFirst = env.history.asReversed()
        if (oldestFirst.isEmpty()) return ShellResult.Print("No commands yet.")
        val count = arg.toIntOrNull()
        if (arg.isNotEmpty() && (count == null || count < 1)) return ShellResult.Print("history: use a number, or -c to clear.")
        val first = if (count == null) 0 else (oldestFirst.size - count).coerceAtLeast(0)
        val width = oldestFirst.size.toString().length
        return ShellResult.Print(
            (first until oldestFirst.size).joinToString("\n") { i -> "${(i + 1).toString().padStart(width + 1)}  ${oldestFirst[i]}" }
        )
    }

    // ---- aliases ------------------------------------------------------------

    private fun alias(arg: String, env: ShellEnv): ShellResult {
        if (arg.isEmpty()) {
            if (env.aliases.isEmpty()) return ShellResult.Print("No aliases. Try: alias pl='printgpt'")
            return ShellResult.Print(env.aliases.toSortedMap().entries.joinToString("\n") { (k, v) -> "alias $k='$v'" })
        }
        val eq = arg.indexOf('=')
        if (eq < 0) {
            val v = env.aliases[arg]
            return ShellResult.Print(if (v != null) "alias $arg='$v'" else "alias: $arg: not found")
        }
        val name = arg.substring(0, eq).trim()
        val value = unquote(arg.substring(eq + 1).trim()).trim()
        if (!isValidAliasName(name)) return ShellResult.Print("alias: '$name' is not a usable name. Use letters, digits, - _ . :")
        if (name in BUILTINS) return ShellResult.Print("alias: '$name' is a built-in and cannot be replaced.")
        if (value.isEmpty()) return ShellResult.Print("alias: nothing to run. Use alias $name='command'")
        if (value.substringBefore(' ') == name) return ShellResult.Print("alias: '$name' would only run itself.")
        return ShellResult.SetAlias(name, value, "alias $name='$value'")
    }

    private fun unalias(arg: String, env: ShellEnv): ShellResult {
        if (arg.isEmpty()) return ShellResult.Print("unalias: which one? Try: unalias pl")
        if (arg !in env.aliases) return ShellResult.Print("unalias: $arg: not found")
        return ShellResult.RemoveAlias(arg, "Removed alias $arg.")
    }

    private fun typeOf(arg: String, env: ShellEnv): String {
        val name = arg.substringBefore(' ')
        if (name.isEmpty()) return "type: which name?"
        return when {
            name in BUILTINS -> "$name is a shell built-in"
            name in env.aliases -> "$name is aliased to '${env.aliases[name]}'"
            else -> env.commands.firstOrNull { it.name == name }?.let { "$name is a device command (${it.title})" }
                ?: "$name: not found"
        }
    }

    private fun unquote(s: String): String {
        if (s.length >= 2 && (s.first() == '\'' && s.last() == '\'' || s.first() == '"' && s.last() == '"')) {
            return s.substring(1, s.length - 1)
        }
        return s
    }
}

object Aliases {
    /** Replaces the first word with its alias, following chains up to a few steps without looping. */
    fun expand(line: String, aliases: Map<String, String>): String {
        if (aliases.isEmpty()) return line
        var current = line.trimStart()
        val seen = HashSet<String>()
        repeat(8) {
            val first = current.substringBefore(' ')
            val target = aliases[first]
            if (target == null || !seen.add(first)) return current
            current = target + current.substring(first.length)
        }
        return current
    }
}

/** bash-style !! / !n / !-n / !prefix, for a whole line only. [history] is newest first. */
object HistoryExpansion {
    sealed class Result {
        data object Unchanged : Result()
        data class Expanded(val line: String) : Result()
        data class Failed(val message: String) : Result()
    }

    fun expand(line: String, history: List<String>): Result {
        if (!line.startsWith("!") || line.length < 2 || line[1].isWhitespace() || line.contains(' ')) return Result.Unchanged
        val ref = line.substring(1)
        val hit: String? = when {
            ref == "!" -> history.firstOrNull()
            ref.startsWith("-") && ref.drop(1).toIntOrNull() != null -> history.getOrNull(ref.drop(1).toInt() - 1)
            ref.toIntOrNull() != null -> history.asReversed().getOrNull(ref.toInt() - 1)
            else -> history.firstOrNull { it.startsWith(ref) }
        }
        return if (hit == null) Result.Failed("$line: event not found") else Result.Expanded(hit)
    }
}
