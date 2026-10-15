package com.bareflash.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bareflash.app.ui.theme.LocalNcScreen

/**
 * Shown on first launch, before anything else (and before any USB access).
 * The only ways forward are "I understand" or leaving the app; Back leaves too.
 */
@Composable
fun DisclaimerScreen(onExit: () -> Unit, onAccept: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    BackHandler(onBack = onExit)

    Column(
        Modifier
            .fillMaxSize()
            .background(LocalNcScreen.current)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(cs.errorContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = cs.onErrorContainer, modifier = Modifier.size(28.dp))
                }
                Column {
                    Text("Warning", style = MaterialTheme.typography.labelLarge, color = cs.error)
                    Text("Read this before you continue", style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(cs.errorContainer)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "This app can and will break your phone.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onErrorContainer
                )
                Text(
                    "If you do not use it carefully, or you do not know exactly what you are doing, " +
                        "you will damage your device.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onErrorContainer
                )
            }

            Text(
                "Bareflash writes directly to the storage and bootloader of a device. A single wrong file, " +
                    "partition or command can permanently brick it, erase all of your data, damage identity and " +
                    "calibration data such as the IMEI, trip security counters, and void your warranty. " +
                    "Most of this cannot be undone.",
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurface
            )

            Section("What can go wrong") {
                Bullet("Flashing firmware that is corrupt, or not made for your exact model and region.")
                Bullet("Interrupting a flash: a loose cable, a dying battery, or a screen that turns off.")
                Bullet("Writing or erasing the wrong partition.")
                Bullet("Unlocking or bypassing security features, or downgrading to an older bootloader.")
                Bullet("Mistakes in commands you type or arguments you confirm.")
            }

            Section("No responsibility") {
                Text(
                    "Bareflash is provided \"as is\", with no warranty of any kind and no promise that it works " +
                        "on your device. The author takes no responsibility for any damage, data loss, bricked " +
                        "or otherwise broken devices, lost warranties, or any other consequence of using this " +
                        "app. You use it entirely at your own risk.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant
                )
            }

            Section("Before you flash anything") {
                Bullet("Back up everything you care about, then test that the backup can be read.")
                Bullet("Check that every file is made for your exact device.")
                Bullet("Charge the device and use a good data cable. Do not touch it while it flashes.")
                Bullet("If you are not sure what a step does, stop and find out first.")
            }

            Text(
                "By tapping \"I understand\" you confirm that you have read this and accept all of the risk.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = cs.onSurface
            )
        }

        HorizontalDivider(color = cs.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = onExit, modifier = Modifier.weight(1f)) { Text("Exit") }
            Button(onClick = onAccept, modifier = Modifier.weight(1f)) { Text("I understand") }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        content()
    }
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
