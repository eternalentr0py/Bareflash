@file:OptIn(ExperimentalMaterial3Api::class)

package com.bareflash.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bareflash.app.data.NavLabelMode
import com.bareflash.app.engine.EngineMode
import com.bareflash.app.ui.theme.LocalLook
import com.bareflash.app.ui.theme.LocalNcAccent
import com.bareflash.app.ui.theme.Nc

/**
 * Mode switching, without a permanent bar at the top of every screen.
 *
 * The current mode lives in the bottom navigation bar as its first slot
 * ([ModeNavItem]). One tap opens [ModeSheet], a bottom sheet with one large
 * card per mode; tapping a card switches and closes it. Both are in thumb
 * reach and take no space from the content.
 */
internal fun EngineMode.icon(): ImageVector = when (this) {
    EngineMode.MTK -> Icons.Filled.Memory
    EngineMode.QDL -> Icons.Filled.DeveloperBoard
    EngineMode.BROKKR -> Icons.Filled.PhoneAndroid
}

/** First slot of the bottom bar: the active mode's glyph in a tonal pill, with its name underneath. */
@Composable
fun RowScope.ModeNavItem(mode: EngineMode, locked: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val labels = LocalLook.current.navLabels
    NavigationBarItem(
        selected = false,
        onClick = onClick,
        icon = {
            Box(
                Modifier
                    .size(width = 48.dp, height = 28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(cs.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(mode.icon(), contentDescription = "Mode: ${mode.label}. Tap to change.", tint = cs.onSecondaryContainer, modifier = Modifier.size(20.dp))
            }
        },
        label = if (labels == NavLabelMode.NEVER) null else ({ Text(mode.label, maxLines = 1) }),
        alwaysShowLabel = labels == NavLabelMode.ALWAYS,
        modifier = Modifier.alpha(if (locked) 0.7f else 1f)
    )
}

/** Bottom sheet with one card per mode. Other modes are disabled while a flash or command runs. */
@Composable
fun ModeSheet(
    mode: EngineMode,
    locked: Boolean,
    onSelect: (EngineMode) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Nc.VoidSoft,
        contentColor = Nc.Fg,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)
                .semantics { paneTitle = "Engine mode" },
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Mode", style = MaterialTheme.typography.labelLarge, color = LocalNcAccent.current.color)
            Text("What are you flashing?", style = MaterialTheme.typography.titleLarge, color = Nc.Fg)
            Box(Modifier.size(2.dp))
            EngineMode.entries.forEach { m ->
                ModeCard(
                    m = m,
                    selected = m == mode,
                    enabled = !locked || m == mode
                ) {
                    if (m != mode) onSelect(m)
                    onDismiss()
                }
            }
            if (locked) {
                Text(
                    "Locked while a flash or command is running.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Nc.Muted,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ModeCard(m: EngineMode, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ink = if (selected) cs.onPrimaryContainer else Nc.Fg
    val soft = if (selected) cs.onPrimaryContainer.copy(alpha = 0.78f) else Nc.Muted
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) cs.primaryContainer else cs.surfaceContainerHigh)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (selected) cs.primary else cs.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                m.icon(), contentDescription = m.label,
                tint = if (selected) cs.onPrimary else cs.onSecondaryContainer,
                modifier = Modifier.size(24.dp)
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(m.label, style = MaterialTheme.typography.titleMedium, color = ink)
            Text(m.subtitle, style = MaterialTheme.typography.bodyMedium, color = soft)
            if (selected) {
                Text(m.connectHint, style = MaterialTheme.typography.bodySmall, color = soft, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (selected) {
            Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = cs.primary)
        }
    }
}
