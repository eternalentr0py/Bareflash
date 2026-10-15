package com.bareflash.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bareflash.app.AppGraph
import com.bareflash.app.console.ConsoleViewModel
import com.bareflash.app.console.shell.ExtraKeys
import com.bareflash.app.console.shell.Prompt
import com.bareflash.app.console.shell.PromptPreset
import com.bareflash.app.console.shell.Shell
import com.bareflash.app.data.CursorShape
import com.bareflash.app.data.ExtraKeysMode
import com.bareflash.app.data.KeyboardMode
import com.bareflash.app.data.TerminalFont
import com.bareflash.app.data.TerminalSettings
import com.bareflash.app.data.TerminalStyle
import com.bareflash.app.data.TitleMode
import com.bareflash.app.ui.theme.Nc
import kotlin.math.roundToInt

// ---- terminal -------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TerminalPage(vm: ConsoleViewModel) {
    val repo = AppGraph.settings
    val s by repo.state.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val t = s.terminal
    val colors = terminalColors(t)

    fun upd(f: TerminalSettings.() -> TerminalSettings) = repo.update { it.copy(terminal = it.terminal.f()) }
    fun round1(v: Float) = (v * 10f).roundToInt() / 10f
    fun round2(v: Float) = (v * 100f).roundToInt() / 100f

    var pickPrompt by remember { mutableStateOf(false) }
    var pickCursor by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var aliasName by remember { mutableStateOf("") }
    var aliasValue by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TerminalPreview(t, colors, mode)

        NcSection("Colours") {
            NcChips(
                value = t.style,
                onChange = { v -> upd { copy(style = v) } },
                options = TerminalStyle.entries.map { NcChoice(it, it.label) }
            )
            NcCaption("Pick a scheme, or set your own text and background colours under Appearance. Those win over the scheme.")
            ColorRow("Prompt colour", "The user@host part. Default follows the scheme.", t.promptColor, colors.prompt) { pickPrompt = true }
            ColorRow("Cursor colour", "Default follows the prompt colour.", t.cursorColor, colors.cursor) { pickCursor = true }
            SwitchRow("Colour by level", "Errors, warnings and successes get their own colour. Off draws all output in the text colour.", t.colorByLevel) { v -> upd { copy(colorByLevel = v) } }
            SwitchRow("Colour the prompt", "Off draws the prompt in the text colour.", t.colorPrompt) { v -> upd { copy(colorPrompt = v) } }
        }

        NcSection("Text") {
            NcChips(
                value = t.font,
                onChange = { v -> upd { copy(font = v) } },
                options = TerminalFont.entries.map { NcChoice(it, it.label) }
            )
            NcSliderRow("Text size", "${t.fontSizeSp}sp", t.fontSizeSp.toFloat(),
                { v -> upd { copy(fontSizeSp = v.roundToInt().coerceIn(TerminalSettings.FONT_SIZE_RANGE)) } },
                TerminalSettings.FONT_SIZE_RANGE.first.toFloat(), TerminalSettings.FONT_SIZE_RANGE.last.toFloat(), 1f)
            NcSliderRow("Line spacing", "${round1(t.lineSpacing)}×", t.lineSpacing,
                { v -> upd { copy(lineSpacing = round1(v)) } }, 1.0f, 2.4f, 0.1f)
            NcSliderRow("Letter spacing", "${round1(t.letterSpacingSp)}sp", t.letterSpacingSp,
                { v -> upd { copy(letterSpacingSp = round1(v)) } }, -0.5f, 2f, 0.1f)
            SwitchRow("Bold text", "Draw everything in bold.", t.boldOutput) { v -> upd { copy(boldOutput = v) } }
        }

        NcSection("Window") {
            NcSliderRow("Corner radius", "${t.cornerDp}dp", t.cornerDp.toFloat(),
                { v -> upd { copy(cornerDp = v.roundToInt()) } }, 0f, 32f, 2f)
            NcSliderRow("Padding", "${t.paddingDp}dp", t.paddingDp.toFloat(),
                { v -> upd { copy(paddingDp = v.roundToInt()) } }, 2f, 28f, 2f)
            NcSliderRow("Background opacity", "${(t.backgroundAlpha * 100).roundToInt()}%", t.backgroundAlpha,
                { v -> upd { copy(backgroundAlpha = round2(v)) } }, 0.3f, 1f, 0.05f)
            SwitchRow("Border", "A thin outline around the terminal.", t.showBorder) { v -> upd { copy(showBorder = v) } }
            SectionLabelInline("Title")
            NcChips(t.titleMode, { v -> upd { copy(titleMode = v) } }, TitleMode.entries.map { NcChoice(it, it.label) })
        }

        NcSection("Cursor") {
            NcChips(t.cursorShape, { v -> upd { copy(cursorShape = v) } }, CursorShape.entries.map { NcChoice(it, it.label) })
            SwitchRow("Blink", "The cursor fades in and out. It stays solid when animations are off in system settings.", t.cursorBlink) { v -> upd { copy(cursorBlink = v) } }
        }

        NcSection("Prompt") {
            NcChips(t.promptPreset, { v -> upd { copy(promptPreset = v) } }, PromptPreset.entries.map { NcChoice(it, it.label) })
            if (t.promptPreset == PromptPreset.CUSTOM) {
                OutlinedTextField(
                    value = t.customPrompt,
                    onValueChange = { v -> upd { copy(customPrompt = v.take(80)) } },
                    label = { Text("PS1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                )
                NcCaption("\\u user, \\h host, \\w ~, \\m engine, \\\$ \$ (or # for root), \\\\ backslash. Anything else is shown as typed. ${Prompt.MAX_LENGTH} characters at most.")
            }
            OutlinedTextField(
                value = t.userName,
                onValueChange = { v -> upd { copy(userName = v.take(24)) } },
                label = { Text("User name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )
            OutlinedTextField(
                value = t.hostName,
                onValueChange = { v -> upd { copy(hostName = v.take(24)) } },
                label = { Text("Host name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )
            NcCaption("Letters, digits and - _ . only. An empty field falls back to user and bareflash. A user named root shows # instead of \$.")
            SwitchRow("Prompt on its own line", "Like a two-line PS1: the prompt above, then \$ where you type.", t.promptOwnLine) { v -> upd { copy(promptOwnLine = v) } }
        }

        NcSection("Input") {
            SwitchRow("Enter runs the command", "Off makes Enter start a new line. Run with the ⏎ extra key or Ctrl+Enter.", t.enterSends) { v -> upd { copy(enterSends = v) } }
            SwitchRow("Grey suggestion", "Finish the line from your history. Right or End accepts it.", t.autosuggest) { v -> upd { copy(autosuggest = v) } }
            SwitchRow("Tab completion", "Tab completes commands, flags and partition names.", t.tabComplete) { v -> upd { copy(tabComplete = v) } }
            SwitchRow("History search by prefix", "Up and Down visit only commands that start with what you typed.", t.historyPrefixSearch) { v -> upd { copy(historyPrefixSearch = v) } }
            SwitchRow("Keep the keyboard open", "Leave it up after you run a command.", t.keepKeyboardOpen) { v -> upd { copy(keepKeyboardOpen = v) } }
            SectionLabelInline("Keyboard")
            NcChips(t.keyboardMode, { v -> upd { copy(keyboardMode = v) } }, KeyboardMode.entries.map { NcChoice(it, it.label) })
            NcCaption("No suggestions asks the keyboard to stop suggesting and auto-correcting words.")
        }

        NcSection("Extra keys") {
            NcChips(t.extraKeysMode, { v -> upd { copy(extraKeysMode = v) } }, ExtraKeysMode.entries.map { NcChoice(it, it.label) })
            NcCaption("The row above the keyboard with Tab, Ctrl, arrows and symbols. Tap a key to add or remove it.")
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ExtraKeys.ALL.forEach { k ->
                    val on = k.id in t.extraKeys
                    FilterChip(
                        selected = on,
                        onClick = { upd { copy(extraKeys = if (on) extraKeys - k.id else extraKeys + k.id) } },
                        label = { Text(k.label) }
                    )
                }
            }
            NcCaption("CTRL then a letter: C stop, L clear, U clear line, A start, E end, W delete word, K delete to end.")
            SwitchRow("Key vibration", "A light tick when you tap an extra key.", t.keyHaptics) { v -> upd { copy(keyHaptics = v) } }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { upd { copy(extraKeys = ExtraKeys.DEFAULT_IDS) } }) { Text("Default keys") }
                OutlinedButton(onClick = { upd { copy(extraKeys = ExtraKeys.ALL.map { it.id }) } }) { Text("All keys") }
            }
        }

        NcSection("Output") {
            SwitchRow("Follow new output", "Scroll to the bottom as lines arrive. Scroll up to pause, tap the arrow to resume.", t.autoScroll) { v -> upd { copy(autoScroll = v) } }
            SectionLabelInline("Lines kept")
            NcChips(t.scrollbackLines, { v -> upd { copy(scrollbackLines = v) } }, TerminalSettings.SCROLLBACK_CHOICES.map { NcChoice(it, "$it") })
            NcCaption("More lines use more memory. Long dumps are trimmed from the top.")
            SwitchRow("Long-press copies a line", "Hold a line to copy it.", t.copyLineOnLongPress) { v -> upd { copy(copyLineOnLongPress = v) } }
            SwitchRow("Welcome text", "Show the short hint when the terminal is empty.", t.showBanner) { v -> upd { copy(showBanner = v) } }
        }

        NcSection("Aliases") {
            NcCaption("Short names for commands you type often. Make them here or in the terminal with alias name='command'.")
            if (t.aliases.isEmpty()) NcCaption("No aliases yet.")
            t.aliases.toSortedMap().forEach { (k, v) ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("$k = $v", style = MaterialTheme.typography.bodyMedium, color = Nc.Fg, modifier = Modifier.weight(1f))
                    TextButton(onClick = { upd { copy(aliases = aliases - k) } }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove alias $k", modifier = Modifier.size(18.dp))
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = aliasName, onValueChange = { aliasName = it.take(24).trim() },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(0.4f)
                )
                OutlinedTextField(
                    value = aliasValue, onValueChange = { aliasValue = it.take(120) },
                    label = { Text("Command") }, singleLine = true, modifier = Modifier.weight(0.6f)
                )
            }
            val nameOk = Shell.isValidAliasName(aliasName) && aliasName !in Shell.BUILTINS
            val valueOk = aliasValue.isNotBlank() && aliasValue.trim().substringBefore(' ') != aliasName
            FilledTonalButton(
                enabled = nameOk && valueOk,
                onClick = {
                    val n = aliasName; val v = aliasValue.trim()
                    upd { copy(aliases = aliases + (n to v)) }
                    aliasName = ""; aliasValue = ""
                },
                modifier = Modifier.padding(horizontal = 12.dp)
            ) { Text("Add alias") }
            if (aliasName.isNotEmpty() && !nameOk) NcCaption("Use letters, digits and - _ . : only, and not a built-in name.")
        }

        NcSection("Clear") {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { vm.clearTerminal() }) { Text("Clear terminal") }
                FilledTonalButton(onClick = { repo.clearHistory(mode) }) { Text("Clear history") }
            }
            Row(Modifier.padding(horizontal = 12.dp)) {
                OutlinedButton(onClick = { confirmReset = true }) { Text("Reset terminal settings") }
            }
            NcCaption("Reset puts every terminal setting back to its default. Your aliases are kept.")
        }
        Box(Modifier.size(24.dp))
    }

    if (pickPrompt) {
        ColorPickerDialog(
            title = "Prompt colour",
            initial = colors.prompt,
            onDismiss = { pickPrompt = false },
            onConfirm = { c -> upd { copy(promptColor = c.copy(alpha = 1f).toArgb()) }; pickPrompt = false },
            onReset = { upd { copy(promptColor = 0) }; pickPrompt = false }
        )
    }
    if (pickCursor) {
        ColorPickerDialog(
            title = "Cursor colour",
            initial = colors.cursor.copy(alpha = 1f),
            onDismiss = { pickCursor = false },
            onConfirm = { c -> upd { copy(cursorColor = c.copy(alpha = 1f).toArgb()) }; pickCursor = false },
            onReset = { upd { copy(cursorColor = 0) }; pickCursor = false }
        )
    }
    if (confirmReset) {
        NcAlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset terminal settings?") },
            text = { Text("Colours, text, prompt, keys and output go back to their defaults. Aliases stay.") },
            confirmButton = {
                TextButton(onClick = {
                    upd { TerminalSettings(aliases = aliases) }
                    confirmReset = false
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
        )
    }
}

/** A small label inside a section card, for a second group of chips. */
@Composable
private fun SectionLabelInline(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Nc.Muted,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
    )
}

@Composable
private fun ColorRow(title: String, description: String, value: Int, shown: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().ncClickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Nc.Fg)
            Text(if (value == 0) description else "Custom. $description", style = MaterialTheme.typography.bodyMedium, color = Nc.Muted)
        }
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(shown.copy(alpha = 1f))
        )
    }
}

