package com.bareflash.app.ui.adbfb

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bareflash.app.adbfb.AdbFbDevice
import com.bareflash.app.adbfb.AdbFbLink
import com.bareflash.app.adbfb.AdbFbLinkState
import com.bareflash.app.adbfb.AdbFbViewModel
import com.bareflash.app.engine.EngineMode
import com.bareflash.app.ui.NcCaption
import com.bareflash.app.ui.NcHeader
import com.bareflash.app.ui.NcSection
import com.bareflash.app.ui.Tab
import com.bareflash.app.ui.ncTile
import com.bareflash.app.ui.theme.Nc
import com.bareflash.app.ui.theme.PlexMono
import com.bareflash.fastboot.RebootTarget
import com.bareflash.usb.DebugProtocol
import java.util.Locale

/** Entry point used by MainShell for the ADB / fastboot tabs (Settings is the shared one). */
@Composable
fun AdbFbTabs(vm: AdbFbViewModel, tab: Tab) {
    when (tab) {
        Tab.AdbDevices -> {
            // The protocol log is one tap away from here instead of a tab of its own.
            var showLog by rememberSaveable { mutableStateOf(false) }
            if (showLog) AdbLogScreen(vm, onBack = { showLog = false }) else AdbDevicesScreen(vm, onShowLog = { showLog = true })
        }
        Tab.AdbConsole -> AdbConsoleScreen(vm)
        Tab.AdbCommands -> AdbCommandsScreen(vm)
        Tab.AdbFastboot -> AdbFastbootScreen(vm)
        Tab.AdbLog -> AdbLogScreen(vm)
        else -> Unit
    }
}

// ---------------------------------------------------------------------------
// Devices
// ---------------------------------------------------------------------------

