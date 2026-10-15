package com.bareflash.app.catalog

/**
 * One fixed answer for an argument that only accepts a few values. [value]
 * goes on the command line; [label] and [description] are what the review
 * sheet shows.
 */
data class McChoice(val value: String, val label: String = value, val description: String = "")

/** Shorthand for choices whose label is the value itself. */
fun pickOf(vararg values: String): List<McChoice> = values.map { McChoice(it) }

/**
 * One argument a command needs before it can run. Mirrors mtk.py's
 * argparse positional/optional args 1:1 (see app/src/main/python/mtk.py) —
 * whenever mtk.py's parser changes, update the matching [McCommand] here.
 *
 * [flag] null            → positional arg, appended in order.
 * [flag] "--something"   → appended as "--something value" (only if the
 *                           user actually typed something, or [optional]
 *                           is false and a [default] is supplied).
 * [pathUnderDumps] true  → the value is a bare filename/folder the user
 *                           picks; it is stored under the dump destination
 *                           (the user-chosen SAF folder, or the app's
 *                           private dumps/ folder). Writes stream there
 *                           directly — no internal copy first.
 * [choices] non-empty    → the argument only takes one of a few values, so
 *                           the review sheet offers them as buttons instead
 *                           of a text field. Optional arguments also get a
 *                           "Default" button that leaves the flag off.
 * [attach] / [attachMulti] → the command reads a file (or several), so the
 *                           review sheet offers an Attach button that copies
 *                           the picked file(s) into the dumps folder and
 *                           fills the field.
 */
data class McArg(
    val label: String,
    val hint: String = "",
    val default: String = "",
    val optional: Boolean = false,
    val flag: String? = null,
    val pathUnderDumps: Boolean = false,
    val choices: List<McChoice> = emptyList(),
    val attach: Boolean = false,
    val attachMulti: Boolean = false,
) {
    /** True when the sheet should show an Attach control for this argument. */
    val attachable: Boolean
        get() = attach || attachMulti || (pathUnderDumps && label.startsWith("Input"))
}
