package com.bareflash.app.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bareflash.app.console.ConsoleViewModel
import com.bareflash.app.engine.EngineMode
import com.bareflash.app.ui.theme.LocalLook
import com.bareflash.app.ui.theme.NcEasing
import com.bareflash.app.ui.theme.Nc
import com.bareflash.app.ui.theme.motionMs
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Settings tab. This file holds only the shell that switches between
 * pages (large title, back navigation, the animated page transition and
 * [MainPage] list of destinations); each destination lives in its own file:
 * - [GeneralPage] -> SettingsGeneral.kt
 * - [TerminalPage] -> SettingsTerminal.kt
 * - [ProtocolsPage] -> SettingsProtocols.kt (MediaTek settings)
 * - [AppearancePage] -> SettingsAppearance.kt
 *   - [IslandSettingsPage] -> SettingsIsland.kt (a submenu of Appearance)
 * - [LoggingPage] -> SettingsLogging.kt
 * - [ExperimentalPage] -> SettingsExperimental.kt
 * - [AboutPage] -> SettingsAbout.kt
 * Building blocks shared across pages ([SwitchRow], [SectionLabel]) live in
 * SettingsComponents.kt.
 */

private enum class Page(val title: String) {
    Main("Settings"), Connection("Connection"), General("General"), Terminal("Terminal"),
    Protocols("MediaTek"), Appearance("Appearance"),
    Island("Dynamic Island"), Accessibility("Accessibility"), Logging("Logging"), Experimental("Experimental"), About("About")
}

/** Where Back goes from a page: the Dynamic Island submenu returns to Appearance, everything else to the list. */
private fun Page.parent(): Page = if (this == Page.Island) Page.Appearance else Page.Main

/** Nesting level, so the page transition knows whether it is going deeper or coming back. */
private fun Page.depth(): Int = when (this) {
    Page.Main -> 0
    Page.Island -> 2
    else -> 1
}

@Composable
fun SettingsScreen(vm: ConsoleViewModel) {
    var page by rememberSaveable { mutableStateOf(Page.Main) }
    var backProgress by remember { mutableFloatStateOf(0f) }

    // System back gesture: the page follows your finger (shrinks and slides
    // right) and either commits to the list or springs back.
    PredictiveBackHandler(enabled = page != Page.Main) { events ->
        try {
            events.collect { backProgress = it.progress }
            page = page.parent()
        } catch (e: CancellationException) {
            // Gesture cancelled: fall through and reset.
        } finally {
            backProgress = 0f
        }
    }

    val slide = 28.dp
    val inMs = motionMs(300)
    val outMs = motionMs(150)

    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val s = 1f - 0.08f * backProgress
                scaleX = s
                scaleY = s
                translationX = backProgress * 24.dp.toPx()
                alpha = 1f - 0.25f * backProgress
            }
    ) {
        AnimatedContent(
            targetState = page,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                // Material "shared axis X": forward slides in from the right, back from the left.
                val forward = targetState.depth() > initialState.depth()
                val px = 40
                val dir = if (forward) 1 else -1
                (fadeIn(tween(inMs, delayMillis = if (inMs == 0) 0 else outMs, easing = NcEasing.EmphasizedDecelerate)) +
                    slideInHorizontally(tween(inMs, easing = NcEasing.EmphasizedDecelerate)) { dir * px * 2 }) togetherWith
                    (fadeOut(tween(outMs, easing = NcEasing.EmphasizedAccelerate)) +
                        slideOutHorizontally(tween(inMs, easing = NcEasing.EmphasizedAccelerate)) { -dir * px })
            },
            label = "settingsPages"
        ) { p ->
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (p == Page.Main) {
                    NcHeader(kicker = "", title = "Settings")
                } else {
                    PageHeader(p) { page = p.parent() }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (p) {
                        Page.Main -> MainPage(vm, onOpen = { page = it })
                        // Pages that keep their own 16dp gutters bleed back out of the shell's.
                        Page.Connection -> ConnectionPage(vm)
                        Page.General -> Box(Modifier.bleed(16.dp)) { GeneralPage(vm) }
                        Page.Terminal -> Box(Modifier.bleed(16.dp)) { TerminalPage(vm) }
                        Page.Protocols -> Box(Modifier.bleed(16.dp)) { ProtocolsPage() }
                        Page.Appearance -> AppearancePage(onOpenIsland = { page = Page.Island })
                        Page.Island -> IslandSettingsPage()
                        Page.Accessibility -> Box(Modifier.bleed(16.dp)) { AccessibilityPage() }
                        Page.Logging -> Box(Modifier.bleed(16.dp)) { LoggingPage() }
                        Page.Experimental -> Box(Modifier.bleed(16.dp)) { ExperimentalPage() }
                        Page.About -> Box(Modifier.bleed(16.dp)) { AboutPage() }
                    }
                }
            }
        }
    }
}

/** Lets a child use [by] more width on each side than its parent's padding gave it. */
private fun Modifier.bleed(by: Dp): Modifier = this.layout { measurable, constraints ->
    val extra = by.roundToPx() * 2
    val w = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-by.roundToPx(), 0) }
}

