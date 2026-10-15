@file:OptIn(ExperimentalLayoutApi::class)

package com.bareflash.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bareflash.app.AppGraph
import com.bareflash.app.data.AppearanceSettings
import com.bareflash.app.data.CardStyle
import com.bareflash.app.data.ContrastLevel
import com.bareflash.app.data.IslandMaterial
import com.bareflash.app.data.IslandMotion
import com.bareflash.app.data.IslandSettings
import com.bareflash.app.data.IslandSize
import com.bareflash.app.data.LookSettings
import com.bareflash.app.data.NavLabelMode
import com.bareflash.app.data.ProgressStyle
import com.bareflash.app.data.SchemeVariant
import com.bareflash.app.data.ThemeMode
import com.bareflash.app.ui.theme.ColorEngine
import com.bareflash.app.ui.theme.ColorRoles
import com.bareflash.app.ui.theme.Nc
import com.bareflash.app.ui.theme.SeedPresets
import java.util.Locale
import kotlin.math.roundToInt

private fun roleColor(cs: ColorScheme, id: String): Color = when (id) {
    "primary" -> cs.primary
    "primaryContainer" -> cs.primaryContainer
    "secondary" -> cs.secondary
    "secondaryContainer" -> cs.secondaryContainer
    "tertiary" -> cs.tertiary
    "tertiaryContainer" -> cs.tertiaryContainer
    "background" -> cs.background
    "surface" -> cs.surface
    "surfaceContainer" -> cs.surfaceContainer
    "onSurface" -> cs.onSurface
    "outline" -> cs.outline
    "error" -> cs.error
    "ok" -> Nc.Ok
    "warn" -> Nc.Warn
    else -> cs.primary
}

/**
 * Appearance: light / dark, Material You or your own palette, every colour
 * role, shapes, spacing, type size, motion and terminal colours. The status
 * island has its own submenu ([IslandSettingsPage]), opened from the row here.
 * The header is drawn by SettingsScreen.
 */
