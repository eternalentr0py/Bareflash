package com.bareflash.app.ui.adbfb

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bareflash.app.adbfb.AdbFbLinkState
import com.bareflash.app.adbfb.AdbFbViewModel
import com.bareflash.app.engine.EngineMode
import com.bareflash.app.ui.NcCaption
import com.bareflash.app.ui.NcHeader
import com.bareflash.app.ui.NcSection
import com.bareflash.app.ui.ncTile
import com.bareflash.app.ui.theme.Nc
import com.bareflash.cli.ArgKind
import com.bareflash.cli.CatalogCommand
import com.bareflash.cli.CommandCatalog
import com.bareflash.cli.FillResult
import com.bareflash.cli.RiskLevel
import com.bareflash.cli.TemplateFiller
import com.bareflash.usb.DebugProtocol

/**
 * Ready-made commands for people who do not want to type: grouped, searchable,
 * each with the blanks it needs. Running one puts the command in the console,
 * so what happens is always visible and can be repeated from the history.
 */
@Composable
fun AdbCommandsScreen(vm: AdbFbViewModel) {
    val link by vm.link.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    val connected: DebugProtocol? = if (link.state == AdbFbLinkState.CONNECTED) link.protocol else null
    var browse by rememberSaveable { mutableStateOf(DebugProtocol.ADB.name) }
    val protocol = connected ?: DebugProtocol.valueOf(browse)
    var query by rememberSaveable { mutableStateOf("") }

    val all = remember(protocol) { CommandCatalog.forProtocol(protocol) }
    val shown = remember(all, query) {
        val q = query.trim()
        if (q.isEmpty()) all else all.filter {
            it.title.contains(q, ignoreCase = true) || it.description.contains(q, ignoreCase = true) ||
                it.template.contains(q, ignoreCase = true) || it.category.contains(q, ignoreCase = true)
        }
    }
    val categories = remember(shown) { shown.map { it.category }.distinct() }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { NcHeader(kicker = EngineMode.ADB_FASTBOOT.short, title = "Commands") }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (connected == null) {
                    NcCaption("Connect a device on the Devices tab to run these. You can browse them now.")
                    Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { browse = DebugProtocol.ADB.name }) {
                            Text("ADB", color = if (protocol == DebugProtocol.ADB) Nc.Fg else Nc.Muted)
                        }
                        TextButton(onClick = { browse = DebugProtocol.FASTBOOT.name }) {
                            Text("Fastboot", color = if (protocol == DebugProtocol.FASTBOOT) Nc.Fg else Nc.Muted)
                        }
                    }
                } else {
                    NcCaption(
                        if (connected == DebugProtocol.ADB) "Commands for the connected Android device."
                        else "Commands for the connected fastboot device."
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Search") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                )
            }
        }

        if (shown.isEmpty()) {
            item { NcCaption("Nothing matches \"$query\".") }
        }

        categories.forEach { category ->
            item(key = "cat-$category") {
                NcSection(category) {
                    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        shown.filter { it.category == category }.forEach { c ->
                            CommandCard(
                                c = c,
                                runnable = connected == c.requires && !busy,
                                why = when {
                                    connected == null -> "Not connected"
                                    connected != c.requires -> if (c.requires == DebugProtocol.ADB) "Needs an Android device" else "Needs fastboot mode"
                                    busy -> "Another command is running"
                                    else -> null
                                },
                                onRun = { line -> vm.runFromCatalog(line) }
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun CommandCard(c: CatalogCommand, runnable: Boolean, why: String?, onRun: (String) -> Unit) {
    val values = remember(c.id) { mutableStateMapOf<String, String>() }
    var problem by remember(c.id) { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxWidth().ncTile().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.title, style = MaterialTheme.typography.titleSmall, color = Nc.Fg, modifier = Modifier.weight(1f))
            when (c.risk) {
                RiskLevel.CAUTION -> RiskBadge("Asks first", Nc.Warn)
                RiskLevel.DANGEROUS -> RiskBadge("Type to confirm", Nc.Err)
                RiskLevel.SAFE -> Unit
            }
        }
        if (c.description.isNotBlank()) {
            Text(c.description, style = MaterialTheme.typography.bodySmall, color = Nc.Muted)
        }
        Text(c.template, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, color = Nc.Muted)

        c.args.forEach { a ->
            OutlinedTextField(
                value = values[a.name] ?: "",
                onValueChange = {
                    values[a.name] = it
                    problem = null
                },
                singleLine = true,
                label = { Text(a.label) },
                placeholder = { Text(a.default.ifEmpty { a.hint }) },
                keyboardOptions = if (a.kind == ArgKind.NUMBER) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth()
            )
        }
        problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Nc.Err) }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = runnable,
                onClick = {
                    when (val r = TemplateFiller.fill(c, values.toMap())) {
                        is FillResult.Ok -> {
                            problem = null
                            onRun(r.line)
                        }
                        is FillResult.Missing -> problem = "Fill in: ${r.label}"
                        is FillResult.Invalid -> problem = "${r.label} ${r.why}"
                    }
                }
            ) { Text("Run") }
            if (!runnable && why != null) {
                Text(why, style = MaterialTheme.typography.bodySmall, color = Nc.Muted)
            }
        }
    }
}

@Composable
private fun RiskBadge(label: String, color: Color) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}
