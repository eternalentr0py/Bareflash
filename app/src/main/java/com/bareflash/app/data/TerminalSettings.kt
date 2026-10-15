package com.bareflash.app.data

import com.bareflash.app.console.shell.ExtraKeys
import com.bareflash.app.console.shell.PromptPreset
import org.json.JSONArray
import org.json.JSONObject

/**
 * Colour scheme of the terminal. CLEAN follows the app theme; the rest pin their own colours,
 * the way a real terminal does not follow your OS theme.
 */
enum class TerminalStyle(val label: String) {
    CLEAN("Follow theme"),
    CLASSIC("Classic green"),
    HIGH_CONTRAST("High contrast"),
    TERMUX("Termux"),
    SOLARIZED("Solarized dark"),
    DRACULA("Dracula"),
    NORD("Nord"),
    GRUVBOX("Gruvbox"),
    MONOKAI("Monokai"),
    AMBER("Amber"),
    PAPER("Paper")
}

enum class TerminalFont(val label: String) { PLEX_MONO("IBM Plex Mono"), SYSTEM_MONO("System monospace") }

enum class CursorShape(val label: String) { BLOCK("Block"), BAR("Bar"), UNDERLINE("Underline") }

enum class ExtraKeysMode(val label: String) { ALWAYS("Always"), WITH_KEYBOARD("With the keyboard"), NEVER("Never") }

enum class KeyboardMode(val label: String) { STANDARD("Standard"), NO_SUGGESTIONS("No suggestions") }

enum class TitleMode(val label: String) { TERMINAL("Terminal"), SHELL("user@host"), HIDDEN("Hidden") }

/**
 * Everything about the console terminal: how it looks, how the prompt reads, how input behaves,
 * how output scrolls, and the aliases you defined. Kept as one JSON blob (see [toJson]).
 */
data class TerminalSettings(
    // ---- look
    val style: TerminalStyle = TerminalStyle.CLEAN,
    val font: TerminalFont = TerminalFont.PLEX_MONO,
    val fontSizeSp: Int = 12,
    /** Line height as a multiple of the text size. */
    val lineSpacing: Float = 1.6f,
    val letterSpacingSp: Float = 0f,
    val boldOutput: Boolean = false,
    /** Colour errors, warnings and successes. Off draws every output line in the text colour. */
    val colorByLevel: Boolean = true,
    val cornerDp: Int = 16,
    val paddingDp: Int = 12,
    val showBorder: Boolean = true,
    val backgroundAlpha: Float = 1f,
    val titleMode: TitleMode = TitleMode.TERMINAL,
    val cursorShape: CursorShape = CursorShape.BLOCK,
    val cursorBlink: Boolean = true,
    /** 0 = the colour scheme's own. */
    val promptColor: Int = 0,
    val cursorColor: Int = 0,
    // ---- prompt
    val promptPreset: PromptPreset = PromptPreset.BASH,
    val customPrompt: String = "\\u@\\h:\\w\\$ ",
    val userName: String = "user",
    val hostName: String = "bareflash",
    val colorPrompt: Boolean = true,
    /** Put the prompt on a line of its own above where you type, like a two-line PS1. */
    val promptOwnLine: Boolean = false,
    // ---- input
    /** Enter runs the line. Off makes Enter add a new line, and the run key (or Ctrl+Enter) runs it. */
    val enterSends: Boolean = true,
    val keyboardMode: KeyboardMode = KeyboardMode.STANDARD,
    val autosuggest: Boolean = true,
    val tabComplete: Boolean = true,
    /** Up and Down only visit earlier commands that start with what is typed. */
    val historyPrefixSearch: Boolean = false,
    val keepKeyboardOpen: Boolean = true,
    val extraKeysMode: ExtraKeysMode = ExtraKeysMode.WITH_KEYBOARD,
    val extraKeys: List<String> = ExtraKeys.DEFAULT_IDS,
    val keyHaptics: Boolean = true,
    // ---- output
    val autoScroll: Boolean = true,
    /** How many lines the terminal keeps. */
    val scrollbackLines: Int = 800,
    val copyLineOnLongPress: Boolean = true,
    val showBanner: Boolean = true,
    // ---- shell
    val aliases: Map<String, String> = emptyMap()
) {
    /** The PS1 in force: the preset's, or the custom one. */
    val ps1: String get() = if (promptPreset == PromptPreset.CUSTOM) customPrompt else promptPreset.ps1

    companion object {
        val FONT_SIZE_RANGE = 8..24
        val SCROLLBACK_CHOICES = listOf(200, 500, 800, 2000, 5000)
    }
}

private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
    runCatching { enumValueOf<E>(name ?: "") }.getOrDefault(fallback)

