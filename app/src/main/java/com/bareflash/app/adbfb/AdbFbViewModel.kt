package com.bareflash.app.adbfb

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bareflash.adb.AdbAuthListener
import com.bareflash.adb.AdbConnection
import com.bareflash.adb.AdbConnectionConfig
import com.bareflash.adb.AdbDeviceInfo
import com.bareflash.adb.FileAdbKeyStore
import com.bareflash.adb.loadOrCreate
import com.bareflash.app.AppGraph
import com.bareflash.app.console.shell.Aliases
import com.bareflash.app.console.shell.HistoryExpansion
import com.bareflash.app.console.shell.Shell
import com.bareflash.app.console.shell.ShellEnv
import com.bareflash.app.console.shell.ShellResult
import com.bareflash.app.engine.EngineMode
import com.bareflash.app.log.AppLog
import com.bareflash.cli.CliCompleter
import com.bareflash.cli.CliHelp
import com.bareflash.cli.CommandParser
import com.bareflash.cli.CommandRunner
import com.bareflash.cli.CompletionContext
import com.bareflash.cli.ConsoleBuffer
import com.bareflash.cli.ConsoleLine
import com.bareflash.cli.DeviceCommand
import com.bareflash.cli.DeviceSession
import com.bareflash.cli.JavaLocalFiles
import com.bareflash.cli.LineKind
import com.bareflash.cli.LocalFiles
import com.bareflash.cli.NativeAdbOps
import com.bareflash.cli.NativeFastbootOps
import com.bareflash.cli.ParseResult
import com.bareflash.cli.RiskAssessor
import com.bareflash.cli.RiskLevel
import com.bareflash.cli.RunResult
import com.bareflash.cli.TerminalSink
import com.bareflash.fastboot.FastbootClient
import com.bareflash.fastboot.FastbootFailException
import com.bareflash.fastboot.RebootTarget
import com.bareflash.usb.CancelToken
import com.bareflash.usb.DebugProtocol
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State and actions for ADB / fastboot mode. Like the Samsung engine it is
 * independent of the MediaTek and Qualcomm console: it owns its own USB
 * receiver and its own connection.
 *
 * All USB traffic runs on one dedicated thread, so a protocol client is never
 * used by two callers at once. Both ADB and fastboot devices can be opened. The
 * console and the Commands tab feed lines through [submit]; the pure-Kotlin
 * `:debugcli` module parses, judges and runs them.
 */
class AdbFbViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context = app.applicationContext
    private val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "adbfb-usb").apply { isDaemon = true } }
    private val io = worker.asCoroutineDispatcher()

    /** True while this mode is the visible engine; gates automatic permission prompts. */
    @Volatile var active = false
        private set

    @Volatile private var cancelRequested = false
    @Volatile private var connection: AdbFbConnection? = null
    @Volatile private var fastboot: FastbootClient? = null
    @Volatile private var adb: AdbConnection? = null
    @Volatile private var session: DeviceSession? = null
    private val permissionRequested = HashSet<String>()

    private val _devices = MutableStateFlow<List<AdbFbDevice>>(emptyList())
    val devices: StateFlow<List<AdbFbDevice>> = _devices.asStateFlow()

    private val _link = MutableStateFlow(AdbFbLink())
    val link: StateFlow<AdbFbLink> = _link.asStateFlow()

    private val _busy = MutableStateFlow(false)
    /** True while a USB operation runs. The mode switcher locks while this is set. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _vars = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    /** `getvar all` of the connected fastboot device, sorted by name. */
    val vars: StateFlow<List<Pair<String, String>>> = _vars.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _log.asStateFlow()

    // ---- console state -------------------------------------------------------

    private val consoleBuffer = ConsoleBuffer()
    private val _console = MutableStateFlow<List<ConsoleLine>>(emptyList())
    /** Scrollback of the console: typed commands, device output, and notes from this app. */
    val console: StateFlow<List<ConsoleLine>> = _console.asStateFlow()

    private val _progress = MutableStateFlow<ConsoleProgress?>(null)
    /** The file transfer in progress, if any. */
    val progress: StateFlow<ConsoleProgress?> = _progress.asStateFlow()

    private val _pending = MutableStateFlow<PendingRun?>(null)
    /** A command waiting for the user to confirm it. */
    val pending: StateFlow<PendingRun?> = _pending.asStateFlow()

    private val _prompt = MutableStateFlow("$ ")
    val prompt: StateFlow<String> = _prompt.asStateFlow()

    private val _adbInfo = MutableStateFlow<AdbDeviceInfo?>(null)
    /** What the connected ADB device says about itself. */
    val adbInfo: StateFlow<AdbDeviceInfo?> = _adbInfo.asStateFlow()

    private val _showConsole = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits when a command from another tab should bring the console to the front. */
    val showConsole: SharedFlow<Unit> = _showConsole.asSharedFlow()

    /** Files on this phone, as the command line sees them. */
    @Suppress("DEPRECATION")
    private val localFiles: LocalFiles by lazy {
        JavaLocalFiles(
            home = Environment.getExternalStorageDirectory(),
            downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        )
    }

    private val sink = object : TerminalSink {
        override fun out(text: String) { consoleBuffer.append(LineKind.OUTPUT, text); publish() }
        override fun err(text: String) { consoleBuffer.append(LineKind.ERROR_OUTPUT, text); publish() }
        override fun info(text: String) { consoleBuffer.appendLine(LineKind.INFO, text); publish() }
        override fun error(text: String) { consoleBuffer.appendLine(LineKind.ERROR, text); publish() }
        override fun progress(label: String, done: Long, total: Long) {
            _progress.value = if (done < 0) null else ConsoleProgress(label, done, total)
        }
    }

    private fun publish() {
        _console.value = consoleBuffer.snapshot()
    }

    // ---- devices and permission ------------------------------------------

    fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        if (on) refreshDevices() else permissionRequested.clear()
    }

    fun refreshDevices(forcePrompt: Boolean = false) {
        val found = AdbFbUsb.scan(usb)
        _devices.value = found.map { d ->
            AdbFbDevice(
                name = d.deviceName,
                vid = d.vendorId,
                pid = d.productId,
                title = titleOf(d),
                protocols = AdbFbUsb.debugInterfaces(d).map { it.protocol }.distinct(),
                permitted = usb.hasPermission(d),
            )
        }
        if (!active) return
        for (d in found) {
            if (usb.hasPermission(d)) continue
            if (forcePrompt) permissionRequested.remove(d.deviceName)
            if (permissionRequested.add(d.deviceName)) requestPermission(d)
        }
    }

    private fun titleOf(d: UsbDevice): String =
        listOfNotNull(d.manufacturerName, d.productName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android device" }

    private fun requestPermission(d: UsbDevice) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        // Explicit package + EXTRA_DEVICE: Android 12+ refuses implicit USB permission intents.
        val intent = Intent(ACTION_PERMISSION).apply {
            setPackage(ctx.packageName)
            putExtra(UsbManager.EXTRA_DEVICE, d)
        }
        usb.requestPermission(d, PendingIntent.getBroadcast(ctx, d.deviceId, intent, flags))
    }

    // ---- connection --------------------------------------------------------

    /** Opens [protocol] on the named device. Fastboot reads its variables; ADB authorizes this phone. */
    fun connect(deviceName: String, protocol: DebugProtocol) {
        if (_busy.value) return
        val device = usb.deviceList[deviceName]
        if (device == null) {
            _link.value = AdbFbLink(AdbFbLinkState.FAILED, message = "The device is no longer attached.")
            refreshDevices()
            return
        }
        if (!usb.hasPermission(device)) {
            requestPermission(device)
            return
        }
        launchWork {
            closeConnection()
            _link.value = AdbFbLink(AdbFbLinkState.CONNECTING, deviceName, protocol)
            try {
                val conn = AdbFbUsb.open(usb, device, protocol)
                connection = conn
                when (protocol) {
                    DebugProtocol.FASTBOOT -> openFastboot(conn, device, deviceName)
                    DebugProtocol.ADB -> openAdb(conn, device, deviceName)
                }
            } catch (e: Exception) {
                fail("Connect", e)
            }
        }
    }

    private fun openFastboot(conn: AdbFbConnection, device: UsbDevice, deviceName: String) {
        val client = FastbootClient(conn.transport, CancelToken { cancelRequested }, ::note)
        fastboot = client
        session = DeviceSession(null, NativeFastbootOps(client))
        note("Connected to ${titleOf(device)} (fastboot).")
        readVars(client)
        sink.info("Connected to ${titleOf(device)} in fastboot mode. Type help for what you can do.")
        refreshPrompt()
        _link.value = AdbFbLink(AdbFbLinkState.CONNECTED, deviceName, DebugProtocol.FASTBOOT)
    }

    /**
     * Opens ADB: the device may show an "Allow USB debugging?" prompt the first
     * time. This call waits for the user to answer it (or for Disconnect).
     */
    private fun openAdb(conn: AdbFbConnection, device: UsbDevice, deviceName: String) {
        val keys = FileAdbKeyStore(File(ctx.filesDir, "adb/adbkey")).loadOrCreate()
        val config = AdbConnectionConfig(keyName = "bareflash@" + Build.MODEL.replace(Regex("[^A-Za-z0-9_-]"), "_"))
        val listener = object : AdbAuthListener {
            override fun onWaitingForUserApproval(fingerprint: String) {
                val msg = "Tap Allow on the USB debugging prompt on the device. Key fingerprint: $fingerprint"
                note(msg)
                sink.info(msg)
                _link.value = AdbFbLink(AdbFbLinkState.CONNECTING, deviceName, DebugProtocol.ADB, msg)
            }
        }
        val a = AdbConnection.connect(conn.transport, keys, config, listener, CancelToken { cancelRequested }, ::note)
        adb = a
        session = DeviceSession(NativeAdbOps(a), null)
        _adbInfo.value = a.info
        val label = a.info.model ?: titleOf(device)
        note("Connected to $label (ADB, ${a.info.state}).")
        sink.info("Connected to $label over ADB (${a.info.state}). Type help for what you can do.")
        refreshPrompt()
        _link.value = AdbFbLink(AdbFbLinkState.CONNECTED, deviceName, DebugProtocol.ADB)
    }

    /** Re-reads `getvar all`. */
    fun refreshVars() {
        if (_link.value.state != AdbFbLinkState.CONNECTED) return
        launchWork {
            val client = fastboot ?: return@launchWork
            try {
                readVars(client)
            } catch (e: Exception) {
                fail("Read variables", e)
            }
        }
    }

    /** Reboots the connected fastboot device. Not destructive: no data is touched. */
    fun reboot(target: RebootTarget) {
        if (_link.value.state != AdbFbLinkState.CONNECTED) return
        launchWork {
            val client = fastboot ?: return@launchWork
            try {
                note("Rebooting: ${target.name.lowercase()}")
                client.reboot(target)
                note("The device accepted the command and is restarting.")
                closeConnection()
                _vars.value = emptyList()
                _link.value = AdbFbLink(message = "The device is restarting. It shows up again when it is ready.")
            } catch (e: Exception) {
                fail("Reboot", e)
            }
        }
    }

    /** Closes the connection. If an operation is running it is asked to stop first. */
    fun disconnect() {
        if (_busy.value) {
            cancelRequested = true
            return
        }
        launchWork {
            closeConnection()
            _vars.value = emptyList()
            _link.value = AdbFbLink()
            note("Disconnected.")
        }
    }

    private fun readVars(client: FastbootClient) {
        val all = client.getvarAll()
        _vars.value = all.entries.sortedBy { it.key }.map { it.key to it.value }
        note("Read ${all.size} variables" + (all["product"]?.let { " (product: $it)" } ?: "") + ".")
    }

    private fun launchWork(block: () -> Unit) {
        if (!_busy.compareAndSet(false, true)) return
        cancelRequested = false
        viewModelScope.launch(io) {
            try {
                block()
            } catch (t: Throwable) {
                // Nothing a device or a command does may take the app down: show it and drop the link.
                AppLog.w(TAG, "Operation crashed: $t", t)
                sink.error("Internal error (${t.javaClass.simpleName}): ${t.message ?: "no details"}")
                dropLink("An internal error stopped the operation, so the connection was closed.")
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Reports a failed operation. A FAIL answer from the device means the
     * protocol is still in step, so the link stays up. Anything else (timeout,
     * unplug, malformed reply, cancel) may have left the device mid-command, so
     * the link is dropped instead of reused.
     */
    private fun fail(what: String, e: Exception) {
        val reason = e.message ?: e.javaClass.simpleName
        AppLog.w(TAG, "$what failed: $reason", e)
        note("$what failed: $reason")
        if (e is FastbootFailException) return
        closeConnection()
        _vars.value = emptyList()
        _link.value = AdbFbLink(AdbFbLinkState.FAILED, message = "$what failed: $reason")
    }

    private fun closeConnection() {
        val a = adb
        val c = connection
        adb = null
        connection = null
        fastboot = null
        session = null
        _adbInfo.value = null
        _progress.value = null
        _pending.value = null
        if (a != null) runCatching { a.close() }
        if (c != null) runCatching { c.close() }
        refreshPrompt()
    }

    /** The connection can no longer be trusted (device gone, rebooting, or stopped mid-transfer). */
    private fun dropLink(reason: String?) {
        closeConnection()
        _vars.value = emptyList()
        _link.value = AdbFbLink(message = reason ?: "The connection ended.")
        reason?.let { note(it) }
    }

    // ---- console -------------------------------------------------------------

    private fun connectedProtocol(): DebugProtocol? =
        if (_link.value.state == AdbFbLinkState.CONNECTED) _link.value.protocol else null

    private fun refreshPrompt() {
        val s = session
        _prompt.value = when {
            s?.adb != null -> {
                val model = (_adbInfo.value?.model ?: "adb").replace(' ', '_')
                "$model:${s.cwd} $ "
            }
            s?.fastboot != null -> "fastboot> "
            else -> "$ "
        }
    }

    /** Commands typed in this mode, newest first. */
    fun history(): List<String> = AppGraph.settings.commandsFor(EngineMode.ADB_FASTBOOT)

    /**
     * Runs a typed line: built-ins and aliases first, then the command parser.
     * Risky commands wait in [pending] for the user's confirmation.
     */
    fun submit(rawLine: String) {
        val typed = rawLine.trim()
        if (typed.isEmpty()) return
        if (_busy.value) {
            sink.info("A command is still running. Stop it first (Ctrl+C).")
            return
        }
        consoleBuffer.appendLine(LineKind.COMMAND, _prompt.value + typed)
        publish()

        val aliases = AppGraph.settings.state.value.terminal.aliases
        val history = history()
        val afterHistory = when (val h = HistoryExpansion.expand(typed, history)) {
            is HistoryExpansion.Result.Expanded -> h.line
            is HistoryExpansion.Result.Failed -> { sink.error(h.message); return }
            HistoryExpansion.Result.Unchanged -> typed
        }
        val line = Aliases.expand(afterHistory, aliases)
        if (line != typed) sink.info("= $line")
        AppGraph.settings.recordCommand(EngineMode.ADB_FASTBOOT, line)

        val word = line.substringBefore(' ')
        when (word) {
            "help", "man" -> { printHelp(line.removePrefix(word).trim()); return }
            "clear", "cls" -> { clearConsole(); return }
            "history", "alias", "unalias" -> { shellBuiltin(line, history, aliases); return }
        }

        when (val parsed = CommandParser.parse(line, connectedProtocol())) {
            ParseResult.Empty -> Unit
            is ParseResult.Error -> sink.error(parsed.message)
            is ParseResult.Ok -> {
                val risk = RiskAssessor.assess(parsed.command)
                if (risk.level == RiskLevel.SAFE) execute(parsed.command) else _pending.value = PendingRun(line, parsed.command, risk)
            }
        }
    }

    /** Runs a ready-made command from the Commands tab, showing it in the console. */
    fun runFromCatalog(line: String) {
        _showConsole.tryEmit(Unit)
        submit(line)
    }

    fun confirmPending() {
        val p = _pending.value ?: return
        _pending.value = null
        execute(p.command)
    }

    fun dismissPending() {
        if (_pending.value == null) return
        _pending.value = null
        sink.info("Cancelled. Nothing was sent to the device.")
    }

    /** Stops the running command, as Ctrl+C does. */
    fun cancelRun() {
        if (_busy.value) cancelRequested = true
    }

    private fun execute(command: DeviceCommand) {
        val a = adb
        if (a != null && !a.isOpen) {
            sink.error("The ADB connection has closed. Reconnect on the Devices tab.")
            dropLink("The ADB connection closed.")
            return
        }
        val s = session ?: DeviceSession(null, null)
        launchWork {
            val runner = CommandRunner(s, localFiles, sink, CancelToken { cancelRequested })
            val result: RunResult = try {
                runner.run(command)
            } catch (e: Exception) {
                AppLog.w(TAG, "Command crashed: ${e.message}", e)
                sink.error("Unexpected error: ${e.message ?: e.javaClass.simpleName}")
                RunResult(1)
            }
            _progress.value = null
            refreshPrompt()
            if (result.linkLost) dropLink(result.note)
        }
    }

    private fun printHelp(topic: String) {
        val text = if (topic.isEmpty()) CliHelp.overview() else (CliHelp.topic(topic) ?: "No help for '$topic'. Type help to see the topics.")
        consoleBuffer.append(LineKind.OUTPUT, text + "\n")
        publish()
    }

    private fun shellBuiltin(line: String, history: List<String>, aliases: Map<String, String>) {
        val env = ShellEnv("user", "bareflash", EngineMode.ADB_FASTBOOT.id, EngineMode.ADB_FASTBOOT.label, history, aliases, emptyList())
        when (val r = Shell.run(line, env)) {
            is ShellResult.Print -> if (r.text.isNotEmpty()) { consoleBuffer.append(LineKind.OUTPUT, r.text + "\n"); publish() }
            is ShellResult.ClearHistory -> {
                AppGraph.settings.clearHistory(EngineMode.ADB_FASTBOOT)
                sink.info(r.text)
            }
            is ShellResult.SetAlias -> {
                AppGraph.settings.update { it.copy(terminal = it.terminal.copy(aliases = it.terminal.aliases + (r.name to r.value))) }
                sink.info(r.text)
            }
            is ShellResult.RemoveAlias -> {
                AppGraph.settings.update { it.copy(terminal = it.terminal.copy(aliases = it.terminal.aliases - r.name)) }
                sink.info(r.text)
            }
            ShellResult.ClearScreen -> clearConsole()
            is ShellResult.Run -> Unit
        }
    }

    fun clearConsole() {
        consoleBuffer.clear()
        publish()
    }

    fun consoleText(): String = consoleBuffer.plainText()

    /**
     * Tab completion. Returns the new text of the input; when the choice is
     * ambiguous the candidates are listed in the console, as a shell does.
     */
    fun complete(line: String): String {
        val keys = _vars.value.map { it.first }
        val ctx = CompletionContext(
            connected = connectedProtocol(),
            partitions = keys.filter { it.startsWith("partition-type:") }.map { it.removePrefix("partition-type:") },
            variables = keys,
            extraWords = listOf("help", "clear", "history", "alias", "unalias") + AppGraph.settings.state.value.terminal.aliases.keys,
        )
        val r = CliCompleter.complete(line, ctx)
        if (r.candidates.isNotEmpty()) {
            consoleBuffer.appendLine(LineKind.COMMAND, _prompt.value + line)
            consoleBuffer.append(LineKind.INFO, CliCompleter.columns(r.candidates) + "\n")
            publish()
        }
        return r.line
    }

    // ---- log ---------------------------------------------------------------

    private fun note(line: String) {
        AppLog.i(TAG, line)
        _log.update { (it + line).takeLast(MAX_LOG_LINES) }
    }

    fun logText(): String = _log.value.joinToString("\n")

    fun clearLog() {
        _log.value = emptyList()
    }

    // ---- USB events --------------------------------------------------------

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    note(if (granted) "USB access granted." else "USB access was denied. Tap Grant to ask again.")
                    refreshDevices()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refreshDevices()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val gone: UsbDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                    if (gone != null) {
                        permissionRequested.remove(gone.deviceName)
                        val c = connection
                        if (c != null && c.device.deviceName == gone.deviceName) {
                            c.markDetached()
                            // A running transfer fails on its own and reports the disconnect.
                            // Closing the connection under it is what we avoid.
                            if (!_busy.value) {
                                note("The device was disconnected.")
                                closeConnection()
                                _vars.value = emptyList()
                                _link.value = AdbFbLink(message = "The device was disconnected.")
                            }
                        }
                    }
                    refreshDevices()
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receiver, filter)
        }
        refreshDevices()
        sink.info("ADB and fastboot console. Connect a device on the Devices tab, then type commands. Type help to begin.")
    }

    override fun onCleared() {
        runCatching { ctx.unregisterReceiver(receiver) }
        cancelRequested = true
        closeConnection()
        worker.shutdownNow()
        super.onCleared()
    }

    private companion object {
        const val TAG = "adbfb"
        const val ACTION_PERMISSION = "com.bareflash.app.ADBFB_USB_PERMISSION"
        const val MAX_LOG_LINES = 500
    }
}
