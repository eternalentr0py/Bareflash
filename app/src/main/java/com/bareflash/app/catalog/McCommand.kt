package com.bareflash.app.catalog

/**
 * One catalog entry: the CLI subcommand string, display title, description,
 * and the ordered argument list the review dialog prompts for.
 *
 * [unsupported] non-null → the command is listed for completeness but cannot
 * run on this platform; the string is the reason shown to the user.
 */
data class McCommand(
    val cmd: String,
    val title: String,
    val description: String,
    val args: List<McArg> = emptyList(),
    val unsupported: String? = null,
)
