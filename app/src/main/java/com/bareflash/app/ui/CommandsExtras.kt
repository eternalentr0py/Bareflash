package com.bareflash.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bareflash.app.console.ConsoleViewModel
import com.bareflash.app.console.DumpSpaceGuard
import com.bareflash.app.data.Extras
import com.bareflash.app.dump.DumpBridge
import com.bareflash.app.dump.DumpIntegrity
import com.bareflash.app.dump.ScatterFile
import com.bareflash.app.ui.theme.LocalNcAccent
import com.bareflash.app.ui.theme.Nc
import com.bareflash.app.ui.theme.PlexMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val mono = TextStyle(fontFamily = PlexMono, fontSize = 11.sp)

// ---- cable and port speed test -----------------------------------------------------

@Composable
internal fun SpeedTestDialog(vm: ConsoleViewModel, onClose: () -> Unit) {
    val running by vm.running.collectAsStateWithLifecycle()
    val result by vm.speedTest.collectAsStateWithLifecycle()
    var started by remember { mutableStateOf(false) }
    val blocker = vm.speedTestBlocker()

    NcAlertDialog(
        onDismissRequest = onClose,
        title = { Text("Cable and port speed test") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val r = result
                when {
                    running && started -> Text("Reading 64 MiB from the start of flash\u2026 The temporary file is deleted afterwards.", color = Nc.Muted)
                    r != null -> {
                        if (r.ok) Text("${"%.1f".format(r.mibPerSec)} MiB/s", fontSize = 28.sp, color = Nc.Fg)
                        r.link?.let { Text("USB link: $it", style = mono, color = Nc.Muted) }
                        Text(r.verdict, color = if (r.ok) Nc.Fg else Nc.Warn, fontSize = 14.sp)
                    }
                    blocker != null -> Text(blocker, color = Nc.Warn)
                    else -> Text(
                        "Reads 64 MiB from the start of flash into a temporary file in the dump folder, measures the average speed, " +
                            "then deletes the file. Nothing is written to the device.",
                        color = Nc.Muted
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !running && blocker == null,
                onClick = { started = true; vm.startSpeedTest() }
            ) { Text(if (result == null) "Start" else "Test again") }
        },
        dismissButton = { TextButton(onClick = { vm.clearSpeedTest(); onClose() }) { Text("Close") } }
    )
}

// ---- job queue ------------------------------------------------------------------------

@Composable
internal fun QueueDialog(vm: ConsoleViewModel, onClose: () -> Unit) {
    val items by vm.queue.collectAsStateWithLifecycle()
    val status by vm.queueStatus.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val active = status != null

    NcAlertDialog(
        onDismissRequest = onClose,
        title = { Text("Job queue") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Runs the steps one after another without you. It stops at the first failure. " +
                        "Writes and erases are not allowed here because they need a confirmation tap.",
                    color = Nc.Muted, fontSize = 13.sp
                )
                status?.let { Text(it, color = LocalNcAccent.current.color, fontSize = 13.sp) }
                if (items.isEmpty() && !active) Text("The queue is empty.", color = Nc.Subtle, fontSize = 13.sp)
                items.forEachIndexed { i, item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}. ${item.label}", style = mono, color = Nc.Fg, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!active) TextButton(onClick = { vm.queueRemove(item.id) }) { Text("Remove") }
                    }
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it; error = null }, singleLine = true,
                    enabled = !active, label = { Text("Command, e.g. printgpt") },
                    isError = error != null, supportingText = { error?.let { Text(it) } }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = !active && text.isNotBlank(), onClick = {
                        error = vm.queueAdd(text)
                        if (error == null) text = ""
                    }) { Text("Add") }
                    TextButton(enabled = !active, onClick = { vm.queueAddVerifyNewest() }) { Text("Add: verify newest backup") }
                }
            }
        },
        confirmButton = {
            if (active) TextButton(onClick = { vm.queueStop() }) { Text("Stop queue") }
            else TextButton(enabled = items.isNotEmpty() && !running, onClick = { vm.queueStart(); onClose() }) { Text("Start") }
        },
        dismissButton = {
            Row {
                if (!active) TextButton(enabled = items.isNotEmpty(), onClick = { vm.queueClear() }) { Text("Clear") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    )
}

// ---- scatter-file flashing --------------------------------------------------------------

private data class ScatterRow(
    val entry: ScatterFile.Entry,
    val doc: DocumentFile?,
    val imageBytes: Long,
    val partitionBytes: Long,
    val problem: String?,
)

/**
 * Flash a stock MediaTek firmware folder without typing partition names. The scatter file is
 * parsed, every image is checked against the live partition table, and the chosen subset goes out
 * as one `w` command through the normal review dialog, so every write safeguard applies.
 */
@Composable
internal fun ScatterScreen(vm: ConsoleViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val partitions by vm.partitions.collectAsStateWithLifecycle()
    val readOnly by Extras.readOnlyLock.collectAsStateWithLifecycle()
    var tree by remember { mutableStateOf<Uri?>(null) }
    var scatterName by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<ScatterFile.Parsed?>(null) }
    var docs by remember { mutableStateOf<Map<String, DocumentFile>>(emptyMap()) }
    var sizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var chosen by remember { mutableStateOf<Set<String>>(emptySet()) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        tree = uri
        loading = true
        message = null
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = DocumentFile.fromTreeUri(ctx, uri) ?: return@runCatching null
                    val children = dir.listFiles().filter { it.isFile }
                    val scatter = children.firstOrNull { ScatterFile.looksLikeScatter(it.name.orEmpty()) }
                        ?: return@runCatching "No scatter file (a *scatter*.txt) was found in that folder."
                    val text = ctx.contentResolver.openInputStream(scatter.uri)?.bufferedReader()?.use { it.readText() }
                        ?: return@runCatching "The scatter file could not be read."
                    val p = ScatterFile.parse(text)
                    val byName = children.associateBy { it.name.orEmpty().lowercase() }
                    val found = HashMap<String, DocumentFile>()
                    val sz = HashMap<String, Long>()
                    for (e in p.entries) {
                        val d = byName[e.fileName.lowercase()] ?: continue
                        found[e.name] = d
                        var size = d.length()
                        // A sparse image is written expanded, so compare the expanded size.
                        runCatching {
                            ctx.contentResolver.openInputStream(d.uri)?.use { s ->
                                val head = ByteArray(28)
                                val n = s.read(head)
                                DumpIntegrity.sparseExpandedBytes(head, n)?.let { size = it }
                            }
                        }
                        sz[e.name] = size
                    }
                    Triple(scatter.name.orEmpty(), p, found to sz)
                }.getOrNull()
            }
            loading = false
            when (loaded) {
                null -> message = "That folder could not be read."
                is String -> message = loaded
                is Triple<*, *, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val t = loaded as Triple<String, ScatterFile.Parsed, Pair<Map<String, DocumentFile>, Map<String, Long>>>
                    scatterName = t.first
                    parsed = t.second
                    docs = t.third.first
                    sizes = t.third.second
                    chosen = emptySet()
                    if (t.second.entries.isEmpty()) message = "The scatter file lists no partitions."
                }
            }
        }
    }

    val rows = remember(parsed, docs, sizes, partitions) {
        parsed?.entries?.map { e ->
            val live = partitions.firstOrNull { it.name.equals(e.name, ignoreCase = true) }
            val img = sizes[e.name] ?: -1L
            val problem = when {
                !e.hasImage -> "No image in the scatter file"
                docs[e.name] == null -> "Image file is missing from the folder"
                e.name.contains(',') || e.fileName.contains(',') || e.fileName.any { it.isWhitespace() } -> "Name has a comma or space"
                partitions.isNotEmpty() && live == null -> "Not on this device"
                live != null && img > live.length -> "Larger than the partition (${DumpSpaceGuard.formatBytes(img)} > ${DumpSpaceGuard.formatBytes(live.length)})"
                else -> null
            }
            ScatterRow(e, docs[e.name], img, live?.length ?: -1L, problem)
        }.orEmpty()
    }
    // Default selection once the rows exist: everything except userdata and the preloader.
    LaunchedEffect(rows) {
        if (rows.isNotEmpty() && chosen.isEmpty()) {
            chosen = rows.filter { it.problem == null && ScatterFile.defaultSelected(it.entry) }.map { it.entry.name }.toSet()
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Nc.Void).padding(16.dp)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Flash firmware folder", fontSize = 20.sp, color = Nc.Fg)
                Text(
                    "Pick the folder of a stock firmware. Bareflash reads its scatter file, checks every image against " +
                        "this device's partition table and writes the ones you tick.",
                    color = Nc.Muted, fontSize = 13.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { picker.launch(tree) }) { Text(if (parsed == null) "Choose folder" else "Change folder") }
                    if (partitions.isEmpty()) {
                        TextButton(onClick = { vm.requestRun("printgpt"); onClose() }) { Text("Read partition table") }
                    }
                }
                if (partitions.isEmpty() && parsed != null) {
                    Text("The partition table has not been read yet, so sizes cannot be checked. Read it first.", color = Nc.Warn, fontSize = 13.sp)
                }
                if (loading) Text("Reading the folder\u2026", color = Nc.Muted)
                message?.let { Text(it, color = Nc.Err, fontSize = 13.sp) }
                parsed?.let { p ->
                    Text(
                        "$scatterName \u00b7 ${p.platform.ifBlank { "unknown platform" }} \u00b7 ${rows.size} partitions",
                        style = mono, color = Nc.Muted
                    )
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(rows, key = { it.entry.name + it.entry.startAddr }) { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = r.entry.name in chosen, enabled = r.problem == null,
                                onCheckedChange = { on -> chosen = if (on) chosen + r.entry.name else chosen - r.entry.name }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(r.entry.name, fontSize = 14.sp, color = if (r.problem == null) Nc.Fg else Nc.Subtle)
                                Text(
                                    buildString {
                                        append(r.entry.fileName.ifBlank { "no file" })
                                        if (r.imageBytes >= 0) append(" \u00b7 image ${DumpSpaceGuard.formatBytes(r.imageBytes)}")
                                        if (r.partitionBytes >= 0) append(" \u00b7 partition ${DumpSpaceGuard.formatBytes(r.partitionBytes)}")
                                    },
                                    style = mono, color = Nc.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                                r.problem?.let { Text(it, style = mono, color = Nc.Warn) }
                            }
                        }
                    }
                }
                if (readOnly) Text("The read-only lock is on, so nothing can be flashed.", color = Nc.Err, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onClose) { Text("Close") }
                    TextButton(
                        enabled = chosen.isNotEmpty() && !readOnly && partitions.isNotEmpty(),
                        onClick = {
                            val picked = rows.filter { it.entry.name in chosen && it.doc != null }
                            val names = ArrayList<String>()
                            val files = ArrayList<String>()
                            val attached = ArrayList<String>()
                            for (r in picked) {
                                val a = DumpBridge.attachInput(r.doc!!.uri) ?: continue
                                if (a.value.contains(',') || a.value.any { it.isWhitespace() }) {
                                    if (!a.inChosenFolder) DumpBridge.forgetInput(a.value)
                                    continue
                                }
                                names += r.entry.name
                                files += "dumps/${a.value}"
                                if (!a.inChosenFolder) attached += a.value
                            }
                            if (names.isEmpty()) {
                                message = "None of the chosen images could be attached."
                            } else {
                                vm.requestRun("w ${names.joinToString(",")} ${files.joinToString(",")}") {
                                    attached.forEach { DumpBridge.forgetInput(it) }
                                }
                                onClose()
                            }
                        }
                    ) { Text("Flash ${chosen.size} selected") }
                }
            }
        }
    }
}
