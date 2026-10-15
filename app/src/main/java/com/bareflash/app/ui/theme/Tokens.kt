package com.bareflash.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Spacing scale. Kept from the earlier design system so screens that have
 * not been touched still resolve.
 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

/** Everything [Nc] needs, resolved from the active Material colour scheme. */
@Immutable
class NcPalette(
    val dark: Boolean,
    val scheme: ColorScheme,
    val ok: Color,
    val warn: Color,
    /** Primary of the dark scheme: the status island is always dark, so it uses this. */
    val islandAccent: Color,
    val islandOnAccent: Color
) {
    companion object {
        val Default = NcPalette(
            dark = true,
            scheme = darkColorScheme(),
            ok = Color(0xFF81C995),
            warn = Color(0xFFFDD663),
            islandAccent = Color(0xFFA8C7FA),
            islandOnAccent = Color(0xFF062E6F)
        )
    }
}

/** Snapshot-backed holder so every composable reading [Nc] recomposes on a theme change. */
object NcLive {
    var palette by mutableStateOf(NcPalette.Default)
}

/**
 * Bareflash colour tokens. The names are historical; every value now comes
 * from the Material 3 colour scheme (wallpaper-derived on Android 12+, stock
 * AOSP palette elsewhere, plus any per-role overrides from Settings), so the
 * whole app follows the system look instead of a fixed dark palette.
 */
object Nc {
    private val p get() = NcLive.palette
    private val s get() = NcLive.palette.scheme

    /** Screen background. */
    val Void get() = s.background
    /** Dialog / sheet / top-level surface. */
    val VoidSoft get() = s.surface
    val Ink get() = s.surfaceContainerLow
    /** Cards, chips, input fields. */
    val Raised get() = s.surfaceContainer
    val Panel get() = s.surfaceContainerHigh
    val PanelHigh get() = s.surfaceContainerHighest
    val Fg get() = s.onSurface
    val Muted get() = s.onSurfaceVariant
    val Subtle get() = s.outline

    val Ok get() = p.ok
    val Warn get() = p.warn
    val Err get() = s.error

    /** The status island is a physical-looking black pill in both themes. */
    val Island = Color(0xFF000000)
    val Lens = Color(0xFF080706)

    val Line get() = s.outlineVariant
    val LineStrong get() = s.outline.copy(alpha = 0.6f)
    val Border get() = s.outlineVariant.copy(alpha = 0.7f)
    val BorderSoft get() = s.outlineVariant.copy(alpha = 0.4f)
}

/** Fixed light-on-black ink for the always-dark status island. */
object NcIsland {
    val Fg = Color(0xFFF1EDE6)
    val Muted = Color(0xFFA39C90)
    val Subtle = Color(0xFF6F685E)
    val Ok = Color(0xFF81C995)
    val Warn = Color(0xFFFDD663)
    val Err = Color(0xFFF28B82)
}

/**
 * The accent the island, progress bars and highlights use. [color] is the
 * lead accent (text, icons); [color2] is kept so older call sites compile and
 * equals [color] (no gradients in the stock look). [onColor] sits on fills.
 */
data class NcAccent(
    val id: String,
    val label: String,
    val color: Color,
    val onColor: Color,
    val color2: Color = color
) {
    /** The accent at 16%, for tinted chips and tiles. */
    val dim: Color get() = color.copy(alpha = 0.16f)
    val multi: Boolean get() = color2 != color
    val brush: Brush get() = Brush.horizontalGradient(listOf(color, color2))
}

/**
 * Legacy status colours, remapped onto the live palette so any remaining
 * reference keeps compiling and looks right.
 */
object Signal {
    val background get() = Nc.Void
    val surface get() = Nc.Raised
    val surfaceHigh get() = Nc.Panel
    val text get() = Nc.Fg
    val muted get() = Nc.Muted
    val accent get() = Nc.Ok
    val amber get() = Nc.Warn
    val warn get() = Nc.Err
}
