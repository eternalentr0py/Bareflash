package com.bareflash.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Live notification (what you see when you leave the app mid-command)
// ---------------------------------------------------------------------------

private enum class DemoKind(
    val chipVerb: String,
    val title: String,
    val totalGiB: Double,
    val label: String
) {
    Flash("Flashing", "Flashing super", 3.5, "Flash"),
    Dump("Dumping", "Reading userdata", 8.0, "Dump"),
    Erase("Erasing", "Erasing cache", 0.0, "Erase")
}

@Composable
internal fun LiveNotificationPage() {
    val cs = MaterialTheme.colorScheme
    var kind by remember { mutableStateOf(DemoKind.Flash) }
    var runId by remember { mutableStateOf(0) }
    // 0..1 over the whole demo. The first stretch is the "starting" phase: the
    // command is running but no byte has moved yet, so there is no percentage.
    var t by remember { mutableFloatStateOf(0f) }
    val startShare = 0.18f
    val running = runId > 0 && t < 1f
    val measurable = kind.totalGiB > 0.0
    val started = t > startShare
    val progress = if (!measurable) 0f else ((t - startShare) / (1f - startShare)).coerceIn(0f, 1f)
    val pct = (progress * 100).roundToInt()

    LaunchedEffect(runId) {
        if (runId == 0) return@LaunchedEffect
        t = 0f
        while (t < 1f) {
            delay(50)
            t = (t + 0.0085f).coerceAtMost(1f)
        }
    }

    val chipText = when {
        runId == 0 -> "Idle"
        t >= 1f -> "Done"
        measurable && started -> "$pct%"
        else -> kind.chipVerb
    }
    val detail = when {
        runId == 0 -> "Press Run demo, then watch the chip and the card."
        t >= 1f -> "Finished"
        !started -> "Starting"
        measurable -> {
            val done = kind.totalGiB * progress
            val left = ((1f - progress) * 5f).roundToInt()
            String.format(Locale.US, "%.1f GiB of %.1f GiB · 31.2 MiB/s · %d s left", done, kind.totalGiB, left)
        }
        else -> "Working"
    }

    PageHead(
        "Live notification", "Leave the app, keep the progress",
        "While a command runs and Bareflash is not on screen, a live notification keeps you posted. Open the app and it goes away.",
        Icons.Filled.Notifications, HeroMotion.Wiggle
    )
    ChipRow(DemoKind.entries, kind, { it.label }) { if (!running) { kind = it; runId = 0; t = 0f } }

    // A miniature status bar with the chip, then the notification card under it.
    Panel {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.Black).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("12:30", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (t >= 1f) Color(0xFF2E9E57) else cs.primary)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .animateContentSize(),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = chipText,
                    transitionSpec = {
                        (fadeIn(tween(200)) + slideInVertically { it / 2 }) togetherWith
                            (fadeOut(tween(120)) + slideOutVertically { -it / 2 })
                    },
                    label = "chip"
                ) { txt ->
                    Text(txt, color = cs.onPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerHigh).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Notifications, null, tint = cs.primary, modifier = Modifier.size(16.dp))
                Text("Bareflash · MediaTek · MT6765", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
            Text(
                if (runId == 0) "Nothing running" else kind.title,
                style = MaterialTheme.typography.titleSmall, color = cs.onSurface
            )
            Text(detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            if (runId > 0 && t < 1f && measurable && started) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp))
            } else if (runId > 0 && t < 1f) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Stop, null, tint = cs.primary, modifier = Modifier.size(16.dp))
                Text("Stop", style = MaterialTheme.typography.labelLarge, color = cs.primary)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Button(onClick = { runId += 1 }, enabled = !running) { Text(if (runId == 0) "Run demo" else "Run again") }
    }
    Caption(
        "The chip names the running command first (Flashing, Dumping, Erasing) and switches to the percentage once data " +
            "is moving. A command with no measurable progress, like an erase, keeps its name until it finishes."
    )
    Panel {
        Text("Good to know", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
        Caption("Pull the notification down for the full card: what is running, bytes done, speed, time left and the device. Stop works from there too.")
        Caption("Turn it on in Settings > General > Live notifications. The Preview button runs a 40 second pretend read so you can see it safely. On Samsung phones, Live updates for other apps sit behind a switch in Developer options, and the same page has a shortcut.")
    }
    TryIt("Pick a command, press Run demo and watch the chip.")
}