@Composable
private fun PageHeader(page: Page, onBack: () -> Unit) {
    val large = LocalLook.current.largeTitles
    Column(Modifier.fillMaxWidth()) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 0.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Nc.Fg)
        }
        Text(
            page.title,
            style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
            color = Nc.Fg,
            modifier = Modifier.padding(start = 4.dp, top = if (large) 4.dp else 0.dp)
        )
    }
}

// ---- navigation building blocks (used only on this screen's main list) ----

private enum class Slot { Only, First, Middle, Last }

@Composable
private fun slotShape(slot: Slot): RoundedCornerShape {
    val k = LocalLook.current.cornerScale
    val outer = (24 * k).dp
    val inner = (6 * k).dp
    return when (slot) {
        Slot.Only -> RoundedCornerShape(outer)
        Slot.First -> RoundedCornerShape(topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner)
        Slot.Middle -> RoundedCornerShape(inner)
        Slot.Last -> RoundedCornerShape(topStart = inner, topEnd = inner, bottomStart = outer, bottomEnd = outer)
    }
}

/** An AOSP Settings row: tonal round icon, title, one-line summary, chevron. */
@Composable
private fun NavRow(
    icon: ImageVector,
    title: String,
    summary: String,
    slot: Slot,
    container: Color,
    onContainer: Color,
    onClick: () -> Unit
) {
    val pad = (14 * LocalLook.current.spacingScale).dp
    Row(
        Modifier
            .fillMaxWidth()
            .ncTile(slotShape(slot))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = pad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = onContainer, modifier = Modifier.size(22.dp)) // label is in the row text
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Nc.Fg)
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = Nc.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Nc.Subtle)
    }
}

// ---- main -----------------------------------------------------------------

@Composable
private fun MainPage(vm: ConsoleViewModel, onOpen: (Page) -> Unit) {
    val connectionDevice by vm.device.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val connectionHint = connectionDevice?.let { it.hwCode ?: "%04X:%04X".format(it.vid, it.pid) } ?: "No device"
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            when (mode) {
                EngineMode.MTK -> "MediaTek is active. Handshake defaults are under MediaTek. QDL uses Session. Samsung uses Flash."
                EngineMode.QDL -> "Qualcomm is active. Programmer and flash options are on Session. MediaTek settings do not apply."
                EngineMode.BROKKR -> "Samsung is active. Package, PIT, and USB or wireless are on Flash."
            },
            style = MaterialTheme.typography.bodySmall,
            color = Nc.Muted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        NavRow(Icons.Filled.Usb, "Connection", connectionHint, Slot.First, cs.primaryContainer, cs.onPrimaryContainer) { onOpen(Page.Connection) }
        NavRow(Icons.Filled.Settings, "General", "Vibration, sound, dump folder, backup", Slot.Middle, cs.secondaryContainer, cs.onSecondaryContainer) { onOpen(Page.General) }
        NavRow(Icons.Filled.Terminal, "Terminal", "Colors, prompt, keys and shell", Slot.Middle, cs.tertiaryContainer, cs.onTertiaryContainer) { onOpen(Page.Terminal) }
        NavRow(
            Icons.Filled.Lan,
            "MediaTek",
            if (mode == EngineMode.MTK) "Handshake, auth, DA, payload defaults" else "Not used in ${mode.label} mode",
            Slot.Last,
            cs.primaryContainer,
            cs.onPrimaryContainer
        ) { onOpen(Page.Protocols) }
        Box(Modifier.size(12.dp))
        NavRow(Icons.Filled.Palette, "Appearance", "Colours, shapes, motion, layout, island", Slot.First, cs.secondaryContainer, cs.onSecondaryContainer) { onOpen(Page.Appearance) }
        NavRow(Icons.Filled.Visibility, "Accessibility", "TalkBack, contrast, larger controls, announcements", Slot.Middle, cs.tertiaryContainer, cs.onTertiaryContainer) { onOpen(Page.Accessibility) }
        NavRow(Icons.Filled.Description, "Logging", "Everything the app does, crashes and stack traces", Slot.Middle, cs.tertiaryContainer, cs.onTertiaryContainer) { onOpen(Page.Logging) }
        NavRow(Icons.Filled.Science, "Experimental", "USB speed features you can switch off if something misbehaves", Slot.Middle, cs.secondaryContainer, cs.onSecondaryContainer) { onOpen(Page.Experimental) }
        NavRow(Icons.Filled.Info, "About", "Application information and credits", Slot.Last, cs.primaryContainer, cs.onPrimaryContainer) { onOpen(Page.About) }
        Box(Modifier.size(12.dp))
        NavRow(Icons.Filled.School, "Tutorial", "A walkthrough of every part of the app", Slot.Only, cs.secondaryContainer, cs.onSecondaryContainer) { Onboarding.openTutorial() }
        Box(Modifier.size(16.dp))
    }
}
