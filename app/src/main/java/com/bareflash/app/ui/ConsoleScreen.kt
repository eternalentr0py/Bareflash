package com.bareflash.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import com.bareflash.app.console.ConsoleStatus
import com.bareflash.app.console.LinkMode
import com.bareflash.app.console.LinkState
import com.bareflash.app.ui.theme.Nc
import com.bareflash.app.ui.theme.PlexMono
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bareflash.app.AppGraph
import com.bareflash.app.console.ConsoleViewModel
import com.bareflash.app.dump.DumpBridge

/**
 * The Console tab. This file is the orchestrator only: top-level state
 * (which dialog/screen is open, snackbar wiring, the destructive-command
 * confirmations) plus the device chip. The two heavy pieces of UI live in
 * their own files:
 * - [TerminalCard] (log + input line + status LED) -> ConsoleTerminalCard.kt
 * - [ConsoleActionRow] (Detect/Dump/Flash/Table + pinned commands)
 *   -> ConsoleActionRow.kt
 * - [PartitionDialog] (name entry for Dump/Flash, live GPT chips) -> ConsoleDialogs.kt
 * - [PartitionTableSheet] (full GPT with dump/flash/erase) -> PartitionTableSheet.kt
 */

private enum class PartitionAction { Dump, Flash }

@Composable
fun ConsoleScreen(vm: ConsoleViewModel) {
    var showFiles by rememberSaveable { mutableStateOf(false) }
    if (showFiles) FilesScreen(onBack = { showFiles = false })
    else ConsoleContent(vm, onOpenFiles = { showFiles = true })
}

@Composable
private fun ConsoleContent(vm: ConsoleViewModel, onOpenFiles: () -> Unit) {
    val lines by vm.terminal.lines.collectAsStateWithLifecycle()
    val text by vm.terminal.text.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val lastError by vm.lastError.collectAsStateWithLifecycle()
    val history by AppGraph.settings.history.collectAsStateWithLifecycle()
    val settings by AppGraph.settings.state.collectAsStateWithLifecycle()

    val partitions by vm.partitions.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val marker by vm.writeMarker.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(mode, running) { vm.refreshWriteMarker() }
    LaunchedEffect(settings.terminal.scrollbackLines) { vm.terminal.setScrollback(settings.terminal.scrollbackLines) }

    var partitionAction by remember { mutableStateOf<PartitionAction?>(null) }
    var chosenPartition by remember { mutableStateOf<String?>(null) }
    var confirmCancel by remember { mutableStateOf(false) }
    var showTable by remember { mutableStateOf(false) }
    val flashLauncher = rememberLauncherForActivityResult(
        OpenFrom(com.bareflash.app.dump.DumpBridge.currentUri())
    ) { uri ->
        val p = chosenPartition; chosenPartition = null
        if (p != null) vm.flashPartition(p, uri)
    }

    // Status toasts (busy, blockers, …) are shown by MainShell's AppToastHost.
    // USB connect/disconnect is island + terminal only — no popup.
    // Command errors: use Terminal overflow "Copy error details".

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NcHeader(
                kicker = mode.short,
                title = "Console",
                trailing = {
                    val readOnly by com.bareflash.app.data.Extras.readOnlyLock.collectAsStateWithLifecycle()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (readOnly) ReadOnlyChip()
                        ModeChip(status, mode)
                    }
                }
            )

            if (mode == com.bareflash.app.engine.EngineMode.QDL) {
                val qdl by vm.qdlSession.state.collectAsStateWithLifecycle()
                ModeFlagChips(
                    dryRun = qdl.dryRun,
                    finalize = qdl.finalizeProvisioning,
                    onDryRunOff = { vm.qdlSession.update { it.copy(dryRun = false) } },
                    onFinalizeOff = { vm.qdlSession.update { it.copy(finalizeProvisioning = false) } }
                )
            }

            ConsoleActionRow(
                vm = vm,
                running = running,
                detecting = status.state == LinkState.Detecting,
                mode = mode,
                pinnedCommands = AppGraph.settings.pinsFor(mode, settings.pinned),
                history = AppGraph.settings.commandsFor(mode, history),
                onDumpRequested = { partitionAction = PartitionAction.Dump },
                onFlashRequested = { partitionAction = PartitionAction.Flash },
                onTableRequested = { showTable = true },
                onRequestCancel = { confirmCancel = true }
            )

            marker?.takeIf { it.engine == mode.short }?.let { m ->
                if (!running) WriteMarkerBanner(
                    marker = m,
                    onRerun = if (m.canRerun) ({ vm.rerunMarked() }) else null,
                    onDismiss = { vm.dismissWriteMarker() }
                )
            }
            summary?.let { sm ->
                JobSummaryCard(
                    s = sm,
                    onDismiss = { vm.dismissSummary() },
                    onResume = { rel -> vm.dismissSummary(); vm.resumeDump(rel) }
                )
            }

            TerminalCard(
                vm = vm,
                text = text,
                lines = lines,
                t = settings.terminal,
                mode = mode,
                running = running,
                hasError = lastError != null,
                history = AppGraph.settings.commandsFor(mode, history),
                partitions = partitions,
                onRequestCancel = { confirmCancel = true }
            )
        }

    }

    // Destructive command confirmation (unchanged safety step).
    pending?.let { p ->
        val offer = remember(p, partitions) {
            com.bareflash.app.console.SafetyNet.offer(
                mode, p, com.bareflash.app.dump.DumpBridge.isEnabled(),
                com.bareflash.app.dump.DumpBridge.availableBytes()
            )
        }
        val attached = status.state == LinkState.Connected || status.state == LinkState.Reconnecting
        val findings = remember(p) {
            com.bareflash.app.console.Preflight.forWrite(appCtx, attached, mode == com.bareflash.app.engine.EngineMode.MTK)
        }
        WriteConfirmDialog(
            p, offer, findings,
            onRun = { vm.confirmPending(it) },
            onCancel = { vm.dismissPending() }
        )
    }

    if (confirmCancel) {
        NcAlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Stop the running command?") },
            text = {
                Text("Stopping mid-transfer can leave a partial file or the device in an unstable state. Letting it finish is usually safer.")
            },
            confirmButton = {
                TextButton(onClick = { confirmCancel = false; vm.cancelCommand() }) { Text("Stop") }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep running") } }
        )
    }

    partitionAction?.let { action ->
        val lastName = if (action == PartitionAction.Dump) {
            settings.general.lastDumpPartition
        } else {
            settings.general.lastFlashPartition
        }
        PartitionDialog(
            title = if (action == PartitionAction.Dump) "Dump partition" else "Flash partition",
            confirmLabel = if (action == PartitionAction.Dump) "Dump" else "Choose file",
            isValid = vm::isValidPartitionName,
            initialName = lastName,
            partitions = partitions,
            dumpsDir = if (action == PartitionAction.Dump) DumpBridge.localDumps() else null,
            sectorSize = settings.protocols.sectorSize,
            onDismiss = { partitionAction = null },
            onConfirm = { name ->
                partitionAction = null
                AppGraph.settings.update { s ->
                    s.copy(
                        general = if (action == PartitionAction.Dump) {
                            s.general.copy(lastDumpPartition = name)
                        } else {
                            s.general.copy(lastFlashPartition = name)
                        }
                    )
                }
                if (action == PartitionAction.Dump) {
                    vm.dumpPartition(name)
                } else {
                    chosenPartition = name
                    flashLauncher.launch(arrayOf("*/*"))
                }
            }
        )
    }

    if (showTable) {
        PartitionTableSheet(
            partitions = partitions,
            busy = running,
            onDismiss = { showTable = false },
            onDump = { p ->
                showTable = false
                AppGraph.settings.update { s -> s.copy(general = s.general.copy(lastDumpPartition = p.name)) }
                vm.dumpPartition(p.name)
            },
            onDumpMany = { list ->
                showTable = false
                vm.dumpPartitions(list.map { it.name })
            },
            onFlash = { p ->
                showTable = false
                AppGraph.settings.update { s -> s.copy(general = s.general.copy(lastFlashPartition = p.name)) }
                chosenPartition = p.name
                flashLauncher.launch(arrayOf("*/*"))
            },
            onErase = { p ->
                showTable = false
                vm.requestRun("e ${p.name}")
            },
            onPrintGpt = { vm.requestRun("printgpt") }
        )
    }
}

