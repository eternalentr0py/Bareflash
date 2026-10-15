package com.bareflash.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bareflash.app.AppGraph
import com.bareflash.app.data.IslandMaterial
import com.bareflash.app.data.IslandMotion
import com.bareflash.app.data.IslandSettings
import com.bareflash.app.data.IslandSize
import com.bareflash.app.data.ProgressStyle
import com.bareflash.app.ui.theme.Nc
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Dynamic Island submenu (Settings > Appearance > Dynamic Island settings):
 * on/off, preview states, behaviour, shape, motion and what each size shows.
 * The header is drawn by SettingsScreen.
 */
@Composable
internal fun IslandSettingsPage() {
    val repo = AppGraph.settings
    val s by repo.state.collectAsStateWithLifecycle()
    val island = s.island
    val controller = LocalIsland.current

    // The preview states are demo data; never leave them on the real island
    // once this page is closed.
    DisposableEffect(Unit) { onDispose { controller.reset() } }

    fun patch(f: (IslandSettings) -> IslandSettings) = repo.update { it.copy(island = f(it.island)) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        NcSection("Dynamic Island") {
            NcToggle(
                "Enable island", island.enabled, { v -> patch { it.copy(enabled = v) } },
                hint = "Live status pill at the camera cutout"
            )
            NcCaption("Preview a state \u2014 tap the island to expand", Modifier.padding(top = 4.dp))
            NcChips(
                controller.preview,
                { controller.select(it) },
                IslandPreview.entries.map { NcChoice(it, it.label) }
            )
        }

        NcSection("Behavior") {
            NcToggle("Tap to expand", island.tapToExpand, { v -> patch { it.copy(tapToExpand = v) } })
            NcToggle("Auto-expand on connect", island.autoExpandOnConnect, { v -> patch { it.copy(autoExpandOnConnect = v) } })
            NcToggle(
                "Auto-expand on command", island.autoExpandOnJob, { v -> patch { it.copy(autoExpandOnJob = v) } },
                hint = "Drops into the large island when a rite starts"
            )
            NcToggle(
                "Compact live activity", island.compactLiveActivity, { v -> patch { it.copy(compactLiveActivity = v) } },
                hint = "After a beat, shrink to a compact progress pill"
            )
            NcSliderRow(
                "Auto-collapse",
                if (island.autoCollapseSec == 0f) "off" else String.format(Locale.US, "%.1fs", island.autoCollapseSec),
                island.autoCollapseSec, { v -> patch { it.copy(autoCollapseSec = v) } }, 0f, 8f, 0.2f
            )
        }

        NcSection("Shape") {
            NcCaption("Size preset")
            NcChips(
                island.sizePreset, { v -> patch { it.copy(sizePreset = v) } },
                listOf(
                    NcChoice(IslandSize.COMPACT, "Compact"), NcChoice(IslandSize.REGULAR, "Regular"),
                    NcChoice(IslandSize.PROMINENT, "Prominent")
                )
            )
            NcCaption("Material")
            NcChips(
                island.material, { v -> patch { it.copy(material = v) } },
                listOf(
                    NcChoice(IslandMaterial.OLED, "OLED black"), NcChoice(IslandMaterial.FROSTED, "Frosted"),
                    NcChoice(IslandMaterial.SOLID, "Solid")
                )
            )
            NcSliderRow(
                "Expanded corner", "${island.cornerRadius}dp",
                island.cornerRadius.toFloat(), { v -> patch { it.copy(cornerRadius = v.roundToInt()) } }, 24f, 52f, 1f
            )
            NcSliderRow(
                "Opacity", "${(island.opacity * 100).roundToInt()}%",
                island.opacity, { v -> patch { it.copy(opacity = v) } }, 0.72f, 1f, 0.02f
            )
            NcToggle("Depth shadow", island.shadow, { v -> patch { it.copy(shadow = v) } })
            NcToggle("Camera lenses", island.showLenses, { v -> patch { it.copy(showLenses = v) } })
        }

        NcSection("Motion") {
            NcChips(
                island.motion, { v -> patch { it.copy(motion = v) } },
                listOf(
                    NcChoice(IslandMotion.APPLE, "Island spring"), NcChoice(IslandMotion.SNAPPY, "Snappy"),
                    NcChoice(IslandMotion.SOFT, "Soft")
                )
            )
            NcToggle(
                "Morph overshoot", island.overshoot, { v -> patch { it.copy(overshoot = v) } },
                hint = "Width blooms past the target then settles \u2014 like the real island"
            )
            NcToggle("Pulse idle LED", island.pulseLed, { v -> patch { it.copy(pulseLed = v) } })
            NcSliderRow(
                "Speed", String.format(Locale.US, "%.2f\u00d7", island.speed),
                island.speed, { v -> patch { it.copy(speed = v) } }, 0.6f, 1.5f, 0.05f
            )
        }

        NcSection("Compact contents") {
            NcToggle("Status LED", island.showLed, { v -> patch { it.copy(showLed = v) } })
            NcCaption("Progress glyph")
            NcChips(
                island.progressStyle, { v -> patch { it.copy(progressStyle = v) } },
                listOf(
                    NcChoice(ProgressStyle.BAR, "Bar only"), NcChoice(ProgressStyle.RING, "Ring"),
                    NcChoice(ProgressStyle.BOTH, "Ring + bar")
                )
            )
        }

        NcSection("Expanded contents") {
            NcToggle("Device name", island.showDevice, { v -> patch { it.copy(showDevice = v) } })
            NcToggle("Mode badge", island.showMode, { v -> patch { it.copy(showMode = v) } })
            NcToggle("HW code + firmware", island.showHw, { v -> patch { it.copy(showHw = v) } })
            NcToggle("Job name", island.showCommand, { v -> patch { it.copy(showCommand = v) } })
            NcToggle("Target partition", island.showTarget, { v -> patch { it.copy(showTarget = v) } })
            NcToggle("Stage line", island.showStage, { v -> patch { it.copy(showStage = v) } })
            NcToggle("Progress bar", island.showProgressBar, { v -> patch { it.copy(showProgressBar = v) } })
            NcToggle("Percent", island.showPercent, { v -> patch { it.copy(showPercent = v) } })
            NcToggle("ETA", island.showEta, { v -> patch { it.copy(showEta = v) } })
            NcToggle("Transfer speed", island.showSpeed, { v -> patch { it.copy(showSpeed = v) } })
        }

        OutlinedButton(
            onClick = { patch { IslandSettings() } },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Reset island to defaults") }
        if (island != IslandSettings()) {
            Text(
                "Custom island saved on this device.",
                style = MaterialTheme.typography.bodySmall, color = Nc.Subtle,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
        }
        Box(Modifier.height(4.dp))
    }
}
