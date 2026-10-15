package com.bareflash.app.ui.samsung

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bareflash.app.engine.EngineMode
import com.bareflash.app.samsung.PlanState
import com.bareflash.app.samsung.RunPhase
import com.bareflash.app.samsung.SamsungLink
import com.bareflash.app.samsung.SamsungViewModel
import com.bareflash.app.ui.NcAlertDialog
import com.bareflash.app.ui.NcCaption
import com.bareflash.app.ui.NcChips
import com.bareflash.app.ui.NcChoice
import com.bareflash.app.ui.NcHeader
import com.bareflash.app.ui.NcSection
import com.bareflash.app.ui.NcToggle
import com.bareflash.app.ui.Tab
import com.bareflash.app.ui.ncTile
import com.bareflash.app.ui.theme.LocalNcAccent
import com.bareflash.app.ui.theme.Nc
import com.bareflash.app.ui.theme.PlexMono
import java.util.Locale

/** Entry point used by MainShell for the Brokkr tabs (Settings is the shared one). */
@Composable
fun BrokkrTabs(vm: SamsungViewModel, tab: Tab) {
    when (tab) {
        Tab.Flash -> BrokkrFlashScreen(vm)
        Tab.Progress -> BrokkrProgressScreen(vm)
        Tab.Devices -> BrokkrDevicesScreen(vm)
        Tab.Log -> BrokkrLogScreen(vm)
        else -> Unit
    }
}

// ---------------------------------------------------------------------------
// Flash
// ---------------------------------------------------------------------------

