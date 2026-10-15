package com.bareflash.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File
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

/** Browser for files under filesDir/dumps: share, export, rename, delete. */
@Composable
fun FilesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dir = remember { File(ctx.getExternalFilesDir(null), "dumps").apply { mkdirs() } }
    var files by remember { mutableStateOf(listOf<File>()) }
    var selected by remember { mutableStateOf<File?>(null) }
    var renaming by remember { mutableStateOf<File?>(null) }
    var deleting by remember { mutableStateOf<File?>(null) }
    var exportTarget by remember { mutableStateOf<File?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun refresh() {
        files = (dir.listFiles()?.toList() ?: emptyList()).sortedByDescending { it.lastModified() }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { refresh() }
    BackHandler(onBack = onBack)

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val f = exportTarget; exportTarget = null
        if (uri != null && f != null) {
            val ok = runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } != null
            }.getOrDefault(false)
            scope.launch { snackbar.showSnackbar(if (ok) "File exported." else "The file could not be exported.") }
        }
    }

    val df = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Text("Dumped files", style = MaterialTheme.typography.headlineSmall)
            }
            if (files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No files.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(files, key = { it.absolutePath }) { f ->
                        ListItem(
                            modifier = Modifier.clickable { selected = f },
                            headlineContent = { Text(f.name) },
                            supportingContent = { Text("${formatSize(f.length())} \u00b7 ${df.format(Date(f.lastModified()))}") }
                        )
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }

    selected?.let { f ->
        NcAlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(f.name) },
            text = {
                Column {
                    TextButton(onClick = {
                        selected = null
                        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/octet-stream"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(send, "Share ${f.name}"))
                    }) { Text("Share") }
                    TextButton(onClick = { selected = null; exportTarget = f; exportLauncher.launch(f.name) }) { Text("Export") }
                    TextButton(onClick = { selected = null; renaming = f }) { Text("Rename") }
                    TextButton(onClick = { selected = null; deleting = f }) { Text("Delete") }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } }
        )
    }

    renaming?.let { f ->
        var name by remember(f) { mutableStateOf(f.name) }
        val clean = name.trim()
        val valid = clean.isNotEmpty() && !clean.contains('/') && !clean.contains('\\') && clean != "." && clean != ".."
        NcAlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename file") },
            text = {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("File name") },
                    isError = clean.isNotEmpty() && !valid)
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    val ok = f.renameTo(File(dir, clean))
                    renaming = null
                    refresh()
                    if (!ok) scope.launch { snackbar.showSnackbar("The file could not be renamed.") }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
        )
    }

    deleting?.let { f ->
        NcAlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${f.name}?") },
            text = { Text("This action cannot be undone.") },
            confirmButton = { TextButton(onClick = { f.delete(); deleting = null; refresh() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}
