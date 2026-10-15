package com.bareflash.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MenuDefaults
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bareflash.app.AppGraph
import com.bareflash.app.engine.Catalogs
import com.bareflash.app.engine.EngineMode
import com.bareflash.app.console.ConsoleViewModel
import com.bareflash.app.ui.theme.LocalNcAccent
import com.bareflash.app.ui.theme.Nc
import com.bareflash.app.ui.theme.Oxanium
import com.bareflash.app.ui.theme.PlexMono

private fun titleFor(cmd: String): String = Catalogs.titleFor(cmd)

private data class PresetChip(val label: String, val cmd: String)

/**
 * Console controls: Detect (accent) + Dump + Flash + Table as 44dp
 * buttons, abort square while running, then a scrolling chip row of
 * pinned commands and recent history.
 */
@Composable
internal fun ConsoleActionRow(
    vm: ConsoleViewModel,
    running: Boolean,
    detecting: Boolean,
    mode: EngineMode,
    pinnedCommands: List<String>,
    history: List<String>,
    onDumpRequested: () -> Unit,
    onFlashRequested: () -> Unit,
    onTableRequested: () -> Unit,
    onRequestCancel: () -> Unit
) {
    val accent = LocalNcAccent.current

    val busy = detecting || running
    // (label, command to run). Pinned first, then recent history not already pinned.
    val lastCommand by vm.lastCommand.collectAsStateWithLifecycle()
    val lastOutcome by vm.lastOutcome.collectAsStateWithLifecycle()

    val chips = remember(pinnedCommands, mode, lastCommand, lastOutcome) {
        val pins = pinnedCommands.filter { Catalogs.isKnown(mode, it) }
        val base = listOf(PresetChip("Identify", "identify")) +
            pins.map { PresetChip(titleFor(it), it) }
        val retry = lastCommand?.takeIf { it.isNotBlank() && Catalogs.isKnown(mode, it) }?.let {
            listOf(PresetChip("Retry", "__retry__"))
        }.orEmpty()
        retry + base
    }
    var historyOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                label = if (detecting) "Listening" else "Detect",
                icon = Icons.Filled.Usb,
                primary = true,
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) { vm.detectDevice() }
            // Dump / Flash / Table are MTK partition shortcuts (they rely on the
            // GPT read by printgpt). QDL mode uses the Commands tab instead.
            if (mode == EngineMode.MTK) {
                ActionButton("Dump", Icons.Filled.Download, primary = false, enabled = !busy, modifier = Modifier.weight(1f), onClick = onDumpRequested)
                ActionButton("Flash", Icons.Filled.Upload, primary = false, enabled = !busy, modifier = Modifier.weight(1f), onClick = onFlashRequested)
                ActionButton("Table", Icons.Filled.TableChart, primary = false, enabled = !busy, modifier = Modifier.weight(1f), onClick = onTableRequested)
            }
            if (running) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Nc.Err.copy(alpha = 0.2f))
                        .ncClickable(onClick = onRequestCancel),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop command", tint = Nc.Err, modifier = Modifier.size(16.dp))
                }
            }
        }

        // Preset row: pinned + recent chips across the full width. Clear and
        // the overflow menu live in the terminal's own title strip.
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 2.dp)
        ) {
            item {
                Box {
                    Row(
                        Modifier
                            .height(32.dp)
                            .clip(CircleShape)
                            .background(Nc.Ink)
                            .border(1.dp, Nc.BorderSoft, CircleShape)
                            .ncClickable { historyOpen = true }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Filled.History, contentDescription = "Command history", tint = Nc.Muted, modifier = Modifier.size(12.dp))
                        Text("History", style = TextStyle(fontFamily = PlexMono, fontSize = 11.sp), color = Nc.Muted)
                    }
                    DropdownMenu(
                        expanded = historyOpen,
                        onDismissRequest = { historyOpen = false },
                        containerColor = Nc.Panel,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        val mine = history.take(15)
                        if (mine.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No commands yet") },
                                onClick = { historyOpen = false },
                                enabled = false
                            )
                        }
                        mine.forEach { h ->
                            DropdownMenuItem(
                                text = { Text(h, maxLines = 1, fontFamily = PlexMono, fontSize = 12.sp) },
                                onClick = {
                                    historyOpen = false
                                    vm.requestRun(h)
                                }
                            )
                        }
                        if (mine.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Clear this mode") },
                                colors = MenuDefaults.itemColors(textColor = Nc.Err),
                                onClick = {
                                    AppGraph.settings.clearHistory(mode)
                                    historyOpen = false
                                }
                            )
                        }
                    }
                }
            }
            items(chips) { chip ->
                val fg = if (busy) Nc.Subtle else accent.color
                Row(
                    Modifier
                        .height(32.dp)
                        .clip(CircleShape)
                        .background(accent.dim)
                        .border(1.dp, accent.color.copy(alpha = 0.35f), CircleShape)
                        .ncClickable(enabled = !busy) {
                            if (chip.cmd == "__retry__") vm.retryLastCommand()
                            else vm.requestRun(chip.cmd)
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Filled.PushPin, contentDescription = "Pinned command", tint = fg.copy(alpha = 0.8f), modifier = Modifier.size(12.dp))
                    Text(chip.label, style = TextStyle(fontFamily = PlexMono, fontSize = 11.sp), color = fg, maxLines = 1)
                }
            }
        }
    }
}

/** h-11 rounded-xl button: accent fill for the primary, raised + ring otherwise. */
@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    primary: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = LocalNcAccent.current
    val shape = androidx.compose.foundation.shape.CircleShape
    Row(
        modifier
            .height(48.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .then(if (primary) Modifier.background(accent.color) else Modifier.background(MaterialTheme.colorScheme.secondaryContainer))
            .ncClickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        val fg = if (primary) accent.onColor else MaterialTheme.colorScheme.onSecondaryContainer
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp)) // adjacent text labels the action
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
