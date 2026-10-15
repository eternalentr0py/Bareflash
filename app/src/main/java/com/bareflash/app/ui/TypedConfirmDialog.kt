package com.bareflash.app.ui

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bareflash.app.ui.theme.Nc

/**
 * A tap on Run is too cheap for the most destructive operations, so the
 * operator has to type [phrase] (a partition or command name) exactly
 * before the confirm button enables. Case-insensitive, surrounding spaces ignored.
 */
@Composable
fun TypedConfirmDialog(
    title: String,
    body: String,
    phrase: String,
    confirmLabel: String = "Confirm",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var typed by remember { mutableStateOf("") }
    val ok = typed.trim().equals(phrase, ignoreCase = true)
    NcAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(body)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    label = { Text("Type \"$phrase\" to continue") }
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = ok) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The destructive-command confirmation: the resolved command, the image and
 * partition sizes it was checked against, host and link checks, optional safety steps
 * (back up first, read back after) and, for userdata, full flashes, RPMB and seccfg, a
 * typed confirmation instead of a bare Run.
 */
@Composable
fun WriteConfirmDialog(
    p: com.bareflash.app.console.PendingCommand,
    offer: com.bareflash.app.console.SafetyNet.Offer,
    findings: List<com.bareflash.app.console.Preflight.Finding>,
    onRun: (com.bareflash.app.console.WriteOptions) -> Unit,
    onCancel: () -> Unit,
) {
    val check = p.check
    val phrase = check?.typedPhrase
    var typed by remember { mutableStateOf("") }
    var backup by remember { mutableStateOf(offer.backupDefault) }
    var verify by remember { mutableStateOf(offer.verifyDefault) }
    val hasStop = findings.any { it.level == com.bareflash.app.console.Preflight.Level.Stop }
    var ack by remember { mutableStateOf(false) }
    val ok = (phrase == null || typed.trim().equals(phrase, ignoreCase = true)) && (!hasStop || ack)
    fun n(v: Long) = "%,d bytes".format(v)
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    NcAlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Confirm destructive command") },
        text = {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "This can erase or overwrite data on the connected device and cannot be undone.",
                )
                Text(
                    "$ ${p.finalCommand}",
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                )
                if (check != null) {
                    if (check.imageBytes >= 0) {
                        Text(
                            "Image: ${n(check.imageBytes)}" + when {
                                check.sparse && check.expandedBytes > 0 -> " (sparse, expands to ${n(check.expandedBytes)})"
                                check.sparse -> " (sparse)"
                                else -> ""
                            }
                        )
                    }
                    if (check.gptBytes >= 0) Text("Partition ${check.partition.orEmpty()}: ${n(check.gptBytes)}")
                    check.notes.forEach {
                        Text(it, color = scheme.error, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                }
                findings.forEach { f ->
                    Text(
                        f.text,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = when (f.level) {
                            com.bareflash.app.console.Preflight.Level.Stop -> scheme.error
                            com.bareflash.app.console.Preflight.Level.Warn -> Nc.Warn
                            com.bareflash.app.console.Preflight.Level.Info -> Nc.Muted
                        }
                    )
                }
                if (offer.partition != null) {
                    if (offer.backupAvailable) {
                        NcToggle("Back up ${offer.partition} first", backup, { backup = it }, offer.backupNote)
                    } else if (offer.backupNote.isNotBlank()) {
                        Text(offer.backupNote, color = Nc.Muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                    if (offer.verifyAvailable) {
                        NcToggle("Verify by reading it back", verify, { verify = it }, offer.verifyNote)
                    } else if (offer.verifyNote.isNotBlank()) {
                        Text(offer.verifyNote, color = Nc.Muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                }
                if (hasStop) NcToggle("Run anyway", ack, { ack = it }, "I understand the risk of writing on this battery.")
                if (phrase != null) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        label = { Text("Type \"$phrase\" to run") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRun(com.bareflash.app.console.WriteOptions(backupFirst = backup && offer.backupAvailable, verifyAfter = verify && offer.verifyAvailable)) },
                enabled = ok
            ) { Text("Run") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}