@Composable
internal fun AppearancePage(onOpenIsland: () -> Unit) {
    val repo = AppGraph.settings
    val s by repo.state.collectAsStateWithLifecycle()
    val a = s.appearance
    val look = s.look
    val island = s.island
    val cs = MaterialTheme.colorScheme

    fun patchLook(f: (LookSettings) -> LookSettings) = repo.update { it.copy(look = f(it.look)) }
    fun patchApp(f: (AppearanceSettings) -> AppearanceSettings) = repo.update { it.copy(appearance = f(it.appearance)) }

    // Which colour is being edited in the picker, if any.
    var editing by remember { mutableStateOf<String?>(null) }
    val dynamicOn = look.dynamicColor && ColorEngine.dynamicAvailable()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        NcSection("Preview") {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Everything below updates live.", style = MaterialTheme.typography.bodyMedium, color = Nc.Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {}) { Text("Filled") }
                    FilledTonalButton(onClick = {}) { Text("Tonal") }
                    OutlinedButton(onClick = {}) { Text("Outlined") }
                    TextButton(onClick = {}) { Text("Text") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NcSwitch(true, {})
                    NcSwitch(false, {})
                    listOf(cs.primary, cs.secondary, cs.tertiary, cs.error).forEach {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(it))
                    }
                }
            }
        }

        NcSection("Theme") {
            NcChips(
                a.themeMode, { v -> patchApp { it.copy(themeMode = v) } },
                listOf(
                    NcChoice(ThemeMode.SYSTEM, "Follow system"), NcChoice(ThemeMode.LIGHT, "Light"),
                    NcChoice(ThemeMode.DARK, "Dark")
                )
            )
            NcToggle(
                "Pure black", a.oled,
                { v -> patchApp { it.copy(oled = v) } },
                hint = "True black background in the dark theme (saves power on OLED)"
            )
            NcToggle(
                "Wallpaper colours", look.dynamicColor,
                { v -> patchLook { it.copy(dynamicColor = v) } },
                hint = if (ColorEngine.dynamicAvailable()) "Material You: colours follow your wallpaper"
                else "Needs Android 12 or newer; your palette below is used instead"
            )
        }

        NcSection("Palette") {
            if (dynamicOn) {
                NcCaption("Colours follow your wallpaper. Turn off wallpaper colours to choose your own palette.")
            } else {
                NcCaption("Source colour")
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SeedPresets.forEach { p ->
                        ColorDot(Color(p.argb), selected = look.seedColor == p.argb) { patchLook { it.copy(seedColor = p.argb) } }
                    }
                    val custom = SeedPresets.none { it.argb == look.seedColor }
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(look.seedColor))
                            .border(if (custom) 3.dp else 1.dp, if (custom) Nc.Fg else Nc.Line, CircleShape)
                            .clickable { editing = "seed" },
                        contentAlignment = Alignment.Center
                    ) { Text("+", color = ColorEngine.onColorFor(Color(look.seedColor)), style = MaterialTheme.typography.titleMedium) }
                }
                NcCaption("Style")
                NcChips(
                    look.variant, { v -> patchLook { it.copy(variant = v) } },
                    SchemeVariant.entries.map { NcChoice(it, it.label) }
                )
                NcCaption("Contrast")
                NcChips(
                    look.contrast, { v -> patchLook { it.copy(contrast = v) } },
                    ContrastLevel.entries.map { NcChoice(it, it.label) }
                )
            }
        }

        NcSection("Advanced colours") {
            NcCaption("Override any single colour. Tap to change; the arrow puts back the palette colour.")
            ColorRoles.all.forEach { role ->
                val overridden = look.overrides.containsKey(role.id)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { editing = role.id }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(roleColor(cs, role.id))
                            .border(1.dp, Nc.Line, CircleShape)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(role.label, style = MaterialTheme.typography.bodyLarge, color = Nc.Fg)
                        Text(
                            if (overridden) "Custom \u00b7 #%06X".format(look.overrides.getValue(role.id) and 0xFFFFFF) else role.hint,
                            style = MaterialTheme.typography.bodyMedium, color = Nc.Muted
                        )
                    }
                    if (overridden) {
                        IconButton(onClick = { patchLook { l -> l.copy(overrides = l.overrides - role.id) } }) {
                            Icon(Icons.Filled.Restore, contentDescription = "Reset ${role.label}", tint = Nc.Muted)
                        }
                    }
                }
            }
        }

        NcSection("Shape and layout") {
            NcSliderRow(
                "Corner roundness", "${(look.cornerScale * 100).roundToInt()}%",
                look.cornerScale, { v -> patchLook { it.copy(cornerScale = v) } }, 0f, 1.6f, 0.1f
            )
            NcSliderRow(
                "Spacing", when {
                    look.spacingScale < 0.95f -> "Compact"
                    look.spacingScale > 1.05f -> "Roomy"
                    else -> "Standard"
                },
                look.spacingScale, { v -> patchLook { it.copy(spacingScale = v) } }, 0.7f, 1.4f, 0.1f
            )
            NcSliderRow(
                "Text size", "${(look.fontScale * 100).roundToInt()}%",
                look.fontScale, { v -> patchLook { it.copy(fontScale = v) } }, 0.85f, 1.3f, 0.05f
            )
            NcCaption("Card style")
            NcChips(
                look.cardStyle, { v -> patchLook { it.copy(cardStyle = v) } },
                CardStyle.entries.map { NcChoice(it, it.label) }
            )
            NcCaption("Navigation bar labels")
            NcChips(
                look.navLabels, { v -> patchLook { it.copy(navLabels = v) } },
                NavLabelMode.entries.map { NcChoice(it, it.label) }
            )
            NcToggle(
                "Large page titles", look.largeTitles, { v -> patchLook { it.copy(largeTitles = v) } },
                hint = "Big Android-style titles at the top of each page"
            )
        }

        NcSection("Motion") {
            NcSliderRow(
                "Animation speed",
                if (look.animationSpeed == 0f) "Off" else String.format(Locale.US, "%.2f\u00d7", look.animationSpeed),
                look.animationSpeed, { v -> patchLook { it.copy(animationSpeed = v) } }, 0f, 2f, 0.25f
            )
            NcCaption("Applies to page changes, tab changes and pop-ups. The status island has its own motion settings in its submenu.")
        }

        NcSection("Terminal colours") {
            listOf("terminalBg" to "Background", "terminalFg" to "Text").forEach { (key, label) ->
                val value = if (key == "terminalBg") look.terminalBg else look.terminalFg
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { editing = key }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(if (value != 0) Color(value) else if (key == "terminalBg") cs.surfaceContainerLowest else cs.onSurface)
                            .border(1.dp, Nc.Line, CircleShape)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodyLarge, color = Nc.Fg)
                        Text(
                            if (value != 0) "Custom \u00b7 #%06X".format(value and 0xFFFFFF) else "Follows the theme and the terminal style",
                            style = MaterialTheme.typography.bodyMedium, color = Nc.Muted
                        )
                    }
                    if (value != 0) {
                        IconButton(onClick = { patchLook { l -> if (key == "terminalBg") l.copy(terminalBg = 0) else l.copy(terminalFg = 0) } }) {
                            Icon(Icons.Filled.Restore, contentDescription = "Reset $label", tint = Nc.Muted)
                        }
                    }
                }
            }
        }

        FilledTonalButton(
            onClick = {
                repo.update { it.copy(look = LookSettings(), appearance = it.appearance.copy(themeMode = ThemeMode.SYSTEM, oled = false)) }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Reset appearance to stock Android") }

        NcSection("Dynamic Island") {
            IslandEntryRow(
                summary = if (!island.enabled) "Off" else "On \u00b7 " + when (island.sizePreset) {
                    IslandSize.COMPACT -> "Compact"
                    IslandSize.REGULAR -> "Regular"
                    IslandSize.PROMINENT -> "Prominent"
                } + " \u00b7 " + when (island.material) {
                    IslandMaterial.OLED -> "OLED black"
                    IslandMaterial.FROSTED -> "Frosted"
                    IslandMaterial.SOLID -> "Solid"
                },
                onClick = onOpenIsland
            )
        }

        Box(Modifier.height(4.dp))
    }

    // One colour picker serves every editable colour.
    editing?.let { key ->
        val (title, initial, apply, reset) = when (key) {
            "seed" -> PickerSpec(
                "Source colour", Color(look.seedColor),
                { c: Color -> patchLook { it.copy(seedColor = c.toArgb()) } },
                null
            )
            "terminalBg" -> PickerSpec(
                "Terminal background",
                if (look.terminalBg != 0) Color(look.terminalBg) else cs.surfaceContainerLowest,
                { c: Color -> patchLook { it.copy(terminalBg = c.toArgb()) } },
                { patchLook { it.copy(terminalBg = 0) } }
            )
            "terminalFg" -> PickerSpec(
                "Terminal text",
                if (look.terminalFg != 0) Color(look.terminalFg) else cs.onSurface,
                { c: Color -> patchLook { it.copy(terminalFg = c.toArgb()) } },
                { patchLook { it.copy(terminalFg = 0) } }
            )
            else -> {
                val role = ColorRoles.all.first { it.id == key }
                PickerSpec(
                    role.label, roleColor(cs, key),
                    { c: Color -> patchLook { l -> l.copy(overrides = l.overrides + (key to c.toArgb())) } },
                    { patchLook { l -> l.copy(overrides = l.overrides - key) } }
                )
            }
        }
        ColorPickerDialog(
            title = title,
            initial = initial,
            onDismiss = { editing = null },
            onConfirm = { apply(it); editing = null },
            onReset = reset?.let { r -> { r(); editing = null } }
        )
    }
}

private data class PickerSpec(
    val title: String,
    val initial: Color,
    val apply: (Color) -> Unit,
    val reset: (() -> Unit)?
)

/** One tappable row that opens the Dynamic Island submenu. */
@Composable
private fun IslandEntryRow(summary: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(cs.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.PhoneAndroid, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("Dynamic Island settings", style = MaterialTheme.typography.bodyLarge, color = Nc.Fg)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = Nc.Muted)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Nc.Subtle)
    }
}