internal fun TerminalSettings.toJson(): JSONObject = JSONObject().apply {
    put("style", style.name)
    put("font", font.name)
    put("fontSizeSp", fontSizeSp)
    put("lineSpacing", lineSpacing.toDouble())
    put("letterSpacingSp", letterSpacingSp.toDouble())
    put("boldOutput", boldOutput)
    put("colorByLevel", colorByLevel)
    put("cornerDp", cornerDp)
    put("paddingDp", paddingDp)
    put("showBorder", showBorder)
    put("backgroundAlpha", backgroundAlpha.toDouble())
    put("titleMode", titleMode.name)
    put("cursorShape", cursorShape.name)
    put("cursorBlink", cursorBlink)
    put("promptColor", promptColor)
    put("cursorColor", cursorColor)
    put("promptPreset", promptPreset.name)
    put("customPrompt", customPrompt)
    put("userName", userName)
    put("hostName", hostName)
    put("colorPrompt", colorPrompt)
    put("promptOwnLine", promptOwnLine)
    put("enterSends", enterSends)
    put("keyboardMode", keyboardMode.name)
    put("autosuggest", autosuggest)
    put("tabComplete", tabComplete)
    put("historyPrefixSearch", historyPrefixSearch)
    put("keepKeyboardOpen", keepKeyboardOpen)
    put("extraKeysMode", extraKeysMode.name)
    put("extraKeys", JSONArray(extraKeys))
    put("keyHaptics", keyHaptics)
    put("autoScroll", autoScroll)
    put("scrollbackLines", scrollbackLines)
    put("copyLineOnLongPress", copyLineOnLongPress)
    put("showBanner", showBanner)
    put("aliases", JSONObject().also { o -> aliases.forEach { (k, v) -> o.put(k, v) } })
}

/** Reads terminal settings; anything missing or out of range keeps the value from [cur]. */
internal fun terminalFromJson(o: JSONObject?, cur: TerminalSettings): TerminalSettings {
    if (o == null) return cur
    val keys = o.optJSONArray("extraKeys")?.let { a -> List(a.length()) { a.optString(it) } }
    val al = o.optJSONObject("aliases")
    val aliases = if (al == null) cur.aliases else buildMap {
        al.keys().forEach { k -> al.optString(k).takeIf { it.isNotBlank() }?.let { put(k, it) } }
    }
    return TerminalSettings(
        style = enumOr(o.optString("style"), cur.style),
        font = enumOr(o.optString("font"), cur.font),
        fontSizeSp = o.optInt("fontSizeSp", cur.fontSizeSp).coerceIn(TerminalSettings.FONT_SIZE_RANGE),
        lineSpacing = o.optDouble("lineSpacing", cur.lineSpacing.toDouble()).toFloat().coerceIn(1.0f, 2.4f),
        letterSpacingSp = o.optDouble("letterSpacingSp", cur.letterSpacingSp.toDouble()).toFloat().coerceIn(-0.5f, 2f),
        boldOutput = o.optBoolean("boldOutput", cur.boldOutput),
        colorByLevel = o.optBoolean("colorByLevel", cur.colorByLevel),
        cornerDp = o.optInt("cornerDp", cur.cornerDp).coerceIn(0, 32),
        paddingDp = o.optInt("paddingDp", cur.paddingDp).coerceIn(2, 28),
        showBorder = o.optBoolean("showBorder", cur.showBorder),
        backgroundAlpha = o.optDouble("backgroundAlpha", cur.backgroundAlpha.toDouble()).toFloat().coerceIn(0.3f, 1f),
        titleMode = enumOr(o.optString("titleMode"), cur.titleMode),
        cursorShape = enumOr(o.optString("cursorShape"), cur.cursorShape),
        cursorBlink = o.optBoolean("cursorBlink", cur.cursorBlink),
        promptColor = o.optInt("promptColor", cur.promptColor),
        cursorColor = o.optInt("cursorColor", cur.cursorColor),
        promptPreset = enumOr(o.optString("promptPreset"), cur.promptPreset),
        customPrompt = o.optString("customPrompt", cur.customPrompt).take(80),
        userName = o.optString("userName", cur.userName).ifBlank { cur.userName }.take(24),
        hostName = o.optString("hostName", cur.hostName).ifBlank { cur.hostName }.take(24),
        colorPrompt = o.optBoolean("colorPrompt", cur.colorPrompt),
        promptOwnLine = o.optBoolean("promptOwnLine", cur.promptOwnLine),
        enterSends = o.optBoolean("enterSends", cur.enterSends),
        keyboardMode = enumOr(o.optString("keyboardMode"), cur.keyboardMode),
        autosuggest = o.optBoolean("autosuggest", cur.autosuggest),
        tabComplete = o.optBoolean("tabComplete", cur.tabComplete),
        historyPrefixSearch = o.optBoolean("historyPrefixSearch", cur.historyPrefixSearch),
        keepKeyboardOpen = o.optBoolean("keepKeyboardOpen", cur.keepKeyboardOpen),
        extraKeysMode = enumOr(o.optString("extraKeysMode"), cur.extraKeysMode),
        extraKeys = keys?.let { ExtraKeys.clean(it) } ?: cur.extraKeys,
        keyHaptics = o.optBoolean("keyHaptics", cur.keyHaptics),
        autoScroll = o.optBoolean("autoScroll", cur.autoScroll),
        scrollbackLines = o.optInt("scrollbackLines", cur.scrollbackLines).let { v ->
            TerminalSettings.SCROLLBACK_CHOICES.minByOrNull { kotlin.math.abs(it - v) } ?: cur.scrollbackLines
        },
        copyLineOnLongPress = o.optBoolean("copyLineOnLongPress", cur.copyLineOnLongPress),
        showBanner = o.optBoolean("showBanner", cur.showBanner),
        aliases = aliases
    )
}
