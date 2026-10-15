package com.bareflash.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bareflash.app.AppGraph
import com.bareflash.app.engine.Catalogs
import com.bareflash.app.engine.EngineMode
import com.bareflash.app.engine.QdlRules
import com.bareflash.app.catalog.McCommand
import com.bareflash.app.console.CommandRules
import com.bareflash.app.console.ConsoleViewModel
import com.bareflash.app.ui.theme.Nc

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CommandsScreen(vm: ConsoleViewModel) {
    val running by vm.running.collectAsStateWithLifecycle()
    val settings by AppGraph.settings.state.collectAsStateWithLifecycle()
    val partitions by vm.partitions.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val qdl by vm.qdlSession.state.collectAsStateWithLifecycle()
    val categories = remember(mode) { Catalogs.categories(mode) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable(mode) { mutableStateOf<String?>(null) } // null = All
    var selected by remember { mutableStateOf<McCommand?>(null) }
    val presets by com.bareflash.app.data.CommandPresets.items.collectAsStateWithLifecycle()
    var presetDraft by remember { mutableStateOf<String?>(null) }
    var presetName by remember { mutableStateOf("") }
    var showBackup by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showScatter by remember { mutableStateOf(false) }
    var extraPreloader by remember { mutableStateOf(false) }
    var extraFooter by remember { mutableStateOf(false) }
    var extraRpmb by remember { mutableStateOf(false) }
    val queued by vm.queue.collectAsStateWithLifecycle()
    val queueStatus by vm.queueStatus.collectAsStateWithLifecycle()
    val deck = rememberLazyListState()
    val selectedIndex = categories.indexOfFirst { it.id == category }.coerceAtLeast(0)

    val filtered = remember(query, category, mode) {
        Catalogs.groups(mode)
            .filter { category == null || it.title == category }
            .flatMap { it.commands }
            .filter { c ->
                query.isBlank() ||
                    c.title.contains(query, ignoreCase = true) ||
                    c.cmd.contains(query, ignoreCase = true) ||
                    c.description.contains(query, ignoreCase = true)
            }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NcHeader(kicker = mode.short, title = "Commands")
        if (mode == EngineMode.MTK) {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().ncTile().ncClickable { showBackup = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Full backup", style = MaterialTheme.typography.titleSmall, color = Nc.Fg)
                    Text(
                        "Partition table, every partition, checksums. One new folder.",
                        style = MaterialTheme.typography.bodySmall, color = Nc.Muted
                    )
                }
            }
        }
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mode == EngineMode.MTK) {
                item {
                    androidx.compose.material3.AssistChip(onClick = { showScatter = true }, label = { Text("Flash firmware folder") })
                }
                item {
                    androidx.compose.material3.AssistChip(onClick = { showSpeed = true }, label = { Text("Speed test") })
                }
            }
            item {
                androidx.compose.material3.AssistChip(
                    onClick = { showQueue = true },
                    label = { Text(queueStatus ?: if (queued.isEmpty()) "Job queue" else "Job queue (${queued.size})") }
                )
            }
        }
        val modePresets = presets.filter { it.mode == mode.id }
        if (modePresets.isNotEmpty()) {
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(modePresets.size) { i ->
                    val pr = modePresets[i]
                    androidx.compose.material3.InputChip(
                        selected = false,
                        onClick = { vm.runPreset(pr) },
                        label = { Text(pr.name) },
                        trailingIcon = {
                            androidx.compose.material3.Icon(
                                Icons.Filled.Close, "Delete preset ${pr.name}",
                                Modifier.size(16.dp).ncClickable { com.bareflash.app.data.CommandPresets.remove(pr) }
                            )
                        }
                    )
                }
            }
        }
        CommandsSearchField(query = query, onQueryChange = { query = it })
        CategoryChips(categories = categories, state = deck, selectedIndex = selectedIndex, onSelect = { category = categories[it].id })

        if (filtered.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Text(
                    "No commands match that search.",
                    style = MaterialTheme.typography.bodyLarge, color = Nc.Muted,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 32.dp)
                )
            }
        } else {
            CommandsList(
                filtered = filtered,
                pinnedCommands = AppGraph.settings.pinsFor(mode, settings.pinned),
                running = running,
                onSelect = { selected = it },
                onTogglePin = { AppGraph.settings.togglePinned(mode, it) },
                modifier = Modifier.weight(1f)
            )
        }
    }

    selected?.let { cmd ->
        CommandReviewDialog(
            command = cmd,
            protocolNotes = if (mode == EngineMode.QDL) QdlRules.notes(qdl)
                else CommandRules.applyDefaults(cmd.cmd, AppGraph.settings.state.value.protocols).notes,
            partitions = partitions,
            onDismiss = { selected = null },
            onSavePreset = { built -> presetName = cmd.title; presetDraft = built },
            onRun = { finalCmd ->
                selected = null
                vm.requestRun(finalCmd)
            }
        )
    }

    presetDraft?.let { built ->
        NcAlertDialog(
            onDismissRequest = { presetDraft = null },
            title = { Text("Save as preset") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedTextField(
                        value = presetName, onValueChange = { presetName = it },
                        singleLine = true, label = { Text("Name") }
                    )
                    Text("$ $built", style = MaterialTheme.typography.bodySmall, color = Nc.Muted)
                    Text(
                        "Files attached from outside the Bareflash folder are remembered by the permission Android gave Bareflash. " +
                            "If that permission is gone the preset tells you which file is missing.",
                        style = MaterialTheme.typography.bodySmall, color = Nc.Muted
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = presetName.isNotBlank(),
                    onClick = {
                        com.bareflash.app.data.CommandPresets.add(
                            presetName, built, mode.id, com.bareflash.app.dump.DumpBridge.pickedFor(built)
                        )
                        presetDraft = null
                        selected = null
                    }
                ) { Text("Save") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { presetDraft = null }) { Text("Cancel") } }
        )
    }

    if (showBackup) {
        val blocker = vm.fullBackupBlocker()
        NcAlertDialog(
            onDismissRequest = { showBackup = false },
            title = { Text("Full backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        blocker ?: "Reads the partition table, checks free space, then dumps every partition into a new folder " +
                            "in your dump folder and saves a MANIFEST.json and SHA256SUMS. Nothing is written to the device. " +
                            "A full backup can take a long time and use many gigabytes."
                    )
                    if (blocker == null) {
                        Text("Also read, after the main backup:", color = Nc.Muted, fontSize = 13.sp)
                        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(extraPreloader, { extraPreloader = it })
                            Text("Preloader", color = Nc.Fg, fontSize = 14.sp)
                        }
                        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(extraFooter, { extraFooter = it })
                            Text("Crypto footer", color = Nc.Fg, fontSize = 14.sp)
                        }
                        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(extraRpmb, { extraRpmb = it })
                            Text("RPMB", color = Nc.Fg, fontSize = 14.sp)
                        }
                        if (extraPreloader || extraFooter || extraRpmb) {
                            Text(
                                "An extra the device does not support is skipped and left out of the manifest.",
                                color = Nc.Subtle, fontSize = 12.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = blocker == null,
                    onClick = {
                        showBackup = false
                        vm.startFullBackup(ConsoleViewModel.FullBackupExtras(extraPreloader, extraFooter, extraRpmb))
                    }
                ) { Text("Start") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { showBackup = false }) { Text("Cancel") } }
        )
    }

    if (showSpeed) SpeedTestDialog(vm) { showSpeed = false }
    if (showQueue) QueueDialog(vm) { showQueue = false }
    if (showScatter) ScatterScreen(vm) { showScatter = false }
}
