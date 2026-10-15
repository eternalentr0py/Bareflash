package com.bareflash.app.samsung

import com.bareflash.app.dump.WriteMarker
import com.bareflash.app.log.AppLog
import com.bareflash.app.log.LogLevel
import com.bareflash.app.log.LogSource
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bareflash.app.console.CommandCompletionCue
import com.bareflash.app.console.JobKind
import com.bareflash.app.console.JobProgress
import com.bareflash.app.data.Extras
import com.bareflash.app.live.LiveControl
import com.bareflash.app.live.LiveEngine
import com.bareflash.app.live.LiveHub
import com.bareflash.app.live.LiveKind
import com.bareflash.app.live.LiveOutcome
import com.bareflash.app.live.LiveResult
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * State and actions for Brokkr (Samsung / Odin) mode. Independent of the MTK
 * and QDL console: it owns its own USB receiver, package list, wireless
 * listener and flash run.
 */
class SamsungViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context = app.applicationContext
    private val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    private val prefs = ctx.getSharedPreferences("samsung", Context.MODE_PRIVATE)
    @Volatile private var liveId = -1
    private val historyInit = FlashHistory.init(ctx)
    private val cue = CommandCompletionCue(ctx)

    // ---- persisted options ------------------------------------------------

    private val _autoReboot = MutableStateFlow(prefs.getBoolean("autoReboot", true))
    val autoReboot: StateFlow<Boolean> = _autoReboot.asStateFlow()

    private val _link = MutableStateFlow(
        if (prefs.getString("link", "usb") == "wireless") SamsungLink.WIRELESS else SamsungLink.USB
    )
    val link: StateFlow<SamsungLink> = _link.asStateFlow()

    private val _allowOtherModel = MutableStateFlow(prefs.getBoolean("allowOtherModel", false))
    /** When on, firmware built for a different model is flashed anyway (default off). */
    val allowOtherModel: StateFlow<Boolean> = _allowOtherModel.asStateFlow()

    fun setAllowOtherModel(on: Boolean) {
        _allowOtherModel.value = on
        prefs.edit().putBoolean("allowOtherModel", on).apply()
    }

    private val _marker = MutableStateFlow(WriteMarker.current())
    /** Unfinished-write marker for this engine, or null. */
    val marker: StateFlow<WriteMarker.Record?> = _marker.asStateFlow()

    fun refreshMarker() { _marker.value = WriteMarker.current()?.takeIf { it.engine == "SAMSUNG" } }

    fun dismissMarker() {
        if (WriteMarker.current()?.engine == "SAMSUNG") WriteMarker.clear()
        _marker.value = null
    }

    @Volatile private var modelMismatchMsg: String? = null
    @Volatile private var writeStarted = false

    fun setAutoReboot(on: Boolean) {
        _autoReboot.value = on
        prefs.edit().putBoolean("autoReboot", on).apply()
    }

    fun setLink(l: SamsungLink) {
        if (_run.value.active || l == _link.value) return
        _link.value = l
        prefs.edit().putString("link", if (l == SamsungLink.WIRELESS) "wireless" else "usb").apply()
        wireless.setEnabled(l == SamsungLink.WIRELESS && active)
    }

    // ---- events (toasts / snackbars) -------------------------------------

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()
    private fun post(msg: String) { _events.tryEmit(msg) }

    // ---- packages ---------------------------------------------------------

    private val _packages = MutableStateFlow<List<SamsungPackage>>(emptyList())
    val packages: StateFlow<List<SamsungPackage>> = _packages.asStateFlow()

    private val _pit = MutableStateFlow<SamsungPit?>(null)
    val pit: StateFlow<SamsungPit?> = _pit.asStateFlow()

    fun addPackages(uris: List<Uri>) {
        if (_run.value.active) { post("A flash is already running."); return }
        var added = 0
        for (uri in uris) {
            val (name, size) = describe(uri)
            if (!SamsungPackage.isValidName(name)) {
                post("Invalid extension. Valid: .tar and .md5 ($name)")
                continue
            }
            if (_packages.value.any { it.uri == uri || (it.name == name && it.size == size) }) {
                post("Already in the list: $name")
                continue
            }
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val pkg = SamsungPackage(uri, name, size, SamsungPackage.roleOf(name))
            _packages.update { (it + pkg).sortedWith(compareBy({ p -> SamsungPackage.rank(p.role) }, { p -> p.name })) }
            added++
        }
        if (added > 0) log(INFO, "Added $added package(s).")
    }

    /**
     * Picks BL / AP / CP / CSC / USERDATA out of an extracted firmware folder.
     * HOME_CSC is preferred over CSC because it keeps user data; add CSC
     * manually for a full wipe.
     */
    fun addFolder(tree: Uri) {
        val dir = DocumentFile.fromTreeUri(ctx, tree)
        if (dir == null || !dir.isDirectory) { post("Could not open that folder."); return }
        val files = dir.listFiles().filter { it.isFile && SamsungPackage.isValidName(it.name.orEmpty()) }
        val byRole = files.groupBy { SamsungPackage.roleOf(it.name.orEmpty()) }
        val picks = mutableListOf<DocumentFile>()
        for (role in listOf("BL", "AP", "CP")) byRole[role]?.firstOrNull()?.let(picks::add)
        val home = byRole["HOME_CSC"]?.firstOrNull()
        val csc = byRole["CSC"]?.firstOrNull()
        when {
            home != null -> { picks += home; if (csc != null) post("Using HOME_CSC (keeps user data). Add the CSC file manually to wipe.") }
            csc != null -> picks += csc
        }
        byRole["USERDATA"]?.firstOrNull()?.let(picks::add)
        if (picks.isEmpty()) { post("No BL / AP / CP / CSC packages found in that folder."); return }
        addPackages(picks.map { it.uri })
    }

    /** Remembers the packages now in the list under a name, to reuse for the next flash. */
    fun saveCurrentSet(name: String) {
        val items = _packages.value.map { it.name to it.uri.toString() }
        if (items.isEmpty()) { post("Add packages first."); return }
        FlashHistory.saveSet(name, items)
        post("Saved the set \"${name.trim()}\".")
    }

    /** Replaces the package list with a saved set. Files that can no longer be opened are skipped and named. */
    fun loadSet(set: FlashHistory.PackageSet) {
        if (_run.value.active) { post("A flash is already running."); return }
        val ok = ArrayList<Uri>()
        for ((n, u) in set.items) {
            val uri = Uri.parse(u)
            val opens = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
            if (opens) ok += uri else post("$n is no longer available. Add it again.")
        }
        _packages.value = emptyList()
        if (ok.isNotEmpty()) addPackages(ok)
    }

    fun removePackage(pkg: SamsungPackage) {
        if (_run.value.active) return
        _packages.update { it - pkg }
    }

    fun clearPackages() {
        if (_run.value.active) return
        _packages.value = emptyList()
    }

    fun setPit(uri: Uri?) {
        if (uri == null) { _pit.value = null; return }
        val (name, _) = describe(uri)
        if (!name.lowercase().endsWith(".pit")) { post("Only .pit files are supported in Manual PIT."); return }
        _pit.value = SamsungPit(uri, name)
    }

    private fun describe(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
        }
        return name to size
    }

    // ---- devices ----------------------------------------------------------

    private val _devices = MutableStateFlow<List<SamsungDevice>>(emptyList())
    val devices: StateFlow<List<SamsungDevice>> = _devices.asStateFlow()

    private val permissionRequested = HashSet<String>()

    /** True while Brokkr mode is the visible engine; gates automatic permission prompts. */
    @Volatile var active = false
        private set

    val wireless = WirelessFlashManager(viewModelScope)

    fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        wireless.setEnabled(on && _link.value == SamsungLink.WIRELESS)
        if (on) refreshDevices() else permissionRequested.clear()
    }

    fun refreshDevices(forcePrompt: Boolean = false) {
        val seen = usb.deviceList.values.filter { it.vendorId == BrokkrUsbTransport.SAMSUNG_VID }
        _devices.value = seen.map { d ->
            SamsungDevice(
                id = BrokkrUsbTransport.idFor(d),
                vid = d.vendorId,
                pid = d.productId,
                product = d.productName?.takeIf { it.isNotBlank() } ?: "Samsung device",
                permitted = usb.hasPermission(d),
                inDownloadMode = BrokkrUsbTransport.isOdin(d)
            )
        }.sortedBy { it.id }
        if (!active) return
        for (d in seen) {
            if (!BrokkrUsbTransport.isOdin(d) || usb.hasPermission(d)) continue
            val id = BrokkrUsbTransport.idFor(d)
            if (forcePrompt) permissionRequested.remove(id)
            if (permissionRequested.add(id)) requestPermission(d)
        }
    }

    private fun requestPermission(d: UsbDevice) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        // Explicit package + EXTRA_DEVICE, same reasoning as ConsoleUsb: Android 12+ refuses implicit ones.
        val intent = Intent(ACTION_PERMISSION).apply {
            setPackage(ctx.packageName)
            putExtra(UsbManager.EXTRA_DEVICE, d)
        }
        usb.requestPermission(d, PendingIntent.getBroadcast(ctx, d.deviceId, intent, flags))
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    log(if (granted) INFO else WARN, if (granted) "USB access granted." else "USB access was denied. Tap Grant on the Devices tab to ask again.")
                    refreshDevices()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> { refreshDevices() }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    permissionRequested.clear()
                    refreshDevices()
                }
            }
        }
    }

    init {
        val f = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receiver, f)
        }
        refreshDevices()
        // Flushes the log buffer to the UI at a steady rate instead of per line.
        viewModelScope.launch {
            while (isActive) {
                delay(LOG_FLUSH_MS)
                flushLog()
            }
        }
    }

    // ---- log --------------------------------------------------------------

    private val logBuffer = ArrayDeque<SamsungLogLine>()
    private val logLock = Any()
    @Volatile private var logDirty = false
    private val _log = MutableStateFlow<List<SamsungLogLine>>(emptyList())
    val logLines: StateFlow<List<SamsungLogLine>> = _log.asStateFlow()

    /** [level] uses the native numbering: 0 trace, 1 debug, 2 info, 3 warn, 4 error, 5 critical. */
    fun log(level: Int, text: String) {
        AppLog.samsung(level, text)
        synchronized(logLock) {
            logBuffer.addLast(SamsungLogLine(level, text))
            while (logBuffer.size > MAX_LOG_LINES) logBuffer.removeFirst()
            logDirty = true
        }
    }

    private fun flushLog() {
        if (!logDirty) return
        synchronized(logLock) {
            _log.value = logBuffer.toList()
            logDirty = false
        }
    }

    fun clearLog() {
        synchronized(logLock) { logBuffer.clear(); logDirty = true }
        flushLog()
    }

    fun logText(): String = synchronized(logLock) { logBuffer.joinToString("\n") { it.text } }

    // ---- flashing ---------------------------------------------------------

    private val _run = MutableStateFlow(RunState())
    val run: StateFlow<RunState> = _run.asStateFlow()

    @Volatile private var bridge: BrokkrNativeBridge? = null

    fun dismissResult() {
        if (!_run.value.active) _run.value = RunState()
    }

    fun cancel() {
        val b = bridge ?: return
        if (!_run.value.active) return
        log(WARN, "Stopping…")
        b.cancel()
    }

    fun startFlash() {
        if (_run.value.active) return
        if (Extras.readOnlyLock.value) {
            post("Read-only lock is on, so nothing was flashed. Turn it off in Settings to flash.")
            return
        }
        val pkgs = _packages.value
        val pit = _pit.value
        if (pkgs.isEmpty() && pit == null) { post("Select at least one package before starting."); return }
        if (!BrokkrNativeBridge.loaded) {
            post("The Samsung engine could not load: ${BrokkrNativeBridge.loadError ?: "unknown error"}")
            return
        }

        var tcp: BrokkrTcpTransport? = null
        var usbDevices: List<UsbDevice> = emptyList()
        if (_link.value == SamsungLink.WIRELESS) {
            tcp = wireless.takeConnectedTransportForFlash()
            if (tcp == null) { post("Wireless is enabled but no device is connected yet."); return }
        } else {
            usbDevices = usb.deviceList.values.filter { BrokkrUsbTransport.isOdin(it) && usb.hasPermission(it) }
            if (usbDevices.isEmpty()) { post("No permitted Samsung download-mode devices are ready."); return }
        }

        val reboot = _autoReboot.value
        val title = "Samsung flash"
        val startedAt = SystemClock.elapsedRealtime()
        modelMismatchMsg = null
        writeStarted = false
        _run.value = RunState(phase = RunPhase.Preparing, stage = "Preparing session", startedAt = startedAt)
        synchronized(speedWindow) { speedWindow.clear() }
        lastNotifyAt = 0L
        log(INFO, "")
        log(INFO, "=== Flash started: ${pkgs.size} package(s)${if (pit != null) " + PIT" else ""}, " +
            (if (tcp != null) "wireless" else "${usbDevices.size} USB device(s)") + " ===")

        val b = BrokkrNativeBridge(ctx.cacheDir)
        bridge = b
        val prevCancel = LiveControl.cancel
        LiveControl.cancel = { cancel() }
        liveId = LiveHub.begin(
            LiveKind.Flash, LiveEngine.Samsung, title,
            pkgs.joinToString(", ") { it.role }.ifBlank { "PIT" },
            usbDevices.firstOrNull()?.productName.orEmpty()
        )

        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
            val transports = ArrayList<BrokkrUsbTransport>()
            val pfds = ArrayList<ParcelFileDescriptor>()
            var pitPfd: ParcelFileDescriptor? = null
            var crash: Throwable? = null
            try {
                for (d in usbDevices) transports += BrokkrUsbTransport.open(usb, d)
                for (t in transports) {
                    if (t.linkIsSlow) {
                        log(WARN, "USB link ${t.getId()}: ${t.linkName}. This is USB 1.1 speed, so flashing will be about " +
                            "10x slower. Try a different OTG adapter or cable, and avoid hubs.")
                    } else {
                        log(INFO, "USB link ${t.getId()}: ${t.linkName}")
                    }
                }
                for (p in pkgs) {
                    pfds += ctx.contentResolver.openFileDescriptor(p.uri, "r")
                        ?: throw IOException("Could not open ${p.name}.")
                }
                if (pit != null) {
                    pitPfd = ctx.contentResolver.openFileDescriptor(pit.uri, "r")
                        ?: throw IOException("Could not open PIT file ${pit.name}.")
                }
                b.run(
                    callbacks = callbacks,
                    usb = transports,
                    tcp = tcp,
                    packageFds = IntArray(pfds.size) { pfds[it].fd },
                    packageLabels = Array(pkgs.size) { pkgs[it].name },
                    manualPitFd = pitPfd?.fd ?: -1,
                    manualPitLabel = pit?.name,
                    autoReboot = reboot
                )
            } catch (t: Throwable) {
                AppLog.log(LogLevel.Fatal, LogSource.Samsung, "flash", "Flash run crashed: ${t.javaClass.simpleName}: ${t.message}", t)
                crash = t
            } finally {
                transports.forEach { it.close() }
                tcp?.close()
                pfds.forEach { runCatching { it.close() } }
                pitPfd?.let { runCatching { it.close() } }
                wireless.finishFlashSession()
                finishRun(b, crash)
                bridge = null
                LiveControl.cancel = prevCancel
                viewModelScope.launch { refreshDevices() }
            }
        }, "brokkr-flash").start()
    }

    /** Runs once, after native has returned, so it is the single place a run ends. */
    private fun finishRun(b: BrokkrNativeBridge, crash: Throwable?) {
        val cur = _run.value
        val outcome = when {
            b.cancelled -> RunPhase.Cancelled
            crash != null -> RunPhase.Failed
            cur.phase == RunPhase.Success -> RunPhase.Success
            else -> RunPhase.Failed
        }
        val mismatch = modelMismatchMsg
        if (mismatch != null) {
            WriteMarker.clear()
            _marker.value = null
        } else if (writeStarted) {
            if (outcome == RunPhase.Success) {
                WriteMarker.clear()
                _marker.value = null
            } else {
                WriteMarker.end(if (outcome == RunPhase.Cancelled) "stopped" else "error")
                _marker.value = WriteMarker.current()
            }
        }
        val msg = if (mismatch != null) mismatch else when (outcome) {
            RunPhase.Cancelled -> "Flash stopped."
            RunPhase.Success -> cur.message.ifBlank { "Flash complete." }
            else -> crash?.message ?: cur.message.ifBlank { "Flash failed." }
        }
        val explanation = if (outcome == RunPhase.Failed) SamsungErrors.explain(msg) else if (mismatch != null) mismatch else null
        _run.update {
            it.copy(
                phase = outcome, message = msg, explanation = explanation, speedBps = 0.0, etaMs = -1,
                elapsedMs = SystemClock.elapsedRealtime() - it.startedAt,
                overallDone = if (outcome == RunPhase.Success) it.overallTotal else it.overallDone
            )
        }
        log(if (outcome == RunPhase.Failed) ERROR else INFO, "=== $msg ===")
        val liveOutcome = when (outcome) {
            RunPhase.Success -> LiveOutcome.Success
            RunPhase.Cancelled -> LiveOutcome.Cancelled
            else -> LiveOutcome.Failure
        }
        val after = _run.value
        FlashHistory.record(
            FlashHistory.Entry(
                at = System.currentTimeMillis(),
                packages = _packages.value.map { it.name },
                model = after.model,
                outcome = when (outcome) { RunPhase.Success -> "success"; RunPhase.Cancelled -> "stopped"; else -> "failed" },
                elapsedMs = after.elapsedMs, bytes = after.overallDone, message = msg,
            )
        )
        val lid = liveId
        liveId = -1
        if (lid >= 0) {
            LiveHub.end(
                lid,
                LiveResult(
                    kind = LiveKind.Flash, engine = LiveEngine.Samsung, outcome = liveOutcome,
                    title = "Samsung flash", target = _packages.value.joinToString(", ") { it.role },
                    elapsedMs = after.elapsedMs, bytes = after.overallDone,
                    avgBps = if (after.elapsedMs > 0L) after.overallDone * 1000.0 / after.elapsedMs else 0.0,
                    detail = if (outcome == RunPhase.Success) "Flash complete" else msg,
                    hint = if (outcome == RunPhase.Failed) (explanation ?: "") else "",
                )
            )
        }
        cue.play(
            when (outcome) {
                RunPhase.Success -> CommandCompletionCue.Outcome.Success
                RunPhase.Cancelled -> CommandCompletionCue.Outcome.Cancelled
                else -> CommandCompletionCue.Outcome.Error
            },
            vibrate = true, sound = false
        )
    }

    // ---- native callbacks -------------------------------------------------

    private val speedWindow = ArrayDeque<Pair<Long, Long>>()
    private var lastNotifyAt = 0L

    private val callbacks = object : NativeFlashCallbacks {
        override fun onLog(level: Int, message: String) { log(level, message) }

        override fun onDevices(ids: Array<String>) {
            _run.update { it.copy(deviceIds = ids.toList()) }
            log(INFO, "Devices: " + ids.joinToString())
        }

        override fun onModel(model: String) {
            _run.update { it.copy(model = model) }
            log(INFO, "Model: $model")
            val bad = ModelCheck.mismatch(model, _packages.value.map { it.name })
            if (bad != null && !_allowOtherModel.value && !writeStarted) {
                modelMismatchMsg = bad
                log(ERROR, bad)
                bridge?.cancel()
            } else if (bad != null) {
                log(WARN, "Model mismatch ignored (\"Allow other models\" is on).")
            }
        }

        override fun onStage(stage: String) {
            _run.update { it.copy(stage = stage) }
        }

        override fun onPlanItem(
            index: Int, kind: Int, partId: Int, devType: Int,
            partName: String, pitFileName: String, sourceBase: String, size: Long
        ) {
            val e = PlanEntry(index, kind == 0, partName, pitFileName, sourceBase, size)
            _run.update { st ->
                val list = st.plan.toMutableList()
                while (list.size <= index) list += PlanEntry(list.size, false, "", "", "", 0)
                list[index] = e
                st.copy(plan = list)
            }
        }

        override fun onPlanReady(count: Int, totalBytes: Long) {
            if (modelMismatchMsg == null) {
                writeStarted = true
                WriteMarker.begin("SAMSUNG", "${_packages.value.size} package(s)", "")
                _marker.value = WriteMarker.current()
            }
            _run.update { it.copy(phase = RunPhase.Flashing, overallTotal = totalBytes) }
        }

        override fun onItemActive(index: Int) = setItemState(index, PlanState.Active)

        override fun onItemDone(index: Int) = setItemState(index, PlanState.Done)

        override fun onProgress(overallDone: Long, overallTotal: Long, itemDone: Long, itemTotal: Long) {
            val now = SystemClock.elapsedRealtime()
            var speed = _run.value.speedBps
            synchronized(speedWindow) {
                speedWindow.addLast(now to overallDone)
                while (speedWindow.size > 2 && now - speedWindow.first().first > SPEED_WINDOW_MS) speedWindow.removeFirst()
                val (t0, d0) = speedWindow.first()
                val dt = now - t0
                if (dt >= 500) speed = (overallDone - d0) * 1000.0 / dt
            }
            val eta = if (speed > 1.0 && overallTotal > overallDone) ((overallTotal - overallDone) / speed * 1000).toLong() else -1L
            val st = _run.updateAndGet {
                it.copy(
                    overallDone = overallDone, overallTotal = overallTotal,
                    itemDone = itemDone, itemTotal = itemTotal,
                    speedBps = speed, etaMs = eta,
                    elapsedMs = now - it.startedAt,
                    phase = if (it.phase == RunPhase.Preparing) RunPhase.Flashing else it.phase
                )
            }
            if (now - lastNotifyAt >= 1000 && liveId >= 0) {
                lastNotifyAt = now
                LiveHub.progress(liveId, overallDone, overallTotal, speed, eta, st.activeItem?.partition ?: st.stage)
            }
        }

        override fun onDeviceError(index: Int, message: String) {
            _run.update { it.copy(deviceErrors = it.deviceErrors + (index to message)) }
            log(ERROR, "Device #$index: $message")
        }

        override fun onError(message: String) {
            _run.update { it.copy(message = message) }
            log(ERROR, "Error: $message")
        }

        override fun onFinished(success: Boolean, message: String) {
            _run.update {
                it.copy(
                    phase = if (success) RunPhase.Success else RunPhase.Failed,
                    message = message.ifBlank { it.message }
                )
            }
        }
    }

    private fun setItemState(index: Int, state: PlanState) {
        _run.update { st ->
            if (index !in st.plan.indices) st
            else st.copy(plan = st.plan.mapIndexed { i, e -> if (i == index) e.copy(state = state) else e })
        }
    }

    override fun onCleared() {
        runCatching { ctx.unregisterReceiver(receiver) }
        bridge?.cancel()
        wireless.close()
        super.onCleared()
    }

    companion object {
        const val INFO = 2
        const val WARN = 3
        const val ERROR = 4
        private const val ACTION_PERMISSION = "com.bareflash.app.SAMSUNG_USB_PERMISSION"
        private const val MAX_LOG_LINES = 3000
        private const val LOG_FLUSH_MS = 120L
        private const val SPEED_WINDOW_MS = 4000L
    }
}
