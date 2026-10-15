package com.bareflash.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bareflash.app.console.ConsoleViewModel
import com.bareflash.app.console.DumpSpaceGuard
import com.bareflash.app.data.Extras
import com.bareflash.app.dump.DumpBridge
import com.bareflash.app.dump.DumpCompare
import com.bareflash.app.dump.DumpIntegrity
import com.bareflash.app.dump.DumpStorage
import com.bareflash.app.dump.PartialDumps
import com.bareflash.app.dump.Restore
import com.bareflash.app.export.DeviceReport
import com.bareflash.app.ui.theme.LocalNcAccent
import com.bareflash.app.ui.theme.Nc
import com.bareflash.app.ui.theme.PlexMono
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

// ---- verify now -----------------------------------------------------------------

/** What the badge under a file says after "Verify now". [state]: 0 running, 1 matches, 2 differs, 3 no saved hash. */
internal data class VerifyBadge(val state: Int, val progress: Float, val text: String)

/** Re-hashes files against their saved SHA-256, streaming, with progress. Not a size check. */
internal class Verifier(private val scope: CoroutineScope) {
    var badges by mutableStateOf(mapOf<String, VerifyBadge>())
        private set

    private fun put(name: String, b: VerifyBadge) { badges = badges + (name to b) }

    fun run(names: List<String>) {
        scope.launch {
            for (n in names) {
                put(n, VerifyBadge(0, 0f, "Verifying 0%"))
                val manifest = withContext(Dispatchers.IO) { DumpIntegrity.readManifest(n) }
                val size = withContext(Dispatchers.IO) { DumpBridge.size(n) }
                val limit = if (size > 0L) size else Long.MAX_VALUE
                val progress: (Long) -> Unit = { done ->
                    if (size > 0L) put(n, VerifyBadge(0, done.toFloat() / size, "Verifying ${done * 100 / size}%"))
                }
                // With region hashes saved, one pass also finds where a mismatch is.
                val regional = manifest != null && manifest.chunks.isNotEmpty()
                val scan = if (regional) withContext(Dispatchers.IO) { DumpIntegrity.scanRel(n, limit, progress) } else null
                val sha = if (regional) scan?.sha256 else withContext(Dispatchers.IO) { DumpIntegrity.sha256Rel(n, limit, progress) }
                put(
                    n,
                    when {
                        sha == null -> VerifyBadge(2, 1f, "Could not be read")
                        manifest == null -> VerifyBadge(3, 1f, "No saved hash. Computed SHA-256 ${sha.take(12)}\u2026")
                        manifest.bytes != size -> VerifyBadge(2, 1f, "Does not match: the size changed since it was hashed")
                        sha.equals(manifest.sha256, ignoreCase = true) -> VerifyBadge(1, 1f, "Verified. SHA-256 ${sha.take(12)}\u2026")
                        else -> {
                            val bad = if (scan != null) DumpIntegrity.badChunks(manifest, scan) else null
                            val where = if (!bad.isNullOrEmpty()) DumpIntegrity.describeRegions(bad, manifest.chunkSize, size) else ""
                            VerifyBadge(2, 1f, if (where.isNotEmpty()) "Does not match its saved SHA-256. Differs in $where" else "Does not match its saved SHA-256")
                        }
                    }
                )
            }
        }
    }
}

@Composable
internal fun rememberVerifier(): Verifier {
    val scope = rememberCoroutineScope()
    return remember { Verifier(scope) }
}

// ---- small pieces -----------------------------------------------------------------

@Composable
private fun ToolChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    val accent = LocalNcAccent.current
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(12.dp)).ncRaised().ncClickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = accent.color, modifier = Modifier.size(16.dp))
        Text(label, fontSize = 13.sp, color = Nc.Fg)
    }
}

@Composable
private fun Bar(fraction: Float) {
    val accent = LocalNcAccent.current
    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Nc.PanelHigh)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).background(accent.color))
    }
}

private val mono = TextStyle(fontFamily = PlexMono, fontSize = 11.sp)

// ---- the tools row above the list ------------------------------------------------

/**
 * Backups, Storage and Report, plus a banner when a multi-file read was cut short.
 * [files] is the dump folder listing the screen already has.
 */
