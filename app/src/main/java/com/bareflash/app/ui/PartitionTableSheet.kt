package com.bareflash.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bareflash.app.console.GptPartition
import com.bareflash.app.ui.theme.LocalNcAccent
import com.bareflash.app.ui.theme.Nc
import com.bareflash.app.ui.theme.Oxanium
import com.bareflash.app.ui.theme.PlexMono

private val BOOT_SET = setOf(
    "boot", "boot_a", "boot_b",
    "init_boot", "init_boot_a", "init_boot_b",
    "vendor_boot", "vendor_boot_a", "vendor_boot_b",
    "recovery", "recovery_a", "recovery_b",
    "vbmeta", "vbmeta_a", "vbmeta_b",
    "vbmeta_system", "vbmeta_system_a", "vbmeta_system_b",
    "vbmeta_vendor", "vbmeta_vendor_a", "vbmeta_vendor_b",
    "dtbo", "dtbo_a", "dtbo_b",
)

/**
 * Live GPT: per-row dump / flash / erase, plus multi-select dump.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PartitionTableSheet(
    partitions: List<GptPartition>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onDump: (GptPartition) -> Unit,
    onDumpMany: (List<GptPartition>) -> Unit,
    onFlash: (GptPartition) -> Unit,
    onErase: (GptPartition) -> Unit,
    onPrintGpt: () -> Unit,
) {
    val accent = LocalNcAccent.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var selected by remember { mutableStateOf(setOf<String>()) }
    val selectMode = selected.isNotEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Nc.VoidSoft,
        contentColor = Nc.Fg,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("GPT", style = MaterialTheme.typography.labelLarge, color = accent.color)
                    Text(
                        "Partition table",
                        style = TextStyle(fontFamily = Oxanium, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
                        color = Nc.Fg
                    )
                    Text(
                        when {
                            partitions.isEmpty() -> "Run printgpt once to fill this list from the connected device."
                            selectMode -> "${selected.size} selected · tap name to toggle"
                            else -> "${partitions.size} partitions · long-press a name to multi-select"
                        },
                        fontSize = 13.sp, color = Nc.Muted, modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Box(
                    Modifier.padding(start = 12.dp).size(36.dp).ncRaised().ncClickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = Nc.Fg, modifier = Modifier.size(16.dp))
                }
            }

            if (partitions.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(accent.brush)
                        .alpha(if (busy) 0.4f else 1f)
                        .ncClickable(enabled = !busy) { onPrintGpt(); onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Run printgpt",
                        style = TextStyle(fontFamily = Oxanium, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                        color = accent.onColor
                    )
                }
            } else {
                val bootSet = partitions.filter { it.name.lowercase() in BOOT_SET }
                if (bootSet.isNotEmpty() && !selectMode) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(accent.dim)
                            .border(1.dp, accent.color.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .alpha(if (busy) 0.4f else 1f)
                            .ncClickable(enabled = !busy) { onDumpMany(bootSet) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Filled.Download, null, tint = accent.color, modifier = Modifier.size(16.dp))
                        Text(
                            "Dump boot set (${bootSet.size})",
                            style = TextStyle(fontFamily = Oxanium, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                            color = accent.color,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
                if (selectMode) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(accent.dim)
                                .border(1.dp, accent.color.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .alpha(if (busy) 0.4f else 1f)
                                .ncClickable(enabled = !busy) {
                                    val list = partitions.filter { it.name in selected }
                                    if (list.isNotEmpty()) {
                                        onDumpMany(list)
                                        onDismiss()
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Filled.Download, null, tint = accent.color, modifier = Modifier.size(16.dp))
                            Text(
                                "Dump ${selected.size}",
                                style = TextStyle(fontFamily = Oxanium, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                                color = accent.color,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                        Row(
                            Modifier
                                .height(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Nc.Raised)
                                .ncClickable { selected = emptySet() }
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Clear", fontSize = 13.sp, color = Nc.Muted)
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(partitions, key = { it.name }) { p ->
                        val isSel = p.name in selected
                        PartitionRow(
                            p = p,
                            enabled = !busy,
                            selected = isSel,
                            selectMode = selectMode,
                            onToggleSelect = {
                                selected = if (isSel) selected - p.name else selected + p.name
                            },
                            onDump = { onDump(p) },
                            onFlash = { onFlash(p) },
                            onErase = { onErase(p) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PartitionRow(
    p: GptPartition,
    enabled: Boolean,
    selected: Boolean,
    selectMode: Boolean,
    onToggleSelect: () -> Unit,
    onDump: () -> Unit,
    onFlash: () -> Unit,
    onErase: () -> Unit,
) {
    val accent = LocalNcAccent.current
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .then(
                if (selected) Modifier.border(1.dp, accent.color.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                else Modifier
            )
            .ncTile()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (selectMode) onToggleSelect() },
                    onLongClick = { onToggleSelect() }
                )
        ) {
            if (selectMode || selected) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) accent.color else Nc.Raised)
                        .border(1.dp, if (selected) accent.color else Nc.BorderSoft, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(Icons.Filled.Check, null, tint = accent.onColor, modifier = Modifier.size(14.dp))
                    }
                }
            }
            Text(
                p.name,
                style = TextStyle(fontFamily = Oxanium, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                color = Nc.Fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                formatBytes(p.length),
                style = TextStyle(fontFamily = PlexMono, fontSize = 11.sp),
                color = Nc.Muted
            )
        }
        if (!selectMode) {
            Row(
                Modifier.padding(top = 8.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MiniAction("Dump", Icons.Filled.Download, accent.color, enabled, Modifier.weight(1f), onDump)
                MiniAction("Flash", Icons.Filled.Upload, Nc.Fg, enabled, Modifier.weight(1f), onFlash)
                MiniAction("Erase", Icons.Filled.Warning, Nc.Err, enabled, Modifier.weight(1f), onErase)
            }
        }
    }
}

@Composable
private fun MiniAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Nc.Raised)
            .ncClickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(
            label,
            style = TextStyle(fontFamily = Oxanium, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
            color = tint,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}
