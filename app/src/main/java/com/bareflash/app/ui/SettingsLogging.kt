package com.bareflash.app.ui

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bareflash.app.log.AppLog
import com.bareflash.app.log.LogEntry
import com.bareflash.app.log.LogLevel
import com.bareflash.app.log.LogSource
import com.bareflash.app.ui.theme.Nc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

// ---- sharing (also used by the Console's overflow menu) ---------------------

/** Builds the diagnostic zip off the main thread, then opens the share sheet. */
internal fun shareLogExport(ctx: Context, onFailed: () -> Unit = {}) {
    val main = Handler(Looper.getMainLooper())
    thread(name = "log-export") {
        val zip = AppLog.exportZip()
        main.post {
            if (zip == null) {
                onFailed()
                android.widget.Toast.makeText(ctx, "Couldn't build the log export.", android.widget.Toast.LENGTH_SHORT).show()
                return@post
            }
            runCatching {
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", zip)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_SUBJECT, "Bareflash diagnostic log")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(
                    Intent.createChooser(send, "Share diagnostic log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { AppLog.e("log-ui", "Sharing the log export failed", it) }
        }
    }
}

// ---- helpers -----------------------------------------------------------------

private val clockFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024.0; i++ }
    return String.format("%.1f %s", v, units[i])
}

@Composable
private fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.Verbose, LogLevel.Debug -> MaterialTheme.colorScheme.outline
    LogLevel.Info -> MaterialTheme.colorScheme.primary
    LogLevel.Warn -> Nc.Warn
    LogLevel.Error, LogLevel.Fatal -> MaterialTheme.colorScheme.error
}

private enum class LevelFilter(val label: String, val min: LogLevel) {
    All("All", LogLevel.Verbose),
    Debug("Debug+", LogLevel.Debug),
    Info("Info+", LogLevel.Info),
    Warn("Warnings", LogLevel.Warn),
    Error("Errors", LogLevel.Error)
}

private const val COPY_CAP = 400_000

// ---- page ------------------------------------------------------------------