/** What the terminal will look like with the current settings: a few lines and the prompt with its cursor. */
@Composable
private fun TerminalPreview(t: TerminalSettings, c: TerminalColors, mode: com.bareflash.app.engine.EngineMode) {
    val mono = terminalTextStyle(t)
    val user = Prompt.cleanName(t.userName, "user")
    val host = Prompt.cleanName(t.hostName, "bareflash")
    val prompt = Prompt.format(t.ps1, user, host, engineWord(mode))
    val promptColor = if (t.colorPrompt) c.prompt else c.fg
    val shape = RoundedCornerShape(t.cornerDp.dp)
    val pad = t.paddingDp.dp

    fun echo(cmd: String): AnnotatedString = buildAnnotatedString {
        withStyle(SpanStyle(color = promptColor)) { append(prompt) }
        withStyle(SpanStyle(color = c.command)) { append(cmd) }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.bg)
            .then(if (t.showBorder) Modifier.border(1.dp, Nc.BorderSoft, shape) else Modifier)
            .padding(pad),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Text(echo("help"), style = mono, color = c.fg)
        Text("Bareflash shell. Built-ins run here.", style = mono, color = c.fg)
        Text(echo("printgpt"), style = mono, color = c.fg)
        Text("Connected to the device", style = mono, color = if (t.colorByLevel) c.ok else c.fg)
        Text("Warning: slow link", style = mono, color = if (t.colorByLevel) c.warn else c.fg)
        Text("Error: could not read preloader", style = mono, color = if (t.colorByLevel) c.error else c.fg)
        if (t.promptOwnLine) Text(prompt, style = mono, color = promptColor)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (t.promptOwnLine) Prompt.format("\\$ ", user, host, engineWord(mode)) else prompt,
                style = mono, color = promptColor
            )
            Text("pri", style = mono, color = c.fg)
            Text("ntgpt", style = mono, color = c.dim)
        }
    }
}