// ---------------------------------------------------------------------------
// Dumps you can trust
// ---------------------------------------------------------------------------

private enum class DumpPhase { Idle, Running, Cut, Done, Verifying, Verified }

@Composable
internal fun DumpsPage() {
    val cs = MaterialTheme.colorScheme
    var phase by remember { mutableStateOf(DumpPhase.Idle) }
    var progress by remember { mutableFloatStateOf(0f) }
    var verify by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(phase) {
        when (phase) {
            DumpPhase.Running -> {
                while (progress < 1f) {
                    delay(40)
                    progress = (progress + 0.01f).coerceAtMost(1f)
                }
                phase = DumpPhase.Done
            }
            DumpPhase.Verifying -> {
                verify = 0f
                while (verify < 1f) {
                    delay(30)
                    verify = (verify + 0.02f).coerceAtMost(1f)
                }
                phase = DumpPhase.Verified
            }
            else -> Unit
        }
    }

    val status = when (phase) {
        DumpPhase.Idle -> "Ready"
        DumpPhase.Running -> "Streaming to your folder, hashing as it writes"
        DumpPhase.Cut -> "Cable dropped at ${(progress * 100).roundToInt()}%. The part that was written is kept"
        DumpPhase.Done -> "Saved. SHA-256 recorded next to the file"
        DumpPhase.Verifying -> "Verifying ${(verify * 100).roundToInt()}%"
        DumpPhase.Verified -> "Matches the saved hash"
    }
    val good = phase == DumpPhase.Verified
    val tick by animateFloatAsState(
        if (good) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "tick"
    )
    val barValue = if (phase == DumpPhase.Verifying) verify else progress

    PageHead(
        "Dumps", "Backups you can trust",
        "Dumps stream straight to a folder you choose, and every image gets a SHA-256 saved beside it, so you can prove a backup is intact before you rely on it.",
        Icons.Filled.Archive, HeroMotion.Bounce
    )
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (good) Color(0xFF2E9E57) else cs.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (good) Icons.Filled.CheckCircle else Icons.Filled.Archive, null,
                    tint = if (good) Color.White else cs.onPrimaryContainer,
                    modifier = Modifier.size(22.dp).graphicsLayer {
                        val s = if (good) 0.6f + 0.4f * tick else 1f
                        scaleX = s
                        scaleY = s
                    }
                )
            }
            Column(Modifier.weight(1f)) {
                Text("userdata.img", style = MaterialTheme.typography.titleSmall, color = cs.onSurface, fontFamily = FontFamily.Monospace)
                AnimatedContent(
                    targetState = status,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "dumpStatus"
                ) { s -> Text(s, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            }
        }
        LinearProgressIndicator(progress = { barValue }, modifier = Modifier.fillMaxWidth().height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            when (phase) {
                DumpPhase.Idle -> Button(onClick = { progress = 0f; phase = DumpPhase.Running }) { Text("Start dump") }
                DumpPhase.Running -> FilledTonalButton(onClick = { phase = DumpPhase.Cut }) { Text("Pull the cable") }
                DumpPhase.Cut -> Button(onClick = { phase = DumpPhase.Running }) {
                    Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Resume")
                }
                DumpPhase.Done -> Button(onClick = { phase = DumpPhase.Verifying }) { Text("Verify now") }
                DumpPhase.Verifying -> Button(onClick = {}, enabled = false) { Text("Verifying") }
                DumpPhase.Verified -> FilledTonalButton(onClick = { progress = 0f; verify = 0f; phase = DumpPhase.Idle }) { Text("Run again") }
            }
        }
    }
    Panel {
        Text("What else is in the Dumps tab", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
        Caption("Resume: a read that was cut short continues from where it stopped. With Settings > General > Resume cut-short dumps automatically on, it does that by itself when the device comes back.")
        Caption("Compare: pick two dumps and compare them. Restore: write a dump back to the device, after a review and a confirmation.")
        Caption("Before a big dump starts, the app checks there is enough free space in the folder.")
    }
    TryIt("Start the dump, pull the cable, resume it, then verify.")
}
