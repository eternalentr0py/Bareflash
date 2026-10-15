package com.bareflash.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bareflash.app.data.TerminalFont
import com.bareflash.app.data.TerminalSettings
import com.bareflash.app.data.TerminalStyle
import com.bareflash.app.ui.theme.LocalLook
import com.bareflash.app.ui.theme.LocalNcAccent
import com.bareflash.app.ui.theme.Nc
import com.bareflash.app.ui.theme.PlexMono

/** Every colour the terminal and its settings preview draw with. */
internal data class TerminalColors(
    val bg: Color,
    val fg: Color,
    val prompt: Color,
    val command: Color,
    val error: Color,
    val ok: Color,
    val warn: Color,
    val cursor: Color,
    /** Placeholder and suggestion text. */
    val dim: Color
)

private class Palette(
    val bg: Long, val fg: Long, val prompt: Long, val command: Long,
    val error: Long, val ok: Long, val warn: Long
)

private fun paletteOf(style: TerminalStyle): Palette? = when (style) {
    TerminalStyle.CLEAN -> null
    TerminalStyle.CLASSIC -> Palette(0xFF000000, 0xFF33FF66, 0xFF33FF66, 0xFF8CFFAA, 0xFFFF5555, 0xFF33FF66, 0xFFFFD866)
    TerminalStyle.HIGH_CONTRAST -> Palette(0xFF000000, 0xFFFFFFFF, 0xFF00E5FF, 0xFFFFFF00, 0xFFFF5252, 0xFF69F0AE, 0xFFFFD740)
    TerminalStyle.TERMUX -> Palette(0xFF000000, 0xFFE6E6E6, 0xFF4DD0E1, 0xFFFFFFFF, 0xFFFF6E6E, 0xFF7CE38B, 0xFFFFCB6B)
    TerminalStyle.SOLARIZED -> Palette(0xFF002B36, 0xFF93A1A1, 0xFF859900, 0xFF268BD2, 0xFFDC322F, 0xFF859900, 0xFFB58900)
    TerminalStyle.DRACULA -> Palette(0xFF282A36, 0xFFF8F8F2, 0xFF50FA7B, 0xFFBD93F9, 0xFFFF5555, 0xFF50FA7B, 0xFFF1FA8C)
    TerminalStyle.NORD -> Palette(0xFF2E3440, 0xFFD8DEE9, 0xFFA3BE8C, 0xFF88C0D0, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B)
    TerminalStyle.GRUVBOX -> Palette(0xFF282828, 0xFFEBDBB2, 0xFFB8BB26, 0xFF83A598, 0xFFFB4934, 0xFFB8BB26, 0xFFFABD2F)
    TerminalStyle.MONOKAI -> Palette(0xFF272822, 0xFFF8F8F2, 0xFFA6E22E, 0xFF66D9EF, 0xFFF92672, 0xFFA6E22E, 0xFFE6DB74)
    TerminalStyle.AMBER -> Palette(0xFF000000, 0xFFFFB000, 0xFFFFB000, 0xFFFFD27F, 0xFFFF5F5F, 0xFFFFB000, 0xFFFFD27F)
    TerminalStyle.PAPER -> Palette(0xFFFAFAFA, 0xFF212121, 0xFF1B5E20, 0xFF0D47A1, 0xFFB71C1C, 0xFF1B5E20, 0xFFE65100)
}

/**
 * The colours in force: the chosen scheme, then the custom background and text colours from
 * Settings > Appearance, then the prompt and cursor colours chosen for the terminal itself.
 */
@Composable
internal fun terminalColors(t: TerminalSettings): TerminalColors {
    val cs = MaterialTheme.colorScheme
    val accent = LocalNcAccent.current.color
    val look = LocalLook.current
    val p = paletteOf(t.style)

    val bg0 = if (p != null) Color(p.bg) else cs.surfaceContainerLowest
    val fg0 = if (p != null) Color(p.fg) else Nc.Fg
    val bg = (if (look.terminalBg != 0) Color(look.terminalBg) else bg0).copy(alpha = t.backgroundAlpha)
    val fg = if (look.terminalFg != 0) Color(look.terminalFg) else fg0
    val prompt = when {
        t.promptColor != 0 -> Color(t.promptColor)
        p != null -> Color(p.prompt)
        else -> accent
    }
    return TerminalColors(
        bg = bg,
        fg = fg,
        prompt = prompt,
        command = if (p != null) Color(p.command) else cs.primary,
        error = if (p != null) Color(p.error) else Nc.Err,
        ok = if (p != null) Color(p.ok) else Nc.Ok,
        warn = if (p != null) Color(p.warn) else Nc.Warn,
        cursor = if (t.cursorColor != 0) Color(t.cursorColor) else prompt,
        dim = fg.copy(alpha = 0.45f)
    )
}

internal fun terminalTextStyle(t: TerminalSettings): TextStyle = TextStyle(
    fontFamily = if (t.font == TerminalFont.PLEX_MONO) PlexMono else FontFamily.Monospace,
    fontSize = t.fontSizeSp.sp,
    lineHeight = (t.fontSizeSp * t.lineSpacing).sp,
    letterSpacing = t.letterSpacingSp.sp,
    fontWeight = if (t.boldOutput) FontWeight.Bold else FontWeight.Normal
)