@Composable
internal fun DumpsToolsRow(
    vm: ConsoleViewModel,
    files: List<DumpBridge.Entry>,
    partials: Map<String, PartialDumps.Record>,
    onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showBackups by remember { mutableStateOf(false) }
    var showStorage by remember { mutableStateOf(false) }
    var restoreFolder by remember { mutableStateOf<String?>(null) }
    var reportBusy by remember { mutableStateOf(false) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolChip(Icons.Filled.Restore, "Backups") { showBackups = true }
        ToolChip(Icons.Filled.Storage, "Storage") { showStorage = true }
        ToolChip(Icons.Filled.Description, if (reportBusy) "Building\u2026" else "Report") {
            if (!reportBusy) {
                reportBusy = true
                scope.launch {
                    val intent = withContext(Dispatchers.IO) { runCatching { DeviceReport.shareIntent(ctx, vm) }.getOrNull() }
                    reportBusy = false
                    if (intent != null) ctx.startActivity(intent) else vm.log("The device report could not be built.\n")
                }
            }
        }
    }

    // A multi-file read that was cut short: one banner, one button.
    partials.values.filter { it.group.isNotEmpty() }.groupBy { it.group }.forEach { (group, recs) ->
        Row(
            Modifier.fillMaxWidth().ncTile().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text("Interrupted read", fontSize = 14.sp, color = Nc.Fg)
                Text("${recs.size} files still to read. Finished files are kept and skipped.", style = mono, color = Nc.Warn)
            }
            if (vm.canResume(recs.first().rel)) {
                TextButton(onClick = { vm.resumeGroup(group) }) { Text("Resume") }
            }
        }
    }

    if (showBackups) {
        BackupsDialog(files, onRestore = { restoreFolder = it; showBackups = false }, onClose = { showBackups = false })
    }
    restoreFolder?.let { RestoreDialog(vm, folder = it, rel = null, onClose = { restoreFolder = null }) }
    if (showStorage) StorageDialog(files, onChanged = { onChanged(); vm.notifyDumpsChanged() }, onClose = { showStorage = false })
}

// ---- backups list ----------------------------------------------------------------

