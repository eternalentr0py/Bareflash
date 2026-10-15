package com.bareflash.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bareflash.app.engine.EngineMode
import com.bareflash.app.ui.theme.NcIsland
import com.bareflash.app.ui.theme.NcLive
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Shared building blocks
// ---------------------------------------------------------------------------

@Composable
internal fun PageHead(kicker: String, title: String, intro: String, icon: ImageVector, motion: HeroMotion) {
    Column(Modifier.reveal(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AnimatedHero(icon, motion)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(kicker, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Text(intro, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun TryIt(text: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .reveal()
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cs.secondaryContainer)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Try it", style = MaterialTheme.typography.labelLarge, color = cs.onSecondaryContainer, fontWeight = FontWeight.SemiBold)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer)
    }
}

/** A tonal card that holds an interactive demo or an explanation. */
@Composable
internal fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .reveal()
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) { content() }
}

@Composable
internal fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.reveal())
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChipRow(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            FilterChip(selected = item == selected, onClick = { onSelect(item) }, label = { Text(label(item)) })
        }
    }
}

// ---------------------------------------------------------------------------
// 1. Welcome
// ---------------------------------------------------------------------------

@Composable
internal fun WelcomePage() {
    val cs = MaterialTheme.colorScheme
    PageHead(
        "Welcome", "Bareflash",
        "A flasher that lives on your phone. Plug a device in over USB OTG, pick its mode, and read, " +
            "write, back up or restore its firmware without a computer.",
        Icons.Filled.Bolt, HeroMotion.Pulse
    )
    Panel {
        EngineMode.entries.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(cs.primaryContainer),
                    contentAlignment = Alignment.Center
                ) { Icon(m.icon(), null, tint = cs.onPrimaryContainer, modifier = Modifier.size(22.dp)) }
                Column {
                    Text(m.label, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                    Caption(m.subtitle)
                }
            }
        }
    }
    Caption("This short tour covers the modes, every tab, connecting a device, running commands, backups you can verify, live notifications, the Dynamic Island, Settings and a pre-flash checklist. It takes about three minutes and nothing in it touches a device.")
    TryIt("Swipe left, or tap Next.")
}

// ---------------------------------------------------------------------------
// 2. Modes
// ---------------------------------------------------------------------------

