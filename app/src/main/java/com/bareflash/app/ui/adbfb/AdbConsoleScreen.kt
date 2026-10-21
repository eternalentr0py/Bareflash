package com.bareflash.app.ui.adbfb

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bareflash.app.AppGraph
import com.bareflash.app.adbfb.AdbFbViewModel
import com.bareflash.app.console.shell.HistoryNav
import com.bareflash.app.console.shell.LineEdit
import com.bareflash.app.engine.EngineMode
import com.bareflash.app.ui.NcAlertDialog
import com.bareflash.app.ui.NcHeader
import com.bareflash.app.ui.TypedConfirmDialog
import com.bareflash.app.ui.ncClickable
import com.bareflash.app.ui.terminalColors
import com.bareflash.app.ui.terminalTextStyle
import com.bareflash.app.ui.theme.Nc
import com.bareflash.cli.LineKind
import com.bareflash.cli.RiskLevel

/**
 * The ADB / fastboot console: type commands the way you would on a computer.
 * Parsing, risk checks and execution live in the `:debugcli` module; this
 * screen is the terminal around them (look from the shared terminal settings).
 */
@Composable
fun AdbConsoleScreen(vm: AdbFbViewModel) {
    val lines by vm.console.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val prompt by vm.prompt.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val settings by AppGraph.settings.state.collectAsStateWithLifecycle()

    val t = settings.terminal
    val colors = terminalColors(t)
    val mono = terminalTextStyle(t)
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    var tfv by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val nav = remember { HistoryNav() }
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var stick by remember { mutableStateOf(true) }

    fun setText(text: String, cursor: Int = text.length) {
        tfv = TextFieldValue(text, TextRange(cursor.coerceIn(0, text.length)))
    }

    fun typeText(s: String) {
        val e = LineEdit.insert(tfv.text, tfv.selection.start, tfv.selection.end, s)
        setText(e.text, e.cursor)
        nav.reset()
    }

    fun submit() {
        val raw = tfv.text.trim()
        if (raw.isEmpty()) return
        nav.reset()
        tfv = TextFieldValue("")
        vm.submit(raw)
    }

    fun historyUp() {
        nav.up(tfv.text, vm.history(), false)?.let { setText(it) }
    }

    fun historyDown() {
        nav.down(vm.history())?.let { setText(it) }
    }

    fun complete() {
        val cursor = tfv.selection.end.coerceIn(0, tfv.text.length)
        val before = tfv.text.substring(0, cursor)
        val after = tfv.text.substring(cursor)
        val done = vm.complete(before)
        setText(done + after, done.length)
        nav.reset()
    }

    fun interrupt() {
        if (busy) vm.cancelRun() else setText("")
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            stick = last != null && last.index >= info.totalItemsCount - 2
        }
    }
    // Follow new output and typing. Every console update is a new list, so this runs per update.
    LaunchedEffect(lines, tfv.text.length) {
        if (stick) {
            val last = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
            listState.scrollToItem(last, Int.MAX_VALUE)
        }
    }

    val extraKeys: List<Pair<String, () -> Unit>> = listOf(
        "TAB" to { complete() },
        "↑" to { historyUp() },
        "↓" to { historyDown() },
        "^C" to { interrupt() },
        "^L" to { vm.clearConsole() },
        "-" to { typeText("-") },
        "--" to { typeText("--") },
        "/" to { typeText("/") },
        "_" to { typeText("_") },
        "." to { typeText(".") },
        "~" to { typeText("~") },
        "|" to { typeText("|") },
        ">" to { typeText(">") },
        "'" to { typeText("'") },
    )

    Column(Modifier.fillMaxSize()) {
        NcHeader(kicker = EngineMode.ADB_FASTBOOT.short, title = "Console", trailing = {
            Row {
                IconButton(onClick = { clipboard.setText(AnnotatedString(vm.consoleText())) }) {
                    Icon(Icons.Filled.ContentCopy, "Copy the console")
                }
                IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, vm.consoleText())
                    }
                    context.startActivity(Intent.createChooser(send, "Share the console").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Icon(Icons.Filled.Share, "Share the console") }
                IconButton(onClick = { vm.clearConsole() }) { Icon(Icons.Filled.DeleteSweep, "Clear the console") }
            }
        })
        Spacer(Modifier.height(8.dp))

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(t.cornerDp.dp))
                .background(colors.bg)
        ) {
            Column(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            focus.requestFocus()
                            keyboard?.show()
                        }
                        .padding(horizontal = t.paddingDp.dp, vertical = 8.dp)
                ) {
                    itemsIndexed(lines) { _, line ->
                        val color = when (line.kind) {
                            LineKind.COMMAND -> colors.command
                            LineKind.OUTPUT -> colors.fg
                            LineKind.ERROR_OUTPUT -> colors.warn
                            LineKind.INFO -> colors.dim
                            LineKind.ERROR -> colors.error
                        }
                        Text(if (line.text.isEmpty()) " " else line.text, style = mono, color = color)
                    }
                    item(key = "prompt") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(prompt, style = mono, color = colors.prompt.copy(alpha = if (busy) 0.45f else 1f))
                            BasicTextField(
                                value = tfv,
                                onValueChange = { nv ->
                                    if (nv.text != tfv.text) nav.reset()
                                    tfv = nv.copy(text = nv.text.replace('\n', ' ').replace('\r', ' '))
                                },
                                readOnly = busy,
                                singleLine = true,
                                textStyle = mono.copy(color = colors.fg),
                                cursorBrush = SolidColor(colors.cursor),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Go
                                ),
                                keyboardActions = KeyboardActions(onGo = { submit() }),
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(focus)
                                    .onPreviewKeyEvent { ev ->
                                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        when {
                                            ev.key == Key.Enter || ev.key == Key.NumPadEnter -> { submit(); true }
                                            ev.key == Key.Tab -> { complete(); true }
                                            ev.key == Key.DirectionUp -> { historyUp(); true }
                                            ev.key == Key.DirectionDown -> { historyDown(); true }
                                            ev.isCtrlPressed && ev.key == Key.C ->
                                                if (!tfv.selection.collapsed) false else { interrupt(); true }
                                            ev.isCtrlPressed && ev.key == Key.L -> { vm.clearConsole(); true }
                                            ev.isCtrlPressed && ev.key == Key.U -> {
                                                val e = LineEdit.killToStart(tfv.text, tfv.selection.end)
                                                setText(e.text, e.cursor)
                                                true
                                            }
                                            else -> false
                                        }
                                    }
                            )
                            if (busy) {
                                Box(
                                    Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Nc.Err.copy(alpha = 0.2f))
                                        .ncClickable { vm.cancelRun() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.Stop, contentDescription = "Stop the command", tint = Nc.Err, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }

                progress?.let { p ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = t.paddingDp.dp, vertical = 6.dp)) {
                        val fraction = p.fraction
                        Text(
                            p.label + (fraction?.let { "  ${(it * 100).toInt()}%" } ?: ""),
                            style = mono.copy(fontSize = 11.sp, lineHeight = 14.sp),
                            color = colors.dim,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.fg.copy(alpha = 0.14f))) {
                            Box(
                                Modifier
                                    .fillMaxWidth(fraction ?: 0.05f)
                                    .fillMaxHeight()
                                    .background(colors.prompt)
                            )
                        }
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    extraKeys.forEach { (label, action) ->
                        Box(
                            Modifier
                                .height(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.fg.copy(alpha = 0.10f))
                                .ncClickable { action() }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, style = mono.copy(fontSize = 12.sp, lineHeight = 14.sp), color = colors.fg)
                        }
                    }
                }
            }
        }
    }

    pending?.let { p ->
        val body = p.risk.reason + "\n\n" + p.line
        val phrase = p.risk.phrase
        if (p.risk.level == RiskLevel.DANGEROUS && !phrase.isNullOrBlank()) {
            TypedConfirmDialog(
                title = "Run this command?",
                body = body,
                phrase = phrase,
                confirmLabel = "Run",
                onConfirm = { vm.confirmPending() },
                onDismiss = { vm.dismissPending() }
            )
        } else {
            NcAlertDialog(
                onDismissRequest = { vm.dismissPending() },
                confirmButton = { TextButton(onClick = { vm.confirmPending() }) { Text("Run") } },
                dismissButton = { TextButton(onClick = { vm.dismissPending() }) { Text("Cancel") } },
                title = { Text("Run this command?") },
                text = { Text(body) }
            )
        }
    }
}
