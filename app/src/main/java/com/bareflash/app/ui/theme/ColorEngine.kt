@file:Suppress("RestrictedApi")

package com.bareflash.app.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import com.bareflash.app.data.LookSettings
import com.bareflash.app.data.SchemeVariant
import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.SchemeContent
import com.google.android.material.color.utilities.SchemeExpressive
import com.google.android.material.color.utilities.SchemeFidelity
import com.google.android.material.color.utilities.SchemeFruitSalad
import com.google.android.material.color.utilities.SchemeMonochrome
import com.google.android.material.color.utilities.SchemeNeutral
import com.google.android.material.color.utilities.SchemeRainbow
import com.google.android.material.color.utilities.SchemeTonalSpot
import com.google.android.material.color.utilities.SchemeVibrant

/** A colour role the user can override in Settings > Appearance > Advanced colours. */
data class ColorRole(val id: String, val label: String, val hint: String)

object ColorRoles {
    val all = listOf(
        ColorRole("primary", "Primary", "Buttons, switches, selected items"),
        ColorRole("primaryContainer", "Primary container", "Selected chips, highlighted tiles"),
        ColorRole("secondary", "Secondary", "Less prominent controls"),
        ColorRole("secondaryContainer", "Secondary container", "Navigation indicator, tonal buttons"),
        ColorRole("tertiary", "Tertiary", "Contrasting accents"),
        ColorRole("tertiaryContainer", "Tertiary container", "Tertiary highlights"),
        ColorRole("background", "Background", "Behind every screen"),
        ColorRole("surface", "Surface", "Dialogs, sheets, app bars"),
        ColorRole("surfaceContainer", "Cards", "Settings groups and command tiles"),
        ColorRole("onSurface", "Text", "Primary text and icons"),
        ColorRole("outline", "Outline", "Borders and dividers"),
        ColorRole("error", "Error", "Destructive actions and failures"),
        ColorRole("ok", "Success", "Connected and completed states"),
        ColorRole("warn", "Warning", "Cautions")
    )
}

/** Stock seed colours offered as one-tap presets. The first is AOSP's own default. */
data class SeedPreset(val label: String, val argb: Int)

val SeedPresets = listOf(
    SeedPreset("Stock blue", 0xFF1B6EF3.toInt()),
    SeedPreset("Teal", 0xFF00897B.toInt()),
    SeedPreset("Green", 0xFF2E7D32.toInt()),
    SeedPreset("Lime", 0xFF7CB342.toInt()),
    SeedPreset("Yellow", 0xFFF9AB00.toInt()),
    SeedPreset("Orange", 0xFFEF6C00.toInt()),
    SeedPreset("Red", 0xFFD93025.toInt()),
    SeedPreset("Pink", 0xFFD81B60.toInt()),
    SeedPreset("Purple", 0xFF7E57C2.toInt()),
    SeedPreset("Indigo", 0xFF3F51B5.toInt())
)

object ColorEngine {