@Composable
internal fun ModesPage() {
    val cs = MaterialTheme.colorScheme
    var picked by remember { mutableStateOf(EngineMode.MTK) }
    PageHead(
        "Modes", "Pick what you are flashing",
        "Everything in the app follows the mode: its commands, the USB devices it looks for, and its tabs.",
        Icons.Filled.Tune, HeroMotion.Wiggle
    )
    Panel {
        EngineMode.entries.forEach { m ->
            val on = m == picked
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (on) cs.primaryContainer else cs.surfaceContainerHigh)
                    .clickable { picked = m }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(if (on) cs.primary else cs.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) { Icon(m.icon(), null, tint = if (on) cs.onPrimary else cs.onSecondaryContainer, modifier = Modifier.size(20.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(m.label, style = MaterialTheme.typography.titleSmall, color = if (on) cs.onPrimaryContainer else cs.onSurface)
                    Text(
                        m.subtitle, style = MaterialTheme.typography.bodySmall,
                        color = if (on) cs.onPrimaryContainer else cs.onSurfaceVariant
                    )
                }
                if (on) Icon(Icons.Filled.Check, "Selected", tint = cs.primary)
            }
        }
    }
    Caption(
        when (picked) {
            EngineMode.MTK -> "MediaTek chips in BootROM, Preloader and DA mode. Partition read and write, erase, full dumps and device control, with a live console."
            EngineMode.QDL -> "Qualcomm devices in EDL (9008) mode. Set up the programmer and firmware once in Session, then every command uses it."
            EngineMode.BROKKR -> "Samsung devices in Download mode. Flash .tar and .md5 firmware packages over USB, or wirelessly for watches."
        }
    )
    Panel {
        Text("Switching modes in the real app", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
        Caption("Tap the first slot in the bottom bar (it shows the current mode). A sheet opens with a card for each mode; tap one to switch. It is locked while a flash or command is running.")
    }
    TryIt("Tap the three cards above.")
}

// ---------------------------------------------------------------------------
// 3. Tabs
// ---------------------------------------------------------------------------

private fun tabInfo(t: Tab): String = when (t) {
    Tab.Console -> "A live terminal for the running command, with one-tap actions, job progress and the connection state."
    Tab.Commands -> "Every command for this mode, grouped and searchable. Pin favourites. Anything destructive opens a review sheet first."
    Tab.Session -> "Qualcomm only. Programmer, firmware folder, storage type and flash options. Set once, applied to every command."
    Tab.Dumps -> "Partition images stream straight to a folder you choose, each with a SHA-256 saved beside it. Verify, compare, restore, or resume a cut-short dump. The safest way to back up before you change anything."
    Tab.Settings -> "Connection, appearance, the Dynamic Island, logging and this tutorial."
    Tab.Flash -> "Add .tar / .md5 packages or a firmware folder, choose USB or Wireless, then press Start flash."
    Tab.Progress -> "Live overall progress, speed, ETA and the partition being written. Stop is here too."
    Tab.Devices -> "Samsung devices on the USB bus. Grant permission, and see whether each is really in download mode."
    Tab.Log -> "The full live log. Copy or share it when you need help."
}

@Composable
internal fun TabsPage() {
    var mode by remember { mutableStateOf(EngineMode.MTK) }
    val tabs = tabsFor(mode)
    var tab by remember(mode) { mutableStateOf(tabs.first()) }
    PageHead("Tabs", "A tour of every tab", "Each mode has its own bottom bar. Pick a mode, then tap its tabs.", Icons.Filled.ViewModule, HeroMotion.Drift)
    ChipRow(EngineMode.entries, mode, { it.label }) { mode = it }
    Panel {
        MiniNavBar(mode, tabs, tab) { tab = it }
    }
    Panel {
        Text(tab.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Caption(tabInfo(tab))
    }
    TryIt("Switch the mode, then tap each tab.")
}

/** A miniature of the real bottom bar: the mode slot first, then the mode's tabs. */
@Composable
private fun MiniNavBar(mode: EngineMode, tabs: List<Tab>, selected: Tab, onSelect: (Tab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerHigh).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(width = 40.dp, height = 26.dp).clip(RoundedCornerShape(13.dp)).background(cs.secondaryContainer),
                contentAlignment = Alignment.Center
            ) { Icon(mode.icon(), null, tint = cs.onSecondaryContainer, modifier = Modifier.size(18.dp)) }
            Text(mode.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = cs.onSurfaceVariant)
        }
        tabs.forEach { t ->
            val on = t == selected
            Column(
                Modifier.weight(1f).clickable { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.size(width = 40.dp, height = 26.dp).clip(RoundedCornerShape(13.dp))
                        .background(if (on) cs.primaryContainer else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (on) t.filled else t.outlined, t.label,
                        tint = if (on) cs.onPrimaryContainer else cs.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    t.label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    color = if (on) cs.onSurface else cs.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 4. Connecting
// ---------------------------------------------------------------------------

@Composable
internal fun ConnectPage() {
    var mode by remember { mutableStateOf(EngineMode.MTK) }
    val done = remember(mode) { mutableStateListOf(false, false, false, false) }
    val steps = listOf(
        mode.connectHint,
        "Use a data cable and a good OTG adapter. Charge-only cables never show up, and hubs slow things down.",
        "Plug the device into your phone. Allow USB access when Android asks.",
        "Check the Console (MediaTek, Qualcomm) or Devices (Samsung) tab: the device should appear."
    )
    PageHead(
        "Connecting", "Getting a device to show up",
        "Most problems are cables, adapters or the wrong mode on the device. Tick each step as you go.",
        Icons.Filled.Usb, HeroMotion.Bounce
    )
    ChipRow(EngineMode.entries, mode, { it.label }) { mode = it }
    Panel {
        steps.forEachIndexed { i, s ->
            Row(
                Modifier.fillMaxWidth().clickable { done[i] = !done[i] },
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Checkbox(checked = done[i], onCheckedChange = { done[i] = it })
                Text(
                    s, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
    Caption("Bareflash tells you the USB link speed when a Samsung flash starts. A Full-Speed (USB 1.1) link is about ten times slower, which almost always means a bad adapter or cable.")
}

// ---------------------------------------------------------------------------
// 5. Commands and review
// ---------------------------------------------------------------------------

private data class DemoCommand(val name: String, val what: String, val destructive: Boolean)

@Composable
internal fun CommandsPage() {
    val cs = MaterialTheme.colorScheme
    val cmds = listOf(
        DemoCommand("Read a partition", "Copies a partition from the device into a file on your phone. Nothing on the device changes.", false),
        DemoCommand("Dump full flash", "Streams everything to a folder you choose. Needs plenty of free space; the app checks first.", false),
        DemoCommand("Write a partition", "Overwrites a partition with a file. A wrong file here can leave the device unable to boot.", true),
        DemoCommand("Erase a partition", "Wipes a partition. The data is gone unless you backed it up.", true)
    )
    var picked by remember { mutableStateOf(cmds[0]) }
    PageHead(
        "MediaTek and Qualcomm", "Running commands safely",
        "Commands tab: find a command, fill in its arguments, review, run. The Console tab then shows it live.",
        Icons.Filled.Terminal, HeroMotion.Pulse
    )
    Panel {
        cmds.forEach { c ->
            val on = c == picked
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) cs.primaryContainer else cs.surfaceContainerHigh)
                    .clickable { picked = c }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    c.name, style = MaterialTheme.typography.bodyLarge,
                    color = if (on) cs.onPrimaryContainer else cs.onSurface, modifier = Modifier.weight(1f)
                )
                Text(
                    if (c.destructive) "DESTRUCTIVE" else "READ-ONLY",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (c.destructive) cs.error else cs.primary
                )
            }
        }
    }
    Panel {
        Text(picked.name, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
        Caption(picked.what)
        if (picked.destructive) {
            Text(
                "Before it runs, the app opens a review sheet showing exactly what will be written or erased, and you must confirm. " +
                    "Read it every time.",
                style = MaterialTheme.typography.bodyMedium, color = cs.error
            )
        } else {
            Caption("Safe to run, but it still shows a review of the arguments before it starts.")
        }
    }
    ReviewSheetDemo()
    TryIt("Tap each command, then try the review sheet. Nothing is run in this tutorial.")
}

/** A miniature of the review sheet: fixed answers are buttons, and a file is attached rather than typed. */
@Composable
private fun ReviewSheetDemo() {
    val cs = MaterialTheme.colorScheme
    var storage by remember { mutableStateOf("Default") }
    var attached by remember { mutableStateOf(false) }
    Panel {
        Text("The review sheet", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
        Text("Storage type", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        ChipRow(listOf("Default", "emmc", "ufs"), storage, { it }) { storage = it }
        Text("Input file", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = { attached = !attached }) {
                Icon(Icons.Filled.AttachFile, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (attached) "Remove" else "Attach a file")
            }
            AnimatedVisibility(visible = attached) {
                Text(
                    "boot.img",
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSecondaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(cs.secondaryContainer)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
        Caption(
            "An argument with a few fixed answers is a row of buttons instead of a text field, and an optional one has a Default button that simply leaves it out. " +
                "Attach opens the file picker and the command reads the file where it is, so nothing is copied into the app."
        )
    }
}

// ---------------------------------------------------------------------------
// 6. Samsung
// ---------------------------------------------------------------------------

@Composable
internal fun SamsungPage() {
    val roles = listOf("BL", "AP", "CP", "CSC", "HOME_CSC")
    var role by remember { mutableStateOf("AP") }
    val info = when (role) {
        "BL" -> "Bootloader. Flash it only when your firmware calls for it; an older bootloader than the one installed is usually refused."
        "AP" -> "The Android system itself: kernel, system and vendor. The biggest file by far."
        "CP" -> "The modem firmware (radio and baseband)."
        "CSC" -> "Region and carrier configuration. The plain CSC file wipes your data."
        else -> "Same as CSC, but keeps your data. When you pick a firmware folder, the app prefers this one."
    }
    PageHead(
        "Samsung", "Flashing a Samsung device",
        "Samsung mode uses Download mode. You hand it firmware packages and it writes each partition.",
        Icons.Filled.PhoneAndroid, HeroMotion.Drift
    )
    Panel {
        listOf(
            "Put the device in Download mode (Volume Down + Volume Up while plugging in the cable, then confirm).",
            "Flash tab: add the .tar / .md5 files, or pick the whole firmware folder.",
            "Press Start flash and confirm. The app jumps to Progress.",
            "Do not touch the device. When it finishes, it reboots if Auto reboot is on."
        ).forEachIndexed { i, s ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) { Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                Text(s, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            }
        }
    }
    Text("What the files are", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    ChipRow(roles, role, { it }) { role = it }
    Panel { Caption(info) }
    Caption("Watches flash over Wireless: choose Wireless on the Flash tab and the app shows an address to type on the watch. Several phones can also be flashed at once over USB.")
    TryIt("Tap the file types.")
}

// ---------------------------------------------------------------------------
// 7. Dynamic Island
// ---------------------------------------------------------------------------

@Composable
internal fun IslandPage() {
    var expanded by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0f) }
    var runId by remember { mutableStateOf(0) }
    // Between pressing run and the first byte there is nothing to measure, so the pill names the command.
    var starting by remember { mutableStateOf(false) }
    val running = runId > 0 && progress < 1f
    val accent = NcLive.palette.islandAccent

    LaunchedEffect(runId) {
        if (runId == 0) return@LaunchedEffect
        progress = 0f
        starting = true
        delay(1000)
        starting = false
        while (progress < 1f) {
            delay(50)
            progress = (progress + 0.012f).coerceAtMost(1f)
        }
    }

    val pct = (progress * 100).roundToInt()
    val part = when {
        progress < 0.15f -> "boot"
        progress < 0.35f -> "recovery"
        progress < 0.85f -> "system"
        else -> "vendor"
    }
    // 84 steps of 50 ms: about 4.2 s for the whole demo.
    val etaSec = ((1f - progress) * 4.2f).roundToInt()

    PageHead(
        "Dynamic Island", "Progress at a glance",
        "The pill at the top of the screen shows what is happening: devices found, and live progress, speed and ETA for every flash, in every mode.",
        Icons.Filled.Speed, HeroMotion.Pulse
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .clip(RoundedCornerShape(if (expanded) 32.dp else 22.dp))
                .background(Color.Black)
                .clickable { expanded = !expanded }
                .padding(horizontal = 18.dp, vertical = if (expanded) 16.dp else 10.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!expanded) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (running) accent else NcIsland.Muted))
                    Text(
                        when {
                            runId == 0 -> "Idle"
                            starting -> "FLASHING"
                            else -> "$pct%"
                        },
                        color = NcIsland.Fg, style = MaterialTheme.typography.labelLarge
                    )
                    if (running) Text("ETA ${etaSec}s", color = NcIsland.Muted, style = MaterialTheme.typography.labelMedium)
                }
            } else {
                Column(Modifier.width(280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Samsung flash", color = NcIsland.Fg, style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            runId == 0 -> "Waiting to start"
                            starting -> "Starting"
                            progress >= 1f -> "Complete"
                            else -> "Writing $part"
                        },
                        color = NcIsland.Muted, style = MaterialTheme.typography.bodySmall
                    )
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = accent,
                        trackColor = Color(0x22FFFFFF)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            if (running) "$pct% · 31.2 MB/s" else "$pct%",
                            color = NcIsland.Muted, style = MaterialTheme.typography.labelMedium
                        )
                        Text(if (running) "ETA ${etaSec}s" else "", color = NcIsland.Muted, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Button(onClick = { runId += 1 }, enabled = !running) { Text(if (runId == 0) "Run demo" else "Run again") }
    }
    Caption("In the real app the island expands by itself when a flash starts, shrinks to a small pill after a moment, and shows a result card when it finishes. Before the first byte moves the pill says what is running (FLASHING or DUMPING), then it switches to the percentage. Tap it to expand or collapse, or swipe it down or up.")
    Panel {
        Text("Make it yours", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Caption("Size, material, motion, what it shows and when it expands: Settings > Appearance > Dynamic Island settings.")
    }
    TryIt("Tap the pill, then press Run demo.")
}

// ---------------------------------------------------------------------------
// 8. Settings map
// ---------------------------------------------------------------------------

private data class SettingsRow(val icon: ImageVector, val title: String, val text: String)

@Composable
internal fun SettingsMapPage() {
    val cs = MaterialTheme.colorScheme
    val rows = listOf(
        SettingsRow(Icons.Filled.Usb, "Connection", "The detected device and its USB details. Start here when something will not connect."),
        SettingsRow(Icons.Filled.Settings, "General", "Live notifications, a read-only lock, automatic resume of cut-short dumps, vibration and sound cues, the dump folder, and settings backup and restore."),
        SettingsRow(Icons.Filled.Terminal, "Terminal", "Colours, font, prompt, extra keys and aliases for the bash-style console."),
        SettingsRow(Icons.Filled.Lan, "MediaTek", "Handshake, auth, DA and payload defaults for the MediaTek engine."),
        SettingsRow(Icons.Filled.Palette, "Appearance", "Light or dark, wallpaper colours or your own palette, shapes, spacing, motion. The Dynamic Island has its own submenu in here."),
        SettingsRow(Icons.Filled.Description, "Logging", "A full diagnostic log of the whole app, including crashes and stack traces. Export it when reporting a bug."),
        SettingsRow(Icons.Filled.Science, "Experimental", "USB speed features and presets, plus hashing dumps as they are written. Switch one off if something misbehaves."),
        SettingsRow(Icons.Filled.Info, "About", "Version, a summary of the three modes, and credits for the open-source tools Bareflash is built on."),
        SettingsRow(Icons.Filled.School, "Tutorial", "This walkthrough. Open it again any time.")
    )
    var expandedRow by remember { mutableStateOf<String?>(null) }
    PageHead(
        "Settings", "Where things live", "Settings is the last tab in every mode. Tap a row to see what is inside.",
        Icons.Filled.Settings, HeroMotion.Spin
    )
    Panel {
        rows.forEach { r ->
            val on = expandedRow == r.title
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) cs.primaryContainer else cs.surfaceContainerHigh)
                    .clickable { expandedRow = if (on) null else r.title }
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(r.icon, null, tint = if (on) cs.onPrimaryContainer else cs.primary, modifier = Modifier.size(22.dp))
                    Text(r.title, style = MaterialTheme.typography.titleSmall, color = if (on) cs.onPrimaryContainer else cs.onSurface)
                }
                if (on) Text(r.text, style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer)
            }
        }
    }
    TryIt("Tap a row to open it.")
}

// ---------------------------------------------------------------------------
// 9. Safety checklist
// ---------------------------------------------------------------------------

@Composable
internal fun SafetyPage() {
    val items = listOf(
        "I backed up everything I care about, and checked the backup opens.",
        "Every file is made for my exact device model and region.",
        "The battery is well charged, and I am using a good data cable.",
        "I know what each command or package does before I confirm it.",
        "I will not unplug, lock or touch the device while it flashes.",
        "I accept that a mistake can brick the device, and it is my risk."
    )
    val checked = remember { mutableStateListOf(false, false, false, false, false, false) }
    val count = checked.count { it }
    PageHead(
        "Last page", "Before every flash", "Run through this each time. It takes ten seconds and prevents most disasters.",
        Icons.Filled.VerifiedUser, HeroMotion.Pulse
    )
    Panel {
        items.forEachIndexed { i, s ->
            Row(
                Modifier.fillMaxWidth().clickable { checked[i] = !checked[i] },
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Checkbox(checked = checked[i], onCheckedChange = { checked[i] = it })
                Text(s, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
    val ready by animateFloatAsState(count.toFloat() / items.size, label = "ready")
    LinearProgressIndicator(progress = { ready }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
    Text(
        if (count == items.size) "You are ready. Good luck, and go slowly."
        else "$count of ${items.size} checked. Not ready yet? That is fine, start with a backup.",
        style = MaterialTheme.typography.titleSmall,
        color = if (count == items.size) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    Caption("Only want to look around? Turn on Settings > General > Read-only lock and every write, erase and flash is refused until you turn it off.")
    Caption("You can reopen this tutorial any time from Settings > Tutorial. Tap Finish to start using Bareflash.")
}