@Composable
fun BrokkrFlashScreen(vm: SamsungViewModel) {
    val packages by vm.packages.collectAsStateWithLifecycle()
    val pit by vm.pit.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val link by vm.link.collectAsStateWithLifecycle()
    val autoReboot by vm.autoReboot.collectAsStateWithLifecycle()
    val allowOtherModel by vm.allowOtherModel.collectAsStateWithLifecycle()
    val marker by vm.marker.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshMarker() }
    val run by vm.run.collectAsStateWithLifecycle()
    val wl by vm.wireless.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addPackages(uris)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addFolder(uri)
    }
    val pickPit = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.setPit(uri)
    }

    val readyCount = if (link == SamsungLink.USB) devices.count { it.ready } else if (wl.peer != null) 1 else 0
    val busy = run.active
    val hasWork = packages.isNotEmpty() || pit != null
    val readOnly by com.bareflash.app.data.Extras.readOnlyLock.collectAsStateWithLifecycle()
    val canStart = hasWork && readyCount > 0 && !busy && !readOnly
    val sets by com.bareflash.app.samsung.FlashHistory.sets.collectAsStateWithLifecycle()
    val history by com.bareflash.app.samsung.FlashHistory.entries.collectAsStateWithLifecycle()
    var saveSetOpen by remember { mutableStateOf(false) }
    var setName by remember { mutableStateOf("") }

    if (confirm) {
        NcAlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Start flashing?") },
            text = {
                val roles = packages.joinToString("\n") { "${it.role}  ${it.name}" }.ifBlank { "No packages" }
                val pitLine = pit?.name ?: "PIT from package"
                val linkLine = if (link == SamsungLink.USB) "USB" else "Wireless · ${wl.address ?: wl.peer ?: "port 13579"}"
                val rebootLine = if (autoReboot) "Reboot after flash" else "Stay in download mode"
                Text(
                    "$roles\n\n$pitLine\n$linkLine · $readyCount device(s)\n$rebootLine"
                )
            },
            confirmButton = { TextButton(onClick = { confirm = false; vm.startFlash() }) { Text("Flash") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            NcHeader(kicker = EngineMode.BROKKR.short, title = "Flash")
        }

        // -- connection ------------------------------------------------------
        item {
            NcSection("Connection") {
                NcChips(
                    value = link,
                    onChange = { if (!busy) vm.setLink(it) },
                    options = listOf(
                        NcChoice(SamsungLink.USB, "USB"),
                        NcChoice(SamsungLink.WIRELESS, "Wireless")
                    )
                )
                NcCaption(
                    if (link == SamsungLink.USB) "USB is for phones and tablets. Wireless is for watches."
                    else "Wireless listens for a watch in wireless download mode."
                )
                StatusLine(
                    ok = readyCount > 0,
                    text = when {
                        link == SamsungLink.WIRELESS && wl.peer != null -> "Watch connected: ${wl.peer}"
                        link == SamsungLink.WIRELESS && wl.error != null -> wl.error!!
                        link == SamsungLink.WIRELESS -> "Waiting for a watch on ${wl.address ?: "port 13579"}"
                        readyCount > 0 -> "$readyCount device(s) ready in download mode"
                        devices.any { it.inDownloadMode && !it.permitted } -> "Device found. Grant USB access on the Devices tab."
                        devices.isNotEmpty() -> "Samsung device found but not in download mode."
                        else -> EngineMode.BROKKR.connectHint
                    }
                )
            }
        }

        // -- packages --------------------------------------------------------
        item {
            NcSection("Packages") {
                if (packages.isEmpty()) {
                    NcCaption("Add the .tar / .md5 files from the firmware (BL, AP, CP, CSC).")
                }
                packages.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RoleBadge(p.role)
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.bodyMedium, color = Nc.Fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (p.size >= 0) Text(formatBytes(p.size), style = MaterialTheme.typography.bodySmall, color = Nc.Muted)
                        }
                        IconButton(onClick = { vm.removePackage(p) }, enabled = !busy) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove ${p.name}")
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = { pickFiles.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add files")
                    }
                    OutlinedButton(onClick = { pickFolder.launch(null) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Folder")
                    }
                }
                if (packages.isNotEmpty()) {
                    TextButton(onClick = vm::clearPackages, enabled = !busy, modifier = Modifier.padding(horizontal = 4.dp)) {
                        Icon(Icons.Filled.DeleteSweep, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Clear all")
                    }
                }
            }
        }

        // -- package sets ----------------------------------------------------
        item {
            NcSection("Package sets") {
                NcCaption("Save the packages above under a name to flash the same set again later.")
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = { setName = ""; saveSetOpen = true }, enabled = !busy && packages.isNotEmpty()) {
                        Text("Save current set")
                    }
                }
                sets.forEach { set ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(set.name, style = MaterialTheme.typography.bodyMedium, color = Nc.Fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${set.items.size} packages", style = MaterialTheme.typography.bodySmall, color = Nc.Muted)
                        }
                        TextButton(onClick = { vm.loadSet(set) }, enabled = !busy) { Text("Load") }
                        TextButton(onClick = { com.bareflash.app.samsung.FlashHistory.deleteSet(set.name) }, enabled = !busy) { Text("Delete") }
                    }
                }
            }
        }

        // -- manual PIT ------------------------------------------------------
        item {
            NcSection("Manual PIT") {
                NcCaption("Overrides the PIT detected from the selected packages. Leave empty unless you know you need it.")
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        pit?.name ?: "No manual PIT selected",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (pit != null) Nc.Fg else Nc.Muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (pit != null) {
                        TextButton(onClick = { vm.setPit(null) }, enabled = !busy) { Text("Clear") }
                    }
                    OutlinedButton(onClick = { pickPit.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Select") }
                }
            }
        }

        marker?.let { m ->
            if (!busy) item {
                com.bareflash.app.ui.WriteMarkerBanner(marker = m, onRerun = null, onDismiss = { vm.dismissMarker() })
            }
        }

        // -- options ---------------------------------------------------------
        item {
            NcSection("Options") {
                NcToggle(
                    label = "Auto reboot",
                    checked = autoReboot,
                    onCheckedChange = { if (!busy) vm.setAutoReboot(it) },
                    hint = "Reboot the device after flashing."
                )
                NcToggle(
                    label = "Allow other models",
                    checked = allowOtherModel,
                    onCheckedChange = { if (!busy) vm.setAllowOtherModel(it) },
                    hint = "Off: a flash stops if the firmware is built for a different model than the phone."
                )
            }
        }

        // -- start / stop ----------------------------------------------------
        item {
            if (busy) {
                Button(
                    onClick = vm::cancel,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
                ) {
                    Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop")
                }
            } else {
                Button(
                    onClick = { confirm = true },
                    enabled = canStart,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Start flash") }
                if (!canStart) {
                    NcCaption(
                        when {
                            readOnly -> "The read-only lock is on. Turn it off in Settings to flash."
                            !hasWork -> "Select at least one package before starting."
                            link == SamsungLink.WIRELESS -> "Wireless is enabled but no device is connected yet."
                            else -> "No permitted Samsung download-mode devices are ready."
                        },
                        Modifier.padding(top = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // -- history ---------------------------------------------------------
        if (history.isNotEmpty()) item {
            NcSection("Flash history") {
                history.take(10).forEach { e ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(
                            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                                .format(java.util.Date(e.at)) +
                                " \u00b7 ${e.outcome}${if (e.model.isNotBlank()) " \u00b7 ${e.model}" else ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = when (e.outcome) { "success" -> Nc.Ok; "failed" -> Nc.Err; else -> Nc.Warn }
                        )
                        Text(
                            e.packages.joinToString(", ").ifBlank { "PIT only" } +
                                " \u00b7 ${formatBytes(e.bytes)} in ${formatDuration(e.elapsedMs)}",
                            style = MaterialTheme.typography.bodySmall, color = Nc.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                TextButton(onClick = { com.bareflash.app.samsung.FlashHistory.clearEntries() }) { Text("Clear history") }
            }
        }
    }

    if (saveSetOpen) {
        NcAlertDialog(
            onDismissRequest = { saveSetOpen = false },
            title = { Text("Save package set") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = setName, onValueChange = { setName = it }, singleLine = true, label = { Text("Name") }
                )
            },
            confirmButton = {
                TextButton(enabled = setName.isNotBlank(), onClick = { vm.saveCurrentSet(setName); saveSetOpen = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saveSetOpen = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (ok) Nc.Ok else Nc.Subtle))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Nc.Fg)
    }
}

@Composable
private fun RoleBadge(role: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .width(56.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (role == "PKG") "FILE" else role.replace("_", " "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1, fontWeight = FontWeight.SemiBold
        )
    }
}

// ---------------------------------------------------------------------------
// Progress
// ---------------------------------------------------------------------------

@Composable
fun BrokkrProgressScreen(vm: SamsungViewModel) {
    val run by vm.run.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    // Wall-clock tick so the elapsed time keeps moving between native callbacks.
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(run.active) {
        while (run.active) {
            now = android.os.SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(500)
        }
    }

    if (run.phase == RunPhase.Idle) {
        Column(Modifier.fillMaxSize()) {
            NcHeader(kicker = EngineMode.BROKKR.short, title = "Progress")
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No flash is running.\nStart one from the Flash tab.", color = Nc.Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }

    val title = when (run.phase) {
        RunPhase.Preparing -> "Preparing"
        RunPhase.Flashing -> "Flashing in progress"
        RunPhase.Success -> "Flash complete"
        RunPhase.Failed -> "Flash failed"
        RunPhase.Cancelled -> "Flash stopped"
        RunPhase.Idle -> ""
    }
    val elapsed = if (run.active) now - run.startedAt else run.elapsedMs
    val accent = LocalNcAccent.current.color

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { NcHeader(kicker = EngineMode.BROKKR.short, title = title) }

        item {
            Column(Modifier.fillMaxWidth().ncTile().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (run.phase) {
                        RunPhase.Success -> Icon(Icons.Filled.CheckCircle, null, tint = Nc.Ok)
                        RunPhase.Failed -> Icon(Icons.Filled.Error, null, tint = Nc.Err)
                        RunPhase.Cancelled -> Icon(Icons.Filled.Stop, null, tint = Nc.Warn)
                        else -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            run.model.ifBlank { run.deviceIds.joinToString().ifBlank { "Samsung device" } },
                            style = MaterialTheme.typography.titleMedium, color = Nc.Fg, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(run.stage.ifBlank { run.message }, style = MaterialTheme.typography.bodySmall, color = Nc.Muted)
                    }
                    Text("${(run.fraction * 100).toInt()}%", style = MaterialTheme.typography.titleLarge, color = accent)
                }
                LinearProgressIndicator(progress = { run.fraction }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Written", "${formatBytes(run.overallDone)} / ${formatBytes(run.overallTotal)}")
                    Stat("Speed", if (run.speedBps > 1) formatSpeed(run.speedBps) else "—")
                    Stat("ETA", if (run.etaMs >= 0 && run.active) formatDuration(run.etaMs) else "—")
                    Stat("Elapsed", formatDuration(elapsed))
                }
            }
        }

        run.activeItem?.let { cur ->
            item {
                Column(Modifier.fillMaxWidth().ncTile().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Current partition", style = MaterialTheme.typography.labelLarge, color = accent)
                    Text(
                        if (cur.isPit) "PIT" else cur.partition.ifBlank { cur.fileName },
                        style = MaterialTheme.typography.titleMedium, color = Nc.Fg
                    )
                    val f = if (run.itemTotal > 0) (run.itemDone.toDouble() / run.itemTotal).coerceIn(0.0, 1.0).toFloat() else 0f
                    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                    Text(
                        "${formatBytes(run.itemDone)} / ${formatBytes(run.itemTotal)}",
                        style = MaterialTheme.typography.bodySmall, color = Nc.Muted
                    )
                }
            }
        }

        if (!run.active && run.message.isNotBlank()) {
            item {
                val bad = run.phase == RunPhase.Failed
                Column(Modifier.fillMaxWidth().ncTile().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(run.message, style = MaterialTheme.typography.bodyMedium, color = if (bad) Nc.Err else Nc.Fg)
                    run.explanation?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Nc.Muted) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (bad) {
                            OutlinedButton(onClick = {
                                clipboard.setText(AnnotatedString(run.message + "\n\n" + vm.logText()))
                            }) {
                                Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copy error")
                            }
                        }
                        TextButton(onClick = vm::dismissResult) { Text("Dismiss") }
                    }
                }
            }
        }

        if (run.deviceErrors.isNotEmpty()) {
            item {
                NcSection("Device errors") {
                    run.deviceErrors.forEach { (idx, msg) ->
                        val id = run.deviceIds.getOrNull(idx) ?: "#$idx"
                        Text(
                            "$id: $msg",
                            style = MaterialTheme.typography.bodySmall, color = Nc.Err,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        if (run.plan.isNotEmpty()) {
            item {
                Text(
                    "Flash plan",
                    style = MaterialTheme.typography.labelLarge, color = accent,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
            itemsIndexed(run.plan) { _, e ->
                Row(
                    Modifier.fillMaxWidth().ncTile().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (e.state) {
                        PlanState.Done -> Icon(Icons.Filled.CheckCircle, null, tint = Nc.Ok, modifier = Modifier.size(22.dp))
                        PlanState.Active -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        PlanState.Pending -> Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = Nc.Subtle, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (e.isPit) "PIT" else e.partition.ifBlank { e.fileName },
                            style = MaterialTheme.typography.bodyMedium, color = Nc.Fg, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (e.source.isNotBlank()) {
                            Text(e.source, style = MaterialTheme.typography.bodySmall, color = Nc.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(formatBytes(e.size), style = MaterialTheme.typography.bodySmall, color = Nc.Muted)
                }
            }
        }

        if (run.active) {
            item {
                Button(
                    onClick = vm::cancel,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
                ) { Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop") }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Nc.Muted)
        Text(value, style = MaterialTheme.typography.bodySmall, color = Nc.Fg, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------
// Devices
// ---------------------------------------------------------------------------

@Composable
fun BrokkrDevicesScreen(vm: SamsungViewModel) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val link by vm.link.collectAsStateWithLifecycle()
    val wl by vm.wireless.state.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            NcHeader(kicker = EngineMode.BROKKR.short, title = "Devices", trailing = {
                IconButton(onClick = { vm.refreshDevices(forcePrompt = true) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Rescan")
                }
            })
        }

        item {
            NcSection("USB") {
                if (devices.isEmpty()) {
                    NcCaption("No Samsung device on the bus. Boot it into download mode and use a data cable. Charge-only cables never show up.")
                }
                devices.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (d.ready) Nc.Ok else Nc.Warn))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (d.inDownloadMode) d.product else "${d.product} (Not in download mode)",
                                style = MaterialTheme.typography.bodyMedium, color = Nc.Fg
                            )
                            Text(
                                "%04x:%04x · %s · %s".format(d.vid, d.pid, d.id, if (d.permitted) "access granted" else "needs access"),
                                style = MaterialTheme.typography.bodySmall, color = Nc.Muted
                            )
                        }
                        if (d.inDownloadMode && !d.permitted) {
                            TextButton(onClick = { vm.refreshDevices(forcePrompt = true) }) { Text("Grant") }
                        }
                    }
                }
                NcCaption("Devices in download mode: ${devices.count { it.ready }} ready.")
            }
        }

        item {
            NcSection("Wireless") {
                if (link != SamsungLink.WIRELESS) {
                    NcCaption("Switch Connection to Wireless on the Flash tab to listen for a watch.")
                } else {
                    StatusLine(ok = wl.peer != null, text = when {
                        wl.peer != null -> "Connected: ${wl.peer}"
                        wl.error != null -> wl.error!!
                        wl.listening -> "Listening, waiting for the watch…"
                        else -> "Starting listener…"
                    })
                    wl.address?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.titleMedium, color = Nc.Fg,
                            fontFamily = PlexMono,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                    NcCaption("Phone and watch must be on the same network. Enter this address on the watch in wireless download mode.")
                }
            }
        }

        item {
            NcSection("How to enter download mode") {
                NcCaption(EngineMode.BROKKR.connectHint)
                NcCaption("Samsung IDs matched: 04e8:6601, 04e8:685d, 04e8:68c3.")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Log
// ---------------------------------------------------------------------------

@Composable
fun BrokkrLogScreen(vm: SamsungViewModel) {
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
        NcHeader(kicker = EngineMode.BROKKR.short, title = "Live console", trailing = {
            Row {
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
                IconButton(onClick = vm::clearLog) { Icon(Icons.Filled.DeleteSweep, "Clear console") }
            }
        })
        Box(
            Modifier.weight(1f).fillMaxWidth().ncTile().padding(10.dp)
        ) {
            if (lines.isEmpty()) {
                Text("Native and transport logs will stream here during a run.", color = Nc.Muted, style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(lines) { _, l ->
                        Text(
                            l.text,
                            fontFamily = PlexMono, fontSize = 11.sp, lineHeight = 15.sp,
                            // native levels: 0 trace, 1 debug, 2 info, 3 warn, 4 error, 5 critical
                            color = when {
                                l.level >= 4 -> Nc.Err
                                l.level == 3 -> Nc.Warn
                                l.level <= 1 -> Nc.Muted
                                else -> Nc.Fg
                            }
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// formatting
// ---------------------------------------------------------------------------

internal fun formatBytes(b: Long): String = when {
    b < 0 -> "—"
    b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
    else -> "$b B"
}

internal fun formatSpeed(bps: Double): String =
    if (bps >= 1_000_000) String.format(Locale.US, "%.1f MB/s", bps / 1_000_000.0)
    else String.format(Locale.US, "%.0f KB/s", bps / 1_000.0)

internal fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
}
