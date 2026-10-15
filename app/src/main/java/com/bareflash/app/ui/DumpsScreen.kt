@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.bareflash.app.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.PlayArrow
import com.bareflash.app.dump.DumpIntegrity
import com.bareflash.app.dump.PartialDumps
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.bareflash.app.ui.theme.LocalNcAccent
import com.bareflash.app.ui.theme.Nc
import com.bareflash.app.ui.theme.Oxanium
import com.bareflash.app.ui.theme.PlexMono
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bareflash.app.AppGraph
import com.bareflash.app.console.ConsoleViewModel
import com.bareflash.app.console.DumpSpaceGuard
import com.bareflash.app.dump.DumpBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB")
    var value = bytes / 1024.0
    var i = 0
    while (value >= 1024 && i < units.size - 1) { value /= 1024.0; i++ }
    return String.format("%.1f %s", value, units[i])
}

/**
 * Files produced by dump commands. When a folder is chosen in Settings,
 * this lists that folder — the same place Python streamed the image to.
 * There is no app-private copy sitting behind it.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun DumpsScreen(vm: ConsoleViewModel) {
    val ctx = LocalContext.current
    val settings by AppGraph.settings.state.collectAsStateWithLifecycle()
    val epoch by vm.dumpsEpoch.collectAsStateWithLifecycle()
    var files by remember { mutableStateOf(listOf<DumpBridge.Entry>()) }
    var freeBytes by remember { mutableLongStateOf(-1L) }
    var pendingExport by remember { mutableStateOf<DumpBridge.Entry?>(null) }
    var deleting by remember { mutableStateOf<DumpBridge.Entry?>(null) }
    var notes by remember { mutableStateOf(mapOf<String, String>()) }
    var hashing by remember { mutableStateOf<DumpBridge.Entry?>(null) }
    var hashResult by remember { mutableStateOf<String?>(null) }
    var manifests by remember { mutableStateOf(mapOf<String, DumpIntegrity.Manifest>()) }
    var partials by remember { mutableStateOf(mapOf<String, PartialDumps.Record>()) }
    var sortBy by remember { mutableStateOf(0) } // 0 newest, 1 largest, 2 name
    var picked by remember { mutableStateOf(setOf<String>()) }
    var bulkDelete by remember { mutableStateOf(false) }
    var bulkHash by remember { mutableStateOf<String?>(null) }
    var bulkHashing by remember { mutableStateOf(false) }
    val verifier = rememberVerifier()
    var compareNames by remember { mutableStateOf<Pair<String, String>?>(null) }
    var restoreRel by remember { mutableStateOf<String?>(null) }
    val job by AppGraph.export.active.collectAsStateWithLifecycle()
    val accent = LocalNcAccent.current
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val scope = rememberCoroutineScope()
    val destName = if (DumpBridge.isEnabled() && settings.general.dumpFolderName.isNotBlank()) {
        settings.general.dumpFolderName
    } else {
        "App folder"
    }

    fun refresh() {
        scope.launch {
            val listed = withContext(Dispatchers.IO) { DumpBridge.listEntries() }
            val free = withContext(Dispatchers.IO) { DumpBridge.availableBytes() }
            files = listed
            freeBytes = free
            notes = withContext(Dispatchers.IO) { com.bareflash.app.console.RunSidecar.subtitles() }
            val names = listed.map { it.name }.toSet()
            manifests = withContext(Dispatchers.IO) {
                listed.filter { !DumpIntegrity.isSidecar(it.name) && (it.name + DumpIntegrity.MANIFEST_SUFFIX) in names }
                    .mapNotNull { e -> DumpIntegrity.readManifest(e.name)?.let { e.name to it } }.toMap()
            }
            partials = PartialDumps.all()
            picked = picked.filter { it in names }.toSet()
        }
    }
    LaunchedEffect(epoch, settings.general.dumpFolderUri) { refresh() }
    LaunchedEffect(job) { if (job?.done == true) refresh() }

    val folderLauncher = rememberLauncherForActivityResult(
        object : ActivityResultContracts.OpenDocumentTree() {
            override fun createIntent(context: android.content.Context, input: Uri?): Intent {
                return super.createIntent(context, input).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
            }
        }
    ) { uri ->
        if (uri != null) {
            DumpBridge.takeTree(uri)
            vm.notifyDumpsChanged()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val f = pendingExport; pendingExport = null
        if (uri != null && f != null) {
            val local = f.localFile
            if (local != null) {
                AppGraph.export.export(local, uri) { msg -> vm.log(msg) }
            } else {
                AppGraph.export.export(f.uri, f.name.substringAfterLast('/'), f.size, uri) { msg -> vm.log(msg) }
            }
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NcHeader(kicker = "", title = "Dumps")

        Row(
            Modifier.fillMaxWidth().ncTile().padding(horizontal = 16.dp, vertical = 12.dp).ncClickable {
                folderLauncher.launch(DumpBridge.currentUri())
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = accent.color, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Saving to $destName",
                    style = TextStyle(fontFamily = Oxanium, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                    color = Nc.Fg
                )
                Text(
                    if (DumpBridge.isEnabled()) "Images stream here as they are read. Tap to change."
                    else "App storage. Tap to pick an SD card or USB stick.",
                    style = TextStyle(fontFamily = PlexMono, fontSize = 11.sp),
                    color = Nc.Muted
                )
            }
        }

        job?.let { j ->
            if (!j.done) {
                Column(Modifier.fillMaxWidth().ncTile().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        "Exporting ${j.fileName}\u2026 ${(j.fraction * 100).toInt()}%",
                        style = TextStyle(fontFamily = PlexMono, fontSize = 11.sp),
                        color = Nc.Muted
                    )
                    Box(
                        Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp)
                            .clip(CircleShape).background(Nc.PanelHigh)
                    ) {
                        Box(Modifier.fillMaxWidth(j.fraction.coerceIn(0f, 1f)).height(4.dp).background(accent.color))
                    }
                }
            }
        }

        if (freeBytes >= 0L) {
            Text(
                "${DumpSpaceGuard.formatBytes(freeBytes)} free in $destName",
                style = TextStyle(fontFamily = PlexMono, fontSize = 11.sp),
                color = Nc.Muted
            )
        }

        DumpsToolsRow(vm, files, partials) { refresh() }

        val visible = remember(files, sortBy) {
            val base = files.filter { !DumpIntegrity.isSidecar(it.name) && !it.name.startsWith("verify_tmp/") }
            when (sortBy) {
                1 -> base.sortedByDescending { it.size }
                2 -> base.sortedBy { it.name.lowercase() }
                else -> base.sortedByDescending { it.modified }
            }
        }
        if (picked.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().ncTile().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("${picked.size} selected", color = Nc.Fg, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { picked = visible.map { it.name }.toSet() }) { Text("All") }
                RoundIcon(Icons.Filled.Tag, "SHA-256 of selected files", Nc.Muted) {
                    val sel = visible.filter { it.name in picked }
                    bulkHash = null
                    bulkHashing = true
                    scope.launch {
                        val out = withContext(Dispatchers.IO) {
                            sel.joinToString("\n") { e ->
                                val sha = DumpIntegrity.sha256Rel(e.name) ?: "(could not be read)"
                                "$sha  ${e.name.substringAfterLast('/')}"
                            }
                        }
                        bulkHash = out
                    }
                }
                RoundIcon(Icons.Filled.Share, "Share selected files", Nc.Muted) {
                    val sel = visible.filter { it.name in picked }
                    val uris = ArrayList<Uri>(sel.map { f ->
                        f.localFile?.let { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", it) } ?: f.uri
                    })
                    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "application/octet-stream"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = ClipData.newRawUri("files", uris.first()).also { cd ->
                            uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) }
                        }
                    }
                    ctx.startActivity(Intent.createChooser(send, "Share ${uris.size} files"))
                }
                RoundIcon(Icons.Filled.Delete, "Delete selected files", Nc.Err) { bulkDelete = true }
                TextButton(onClick = { picked = emptySet() }) { Text("Clear") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { verifier.run(visible.filter { it.name in picked }.map { it.name }) }) { Text("Verify selected") }
                if (picked.size == 2) {
                    TextButton(onClick = {
                        val two = visible.filter { it.name in picked }
                        if (two.size == 2) compareNames = two[0].name to two[1].name
                    }) { Text("Compare the two") }
                }
            }
        } else if (visible.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("Newest", "Largest", "Name").forEachIndexed { i, label ->
                    Text(
                        label,
                        style = TextStyle(fontFamily = PlexMono, fontSize = 12.sp),
                        color = if (sortBy == i) accent.color else Nc.Muted,
                        modifier = Modifier.ncClickable { sortBy = i }.padding(vertical = 4.dp)
                    )
                }
            }
        }

        if (visible.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().ncBlack(ring = Nc.BorderSoft),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
            ) {
                Icon(Icons.Filled.Archive, contentDescription = null, tint = Nc.Subtle, modifier = Modifier.size(32.dp))
                val hasFolder = AppGraph.settings.state.value.general.dumpFolderUri.isNotBlank()
                Text(
                    if (hasFolder) "No captures yet. Run a dump from Console or Commands."
                    else "No dump folder set.",
                    fontSize = 14.sp, color = Nc.Muted
                )
                if (!hasFolder) {
                    Row(
                        Modifier
                            .padding(top = 4.dp)
                            .height(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .ncRaised()
                            .ncClickable { vm.pickDumpFolder() }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Set dump folder", fontSize = 13.sp, color = Nc.Fg)
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                items(visible, key = { it.uri.toString() + it.name }) { f ->
                    val isSel = f.name in picked
                    Column(
                        Modifier.fillMaxWidth().ncTile()
                            .combinedClickable(
                                onClick = { if (picked.isNotEmpty()) picked = if (isSel) picked - f.name else picked + f.name },
                                onLongClick = { picked = if (isSel) picked - f.name else picked + f.name }
                            )
                            .then(if (isSel) Modifier.border(1.dp, accent.color, RoundedCornerShape(16.dp)) else Modifier)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                f.name,
                                style = TextStyle(fontFamily = Oxanium, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                                color = Nc.Fg, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${formatSize(f.size)} \u00b7 ${df.format(Date(if (f.modified > 0) f.modified else System.currentTimeMillis()))}",
                                style = TextStyle(fontFamily = PlexMono, fontSize = 11.sp),
                                color = Nc.Muted
                            )
                            val part = partials[f.name]
                            val mf = manifests[f.name]
                            if (part != null && part.total > 0L && f.size < part.total) {
                                Text(
                                    "Interrupted at ${(f.size * 100 / part.total).coerceIn(0, 99)}%. Tap resume to continue.",
                                    style = TextStyle(fontFamily = PlexMono, fontSize = 11.sp), color = Nc.Warn
                                )
                            } else if (mf != null) {
                                Text(
                                    if (mf.bytes == f.size) "SHA-256 ${mf.sha256.take(12)}\u2026 saved" else "Changed since it was hashed",
                                    style = TextStyle(fontFamily = PlexMono, fontSize = 11.sp),
                                    color = if (mf.bytes == f.size) Nc.Ok else Nc.Warn
                                )
                                if (mf.bytes == f.size) {
                                    DumpIntegrity.blankNote(f.name, mf)?.let { note ->
                                        Text(
                                            note,
                                            style = TextStyle(fontFamily = PlexMono, fontSize = 11.sp),
                                            color = Nc.Warn, maxLines = 3, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            notes[f.name]?.let { n ->
                                Text(
                                    n,
                                    style = TextStyle(fontFamily = PlexMono, fontSize = 11.sp),
                                    color = Nc.Subtle, maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                            }
                            verifier.badges[f.name]?.let { b ->
                                Text(
                                    b.text,
                                    style = TextStyle(fontFamily = PlexMono, fontSize = 11.sp),
                                    color = when (b.state) { 1 -> Nc.Ok; 2 -> Nc.Err; 3 -> Nc.Warn; else -> Nc.Muted }
                                )
                                if (b.state == 0) {
                                    Box(
                                        Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp)
                                            .clip(CircleShape).background(Nc.PanelHigh)
                                    ) { Box(Modifier.fillMaxWidth(b.progress.coerceIn(0f, 1f)).height(3.dp).background(accent.color)) }
                                }
                            }
                        }
                        if (partials[f.name] != null && vm.canResume(f.name)) {
                            RoundIcon(Icons.Filled.PlayArrow, "Resume ${f.name}", accent.color) { vm.resumeDump(f.name) }
                        }
                        RoundIcon(Icons.Filled.Tag, "SHA-256 of ${f.name}", Nc.Muted) {
                            hashResult = null
                            hashing = f
                            scope.launch {
                                val digest = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val md = java.security.MessageDigest.getInstance("SHA-256")
                                        val input = f.localFile?.inputStream() ?: ctx.contentResolver.openInputStream(f.uri)
                                        input!!.use { s ->
                                            val buf = ByteArray(1 shl 20)
                                            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
                                        }
                                        md.digest().joinToString("") { "%02x".format(it) }
                                    }.getOrNull()
                                }
                                hashResult = digest ?: "The file could not be read."
                            }
                        }
                        RoundIcon(Icons.Filled.Share, "Share ${f.name}", Nc.Muted) {
                            val uri = f.localFile?.let {
                                FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", it)
                            } ?: f.uri
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/octet-stream"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                clipData = ClipData.newRawUri(f.name, uri)
                            }
                            ctx.startActivity(Intent.createChooser(send, "Share ${f.name}"))
                        }
                        RoundIcon(Icons.Filled.FileUpload, "Export ${f.name}", Nc.Muted) {
                            pendingExport = f
                            exportLauncher.launch(f.name.substringAfterLast('/'))
                        }
                        RoundIcon(Icons.Filled.Delete, "Delete ${f.name}", Nc.Err) { deleting = f }
                    }
                    val leaf = f.name.substringAfterLast('/')
                    val mfa = manifests[f.name]
                    val restorable = partials[f.name] == null && leaf != "gpt.bin" && leaf != "gpt_backup.bin" &&
                        !leaf.startsWith("extra_") && !leaf.endsWith(".txt") && f.size > 0L
                    val actionStyle = TextStyle(fontFamily = PlexMono, fontSize = 12.sp)
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        Text(
                            "Verify file", style = actionStyle, color = accent.color, maxLines = 1, softWrap = false,
                            modifier = Modifier.ncClickable { verifier.run(listOf(f.name)) }.padding(vertical = 8.dp)
                        )
                        if (mfa != null && partials[f.name] == null && mfa.bytes == f.size) {
                            Text(
                                "Verify on device", style = actionStyle, color = accent.color, maxLines = 1, softWrap = false,
                                modifier = Modifier.ncClickable { vm.verifyOnDevice(f.name) }.padding(vertical = 8.dp)
                            )
                        }
                        if (restorable) {
                            Text(
                                "Restore", style = actionStyle, color = accent.color, maxLines = 1, softWrap = false,
                                modifier = Modifier.ncClickable { restoreRel = f.name }.padding(vertical = 8.dp)
                            )
                        }
                    }
                    }
                }
            }
        }
    }

    hashing?.let { f ->
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        NcAlertDialog(
            onDismissRequest = { hashing = null; hashResult = null },
            title = { Text("SHA-256 of ${f.name}") },
            text = {
                // Computed on this phone from the file itself, not by the device's sha256 command.
                Text(hashResult ?: "Hashing ${formatSize(f.size)}\u2026", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            },
            confirmButton = {
                TextButton(
                    enabled = hashResult != null,
                    onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(hashResult.orEmpty())) }
                ) { Text("Copy") }
            },
            dismissButton = { TextButton(onClick = { hashing = null; hashResult = null }) { Text("Close") } }
        )
    }

    if (bulkDelete) {
        NcAlertDialog(
            onDismissRequest = { bulkDelete = false },
            title = { Text("Delete ${picked.size} files?") },
            text = { Text("This only removes the files and their checksums from the dump folder; it does not affect the device.") },
            confirmButton = {
                TextButton(onClick = {
                    files.filter { it.name in picked }.forEach { f ->
                        DumpBridge.delete(f)
                        DumpBridge.deleteRel(f.name + DumpIntegrity.MANIFEST_SUFFIX)
                        DumpBridge.deleteRel(f.name + DumpIntegrity.SHA_SUFFIX)
                        PartialDumps.remove(f.name)
                    }
                    picked = emptySet()
                    bulkDelete = false
                    vm.notifyDumpsChanged()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { bulkDelete = false }) { Text("Cancel") } }
        )
    }

    if (bulkHashing) {
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        NcAlertDialog(
            onDismissRequest = { bulkHashing = false; bulkHash = null },
            title = { Text("SHA-256") },
            text = {
                Text(
                    bulkHash ?: "Hashing the selected files\u2026",
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 11.sp
                )
            },
            confirmButton = {
                TextButton(
                    enabled = bulkHash != null,
                    onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(bulkHash.orEmpty())) }
                ) { Text("Copy") }
            },
            dismissButton = { TextButton(onClick = { bulkHashing = false; bulkHash = null }) { Text("Close") } }
        )
    }

    compareNames?.let { (a, b) -> CompareDialog(a, b) { compareNames = null } }
    restoreRel?.let { r -> RestoreDialog(vm, folder = null, rel = r, onClose = { restoreRel = null }) }

    deleting?.let { f ->
        NcAlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${f.name}?") },
            text = { Text("This only removes the file from the dump folder; it does not affect the device.") },
            confirmButton = {
                TextButton(onClick = {
                    DumpBridge.delete(f)
                    DumpBridge.deleteRel(f.name + DumpIntegrity.MANIFEST_SUFFIX)
                    DumpBridge.deleteRel(f.name + DumpIntegrity.SHA_SUFFIX)
                    PartialDumps.remove(f.name)
                    deleting = null
                    vm.notifyDumpsChanged()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

/** 40dp raised round icon button (share / export / delete). */
@Composable
private fun RoundIcon(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).ncRaised().ncClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(16.dp))
    }
}
