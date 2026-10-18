package com.bareflash.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bareflash.app.AppGraph
import com.bareflash.app.console.ConsoleViewModel
import com.bareflash.app.console.GptPartition
import com.bareflash.app.console.logging.LogCategory
import com.bareflash.app.console.logging.LogColorizer
import com.bareflash.app.console.shell.CommandDoc
import com.bareflash.app.console.shell.Completer
import com.bareflash.app.console.shell.Ctrl
import com.bareflash.app.console.shell.CtrlAction
import com.bareflash.app.console.shell.Edit
import com.bareflash.app.console.shell.ExtraKeys
import com.bareflash.app.console.shell.HistoryNav
import com.bareflash.app.console.shell.LineEdit
import com.bareflash.app.console.shell.Prompt
import com.bareflash.app.console.shell.Shell
import com.bareflash.app.console.shell.ShellEnv
import com.bareflash.app.console.shell.ShellResult
import com.bareflash.app.console.shell.Suggest
import com.bareflash.app.data.CursorShape
import com.bareflash.app.data.ExtraKeysMode
import com.bareflash.app.data.KeyboardMode
import com.bareflash.app.data.TerminalSettings
import com.bareflash.app.data.TitleMode
import com.bareflash.app.engine.Catalogs
import com.bareflash.app.engine.EngineMode
import com.bareflash.app.ui.theme.LocalNcAccent
import com.bareflash.app.ui.theme.Nc
import kotlinx.coroutines.launch

/** What `\m` in a prompt stands for. */
internal fun engineWord(mode: EngineMode): String = when (mode) {
    EngineMode.MTK -> "mtk"
    EngineMode.QDL -> "qdl"
    EngineMode.BROKKR -> "odin"
    EngineMode.ADB_FASTBOOT -> "adb"
}

private fun lineColor(line: String, c: TerminalColors, byLevel: Boolean): Color {
    if (!byLevel) return c.fg
    return when (LogColorizer.classify(line)) {
        LogCategory.ERROR -> c.error
        LogCategory.CONNECTED -> c.ok
        LogCategory.DISCONNECTED -> c.warn
        LogCategory.WARNING -> c.warn
        LogCategory.COMMAND -> c.command
        LogCategory.DEFAULT -> c.fg
    }
}

private const val ECHO = "$ "