    fun dynamicAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** The scheme the app draws with, before overrides. */
    fun base(look: LookSettings, dark: Boolean, context: Context): ColorScheme =
        if (look.dynamicColor && dynamicAvailable()) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            generated(look, dark)
        }

    private fun dynamicScheme(look: LookSettings, dark: Boolean): DynamicScheme {
        val hct = Hct.fromInt(look.seedColor)
        val c = look.contrast.value
        return when (look.variant) {
            SchemeVariant.TONAL_SPOT -> SchemeTonalSpot(hct, dark, c)
            SchemeVariant.VIBRANT -> SchemeVibrant(hct, dark, c)
            SchemeVariant.EXPRESSIVE -> SchemeExpressive(hct, dark, c)
            SchemeVariant.NEUTRAL -> SchemeNeutral(hct, dark, c)
            SchemeVariant.MONOCHROME -> SchemeMonochrome(hct, dark, c)
            SchemeVariant.FRUIT_SALAD -> SchemeFruitSalad(hct, dark, c)
            SchemeVariant.RAINBOW -> SchemeRainbow(hct, dark, c)
            SchemeVariant.CONTENT -> SchemeContent(hct, dark, c)
            SchemeVariant.FIDELITY -> SchemeFidelity(hct, dark, c)
        }
    }

    private fun generated(look: LookSettings, dark: Boolean): ColorScheme {
        val ds = dynamicScheme(look, dark)
        val m = MaterialDynamicColors()
        fun com.google.android.material.color.utilities.DynamicColor.c() = Color(getArgb(ds))
        val seed = if (dark) darkColorScheme() else lightColorScheme()
        return seed.copy(
            primary = m.primary().c(), onPrimary = m.onPrimary().c(),
            primaryContainer = m.primaryContainer().c(), onPrimaryContainer = m.onPrimaryContainer().c(),
            inversePrimary = m.inversePrimary().c(),
            secondary = m.secondary().c(), onSecondary = m.onSecondary().c(),
            secondaryContainer = m.secondaryContainer().c(), onSecondaryContainer = m.onSecondaryContainer().c(),
            tertiary = m.tertiary().c(), onTertiary = m.onTertiary().c(),
            tertiaryContainer = m.tertiaryContainer().c(), onTertiaryContainer = m.onTertiaryContainer().c(),
            background = m.background().c(), onBackground = m.onBackground().c(),
            surface = m.surface().c(), onSurface = m.onSurface().c(),
            surfaceVariant = m.surfaceVariant().c(), onSurfaceVariant = m.onSurfaceVariant().c(),
            surfaceTint = m.surfaceTint().c(),
            inverseSurface = m.inverseSurface().c(), inverseOnSurface = m.inverseOnSurface().c(),
            error = m.error().c(), onError = m.onError().c(),
            errorContainer = m.errorContainer().c(), onErrorContainer = m.onErrorContainer().c(),
            outline = m.outline().c(), outlineVariant = m.outlineVariant().c(),
            scrim = m.scrim().c(),
            surfaceBright = m.surfaceBright().c(), surfaceDim = m.surfaceDim().c(),
            surfaceContainerLowest = m.surfaceContainerLowest().c(),
            surfaceContainerLow = m.surfaceContainerLow().c(),
            surfaceContainer = m.surfaceContainer().c(),
            surfaceContainerHigh = m.surfaceContainerHigh().c(),
            surfaceContainerHighest = m.surfaceContainerHighest().c()
        )
    }

    /** Black or white, whichever reads better on [bg]. */
    fun onColorFor(bg: Color): Color = if (bg.luminance() > 0.45f) Color(0xFF111111) else Color.White

    /** Pure-black dark theme: the screen and the lowest surfaces go to #000. */
    fun pureBlack(cs: ColorScheme): ColorScheme = cs.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black
    )

    fun applyOverrides(cs: ColorScheme, o: Map<String, Int>): ColorScheme {
        if (o.isEmpty()) return cs
        var r = cs
        fun get(id: String): Color? = o[id]?.let { Color(it) }
        get("primary")?.let { r = r.copy(primary = it, onPrimary = onColorFor(it), surfaceTint = it) }
        get("primaryContainer")?.let { r = r.copy(primaryContainer = it, onPrimaryContainer = onColorFor(it)) }
        get("secondary")?.let { r = r.copy(secondary = it, onSecondary = onColorFor(it)) }
        get("secondaryContainer")?.let { r = r.copy(secondaryContainer = it, onSecondaryContainer = onColorFor(it)) }
        get("tertiary")?.let { r = r.copy(tertiary = it, onTertiary = onColorFor(it)) }
        get("tertiaryContainer")?.let { r = r.copy(tertiaryContainer = it, onTertiaryContainer = onColorFor(it)) }
        get("error")?.let { r = r.copy(error = it, onError = onColorFor(it)) }
        get("background")?.let { r = r.copy(background = it, onBackground = onColorFor(it)) }
        get("surface")?.let { r = r.copy(surface = it, surfaceDim = it) }
        get("onSurface")?.let { r = r.copy(onSurface = it, onBackground = it) }
        get("outline")?.let { r = r.copy(outline = it, outlineVariant = it.copy(alpha = 0.5f)) }
        get("surfaceContainer")?.let { c ->
            r = r.copy(
                surfaceContainer = c,
                surfaceContainerLow = lerp(r.surface, c, 0.5f),
                surfaceContainerHigh = lerp(c, r.onSurface, 0.06f),
                surfaceContainerHighest = lerp(c, r.onSurface, 0.12f)
            )
        }
        return r
    }

    /** Default success / warning colours, tuned per brightness (Google green / yellow). */
    fun ok(dark: Boolean) = if (dark) Color(0xFF81C995) else Color(0xFF1E8E3E)
    fun warn(dark: Boolean) = if (dark) Color(0xFFFDD663) else Color(0xFFB06000)
}