@Composable
fun AdbDevicesScreen(vm: AdbFbViewModel, onShowLog: () -> Unit = {}) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val link by vm.link.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            NcHeader(kicker = EngineMode.ADB_FASTBOOT.short, title = "Devices", trailing = {
                Row {
                    IconButton(onClick = onShowLog) {
                        Icon(Icons.Filled.Description, contentDescription = "Show the log")
                    }
                    IconButton(onClick = { vm.refreshDevices(forcePrompt = true) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Rescan")
                    }
                }
            })
        }

        item {
            NcSection("USB") {
                if (devices.isEmpty()) {
                    NcCaption(
                        "No ADB or fastboot device found. Use a data cable, turn on USB debugging " +
                            "(or boot the device into fastboot mode) and plug it in over OTG."
                    )
                }
                devices.forEach { d -> DeviceRow(d, link, busy, vm) }
            }
        }

        item {
            NcSection("Connection") {
                val text = link.message ?: when (link.state) {
                    AdbFbLinkState.IDLE -> "Not connected."
                    AdbFbLinkState.CONNECTING -> "Connecting…"
                    AdbFbLinkState.CONNECTED -> "Connected."
                    AdbFbLinkState.FAILED -> "The connection failed."
                }
                StatusLine(
                    color = when (link.state) {
                        AdbFbLinkState.CONNECTED -> Nc.Ok
                        AdbFbLinkState.FAILED -> Nc.Err
                        else -> Nc.Muted
                    },
                    text = text,
                )
            }
        }

        item {
            NcSection("How to connect") {
                NcCaption(EngineMode.ADB_FASTBOOT.connectHint)
                NcCaption(
                    "Devices are recognised by their USB interface (ADB or fastboot), not by vendor, " +
                        "so any manufacturer works. Everything stays on this phone: there is no network access."
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DeviceRow(d: AdbFbDevice, link: AdbFbLink, busy: Boolean, vm: AdbFbViewModel) {
    val connected = link.state == AdbFbLinkState.CONNECTED && link.deviceName == d.name
    val connecting = link.state == AdbFbLinkState.CONNECTING && link.deviceName == d.name
    val protocols = d.protocols.joinToString(" + ") { if (it == DebugProtocol.ADB) "ADB" else "fastboot" }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (d.permitted) Nc.Ok else Nc.Warn))
        Column(Modifier.weight(1f)) {
            Text(d.title, style = MaterialTheme.typography.bodyMedium, color = Nc.Fg)
            Text(
                String.format(
                    Locale.ROOT, "%04x:%04x · %s · %s",
                    d.vid, d.pid, protocols, if (d.permitted) "access granted" else "needs access"
                ),
                style = MaterialTheme.typography.bodySmall, color = Nc.Muted
            )
        }
        when {
            !d.permitted -> TextButton(onClick = { vm.refreshDevices(forcePrompt = true) }) { Text("Grant") }
            connected -> TextButton(onClick = vm::disconnect) { Text("Disconnect") }
            connecting -> TextButton(onClick = vm::disconnect) { Text("Cancel") }
            else -> Row {
                if (d.hasAdb) {
                    TextButton(
                        onClick = { vm.connect(d.name, DebugProtocol.ADB) },
                        enabled = !busy
                    ) { Text(if (d.hasFastboot) "ADB" else "Connect") }
                }
                if (d.hasFastboot) {
                    TextButton(
                        onClick = { vm.connect(d.name, DebugProtocol.FASTBOOT) },
                        enabled = !busy
                    ) { Text(if (d.hasAdb) "Fastboot" else "Connect") }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(color: androidx.compose.ui.graphics.Color, text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Nc.Fg)
    }
}

// ---------------------------------------------------------------------------
// Fastboot
// ---------------------------------------------------------------------------

@Composable
fun AdbFastbootScreen(vm: AdbFbViewModel) {
    val link by vm.link.collectAsStateWithLifecycle()
    val vars by vm.vars.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val connected = link.state == AdbFbLinkState.CONNECTED && link.protocol == DebugProtocol.FASTBOOT

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            NcHeader(kicker = EngineMode.ADB_FASTBOOT.short, title = "Fastboot", trailing = {
                if (connected) {
                    IconButton(onClick = vm::refreshVars, enabled = !busy) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Read variables again")
                    }
                }
            })
        }

        if (!connected) {
            item {
                NcSection("Not connected") {
                    NcCaption("Boot a device into fastboot mode, then connect to it on the Devices tab.")
                }
            }
        } else {
            item {
                NcSection("Reboot") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RebootButton("System", RebootTarget.SYSTEM, busy, vm, Modifier.weight(1f))
                        RebootButton("Bootloader", RebootTarget.BOOTLOADER, busy, vm, Modifier.weight(1f))
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RebootButton("Recovery", RebootTarget.RECOVERY, busy, vm, Modifier.weight(1f))
                        RebootButton("Fastbootd", RebootTarget.FASTBOOTD, busy, vm, Modifier.weight(1f))
                    }
                    NcCaption("Rebooting does not change any data. Flashing and erasing arrive in a later step.")
                }
            }
            item {
                NcSection("Device variables (${vars.size})") {
                    if (vars.isEmpty()) NcCaption("The device returned no variables.")
                    vars.forEach { (name, value) -> VarRow(name, value) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun RebootButton(label: String, target: RebootTarget, busy: Boolean, vm: AdbFbViewModel, modifier: Modifier) {
    OutlinedButton(onClick = { vm.reboot(target) }, enabled = !busy, modifier = modifier) {
        Text(label, maxLines = 1)
    }
}

@Composable
private fun VarRow(name: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            name,
            fontFamily = PlexMono, fontSize = 11.sp, lineHeight = 15.sp, color = Nc.Muted,
            modifier = Modifier.weight(0.45f)
        )
        Text(
            value,
            fontFamily = PlexMono, fontSize = 11.sp, lineHeight = 15.sp, color = Nc.Fg,
            modifier = Modifier.weight(0.55f)
        )
    }
}

// ---------------------------------------------------------------------------
// Log
// ---------------------------------------------------------------------------

@Composable
fun AdbLogScreen(vm: AdbFbViewModel, onBack: (() -> Unit)? = null) {
    val lines by vm.logLines.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val state = rememberLazyListState()

    // Follow the tail unless the user has scrolled up to read.
    LaunchedEffect(lines.size) {
        if (lines.isEmpty()) return@LaunchedEffect
        val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (last >= lines.size - 3) state.scrollToItem(lines.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        NcHeader(kicker = EngineMode.ADB_FASTBOOT.short, title = "Log", trailing = {
            Row {
                if (onBack != null) TextButton(onClick = onBack) { Text("Devices") }
                IconButton(onClick = { clipboard.setText(AnnotatedString(vm.logText())) }) {
                    Icon(Icons.Filled.ContentCopy, "Copy log")
                }
                IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, vm.logText())
                    }
                    ctx.startActivity(Intent.createChooser(send, "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Icon(Icons.Filled.Share, "Share log") }
                IconButton(onClick = vm::clearLog) { Icon(Icons.Filled.DeleteSweep, "Clear log") }
            }
        })
        Box(Modifier.weight(1f).fillMaxWidth().ncTile().padding(10.dp)) {
            if (lines.isEmpty()) {
                Text(
                    "Connection and protocol messages appear here.",
                    color = Nc.Muted, style = MaterialTheme.typography.bodySmall
                )
            } else {
                LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(lines) { _, line ->
                        Text(
                            line,
                            fontFamily = PlexMono, fontSize = 11.sp, lineHeight = 15.sp,
                            color = if (line.contains("failed", ignoreCase = true)) Nc.Err else Nc.Fg
                        )
                    }
                }
            }
        }
    }
}
