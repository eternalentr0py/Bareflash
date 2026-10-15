package com.bareflash.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.bareflash.app.data.AppearanceSettings
import com.bareflash.app.data.AccessibilitySettings
import com.bareflash.app.data.ContrastLevel
import com.bareflash.app.data.LookSettings
import com.bareflash.app.data.ThemeMode
import com.bareflash.app.engine.EngineMode

/** Active accent: the scheme's primary (colour + the ink that sits on it). */
val LocalNcAccent = staticCompositionLocalOf { NcAccent("primary", "Primary", Color(0xFFA8C7FA), Color(0xFF062E6F)) }

/** True when the dark theme is showing. */
val LocalNcDark = staticCompositionLocalOf { true }

/** Kept for older call sites. */
val LocalNcOled = staticCompositionLocalOf { false }

/** Screen background. */
val LocalNcScreen = staticCompositionLocalOf { Color.Black }

/** Card fill. */
val LocalNcTile = staticCompositionLocalOf { Color.Black }

/** The user's appearance choices, readable anywhere in the tree. */
val LocalLook = staticCompositionLocalOf { LookSettings() }

private fun shapes(scale: Float): Shapes {
    fun r(dp: Int) = RoundedCornerShape((dp * scale).dp)
    return Shapes(
        extraSmall = r(4),
        small = r(8),
        medium = r(12),
        large = r(16),
        extraLarge = r(28)
    )
}

/**
 * Bareflash theme: stock Material 3 the way AOSP ships it. Light, dark or
 * follow-system; Material You colours from the wallpaper on Android 12+ (or
 * the AOSP fallback blue); system fonts; standard shapes and motion. Every
 * piece can be changed from Settings > Appearance via [LookSettings].
 */
@Composable
fun BareflashTheme(
    appearance: AppearanceSettings,
    look: LookSettings,
    @Suppress("UNUSED_PARAMETER") engine: EngineMode,
    accessibility: AccessibilitySettings = AccessibilitySettings(),
    content: @Composable () -> Unit
) {
    val dark = when (appearance.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val pureBlack = appearance.oled && dark

    // Accessibility overrides sit on top of Appearance without rewriting stored look prefs.
    val effectiveLook = look.copy(
        contrast = if (accessibility.highContrast) ContrastLevel.HIGH else look.contrast,
        fontScale = if (accessibility.largeControls) (look.fontScale * 1.15f).coerceAtMost(1.4f) else look.fontScale,
        spacingScale = if (accessibility.largeControls) (look.spacingScale * 1.12f).coerceAtMost(1.4f) else look.spacingScale,
        animationSpeed = if (accessibility.reduceMotion) 0f else look.animationSpeed,
    )

    val scheme = remember(effectiveLook, dark, pureBlack) {
        var cs = ColorEngine.base(effectiveLook, dark, context)
        if (pureBlack) cs = ColorEngine.pureBlack(cs)
        ColorEngine.applyOverrides(cs, effectiveLook.overrides)
    }
    val palette = remember(scheme, effectiveLook, dark) {
        val darkScheme = if (dark) scheme else ColorEngine.applyOverrides(ColorEngine.base(effectiveLook, true, context), effectiveLook.overrides)
        NcPalette(
            dark = dark,
            scheme = scheme,
            ok = effectiveLook.overrides["ok"]?.let { Color(it) } ?: ColorEngine.ok(dark),
            warn = effectiveLook.overrides["warn"]?.let { Color(it) } ?: ColorEngine.warn(dark),
            islandAccent = darkScheme.primary,
            islandOnAccent = darkScheme.onPrimary
        )
    }
    // Written before any child composes, so the whole tree reads the new palette.
    if (NcLive.palette !== palette) NcLive.palette = palette

    val accent = remember(scheme) { NcAccent("primary", "Primary", scheme.primary, scheme.onPrimary) }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalNcAccent provides accent,
        LocalNcDark provides dark,
        LocalNcOled provides pureBlack,
        LocalNcScreen provides scheme.background,
        LocalNcTile provides scheme.surfaceContainer,
        LocalLook provides effectiveLook,
        LocalMotionSpeed provides effectiveLook.animationSpeed,
        LocalDensity provides Density(density.density, density.fontScale * effectiveLook.fontScale)
    ) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = remember(effectiveLook.cornerScale) { shapes(effectiveLook.cornerScale) },
            typography = ncTypography(),
            motionScheme = MotionScheme.standard(),
            content = content
        )
    }
}
