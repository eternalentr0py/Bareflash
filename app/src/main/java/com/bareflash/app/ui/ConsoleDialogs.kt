package com.bareflash.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.bareflash.app.console.ConsoleViewModel
import com.bareflash.app.console.DumpSpaceGuard
import com.bareflash.app.console.GptPartition
import com.bareflash.app.dump.DumpBridge
import java.io.File

@Composable
internal fun PartitionDialog(
    title: String,
    confirmLabel: String,
    isValid: (String) -> Boolean,
    initialName: String = "",
    partitions: List<GptPartition> = emptyList(),
    dumpsDir: File? = null,
    sectorSize: String = "0x200",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val trimmed = name.trim()
    val valid = isValid(trimmed)
    val space = remember(trimmed, dumpsDir, sectorSize, partitions) {
        if (dumpsDir == null || trimmed.isEmpty() || !valid) null
        else DumpSpaceGuard.check("r $trimmed dumps/$trimmed.img", dumpsDir, sectorSize, partitions)
    }
    val blocked = space is DumpSpaceGuard.Verdict.Blocked
    val liveMatch = partitions.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
    NcAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Partition name") },
                    singleLine = true,
                    isError = (trimmed.isNotEmpty() && !valid) || blocked,
                    supportingText = when {
                        trimmed.isNotEmpty() && !valid -> {
                            { Text("Use letters, numbers, dots, dashes and underscores only.") }
                        }
                        blocked -> {
                            { Text("Not enough free space") }
                        }
                        dumpsDir != null -> {
                            val free = DumpSpaceGuard.availableBytes(dumpsDir)
                            val dest = DumpBridge.displayName()
                            val sizeBit = liveMatch?.let { "${DumpSpaceGuard.formatBytes(it.length)} partition · " } ?: ""
                            if (free >= 0L) {
                                {
                                    Text(
                                        "$sizeBit${DumpSpaceGuard.formatBytes(free)} free in $dest.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else if (liveMatch != null) {
                                { Text("${DumpSpaceGuard.formatBytes(liveMatch.length)} on the device.") }
                            } else if (initialName.isNotEmpty() && trimmed == initialName) {
                                { Text("Last used") }
                            } else null
                        }
                        liveMatch != null -> {
                            { Text("${DumpSpaceGuard.formatBytes(liveMatch.length)} on the device.") }
                        }
                        initialName.isNotEmpty() && trimmed == initialName -> {
                            { Text("Last used") }
                        }
                        else -> null
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
                )
                val live = partitions.filter { trimmed.isEmpty() || it.name.contains(trimmed, ignoreCase = true) }
                val extras = ConsoleViewModel.commonPartitions.filter { common ->
                    partitions.none { it.name.equals(common, ignoreCase = true) } &&
                        (trimmed.isEmpty() || common.contains(trimmed, ignoreCase = true))
                }.take(10)
                if (live.isNotEmpty()) {
                    Text(
                        "From the last printgpt",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(live, key = { it.name }) { p ->
                            SuggestionChip(onClick = { name = p.name }, label = { Text(p.name) })
                        }
                    }
                }
                if (extras.isNotEmpty()) {
                    Text(
                        if (live.isEmpty()) "Common names" else "Other common names",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(extras) { s ->
                            SuggestionChip(onClick = { name = s }, label = { Text(s) })
                        }
                    }
                }
                if (blocked) {
                    Text(
                        (space as DumpSpaceGuard.Verdict.Blocked).userMessage(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = { TextButton(enabled = valid && !blocked, onClick = { onConfirm(trimmed) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