/**
 * Sticky QDL session switches that change what a "successful" run means.
 * Dry run sends nothing; finalize provisioning is possibly irreversible.
 * Each chip turns its switch off in one tap.
 */
@Composable
private fun ModeFlagChips(dryRun: Boolean, finalize: Boolean, onDryRunOff: () -> Unit, onFinalizeOff: () -> Unit) {
    if (!dryRun && !finalize) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (dryRun) FlagChip("DRY RUN \u2014 nothing is sent \u00b7 turn off", Nc.Warn, onDryRunOff)
        if (finalize) FlagChip("FINALIZE PROVISIONING \u00b7 turn off", Nc.Warn, onFinalizeOff)
    }
}

@Composable
private fun FlagChip(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        label,
        style = TextStyle(fontFamily = PlexMono, fontSize = 11.sp),
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .border(1.dp, color, CircleShape)
            .ncClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/** Top-right state chip: USB glyph + PRELOADER / BROM / DA / SCAN / IDLE. */
@Composable
private fun ModeChip(status: ConsoleStatus, engine: com.bareflash.app.engine.EngineMode) {
    val label = when (status.state) {
        LinkState.Detecting -> "SCAN"
        LinkState.Reconnecting -> "WAIT"
        LinkState.Disconnected -> "IDLE"
        LinkState.Error -> "ERR"
        else -> if (engine == com.bareflash.app.engine.EngineMode.QDL) "EDL" else when (status.mode) {
            LinkMode.Preloader -> "PRELOADER"
            LinkMode.BootRom -> "BROM"
            LinkMode.Da -> "DA"
            LinkMode.Unknown -> "LINK"
        }
    }
    Row(
        Modifier
            .clip(CircleShape)
            .background(Nc.Raised)
            .border(1.dp, Nc.BorderSoft, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Filled.Usb, contentDescription = null, tint = Nc.Muted, modifier = Modifier.size(14.dp))
        Text(label, style = TextStyle(fontFamily = PlexMono, fontSize = 11.sp), color = Nc.Muted, maxLines = 1)
    }
}

/** Shown in the Console header while the read-only lock refuses every write, erase and flash. */
@Composable
private fun ReadOnlyChip() {
    Row(
        Modifier
            .clip(CircleShape)
            .background(Nc.Raised)
            .border(1.dp, Nc.Warn.copy(alpha = 0.6f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("READ-ONLY", style = TextStyle(fontFamily = PlexMono, fontSize = 11.sp), color = Nc.Warn, maxLines = 1)
    }
}
