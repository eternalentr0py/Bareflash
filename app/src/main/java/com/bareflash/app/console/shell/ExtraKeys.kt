package com.bareflash.app.console.shell

/** One key on the extra keys row above the keyboard (the row Termux has for Esc, Tab, Ctrl and arrows). */
data class ExtraKeyDef(val id: String, val label: String, val description: String)

object ExtraKeys {
    val ALL: List<ExtraKeyDef> = listOf(
        ExtraKeyDef("tab", "TAB", "Complete the word"),
        ExtraKeyDef("ctrl", "CTRL", "Hold for the next letter: C stop, L clear, U clear line, A start, E end, W delete word, K delete to end"),
        ExtraKeyDef("up", "↑", "Earlier command"),
        ExtraKeyDef("down", "↓", "Later command"),
        ExtraKeyDef("left", "←", "Cursor left"),
        ExtraKeyDef("right", "→", "Cursor right, or accept the grey suggestion at the end"),
        ExtraKeyDef("home", "HOME", "Start of the line"),
        ExtraKeyDef("end", "END", "End of the line"),
        ExtraKeyDef("ctrl_c", "^C", "Stop the running command, or drop the line"),
        ExtraKeyDef("ctrl_l", "^L", "Clear the screen"),
        ExtraKeyDef("ctrl_u", "^U", "Clear the line before the cursor"),
        ExtraKeyDef("dash", "-", "Type -"),
        ExtraKeyDef("dashdash", "--", "Type --"),
        ExtraKeyDef("slash", "/", "Type /"),
        ExtraKeyDef("underscore", "_", "Type _"),
        ExtraKeyDef("dot", ".", "Type ."),
        ExtraKeyDef("hex", "0x", "Type 0x"),
        ExtraKeyDef("paste", "PASTE", "Paste from the clipboard"),
        ExtraKeyDef("enter", "⏎", "Run the line")
    )

    /** What is shown out of the box. Enter is left out because the keyboard's own Enter runs the line. */
    val DEFAULT_IDS: List<String> = listOf(
        "tab", "ctrl", "up", "down", "left", "right", "ctrl_c", "ctrl_l", "dash", "dashdash", "slash", "underscore"
    )

    private val known = ALL.map { it.id }.toSet()

    /** Drops ids this version does not know, keeping the order the user's list is in. */
    fun clean(ids: Collection<String>): List<String> = ids.filter { it in known }.distinct()
}