@Composable
private fun BackupsDialog(files: List<DumpBridge.Entry>, onRestore: (String) -> Unit, onClose: () -> Unit) {
    val folders = remember(files) {
        files.filter { it.name.endsWith("/MANIFEST.json") }.sortedByDescending { it.modified }
            .map { it.name.substringBeforeLast('/') to files.count { f -> f.name.startsWith(it.name.substringBeforeLast('/') + "/") && !DumpIntegrity.isSidecar(f.name) } }
    }
    NcAlertDialog(
        onDismissRequest = onClose,
        title = { Text("Full backups") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (folders.isEmpty()) Text("No full backup with a manifest was found in the dump folder.", color = Nc.Muted)
                folders.forEach { (name, count) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(name, fontSize = 14.sp, color = Nc.Fg)
                            Text("$count files", style = mono, color = Nc.Muted)
                        }
                        TextButton(onClick = { onRestore(name) }) { Text("Restore") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

// ---- restore -----------------------------------------------------------------------

/**
 * Restore a backup folder ([folder]) or one image ([rel]). Nothing is sent until every file
 * has been re-hashed against its manifest and, when the chip differs or is unknown, a phrase
 * has been typed. The write itself runs through the normal write path.
 */
@Composable
internal fun RestoreDialog(vm: ConsoleViewModel, folder: String?, rel: String?, onClose: () -> Unit) {
    val target by vm.target.collectAsStateWithLifecycle()
    val readOnly by Extras.readOnlyLock.collectAsStateWithLifecycle()
    var plan by remember { mutableStateOf<Restore.Plan?>(null) }
    var missing by remember { mutableStateOf(false) }
    var verdict by remember { mutableStateOf<Restore.Verdict?>(null) }
    var verifying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var progressText by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var partition by remember { mutableStateOf("") }
    var readBack by remember { mutableStateOf(true) }
    val cancel = remember { AtomicBoolean(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(folder, rel) {
        val p = withContext(Dispatchers.IO) {
            if (folder != null) Restore.planFolder(folder) else rel?.let { Restore.planImage(it) }
        }
        if (p == null) missing = true else { plan = p; partition = p.partition.orEmpty() }
    }

    val p = plan
    val chip = p?.let { Restore.chipCheck(it, target) }
    val needTyped = p != null && (chip !is Restore.ChipCheck.Same || !p.hasManifest)
    val phrase = "RESTORE ANYWAY"
    val typedOk = !needTyped || typed.trim().equals(phrase, ignoreCase = true)
    val verified = p != null && (verdict?.ok == true || !p.hasManifest)
    val canRun = p != null && verified && typedOk && !readOnly && !verifying && (p.isFolder || partition.isNotBlank())

    NcAlertDialog(
        onDismissRequest = { cancel.set(true); onClose() },
        title = { Text(if (folder != null) "Restore $folder" else "Restore ${rel?.substringAfterLast('/').orEmpty()}") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (missing) Text("The manifest of this backup could not be read, so it cannot be restored from here.", color = Nc.Err)
                if (p == null && !missing) Text("Reading the manifest\u2026", color = Nc.Muted)
                if (p != null) {
                    Text(
                        if (p.isFolder) "${p.items.size} files are written back to the device. Nothing is sent until the files check out."
                        else "This image is written to one partition. Nothing is sent until it checks out.",
                        color = Nc.Fg, fontSize = 13.sp
                    )
                    if (!p.isFolder) {
                        OutlinedTextField(
                            value = partition, onValueChange = { partition = it.trim() }, singleLine = true,
                            label = { Text("Partition to write") }
                        )
                    }
                    when (chip) {
                        is Restore.ChipCheck.Different -> Text(chip.message, color = Nc.Err, fontSize = 13.sp)
                        is Restore.ChipCheck.Unknown -> Text(chip.message, color = Nc.Warn, fontSize = 13.sp)
                        else -> Text("The chip matches the device that made this backup.", color = Nc.Ok, fontSize = 13.sp)
                    }
                    if (!p.hasManifest) {
                        Text("There is no manifest for this image, so its contents cannot be checked.", color = Nc.Warn, fontSize = 13.sp)
                    }
                    if (p.hasManifest) {
                        if (verifying) {
                            Bar(progress)
                            Text(progressText, style = mono, color = Nc.Muted)
                        }
                        verdict?.let { v ->
                            if (v.ok) Text("All ${v.checked} files match their saved SHA-256.", color = Nc.Ok, fontSize = 13.sp)
                            else Text("Blocked. " + v.problems.joinToString(" "), color = Nc.Err, fontSize = 13.sp)
                        }
                        TextButton(
                            enabled = !verifying,
                            onClick = {
                                cancel.set(false)
                                verifying = true; verdict = null; progress = 0f
                                scope.launch {
                                    val total = p.items.sumOf { it.bytes.coerceAtLeast(0L) }.coerceAtLeast(1L)
                                    var base = 0L
                                    val v = withContext(Dispatchers.IO) {
                                        Restore.verify(
                                            p,
                                            { file, done, tot ->
                                                progress = (base + done).toFloat() / total
                                                progressText = "$file ${DumpSpaceGuard.formatBytes(done)} of ${DumpSpaceGuard.formatBytes(tot)}"
                                                if (done >= tot) base += tot
                                            },
                                            { cancel.get() }
                                        )
                                    }
                                    verdict = v
                                    verifying = false
                                }
                            }
                        ) { Text(if (verdict == null) "Check the files" else "Check again") }
                    }
                    if (p.isFolder) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = readBack, onCheckedChange = { readBack = it })
                            Text("Read back partitions up to 256 MiB afterwards and compare", fontSize = 13.sp, color = Nc.Fg)
                        }
                    }
                    if (needTyped) {
                        OutlinedTextField(
                            value = typed, onValueChange = { typed = it }, singleLine = true,
                            label = { Text("Type \"$phrase\" to continue") }
                        )
                    }
                    if (readOnly) Text("The read-only lock is on. Turn it off in Settings to restore.", color = Nc.Err, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canRun,
                onClick = {
                    val pl = plan ?: return@TextButton
                    vm.runRestore(pl, partition.ifBlank { null }, readBack)
                    onClose()
                }
            ) { Text("Restore") }
        },
        dismissButton = { TextButton(onClick = { cancel.set(true); onClose() }) { Text("Cancel") } }
    )
}

// ---- compare two dumps --------------------------------------------------------------

@Composable
internal fun CompareDialog(a: String, b: String, onClose: () -> Unit) {
    var result by remember { mutableStateOf<DumpCompare.Result?>(null) }
    var progress by remember { mutableStateOf(0f) }
    val cancel = remember { AtomicBoolean(false) }
    LaunchedEffect(a, b) {
        result = withContext(Dispatchers.IO) {
            DumpCompare.compare(a, b, { d, t -> if (t > 0L) progress = d.toFloat() / t }, { cancel.get() })
        }
    }
    NcAlertDialog(
        onDismissRequest = { cancel.set(true); onClose() },
        title = { Text("Compare") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(a.substringAfterLast('/'), style = mono, color = Nc.Muted)
                Text(b.substringAfterLast('/'), style = mono, color = Nc.Muted)
                val r = result
                if (r == null) {
                    Bar(progress)
                    Text("Comparing\u2026 ${(progress * 100).toInt()}%", color = Nc.Muted, fontSize = 13.sp)
                } else {
                    Text(r.message, color = if (r.identical) Nc.Ok else Nc.Warn, fontSize = 14.sp)
                    if (!r.identical && r.sizeA != r.sizeB) {
                        Text("Sizes: ${r.sizeA} and ${r.sizeB} bytes.", style = mono, color = Nc.Muted)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { cancel.set(true); onClose() }) { Text("Close") } }
    )
}

// ---- storage and retention -----------------------------------------------------------

@Composable
private fun StorageDialog(files: List<DumpBridge.Entry>, onChanged: () -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val groups = remember(files) { DumpStorage.groups(files) }
    val orphans = remember(files) { DumpStorage.orphans(files) }
    var days by remember { mutableStateOf(30) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val old = DumpStorage.olderThan(groups, days)
    val oldBytes = old.sumOf { it.bytes }

    NcAlertDialog(
        onDismissRequest = onClose,
        title = { Text("Storage") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${DumpSpaceGuard.formatBytes(groups.sumOf { it.bytes })} in ${groups.size} items.", color = Nc.Fg, fontSize = 14.sp)
                groups.take(12).forEach { g ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (g.isFolder) "${g.name}/" else g.name,
                            style = mono, color = Nc.Muted, modifier = Modifier.weight(1f), maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(DumpSpaceGuard.formatBytes(g.bytes), style = mono, color = Nc.Fg)
                    }
                }
                if (groups.size > 12) Text("and ${groups.size - 12} smaller items", style = mono, color = Nc.Subtle)
                Text("Delete items older than", color = Nc.Fg, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(7, 30, 90).forEach { d ->
                        TextButton(onClick = { days = d }) {
                            Text("$d days", color = if (days == d) LocalNcAccent.current.color else Nc.Muted)
                        }
                    }
                }
                TextButton(enabled = old.isNotEmpty() && !busy, onClick = { confirm = true }) {
                    Text(if (old.isEmpty()) "Nothing older than $days days" else "Delete ${old.size} items (${DumpSpaceGuard.formatBytes(oldBytes)})")
                }
                TextButton(enabled = orphans.isNotEmpty() && !busy, onClick = {
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) { DumpStorage.deleteOrphans(files) }
                        busy = false
                        onChanged()
                    }
                }) { Text(if (orphans.isEmpty()) "No leftover checksum files" else "Remove ${orphans.size} leftover checksum files") }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )

    if (confirm) {
        NcAlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete ${old.size} items?") },
            text = {
                Text(
                    "Each item is removed together with its checksum files and any resume record. " +
                        "This only affects the dump folder, never the device."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            old.forEach { DumpStorage.delete(it) }
                            DumpStorage.deleteOrphans(DumpBridge.listEntries())
                        }
                        busy = false
                        onChanged()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
}