/**
 * Settings -> Logging. A live, filterable view of everything [AppLog]
 * records for any session, with stack traces, export and logging options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LoggingPage() {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun toast(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    val revision by AppLog.revision.collectAsStateWithLifecycle()
    val crashBanner by AppLog.crashNotice.collectAsStateWithLifecycle()
    val config by AppLog.config.collectAsStateWithLifecycle()

    var sessions by remember { mutableStateOf(AppLog.sessions()) }
    var selected by remember { mutableStateOf(AppLog.sessionId) }
    var levelFilter by remember { mutableStateOf(LevelFilter.Info) }
    var sources by remember { mutableStateOf(emptySet<LogSource>()) } // empty = all
    var query by remember { mutableStateOf("") }
    var follow by remember { mutableStateOf(true) }
    var expandedSeq by remember { mutableStateOf<Long?>(null) }
    var showOptions by remember { mutableStateOf(false) }
    var sessionMenu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    var all by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var shown by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    val isLive = selected == AppLog.sessionId

    // Load: live session follows the revision counter, a past session loads once.
    LaunchedEffect(selected, if (isLive) revision else 0L) {
        if (isLive) delay(150) // coalesce bursts of writes
        all = withContext(Dispatchers.Default) { AppLog.load(selected) }
    }
    LaunchedEffect(revision) { sessions = withContext(Dispatchers.Default) { AppLog.sessions() } }

    // Filter off the main thread; typing in the search box is debounced by restarting this effect.
    LaunchedEffect(all, levelFilter, sources, query) {
        if (query.isNotEmpty()) delay(180)
        shown = withContext(Dispatchers.Default) {
            val q = query.trim()
            all.filter { e ->
                e.level.ordinal >= levelFilter.min.ordinal &&
                    (sources.isEmpty() || e.source in sources) &&
                    (q.isEmpty() ||
                        e.message.contains(q, ignoreCase = true) ||
                        e.tag.contains(q, ignoreCase = true) ||
                        (e.stack?.contains(q, ignoreCase = true) == true))
            }
        }
    }

    val errors = remember(all) { all.count { it.level == LogLevel.Error || it.level == LogLevel.Fatal } }
    val warnings = remember(all) { all.count { it.level == LogLevel.Warn } }
    val listState = rememberLazyListState()

    // Follow the tail while live and the user hasn't scrolled away.
    LaunchedEffect(shown.size, follow) {
        if (follow && shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex)
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && listState.canScrollForward) follow = false
    }

    fun copyText(entries: List<LogEntry>): String {
        val sb = StringBuilder()
        for (e in entries) {
            val line = AppLog.format(e)
            if (sb.length + line.length > COPY_CAP) {
                sb.append("\n… clipped; use Share to export the full log.")
                break
            }
            sb.append(line).append('\n')
        }
        return sb.toString()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // -- summary ------------------------------------------------------
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (isLive) "This session" else "Past session",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val s = sessions.firstOrNull { it.id == selected }
                            Text(
                                (s?.let { dateFmt.format(Instant.ofEpochMilli(it.startMs)) } ?: selected) +
                                    (s?.let { " · ${formatSize(it.sizeBytes)}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box {
                            TextButton(onClick = { sessionMenu = true }) {
                                Text("Sessions (${sessions.size})")
                                Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp))
                            }
                            DropdownMenu(expanded = sessionMenu, onDismissRequest = { sessionMenu = false }) {
                                sessions.forEach { s ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    dateFmt.format(Instant.ofEpochMilli(s.startMs)) + if (s.isCurrent) "  (current)" else "",
                                                    fontWeight = if (s.id == selected) FontWeight.SemiBold else FontWeight.Normal
                                                )
                                                Text(formatSize(s.sizeBytes), style = MaterialTheme.typography.bodySmall)
                                            }
                                        },
                                        onClick = {
                                            sessionMenu = false
                                            selected = s.id
                                            follow = s.isCurrent
                                            expandedSeq = null
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatPill("${all.size}", "entries", MaterialTheme.colorScheme.onSurfaceVariant)
                        StatPill("$warnings", "warnings", if (warnings > 0) Nc.Warn else MaterialTheme.colorScheme.onSurfaceVariant)
                        StatPill("$errors", "errors", if (errors > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    crashBanner?.let { what ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.WarningAmber, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "The previous run ended badly: $what. The full report is in the log below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                AppLog.dismissCrashBanner()
                                levelFilter = LevelFilter.Error
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Close, "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { shareLogExport(ctx) }) {
                            Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Export")
                        }
                        FilledTonalButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(copyText(shown)))
                                toast("Copied ${shown.size} entries.")
                            },
                            enabled = shown.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy")
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { showOptions = true }) { Icon(Icons.Filled.Tune, "Logging options") }
                        IconButton(onClick = { confirmClear = true }) { Icon(Icons.Filled.DeleteSweep, "Clear logs") }
                    }
                }
            }

            // -- filters ------------------------------------------------------
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search message, tag or stack trace") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear search") }
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LevelFilter.entries.forEach { f ->
                    FilterChip(selected = levelFilter == f, onClick = { levelFilter = f }, label = { Text(f.label) })
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = sources.isEmpty(), onClick = { sources = emptySet() }, label = { Text("All sources") })
                LogSource.entries.forEach { s ->
                    FilterChip(
                        selected = s in sources,
                        onClick = { sources = if (s in sources) sources - s else sources + s },
                        label = { Text(s.label) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // -- entries --------------------------------------------------------
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (shown.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (all.isEmpty()) "Nothing logged yet." else "No entries match the current filters.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (all.isNotEmpty() && levelFilter != LevelFilter.All) {
                            TextButton(onClick = { levelFilter = LevelFilter.All; sources = emptySet(); query = "" }) {
                                Text("Show everything")
                            }
                        }
                    }
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(shown, key = { it.seq }) { e ->
                            LogRow(
                                entry = e,
                                expanded = expandedSeq == e.seq,
                                onToggle = { expandedSeq = if (expandedSeq == e.seq) null else e.seq },
                                onCopy = {
                                    clipboard.setText(AnnotatedString(AppLog.format(e)))
                                    toast("Entry copied.")
                                }
                            )
                        }
                    }
                }
                if (!follow && isLive && shown.isNotEmpty()) {
                    FloatingActionButton(
                        onClick = { follow = true },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ) { Icon(Icons.Filled.KeyboardArrowDown, "Jump to latest") }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }

    if (showOptions) {
        ModalBottomSheet(onDismissRequest = { showOptions = false }) {
            OptionsSheet(config) { showOptions = false }
        }
    }

    if (confirmClear) {
        NcAlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all logs?") },
            text = { Text("Deletes every saved session, including crash reports. This can't be undone. Export first if you need them.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    AppLog.clearAll()
                    selected = AppLog.sessionId
                    follow = true
                    expandedSeq = null
                    sessions = AppLog.sessions()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun StatPill(value: String, label: String, tint: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(value, style = MaterialTheme.typography.labelLarge, color = tint, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LogRow(entry: LogEntry, expanded: Boolean, onToggle: () -> Unit, onCopy: () -> Unit) {
    val tint = levelColor(entry.level)
    val severe = entry.level == LogLevel.Error || entry.level == LogLevel.Fatal
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (severe) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onToggle)
            .padding(end = 12.dp)
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(tint))
        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(4.dp)).background(tint.copy(alpha = 0.18f)).padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(entry.level.letter.toString(), color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    clockFmt.format(Instant.ofEpochMilli(entry.timeMs)),
                    fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    entry.source.label,
                    fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    "  ${entry.tag}",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                if (entry.stack != null) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Hide details" else "Show details",
                        tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                entry.message,
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis
            )
            if (expanded) {
                if (entry.stack != null) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        SelectionContainer {
                            Text(
                                entry.stack,
                                fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, lineHeight = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp)
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "thread: ${entry.thread}",
                        fontSize = 10.5.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onCopy) {
                        Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy entry")
                    }
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun OptionsSheet(config: AppLog.Config, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Logging options", style = MaterialTheme.typography.titleLarge)

        Text("Record from level", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(LogLevel.Verbose, LogLevel.Debug, LogLevel.Info, LogLevel.Warn, LogLevel.Error).forEach { l ->
                FilterChip(
                    selected = config.minLevel == l,
                    onClick = { AppLog.updateConfig { it.copy(minLevel = l) } },
                    label = { Text(l.label) }
                )
            }
        }

        OptionSwitch(
            "Capture system log",
            "Everything the app writes to Android's log: native code, libusb, Python output, UI frame drops, runtime warnings.",
            config.captureSystem
        ) { v -> AppLog.updateConfig { it.copy(captureSystem = v) } }

        OptionSwitch(
            "Watch for UI freezes",
            "Records the blocked stack when the main thread stops responding for 5 seconds or more.",
            config.watchdog
        ) { v -> AppLog.updateConfig { it.copy(watchdog = v) } }

        Text("Keep up to", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(25, 50, 100, 250).forEach { mb ->
                FilterChip(
                    selected = config.maxTotalMb == mb,
                    onClick = { AppLog.updateConfig { it.copy(maxTotalMb = mb) } },
                    label = { Text("$mb MB") }
                )
            }
        }
        Text(
            "Using ${formatSize(AppLog.totalBytes())} now. The oldest sessions are deleted first when the limit is reached. Crashes, ANRs and native crashes are always recorded.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

@Composable
private fun OptionSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
