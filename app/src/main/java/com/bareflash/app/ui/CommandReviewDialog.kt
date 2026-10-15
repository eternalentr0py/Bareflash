@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.bareflash.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.net.Uri
import com.bareflash.app.AppGraph
import com.bareflash.app.catalog.McArg
import com.bareflash.app.catalog.McChoice
import com.bareflash.app.catalog.McCommand
import com.bareflash.app.console.CommandRules
import com.bareflash.app.console.DumpSpaceGuard
import com.bareflash.app.console.GptPartition
import com.bareflash.app.dump.DumpBridge
import com.bareflash.app.ui.theme.LocalLook
import com.bareflash.app.ui.theme.Nc
import com.bareflash.app.ui.theme.PlexMono

/**
 * The single review step for a command: its arguments, then Run.
 *
 * - An argument that only accepts a few values is shown as buttons (or
 *   chips) to pick from, not a text field.
 * - An argument that reads a file has an Attach button: the file is copied
 *   into the dumps folder and shown as a chip you can remove.
 * - Everything else is a normal text field.
 *
 * A bottom sheet rather than a centred dialog, so a long argument list or
 * the destructive-command warning has room.
 */
@Composable
internal fun CommandReviewDialog(
    command: McCommand,
    protocolNotes: List<String>,
    partitions: List<GptPartition> = emptyList(),
    onDismiss: () -> Unit,
    onSavePreset: ((String) -> Unit)? = null,
    onRun: (String) -> Unit
) {
    val ctx = LocalContext.current
    val destructive = remember(command.cmd) { CommandRules.isDestructive(command.cmd) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Last-used arguments: restored when the count still matches the
    // catalog, otherwise the catalog defaults.
    val saved = remember(command) {
        AppGraph.settings.state.value.lastReviewArgs[command.cmd]?.takeIf { it.size == command.args.size }
    }
    val values = remember(command) { mutableStateOf(saved ?: command.args.map { it.default }) }
    val usingSaved = command.args.isNotEmpty() && saved != null && values.value == saved
    fun setValue(i: Int, v: String) {
        values.value = values.value.toMutableList().also { it[i] = v }
    }
    val built = remember(values.value) { buildCommand(command, values.value) }
    val dumpsDir = remember { DumpBridge.localDumps() }
    val sectorSize = AppGraph.settings.state.value.protocols.sectorSize
    val space = remember(built, sectorSize, partitions) {
        built?.let { DumpSpaceGuard.check(it, dumpsDir, sectorSize, partitions) }
    }
    val spacing = (12 * LocalLook.current.spacingScale).dp

    // ---- attaching files -------------------------------------------------
    // Attach never copies anything. The picker opens in your chosen dump
    // folder; the command then reads the file in place (a file in that folder
    // by its path there, a file elsewhere through its own URI), the same way
    // dumps are written straight to that folder.
    var attachTarget by remember { mutableStateOf(-1) }
    var attachError by remember { mutableStateOf<String?>(null) }
    val initial = remember { DumpBridge.currentUri() }

    fun attach(index: Int, uris: List<Uri>) {
        if (uris.isEmpty() || index < 0) return
        val arg = command.args[index]
        attachError = null
        val entries = mutableListOf<String>()
        for (uri in uris) {
            val a = DumpBridge.attachInput(uri)
            if (a == null) { attachError = "Could not open that file."; continue }
            entries += if (arg.pathUnderDumps) a.value else "dumps/${a.value}"
        }
        if (entries.isNotEmpty()) {
            val existing = if (arg.attachMulti) splitList(values.value[index]) else emptyList()
            setValue(index, (existing + entries).distinct().joinToString(","))
        }
    }

    val single = rememberLauncherForActivityResult(OpenFrom(initial)) { uri ->
        val i = attachTarget; attachTarget = -1
        if (uri != null) attach(i, listOf(uri))
    }
    val multi = rememberLauncherForActivityResult(OpenManyFrom(initial)) { uris ->
        val i = attachTarget; attachTarget = -1
        attach(i, uris)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Nc.VoidSoft,
        contentColor = Nc.Fg
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(spacing)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(command.title, style = MaterialTheme.typography.headlineSmall, color = Nc.Fg)
                    Text(
                        command.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Nc.Muted,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (usingSaved) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text("Last used values") },
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = Nc.Muted)
                }
            }

            if (destructive) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Text(
                            "Destructive. This writes or erases data on the device \u2014 confirm the target and file before you run.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            command.args.forEachIndexed { i, arg ->
                if (arg.choices.isNotEmpty()) {
                    ChoiceField(arg, values.value[i]) { setValue(i, it) }
                } else {
                    OutlinedTextField(
                        value = values.value[i],
                        onValueChange = { setValue(i, it) },
                        label = { Text(arg.label + if (arg.optional && !arg.label.contains("optional", ignoreCase = true)) " (optional)" else "") },
                        placeholder = { Text(arg.hint) },
                        singleLine = true,
                        trailingIcon = if (arg.attachable) {
                            {
                                IconButton(
                                                                        onClick = {
                                        attachTarget = i
                                        if (arg.attachMulti) multi.launch(arrayOf("*/*")) else single.launch(arrayOf("*/*"))
                                    }
                                ) { Icon(Icons.Filled.AttachFile, contentDescription = "Attach ${if (arg.attachMulti) "files" else "a file"}") }
                            }
                        } else null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (arg.attachable) {
                        AttachedFiles(
                            entries = splitList(values.value[i]),
                            onRemove = { entry ->
                                DumpBridge.forgetInput(entry.removePrefix("dumps/"))
                                setValue(i, splitList(values.value[i]).filter { it != entry }.joinToString(","))
                            }
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = attachError != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Text(attachError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Nc.Err)
            }

            if (protocolNotes.isNotEmpty()) {
                Text(
                    "Applied from Settings \u2192 MediaTek: " + protocolNotes.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall, color = Nc.Muted
                )
            }

            Surface(color = Nc.PanelHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Text(
                    built?.let { "$ $it" } ?: "Fill in the required fields above.",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = PlexMono),
                    color = Nc.Muted,
                    modifier = Modifier.padding(12.dp)
                )
            }

            when (val v = space) {
                is DumpSpaceGuard.Verdict.Blocked -> {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                        Text(
                            v.userMessage(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.fillMaxWidth().padding(12.dp)
                        )
                    }
                }
                DumpSpaceGuard.Verdict.Ok -> {
                    val free = DumpSpaceGuard.availableBytes(dumpsDir)
                    if (free >= 0L) {
                        Text(
                            "${DumpSpaceGuard.formatBytes(free)} free in ${DumpBridge.displayName()}. Userdata and full-flash dumps can be much larger.",
                            style = MaterialTheme.typography.bodySmall, color = Nc.Muted
                        )
                    }
                }
                else -> Unit
            }

            if (onSavePreset != null && built != null) {
                TextButton(onClick = { onSavePreset(built) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Save as preset")
                }
            }
            val canRun = built != null && space !is DumpSpaceGuard.Verdict.Blocked 
            Button(
                onClick = {
                    // Remember on Run only, never on Cancel/dismiss.
                    if (command.args.isNotEmpty()) {
                        val snapshot = values.value
                        AppGraph.settings.update { it.copy(lastReviewArgs = it.lastReviewArgs + (command.cmd to snapshot)) }
                    }
                    built?.let(onRun)
                },
                enabled = canRun,
                colors = if (destructive) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ) else ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Run", style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/** A pick-one argument: segmented buttons for a few short options, chips otherwise. */
@Composable
private fun ChoiceField(arg: McArg, value: String, onChange: (String) -> Unit) {
    val options: List<McChoice> = remember(arg) {
        buildList {
            if (arg.optional) add(McChoice("", "Default", "Leave this out and let the app decide."))
            addAll(arg.choices)
        }
    }
    val compact = options.size <= 3 || (options.size <= 5 && options.all { it.label.length <= 4 })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            arg.label + if (arg.optional && !arg.label.contains("optional", ignoreCase = true)) " (optional)" else "",
            style = MaterialTheme.typography.labelLarge,
            color = Nc.Muted
        )
        if (compact) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, o ->
                    SegmentedButton(
                        selected = value == o.value,
                        onClick = { onChange(o.value) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                        label = { Text(o.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { o ->
                    val on = value == o.value
                    FilterChip(
                        selected = on,
                        onClick = { onChange(o.value) },
                        label = { Text(o.label) },
                        leadingIcon = if (on) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null
                    )
                }
            }
        }
        val note = options.firstOrNull { it.value == value }?.description.orEmpty()
        AnimatedContent(
            targetState = note,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(100)) },
            label = "choiceNote"
        ) { text ->
            if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodySmall, color = Nc.Muted)
        }
    }
}

/** The files currently named in an attachable field, each removable. */
@Composable
private fun AttachedFiles(entries: List<String>, onRemove: (String) -> Unit) {
    AnimatedVisibility(
        visible = entries.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            entries.forEach { entry ->
                val name = entry.removePrefix("dumps/")
                val bytes = DumpBridge.size(name)
                val size = if (bytes >= 0) " \u00b7 " + DumpSpaceGuard.formatBytes(bytes) else ""
                InputChip(
                    selected = false,
                    onClick = {},
                    label = { Text(name + size, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove $name",
                            modifier = Modifier.size(18.dp).ncClickable { onRemove(entry) }
                        )
                    }
                )
            }
        }
    }
}

/** Document picker that opens in the chosen dump folder. */
internal class OpenFrom(private val start: Uri?) : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: android.content.Context, input: Array<String>) =
        super.createIntent(context, input).also { i ->
            if (start != null) i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, start)
        }
}

private class OpenManyFrom(private val start: Uri?) : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: android.content.Context, input: Array<String>) =
        super.createIntent(context, input).also { i ->
            if (start != null) i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, start)
        }
}

private fun splitList(v: String): List<String> = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }

/** Same rule the app has always used: null if a required field is blank. */
private fun buildCommand(command: McCommand, values: List<String>): String? {
    val tokens = mutableListOf(command.cmd)
    for ((arg, raw) in command.args.zip(values)) {
        val value = raw.trim()
        if (value.isEmpty()) {
            if (!arg.optional) return null
            continue
        }
        val finalValue = if (arg.pathUnderDumps) {
            if (value.startsWith("dumps/") || value.startsWith("/")) value else "dumps/$value"
        } else value
        val quoted = if (finalValue.contains(" ")) "\"$finalValue\"" else finalValue
        if (arg.flag != null) { tokens += arg.flag; tokens += quoted } else tokens += quoted
    }
    return tokens.joinToString(" ")
}
