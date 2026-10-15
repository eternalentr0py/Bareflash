package com.bareflash.app.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bareflash.app.catalog.McCommand
import com.bareflash.app.console.CommandRules
import com.bareflash.app.ui.theme.LocalLook
import com.bareflash.app.ui.theme.LocalNcAccent
import com.bareflash.app.ui.theme.Nc
import com.bareflash.app.ui.theme.NcEasing
import com.bareflash.app.ui.theme.motionMs

/** The command list: name + warning, description, pin. Pinned commands float to the top. */
@Composable
internal fun CommandsList(
    filtered: List<McCommand>,
    pinnedCommands: List<String>,
    running: Boolean,
    onSelect: (McCommand) -> Unit,
    onTogglePin: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ordered = remember(filtered, pinnedCommands) {
        filtered.sortedByDescending { it.cmd in pinnedCommands }
    }
    val gap = (8 * LocalLook.current.spacingScale).dp
    val moveMs = motionMs(350)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(gap)
    ) {
        items(ordered, key = { it.cmd }) { c ->
            CommandTile(
                c, c.cmd in pinnedCommands, enabled = !running,
                onClick = { onSelect(c) }, onTogglePin = { onTogglePin(c.cmd) },
                // Items glide to their new place when you pin, unpin or filter.
                modifier = Modifier.animateItem(
                    fadeInSpec = tween(moveMs, easing = NcEasing.EmphasizedDecelerate),
                    placementSpec = tween(moveMs, easing = NcEasing.Emphasized),
                    fadeOutSpec = tween(motionMs(150))
                )
            )
        }
    }
}

@Composable
private fun CommandTile(
    c: McCommand,
    pinned: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = LocalNcAccent.current
    val destructive = remember(c.cmd) { CommandRules.isDestructive(c.cmd) }
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .ncTile(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier
                .weight(1f)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(start = 16.dp, top = 14.dp, bottom = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    c.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Nc.Fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (destructive) {
                    Icon(Icons.Filled.Warning, contentDescription = "Destructive command", tint = Nc.Err, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                c.description,
                style = MaterialTheme.typography.bodyMedium,
                color = Nc.Muted,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        IconButton(onClick = onTogglePin) {
            Icon(
                if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                contentDescription = if (pinned) "Unpin" else "Pin as quick action",
                tint = if (pinned) accent.color else Nc.Muted
            )
        }
    }
}