/**
 * The terminal: scrolling output with a bash-style prompt at the bottom. Enter runs the line (there is no
 * send button), Tab completes, Up and Down walk the history, and the whole look is driven by
 * [TerminalSettings].
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun ColumnScope.TerminalCard(
    vm: ConsoleViewModel,
    text: String,
    lines: List<String>,
    t: TerminalSettings,
    mode: EngineMode,
    running: Boolean,
    hasError: Boolean,
    /** Commands typed in this engine, newest first. */
    history: List<String>,
    partitions: List<GptPartition>,
    onRequestCancel: () -> Unit
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val accent = LocalNcAccent.current
    val colors = terminalColors(t)
    val mono = terminalTextStyle(t)
    val imeVisible = WindowInsets.isImeVisible

    var menuOpen by remember { mutableStateOf(false) }
    var tfv by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val nav = remember { HistoryNav() }
    var ctrlArmed by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    val user = Prompt.cleanName(t.userName, "user")
    val host = Prompt.cleanName(t.hostName, "bareflash")
    val promptStr = Prompt.format(t.ps1, user, host, engineWord(mode))
    val lineTwoPrompt = Prompt.format("\\$ ", user, host, engineWord(mode))

    val commands = remember(mode) { Catalogs.all(mode) }
    val docs = remember(commands) { commands.map { CommandDoc.from(it) } }

    // ---- editing helpers ---------------------------------------------------------------

    fun setEdit(e: Edit) { tfv = TextFieldValue(e.text, TextRange(e.cursor)) }
    fun typeText(s: String) {
        val sel = tfv.selection
        setEdit(LineEdit.insert(tfv.text, sel.start, sel.end, s))
        nav.reset()
    }
    fun buzz() { if (t.keyHaptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    fun echo(raw: String) = vm.log("$ECHO$raw\n")

    val suggestion: String? =
        if (t.autosuggest && !running && tfv.selection.collapsed && tfv.selection.end == tfv.text.length)
            Suggest.forInput(tfv.text, history)
        else null

    fun acceptSuggestion(): Boolean {
        val s = suggestion ?: return false
        setEdit(Edit(tfv.text + s, tfv.text.length + s.length))
        nav.reset()
        return true
    }

    fun submit() {
        if (running) return
        val raw = tfv.text.trim()
        if (raw.isEmpty()) return
        nav.reset()
        tfv = TextFieldValue("")
        val env = ShellEnv(user, host, mode.id, mode.label, history, t.aliases, docs)
        when (val r = Shell.run(raw, env)) {
            is ShellResult.Run -> vm.requestRun(r.command)
            is ShellResult.Print -> { echo(raw); if (r.text.isNotEmpty()) vm.log(r.text + "\n") }
            ShellResult.ClearScreen -> vm.clearTerminal()
            is ShellResult.ClearHistory -> {
                AppGraph.settings.clearHistory(mode)
                echo(raw); vm.log(r.text + "\n")
            }
            is ShellResult.SetAlias -> {
                AppGraph.settings.update { it.copy(terminal = it.terminal.copy(aliases = it.terminal.aliases + (r.name to r.value))) }
                echo(raw); vm.log(r.text + "\n")
            }
            is ShellResult.RemoveAlias -> {
                AppGraph.settings.update { it.copy(terminal = it.terminal.copy(aliases = it.terminal.aliases - r.name)) }
                echo(raw); vm.log(r.text + "\n")
            }
        }
        if (!t.keepKeyboardOpen) keyboard?.hide()
    }

    fun complete() {
        if (!t.tabComplete || running) return
        val names = buildList {
            commands.filter { it.unsupported == null }.forEach { add(it.cmd) }
            addAll(Shell.BUILTINS)
            addAll(t.aliases.keys)
        }
        val before = tfv.text.substring(0, tfv.selection.end.coerceIn(0, tfv.text.length))
        val after = tfv.text.substring(tfv.selection.end.coerceIn(0, tfv.text.length))
        val result = Completer.complete(before, names) { first ->
            val real = (t.aliases[first] ?: first).substringBefore(' ')
            if (real in Shell.BUILTINS) {
                if (real == "help" || real == "type" || real == "man") names else emptyList()
            } else {
                val c = commands.firstOrNull { it.cmd == real }
                if (c == null) emptyList() else buildList {
                    c.args.forEach { a ->
                        a.flag?.let { add(it) }
                        a.choices.forEach { ch -> add(ch.value) }
                    }
                    partitions.forEach { add(it.name) }
                }
            }
        }
        if (result.candidates.isNotEmpty()) {
            vm.log("$promptStr$before\n${Completer.columns(result.candidates)}\n")
        } else if (result.line != before) {
            setEdit(Edit(result.line + after, result.line.length))
            nav.reset()
        }
    }

    fun historyUp() {
        val h = nav.up(tfv.text, history, t.historyPrefixSearch) ?: return
        setEdit(Edit(h, h.length))
    }

    fun historyDown() {
        val h = nav.down(history) ?: return
        setEdit(Edit(h, h.length))
    }

    fun doCtrl(a: CtrlAction) {
        val sel = tfv.selection.end
        when (a) {
            CtrlAction.Interrupt -> {
                if (running) onRequestCancel()
                else if (tfv.text.isNotEmpty()) { vm.log("$ECHO${tfv.text}^C\n"); tfv = TextFieldValue(""); nav.reset() }
            }
            CtrlAction.ClearScreen -> vm.clearTerminal()
            CtrlAction.KillToStart -> { setEdit(LineEdit.killToStart(tfv.text, sel)); nav.reset() }
            CtrlAction.KillToEnd -> { setEdit(LineEdit.killToEnd(tfv.text, sel)); nav.reset() }
            CtrlAction.DeleteWord -> { setEdit(LineEdit.deleteWordBack(tfv.text, sel)); nav.reset() }
            CtrlAction.Home -> setEdit(Edit(tfv.text, 0))
            CtrlAction.End -> setEdit(Edit(tfv.text, tfv.text.length))
            CtrlAction.None -> Unit
        }
    }

    // Browse picks a file and inserts its name so a typed command ("w boot <name>") can use it. The file
    // is read where it is; nothing is copied into the app.
    val browseLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val attached = com.bareflash.app.dump.DumpBridge.attachInput(uri)
            if (attached != null) {
                val lead = if (tfv.text.isEmpty() || tfv.text.endsWith(" ")) "" else " "
                typeText(lead + attached.value)
            } else {
                vm.log("The selected file could not be read.\n")
            }
        }
    }

    fun pressKey(id: String) {
        buzz()
        when (id) {
            "tab" -> complete()
            "ctrl" -> ctrlArmed = !ctrlArmed
            "up" -> historyUp()
            "down" -> historyDown()
            "left" -> setEdit(LineEdit.moveBy(tfv.text, tfv.selection.end, -1))
            "right" -> if (!(tfv.selection.end == tfv.text.length && acceptSuggestion()))
                setEdit(LineEdit.moveBy(tfv.text, tfv.selection.end, 1))
            "home" -> doCtrl(CtrlAction.Home)
            "end" -> if (!(tfv.selection.end == tfv.text.length && acceptSuggestion())) doCtrl(CtrlAction.End)
            "ctrl_c" -> doCtrl(CtrlAction.Interrupt)
            "ctrl_l" -> doCtrl(CtrlAction.ClearScreen)
            "ctrl_u" -> doCtrl(CtrlAction.KillToStart)
            "dash" -> typeText("-")
            "dashdash" -> typeText("--")
            "slash" -> typeText("/")
            "underscore" -> typeText("_")
            "dot" -> typeText(".")
            "hex" -> typeText("0x")
            "paste" -> clipboard.getText()?.text?.let { typeText(it.replace('\n', ' ').replace('\r', ' ')) }
            "enter" -> submit()
        }
    }

    // ---- scrolling and find ---------------------------------------------------------------

    val listState = rememberLazyListState()
    var stick by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    var findOpen by remember { mutableStateOf(false) }
    var errorsOnly by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var errCursor by remember { mutableStateOf(-1) }
    val errorIdx = remember(lines, findOpen) {
        if (!findOpen) emptyList()
        else lines.withIndex().filter { LogColorizer.classify(it.value) == LogCategory.ERROR }.map { it.index }
    }
    val shown = remember(lines, findOpen, errorsOnly, findQuery) {
        if (!findOpen || (!errorsOnly && findQuery.isBlank())) null
        else lines.withIndex().filter { (_, l) ->
            (!errorsOnly || LogColorizer.classify(l) == LogCategory.ERROR) &&
                (findQuery.isBlank() || l.contains(findQuery, ignoreCase = true))
        }.map { it.index to it.value }
    }
    fun jumpToError(step: Int) {
        if (errorIdx.isEmpty()) return
        errCursor = if (errCursor !in errorIdx.indices) {
            if (step > 0) 0 else errorIdx.lastIndex
        } else (errCursor + step).mod(errorIdx.size)
        val target = errorIdx[errCursor]
        val pos = if (shown == null) target else shown.indexOfFirst { it.first == target }
        if (pos >= 0) {
            stick = false
            scope.launch { listState.scrollToItem(pos) }
        }
    }

    val rows: List<Pair<Int, String>> = remember(lines, shown) { shown ?: lines.mapIndexed { i, l -> i to l } }

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            stick = last != null && last.index >= info.totalItemsCount - 2
        }
    }
    // Follow new output and typing. This is deliberately not keyed on the keyboard: opening or closing it
    // resizes the list, and re-scrolling at the same moment as the system's own "show the field" scroll
    // made the terminal jump up and settle back. The list clamps to the end by itself when it resizes.
    LaunchedEffect(lines.size, tfv.text.length, t.autoScroll) {
        if (stick && t.autoScroll) {
            val last = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
            listState.scrollToItem(last, Int.MAX_VALUE)
        }
    }

    // ---- cursor ---------------------------------------------------------------------------

    val measurer = rememberTextMeasurer()
    val charWidthPx = remember(mono) { measurer.measure("M", mono).size.width.toFloat() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val reduceMotion = rememberTutorialReduceMotion()
    val blink by rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes<Float> {
                durationMillis = 1060
                1f at 0
                1f at 530
                0f at 531
                0f at 1060
            },
            RepeatMode.Restart
        ),
        label = "cursorBlink"
    )

    // ---- layout ---------------------------------------------------------------------------

    val iconTint = colors.fg.copy(alpha = 0.6f)
    val pad = t.paddingDp.dp
    val showKeys = when (t.extraKeysMode) {
        ExtraKeysMode.ALWAYS -> true
        ExtraKeysMode.WITH_KEYBOARD -> imeVisible
        ExtraKeysMode.NEVER -> false
    }

    Box(Modifier.weight(1f).fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(t.cornerDp.dp),
            color = colors.bg,
            border = if (t.showBorder) BorderStroke(1.dp, Nc.BorderSoft) else null
        ) {
            Column(Modifier.fillMaxSize()) {
                // Title strip: the window's own controls, so the prompt below is only a prompt.
                Row(
                    Modifier.fillMaxWidth().height(44.dp).padding(start = 14.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (running) accent.color else colors.dim))
                    Text(
                        when (t.titleMode) {
                            TitleMode.TERMINAL -> "Terminal"
                            TitleMode.SHELL -> "$user@$host"
                            TitleMode.HIDDEN -> ""
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (running) colors.fg.copy(alpha = 0.7f) else colors.dim,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp).weight(1f)
                    )
                    StripAction(Icons.Filled.AttachFile, "Attach a file", iconTint, enabled = !running) {
                        browseLauncher.launch(arrayOf("*/*"))
                    }
                    StripAction(Icons.Filled.Search, "Find in terminal", iconTint) { findOpen = !findOpen }
                    StripAction(Icons.Filled.DeleteSweep, "Clear terminal", iconTint, enabled = text.isNotEmpty()) { vm.clearTerminal() }
                    Box {
                        StripAction(Icons.Filled.MoreVert, "More", iconTint) { menuOpen = true }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            containerColor = Nc.Panel,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            val mc = MenuDefaults.itemColors(textColor = Nc.Fg, leadingIconColor = accent.color)
                            DropdownMenuItem(
                                text = { Text("Share terminal") }, colors = mc,
                                leadingIcon = { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)) },
                                onClick = {
                                    menuOpen = false
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, text)
                                    }
                                    ctx.startActivity(Intent.createChooser(send, "Share terminal"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Copy all") }, colors = mc, enabled = text.isNotEmpty(),
                                leadingIcon = { Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)) },
                                onClick = {
                                    menuOpen = false
                                    clipboard.setText(AnnotatedString(text))
                                    vm.toast("Terminal copied")
                                }
                            )
                            if (hasError) {
                                DropdownMenuItem(
                                    text = { Text("Copy error") }, colors = mc,
                                    leadingIcon = { Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)) },
                                    onClick = {
                                        menuOpen = false
                                        clipboard.setText(AnnotatedString(vm.errorReport().orEmpty()))
                                    }
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = Nc.BorderSoft)

                if (findOpen) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        BasicTextField(
                            value = findQuery,
                            onValueChange = { findQuery = it },
                            singleLine = true,
                            textStyle = mono.copy(color = colors.fg),
                            cursorBrush = SolidColor(colors.cursor),
                            decorationBox = { inner ->
                                Box {
                                    if (findQuery.isEmpty()) Text("find", style = mono, color = colors.dim)
                                    inner()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "Errors only", style = mono,
                            color = if (errorsOnly) accent.color else iconTint,
                            modifier = Modifier.ncClickable { errorsOnly = !errorsOnly }.padding(vertical = 6.dp)
                        )
                        Text(
                            if (errorIdx.isEmpty()) "no errors" else "${if (errCursor in errorIdx.indices) errCursor + 1 else 0}/${errorIdx.size}",
                            style = mono, color = iconTint
                        )
                        Text("Prev", style = mono, color = accent.color, modifier = Modifier.ncClickable(enabled = errorIdx.isNotEmpty()) { jumpToError(-1) }.padding(vertical = 6.dp))
                        Text("Next", style = mono, color = accent.color, modifier = Modifier.ncClickable(enabled = errorIdx.isNotEmpty()) { jumpToError(1) }.padding(vertical = 6.dp))
                    }
                    HorizontalDivider(color = Nc.BorderSoft)
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                focus.requestFocus()
                                keyboard?.show()
                            }
                            .padding(horizontal = pad, vertical = (pad.value * 0.8f).dp)
                    ) {
                        if (lines.isEmpty() && t.showBanner && shown == null) {
                            item(key = "banner") {
                                Column(Modifier.padding(bottom = 8.dp)) {
                                    Text("Bareflash shell  ·  ${mode.label}", style = mono, color = colors.fg)
                                    Text(
                                        "help lists commands. Tab completes, ↑ ↓ walk the history, Ctrl+C stops.",
                                        style = mono, color = colors.dim
                                    )
                                }
                            }
                        }
                        itemsIndexed(rows, key = { _, r -> r.first }) { _, (_, line) ->
                            val isEcho = line.startsWith(ECHO)
                            val shownText: AnnotatedString = if (isEcho) {
                                val cmd = line.removePrefix(ECHO)
                                buildAnnotatedString {
                                    val pc = if (t.colorPrompt) colors.prompt else colors.fg
                                    if (t.promptOwnLine) {
                                        withStyle(SpanStyle(color = pc)) { append(promptStr) }
                                        append("\n")
                                        withStyle(SpanStyle(color = pc)) { append(lineTwoPrompt) }
                                    } else {
                                        withStyle(SpanStyle(color = pc)) { append(promptStr) }
                                    }
                                    withStyle(SpanStyle(color = colors.command)) { append(cmd) }
                                }
                            } else AnnotatedString(line)
                            Text(
                                text = shownText,
                                style = mono,
                                color = lineColor(line, colors, t.colorByLevel),
                                modifier = Modifier.combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { focus.requestFocus(); keyboard?.show() },
                                    onLongClick = {
                                        if (t.copyLineOnLongPress) {
                                            clipboard.setText(AnnotatedString(line))
                                            vm.toast("Line copied")
                                        }
                                    }
                                )
                            )
                        }

                        item(key = "prompt") {
                            Column(Modifier.fillMaxWidth()) {
                                if (t.promptOwnLine) {
                                    Text(promptStr, style = mono, color = if (t.colorPrompt) colors.prompt else colors.fg)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val lead = if (t.promptOwnLine) lineTwoPrompt else promptStr
                                    Text(
                                        lead, style = mono,
                                        color = (if (t.colorPrompt) colors.prompt else colors.fg).copy(alpha = if (running) 0.45f else 1f)
                                    )
                                    BasicTextField(
                                        value = tfv,
                                        onValueChange = { nv ->
                                            val old = tfv
                                            if (ctrlArmed && nv.text.length == old.text.length + 1 && nv.selection.start > 0) {
                                                ctrlArmed = false
                                                doCtrl(Ctrl.forChar(nv.text[nv.selection.start - 1]))
                                            } else {
                                                var next = nv
                                                if (t.enterSends && (nv.text.contains('\n') || nv.text.contains('\r'))) {
                                                    next = nv.copy(text = nv.text.replace('\n', ' ').replace('\r', ' '))
                                                }
                                                if (next.text != old.text) nav.reset()
                                                tfv = next
                                            }
                                        },
                                        readOnly = running,
                                        singleLine = t.enterSends,
                                        maxLines = if (t.enterSends) 1 else 6,
                                        textStyle = mono.copy(color = colors.fg),
                                        cursorBrush = SolidColor(Color.Transparent),
                                        onTextLayout = { layout = it },
                                        keyboardOptions = KeyboardOptions(
                                            capitalization = KeyboardCapitalization.None,
                                            autoCorrectEnabled = false,
                                            keyboardType = if (t.keyboardMode == KeyboardMode.NO_SUGGESTIONS) KeyboardType.Password else KeyboardType.Text,
                                            imeAction = if (t.enterSends) ImeAction.Go else ImeAction.Default
                                        ),
                                        keyboardActions = KeyboardActions(onGo = { submit() }),
                                        decorationBox = { inner ->
                                            Box {
                                                if (suggestion != null) {
                                                    Text(
                                                        buildAnnotatedString {
                                                            withStyle(SpanStyle(color = Color.Transparent)) { append(tfv.text) }
                                                            withStyle(SpanStyle(color = colors.dim)) { append(suggestion) }
                                                        },
                                                        style = mono, maxLines = 1
                                                    )
                                                }
                                                inner()
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .focusRequester(focus)
                                            .onFocusChanged { focused = it.isFocused }
                                            .drawBehind {
                                                val l = layout ?: return@drawBehind
                                                if (!focused || running || !tfv.selection.collapsed) return@drawBehind
                                                val a = if (t.cursorBlink && !reduceMotion) blink else 1f
                                                if (a <= 0f) return@drawBehind
                                                val idx = tfv.selection.start.coerceIn(0, l.layoutInput.text.length)
                                                val r = l.getCursorRect(idx)
                                                val thick = 2.dp.toPx()
                                                when (t.cursorShape) {
                                                    CursorShape.BLOCK -> drawRect(
                                                        colors.cursor.copy(alpha = 0.55f * a),
                                                        Offset(r.left, r.top), Size(charWidthPx, r.height)
                                                    )
                                                    CursorShape.BAR -> drawRect(
                                                        colors.cursor.copy(alpha = a),
                                                        Offset(r.left, r.top), Size(thick, r.height)
                                                    )
                                                    CursorShape.UNDERLINE -> drawRect(
                                                        colors.cursor.copy(alpha = a),
                                                        Offset(r.left, r.bottom - thick), Size(charWidthPx, thick)
                                                    )
                                                }
                                            }
                                            .onPreviewKeyEvent { ev ->
                                                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                                val ctrl = ev.isCtrlPressed
                                                when {
                                                    ev.key == Key.Enter || ev.key == Key.NumPadEnter ->
                                                        if (t.enterSends || ctrl) { submit(); true } else false
                                                    ctrl && ev.key == Key.C ->
                                                        if (!tfv.selection.collapsed) false else { doCtrl(CtrlAction.Interrupt); true }
                                                    ctrl && ev.key == Key.L -> { doCtrl(CtrlAction.ClearScreen); true }
                                                    ctrl && ev.key == Key.U -> { doCtrl(CtrlAction.KillToStart); true }
                                                    ctrl && ev.key == Key.K -> { doCtrl(CtrlAction.KillToEnd); true }
                                                    ctrl && ev.key == Key.W -> { doCtrl(CtrlAction.DeleteWord); true }
                                                    ctrl && ev.key == Key.A -> { doCtrl(CtrlAction.Home); true }
                                                    ctrl && ev.key == Key.E -> { doCtrl(CtrlAction.End); true }
                                                    ev.key == Key.Tab && t.tabComplete -> { complete(); true }
                                                    ev.key == Key.DirectionUp && t.enterSends -> { historyUp(); true }
                                                    ev.key == Key.DirectionDown && t.enterSends -> { historyDown(); true }
                                                    (ev.key == Key.DirectionRight || ev.key == Key.MoveEnd) &&
                                                        tfv.selection.collapsed && tfv.selection.end == tfv.text.length ->
                                                        acceptSuggestion()
                                                    else -> false
                                                }
                                            }
                                    )
                                    if (running) {
                                        Box(
                                            Modifier.size(28.dp).clip(CircleShape).background(Nc.Err.copy(alpha = 0.2f)).ncClickable(onClick = onRequestCancel),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Filled.Stop, contentDescription = "Stop command", tint = Nc.Err, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (!stick && t.autoScroll) {
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(10.dp)
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(colors.fg.copy(alpha = 0.14f))
                                .ncClickable {
                                    stick = true
                                    scope.launch { listState.animateScrollToItem(lines.size.coerceAtLeast(0)) }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.KeyboardArrowDown, "Scroll to the bottom", tint = colors.fg, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showKeys && t.extraKeys.isNotEmpty(),
                    enter = expandVertically(tween(160)) + fadeIn(tween(160)),
                    exit = shrinkVertically(tween(120)) + fadeOut(tween(120))
                ) {
                  Column {
                    HorizontalDivider(color = Nc.BorderSoft)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        t.extraKeys.forEach { id ->
                            val def = ExtraKeys.ALL.firstOrNull { it.id == id } ?: return@forEach
                            val armed = id == "ctrl" && ctrlArmed
                            Box(
                                Modifier
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (armed) accent.color else colors.fg.copy(alpha = 0.10f))
                                    .ncClickable { pressKey(id) }
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    def.label,
                                    style = mono.copy(fontSize = 12.sp, lineHeight = 14.sp),
                                    fontFamily = FontFamily.Monospace,
                                    color = if (armed) accent.onColor else colors.fg
                                )
                            }
                        }
                    }
                  }
                }
            }
        }
    }
}

/** 32dp icon button for the terminal title strip: muted glyph, dims when disabled. */
@Composable
private fun StripAction(icon: ImageVector, description: String, tint: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .alpha(if (enabled) 1f else 0.3f)
            .clip(CircleShape)
            .ncClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp))
    }
}
