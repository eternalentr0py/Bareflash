package com.bareflash.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bareflash.app.console.ConsoleViewModel
import com.bareflash.app.console.JobSummary
import com.bareflash.app.dump.WriteMarker
import com.bareflash.app.ui.theme.Nc
import com.bareflash.app.ui.theme.PlexMono

/**
 * Shown above the terminal when a write did not finish: the app saved "a write is in
 * progress" before it started and it was never cleared by a success.
 */
@Composable
internal fun WriteMarkerBanner(
    marker: WriteMarker.Record,
    onRerun: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val what = when (marker.state) {
        "running" -> "The app closed, or the cable came out, while it was running."
        "stopped" -> "It was stopped part-way."
        else -> "It ended with an error."
    }
    Column(
        Modifier.fillMaxWidth().ncTile().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "The last write to ${marker.target} did not finish",
            style = MaterialTheme.typography.titleSmall, color = Nc.Warn
        )
        Text(
            "$what The partition may be half-written. Don't reboot the phone into Android yet; run the write again until it succeeds.",
            style = MaterialTheme.typography.bodySmall, color = Nc.Muted
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (onRerun != null) TextButton(onClick = onRerun) { Text("Run it again") }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/** One card after a job ends: time, size, speed, destination, hashes and any read-back result. */
@Composable
internal fun JobSummaryCard(
    s: JobSummary,
    onDismiss: () -> Unit,
    onResume: ((String) -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val outcome = when (s.outcome) {
        ConsoleViewModel.RESULT_OK -> "Done" to Nc.Ok
        ConsoleViewModel.RESULT_CANCELLED -> "Stopped" to Nc.Warn
        else -> "Failed" to Nc.Err
    }
    val mono = TextStyle(fontFamily = PlexMono, fontSize = 11.sp)
    Column(
        Modifier.fillMaxWidth().ncTile().padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    buildString {
                        append(s.title)
                        if (s.target.isNotBlank()) append(" \u00b7 ").append(s.target)
                    },
                    style = MaterialTheme.typography.titleSmall, color = Nc.Fg,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(outcome.first, style = MaterialTheme.typography.labelMedium, color = outcome.second)
            }
            if (s.hashes.isNotEmpty()) {
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(s.hashes.joinToString("\n") { "${it.second}  ${it.first.substringAfterLast('/')}" }))
                }) { Icon(Icons.Filled.ContentCopy, "Copy checksums", Modifier.size(16.dp), tint = Nc.Muted) }
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Dismiss", Modifier.size(16.dp), tint = Nc.Muted) }
        }
        val facts = buildList {
            add(ConsoleViewModel.formatElapsed(s.elapsedMs))
            if (s.bytes > 0L) add(ConsoleViewModel.formatBytes(s.bytes))
            ConsoleViewModel.formatRate(s.avgBps).takeIf { it.isNotEmpty() }?.let { add("avg $it") }
            s.destination?.let { add("\u2192 $it") }
        }
        Text(facts.joinToString(" \u00b7 "), style = mono, color = Nc.Muted)
        s.hashes.take(4).forEach { (file, sha) ->
            Text(
                "${sha.take(16)}\u2026  ${file.substringAfterLast('/')}",
                style = mono, color = Nc.Subtle, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (s.hashes.size > 4) Text("+ ${s.hashes.size - 4} more (copy for all)", style = mono, color = Nc.Subtle)
        s.verify?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = when (s.verifyOk) { true -> Nc.Ok; false -> Nc.Err; null -> Nc.Muted },
                modifier = Modifier.padding(end = 12.dp)
            )
        }
        s.warnings.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = Nc.Warn, modifier = Modifier.padding(end = 12.dp))
        }
        s.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Nc.Err, modifier = Modifier.padding(end = 12.dp)) }
        s.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Nc.Muted, modifier = Modifier.padding(end = 12.dp)) }
        s.resumable?.let { rel ->
            if (onResume != null) TextButton(onClick = { onResume(rel) }) { Text("Resume this dump") }
        }
    }
}
