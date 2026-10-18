package com.bareflash.app.console

import com.bareflash.app.log.AppLog
import com.bareflash.app.log.LogLevel
import com.bareflash.app.log.LogSource
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.bareflash.app.AppGraph
import com.bareflash.app.UiBridge
import com.bareflash.app.UsbBridge
import com.bareflash.app.console.logging.TerminalModel
import com.bareflash.app.console.logging.TerminalNoiseFilter
import com.bareflash.app.data.ChipProfiles
import com.bareflash.app.data.CommandPresets
import com.bareflash.app.data.Extras
import com.bareflash.app.dump.Restore
import com.bareflash.app.live.LiveControl
import com.bareflash.app.live.LiveEngine
import com.bareflash.app.live.LiveHub
import com.bareflash.app.live.LiveKind
import com.bareflash.app.live.LiveOutcome
import com.bareflash.app.live.LiveResult
import com.bareflash.app.live.LiveStatus
import com.bareflash.app.dump.DumpBridge
import com.bareflash.app.dump.DumpIntegrity
import com.bareflash.app.dump.PartialDumps
import com.bareflash.app.dump.WriteMarker
import com.bareflash.app.engine.Catalogs
import com.bareflash.app.engine.EngineMode
import com.bareflash.app.engine.QdlEngine
import com.bareflash.app.engine.QdlRules
import com.bareflash.app.engine.QdlSessionStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Owns command execution, USB state and the terminal. Scoped to the
 * activity so the Console and Commands tabs share one instance.
 *
 * USB attach/permission/listing lives in [ConsoleUsb]; the types the UI
 * collects ([ConsoleStatus], [DeviceInfo], …) live in ConsoleModels.kt.
 */
class ConsoleViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val RESULT_OK = "ok"
        const val RESULT_ERROR = "error"
        const val RESULT_CANCELLED = "cancelled"
        const val RESULT_BUSY = "busy"

        /** Last-resort hint if the device does not reappear on USB by itself. */
        const val REPLUG_HINT =
            "The device did not come back on USB. Unplug it and plug it back in, then try again."

        /** How long we wait for the target to re-enumerate after a command. */
        const val RECONNECT_WAIT_MS = 18_000L

        /** How many times one interrupted dump is continued automatically. */
        const val MAX_AUTO_RESUME = 3

        val commonPartitions = listOf(
            "boot", "recovery", "vbmeta", "vbmeta_system", "vbmeta_vendor",
            "dtbo", "super", "system", "vendor", "userdata", "metadata",
            "cache", "frp", "nvram", "nvdata", "seccfg", "preloader", "lk",
            "md1img", "para", "logo", "misc"
        )
        private val partitionNamePattern = Regex("^[A-Za-z0-9_.\\-]{1,64}$")

        private val rePreloader = Regex("Device is in Preloader[- ]?[Mm]ode")
        private val reBrom = Regex("Device is in BROM[- ]?[Mm]ode|BROM mode detected")
        private val reDa = Regex("Connected to stage2|Connected to device, loading")
        private val reHw = Regex("HW ?code\\s*:?\\s*(0x[0-9a-fA-F]+)", RegexOption.IGNORE_CASE)
        private val reTarget = Regex("Target config:\\s*(0x[0-9a-fA-F]+)")
        private val reAnsi = Regex("\u001B\\[[0-9;]*[A-Za-z]")
        private val reCpu = Regex("CPU:\\s*([A-Za-z0-9/_\\-]+)\\s*\\((.*)\\)", RegexOption.MULTILINE)
        private val reHwVer = Regex("HW version:\\s*(0x[0-9a-fA-F]+)", RegexOption.IGNORE_CASE)
        private val reSwVer = Regex("SW Ver:\\s*(0x[0-9a-fA-F]+)", RegexOption.IGNORE_CASE)
        private val reBlVer = Regex("BL Ver:\\s*(0x[0-9a-fA-F]+)", RegexOption.IGNORE_CASE)
        private val reBromVer = Regex("BROM Ver:\\s*(0x[0-9a-fA-F]+)", RegexOption.IGNORE_CASE)

        /**
         * How long a command ran, for the terminal line after it finishes.
         * Under a minute: one decimal second. Then `Xm Ys`, then `Xh Ym Zs`.
         */
        fun formatElapsed(elapsedMs: Long): String {
            val ms = elapsedMs.coerceAtLeast(0L)
            if (ms < 60_000L) {
                return String.format(Locale.US, "%.1fs", ms / 1000.0)
            }
            val totalSeconds = ms / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0L) {
                "${hours}h ${minutes}m ${seconds}s"
            } else {
                String.format(Locale.US, "%dm %02ds", minutes, seconds)
            }
        }

        fun durationLogLine(result: String, elapsedMs: Long): String {
            val dur = formatElapsed(elapsedMs)
            return when (result) {
                RESULT_OK -> "Finished in $dur."
                RESULT_CANCELLED -> "Stopped after $dur."
                else -> "Failed after $dur."
            }
        }

        fun formatRate(bps: Double): String {
            if (bps < 1.0) return ""
            val mib = bps / (1024.0 * 1024.0)
            return if (mib >= 1.0) String.format(Locale.US, "%.1f MiB/s", mib)
            else String.format(Locale.US, "%.0f KiB/s", bps / 1024.0)
        }

        fun formatBytes(n: Long): String {
            if (n < 1024) return "$n B"
            if (n < 1024 * 1024) return String.format(Locale.US, "%.1f KiB", n / 1024.0)
            if (n < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MiB", n / (1024.0 * 1024.0))
            return String.format(Locale.US, "%.2f GiB", n / (1024.0 * 1024.0 * 1024.0))
        }
    }

    private val ctx: Context = app.applicationContext
    /** Id of this run's live notification session ([LiveHub]); -1 when none. */
    @Volatile private var liveId = -1
    private val completionCue = CommandCompletionCue(ctx)
    private var currentCommandLabel: String = ""
    private var lastNotifyAt = 0L
    private var lastUiProgressAt = 0L
    private var commandStartedAt = 0L
    private var commandLogStartLen = 0
    // Per-run facts for the transcript file written in finish().
    private var runWallStartMs = 0L
    private var runUsbId: String? = null
    @Volatile private var runArgv: List<String>? = null
    private val repo = AppGraph.settings
    private val mainHandler = Handler(Looper.getMainLooper())

    val terminal = TerminalModel()

    // ---- engine mode (MTK Client / QDL Flasher) ------------------------

    private val modePrefs = ctx.getSharedPreferences("engine", Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(EngineMode.fromId(modePrefs.getString("mode", null)))
    val mode: StateFlow<EngineMode> = _mode

    /** Session-tab state for QDL (programmer, storage, flags). */
    val qdlSession = QdlSessionStore(ctx)
    private val qdlEngine = QdlEngine(ctx)
    @Volatile private var qdlCancelled = false

    private val _status = MutableStateFlow(ConsoleStatus())
    val status: StateFlow<ConsoleStatus> = _status

    private val _device = MutableStateFlow<DeviceInfo?>(null)
    val device: StateFlow<DeviceInfo?> = _device

    // Chip details from the last handshake. Survives the device re-enumerating
    // (after a command it usually drops off USB and comes back), and is only
    // cleared when the phone is really unplugged.
    private val _target = MutableStateFlow(TargetInfo())
    val target: StateFlow<TargetInfo> = _target

    // Live job for the Dynamic Island: what is running, how far along, how
    // fast, and (once it ends) a one-shot result the island shows briefly.
    private val _job = MutableStateFlow<JobProgress?>(null)
    val job: StateFlow<JobProgress?> = _job

    private val _jobResult = MutableStateFlow<JobResult?>(null)
    val jobResult: StateFlow<JobResult?> = _jobResult
    private var jobSeq = 0L
    private var speedSampleAt = 0L
    private var speedSampleDone = 0L
    private var speedBps = 0.0
    @Volatile private var runSpeedSum = 0.0
    @Volatile private var runSpeedN = 0

    fun clearJobResult() { _jobResult.value = null }

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _lastError = MutableStateFlow<ErrorInfo?>(null)
    val lastError: StateFlow<ErrorInfo?> = _lastError

    /** Last command string that actually started (for Retry). */
    private val _lastCommand = MutableStateFlow<String?>(null)
    val lastCommand: StateFlow<String?> = _lastCommand
    private val lastByMode = mutableMapOf<EngineMode, Pair<String?, String?>>()

    /** Last finished outcome for the Retry chip label. */
    private val _lastOutcome = MutableStateFlow<String?>(null)
    val lastOutcome: StateFlow<String?> = _lastOutcome

    // ---- post-job summary, unfinished-write marker, resumable dumps ---------

    private val _summary = MutableStateFlow<JobSummary?>(null)
    val summary: StateFlow<JobSummary?> = _summary
    fun dismissSummary() { _summary.value = null }

    private val _writeMarker = MutableStateFlow(WriteMarker.current())
    /** Set while a destructive command is running and after one ended without success. */
    val writeMarker: StateFlow<WriteMarker.Record?> = _writeMarker

    /** Re-reads the saved marker (the Samsung flow shares it). */
    fun refreshWriteMarker() { _writeMarker.value = WriteMarker.current() }

    fun dismissWriteMarker() {
        WriteMarker.clear()
        _writeMarker.value = null
    }

    /** Runs the interrupted write again, through the normal review and confirmation. */
    fun rerunMarked() {
        val m = _writeMarker.value ?: return
        dismissWriteMarker()
        if (m.canRerun) requestRun(m.command)
    }

    /** What the current run is writing, when it is a single-file read that could be resumed. */
    @Volatile private var runTrack: PartialDumps.Record? = null
    /** One record per file when the run reads several (r a,b,c ... or rl <folder>). */
    @Volatile private var runTracks: List<PartialDumps.Record> = emptyList()
    /** Digests of the files the last run wrote (streamed by Python while writing). */
    @Volatile private var runDigests: List<DumpIntegrity.Streamed> = emptyList()

    private val _pending = MutableStateFlow<PendingCommand?>(null)
    val pending: StateFlow<PendingCommand?> = _pending
    private var pendingCallback: ((String) -> Unit)? = null

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events

    private val _partitions = MutableStateFlow<List<GptPartition>>(emptyList())
    val partitions: StateFlow<List<GptPartition>> = _partitions
    private val gptParse = GptParseState()

    private val _dumpsEpoch = MutableStateFlow(0L)
    val dumpsEpoch: StateFlow<Long> = _dumpsEpoch

    @Volatile private var reconnectGen = 0
    private var reconnectPermissionFor: String? = null

    // Fired whenever a command actually starts running, regardless of which
    // tab it was launched from — MainShell switches to the Console tab on
    // this so the running command and its output are always visible instead
    // of silently continuing behind whatever tab you were on.
    private val _switchToConsole = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val switchToConsole: SharedFlow<Unit> = _switchToConsole

    /** UI opens OpenDocumentTree; result goes to [onDumpFolderPicked]. */
    private val _requestDumpFolder = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requestDumpFolder: SharedFlow<Unit> = _requestDumpFolder

    private var pendingFolderCmd: String? = null
    private var pendingFolderCallback: ((String) -> Unit)? = null

    @Volatile private var detachedDuringRun = false
    /** The user pressed Stop for the current run: never auto-resume or continue a queue after it. */
    @Volatile private var userStopped = false
    /** Numbers every run so a Stop can only ever reach the run it was pressed on (see android_entry.cancel). */
    @Volatile private var runSeq = 0L
    /** A resume started by [resumeDump]; keeps its own auto-resume bookkeeping. */
    @Volatile private var resumeInFlight = false
    /** The interrupted dump to continue when the device is back (see [Extras.autoResume]). */
    @Volatile private var autoResumeRel: String? = null
    private val autoAttempts = HashMap<String, Int>()
    /** A hash / compare / verify job runs on a worker thread outside the command loop. */
    @Volatile private var verifyBusy = false
    /** Set while the cable and port speed test runs, so its result is kept apart from normal reads. */
    @Volatile private var speedTestActive = false
    @Volatile private var fullBackupActive = false
    private var lastProfileHw: String? = null

    // ---- setup ---------------------------------------------------------

    init {
        UsbBridge.init(ctx)
        if (!Python.isStarted()) Python.start(AndroidPlatform(ctx))

        UiBridge.listener = { msg -> onLog(msg) }
        UiBridge.progressListener = { done, total, label ->
            if (total > 0) {
                val nowMs = SystemClock.elapsedRealtime()
                val frac = (done.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
                val throttled = frac < 1f && nowMs - lastUiProgressAt < 80L && lastUiProgressAt != 0L
                com.bareflash.app.PerfBoost.tick()
                if (!throttled) {
                    lastUiProgressAt = nowMs
                    _status.update {
                        if (it.state == LinkState.Running) it.copy(progress = frac, progressLabel = label) else it
                    }
                    onJobProgress(done, total, label)
                    val j = _job.value
                    if (liveId >= 0 && j != null) LiveHub.progress(liveId, done, total, j.speedBps, j.etaMs, label)
                }
            }
        }
        UiBridge.errorListener = { friendly, detail -> _lastError.value = ErrorInfo(friendly, detail) }
        // Same entry point as the Console's Stop button; used by the notification's Stop action.
        LiveControl.cancel = { cancelCommand() }
        AppLog.extraExport = {
            buildMap {
                errorReport()?.let { put("last-error.txt", it) }
                put("terminal-tail.txt", terminal.text.value.takeLast(200_000))
                put("state.txt", buildString {
                    append("mode=").append(_mode.value.short).append('\n')
                    append("write marker=").append(WriteMarker.current()?.let { "${it.target} (${it.state})" } ?: "none").append('\n')
                    PartialDumps.all().values.forEach { append("partial dump: ${it.rel} total=${it.total}\n") }
                })
            }
        }
    }

    /** Called from the engine thread; folds raw byte counts into speed and ETA. */
    private fun onJobProgress(done: Long, total: Long, label: String) {
        val now = SystemClock.elapsedRealtime()
        if (speedSampleAt == 0L) {
            speedSampleAt = now
            speedSampleDone = done
        } else {
            val dt = now - speedSampleAt
            if (dt >= 400L) {
                val inst = (done - speedSampleDone).coerceAtLeast(0L) * 1000.0 / dt
                speedBps = if (speedBps <= 0.0) inst else speedBps * 0.7 + inst * 0.3
                speedSampleAt = now
                speedSampleDone = done
            }
        }
        if (speedBps > 1.0) { runSpeedSum += speedBps; runSpeedN++ }
        val eta = if (speedBps > 1.0 && total > done) ((total - done) / speedBps * 1000.0).toLong() else -1L
        _job.update { it?.copy(stage = label, bytesDone = done, bytesTotal = total, speedBps = speedBps, etaMs = eta) }
    }

    override fun onCleared() {
        LiveControl.cancel = null
        AppLog.extraExport = null
        stopReconnect()
        UiBridge.listener = null
        UiBridge.progressListener = null
        UiBridge.errorListener = null
        runCatching { usb.unregister() }
        UsbBridge.close(false)
    }

    // ---- terminal ------------------------------------------------------

    // Collapses Kotlin-originated connection chatter (see
    // TerminalNoiseFilter's doc comment). Applied in onLog(), the single
    // funnel every message passes through regardless of whether it
    // originated in Kotlin (USB attach/detach, detectDevice()) or Python
    // (via UiBridge) -- so one filter covers both.
    private val terminalNoiseFilter = TerminalNoiseFilter()

    private fun engineSource() =
        if (_mode.value == EngineMode.QDL) LogSource.Qualcomm else LogSource.MediaTek

    private fun onLog(msg: String) {
        AppLog.terminal(engineSource(), msg)
        parseState(msg)
        ingestGpt(msg)
        val filtered = terminalNoiseFilter.filter(msg)
        if (filtered.isNotEmpty()) terminal.append(filtered)
    }

    private fun ingestGpt(raw: String) {
        val clean = reAnsi.replace(raw, "")
        val table = gptParse.ingest(clean) ?: return
        _partitions.value = table
        post("Partition table: ${table.size} partitions. Open Table to dump or flash.")
    }

    fun log(msg: String) = onLog(msg)

    fun clearTerminal() {
        terminal.clear()
        terminalNoiseFilter.reset()
    }

    private fun parseState(raw: String) {
        val msg = reAnsi.replace(raw, "")
        var mode: LinkMode? = null
        if (rePreloader.containsMatchIn(msg)) mode = LinkMode.Preloader
        if (reBrom.containsMatchIn(msg)) mode = LinkMode.BootRom
        if (reDa.containsMatchIn(msg)) mode = LinkMode.Da
        val newMode = mode
        if (newMode != null) _status.update {
            if (it.state == LinkState.Connected || it.state == LinkState.Running) it.copy(mode = newMode) else it
        }
        val hw = reHw.find(msg)?.groupValues?.get(1)
        val tc = reTarget.find(msg)?.groupValues?.get(1)
        val cpu = reCpu.find(msg)
        val hwVer = reHwVer.find(msg)?.groupValues?.get(1)
        val swVer = reSwVer.find(msg)?.groupValues?.get(1)
        val blVer = reBlVer.find(msg)?.groupValues?.get(1)
        val bromVer = reBromVer.find(msg)?.groupValues?.get(1)
        if (hw != null || tc != null || cpu != null || hwVer != null || swVer != null || blVer != null || bromVer != null) {
            _target.update { t ->
                t.copy(
                    cpu = cpu?.groupValues?.get(1) ?: t.cpu,
                    chipset = cpu?.groupValues?.get(2)?.trim() ?: t.chipset,
                    hwCode = hw ?: t.hwCode,
                    hwVer = hwVer ?: t.hwVer,
                    swVer = swVer ?: t.swVer,
                    blVer = blVer ?: t.blVer,
                    bromVer = bromVer ?: t.bromVer,
                    targetConfig = tc ?: t.targetConfig
                )
            }
        }
        if (hw != null || tc != null) _device.update { d ->
            d?.copy(hwCode = hw ?: d.hwCode, targetConfig = tc ?: d.targetConfig)
        }
        if (cpu != null && liveId >= 0) LiveHub.device(liveId, liveDevice())
        if (hw != null && hw != lastProfileHw) {
            lastProfileHw = hw
            applyChipProfile(hw)
        }
    }

    /** Re-applies the protocol settings saved for this chip, if there are any and the switch is on. */
    private fun applyChipProfile(hw: String) {
        if (!Extras.chipProfiles.value) return
        val cur = repo.state.value.protocols
        val next = ChipProfiles.applyTo(hw, cur) ?: return
        if (next == cur) return
        repo.update { it.copy(protocols = next) }
        mainHandler.post { log("Applied the saved settings for chip $hw.\n") }
    }

    /** Saves the current protocol settings for the connected chip. Returns false when no chip is known yet. */
    fun saveChipProfile(): Boolean {
        val hw = _target.value.hwCode ?: return false
        ChipProfiles.save(hw, repo.state.value.protocols)
        return true
    }

    fun forgetChipProfile() { _target.value.hwCode?.let { ChipProfiles.forget(it) } }
    fun hasChipProfile(): Boolean = _target.value.hwCode?.let { ChipProfiles.has(it) } ?: false

    private fun post(message: String) { _events.tryEmit(message) }

    /** Shows a short in-app toast, for the terminal ("Line copied"). */
    fun toast(message: String) = post(message)

    // ---- USB -----------------------------------------------------------

    private val usbHost = object : ConsoleUsb.Host {
        override fun log(msg: String) = onLog(msg)
        override fun onPermissionGranted() {
            if (!tryClaimReconnect()) refreshIdle()
        }
        override fun onPermissionDenied() {
            _lastError.value = ErrorInfo(
                "USB access was denied.",
                "The user declined the USB permission request for the device."
            )
            _status.value = ConsoleStatus(LinkState.Error, message = "Access denied")
            stopReconnect()
        }
        override fun onAttached() {
            if (_running.value) return
            if (_mode.value == EngineMode.BROKKR || _mode.value == EngineMode.ADB_FASTBOOT) return
            if (!tryClaimReconnect()) detectDevice()
        }
        override fun onDetached() {
            if (_running.value) {
                // Devices re-enumerate as part of normal operation, so this only
                // marks the run; it becomes "Connection lost" if the command fails.
                detachedDuringRun = true
            } else if (_status.value.state == LinkState.Reconnecting) {
                // Expected drop between commands — keep chip details and wait.
            } else {
                _device.value = null
                _target.value = TargetInfo()
                lastProfileHw = null
                _partitions.value = emptyList()
                gptParse.reset()
                refreshIdle()
            }
        }
    }

    private val usb = ConsoleUsb(ctx, usbHost)

    // Registered here, after usb exists (property initialisers and
    // init blocks run in declaration order).
    init {
        usb.vendorIds = _mode.value.vendorIds
        usb.register()
        refreshIdle()
    }

    /**
     * Switches the active engine. Refused while a command runs. Clears the
     * per-device state (chip details, partitions) because it belongs to the
     * other engine, then re-scans USB for the new engine's vendor.
     */
    fun setMode(m: EngineMode) {
        if (m == _mode.value) return
        if (_running.value) { post("Wait for the current command to finish."); return }
        stopReconnect()
        pendingCallback = null
        _pending.value = null
        lastByMode[_mode.value] = _lastCommand.value to _lastOutcome.value
        _mode.value = m
        modePrefs.edit().putString("mode", m.id).apply()
        usb.vendorIds = m.vendorIds
        _device.value = null
        _target.value = TargetInfo()
        _partitions.value = emptyList()
        gptParse.reset()
        _lastError.value = null
        val saved = lastByMode[m]
        _lastCommand.value = saved?.first
        _lastOutcome.value = saved?.second
        _status.value = ConsoleStatus()
        refreshIdle()
        if (usb.attachedDevices().any { it.vendorId in m.vendorIds }) detectDevice()
    }

    private fun applyDefaults(cmd: String): CommandRules.Applied =
        if (_mode.value == EngineMode.MTK) CommandRules.applyDefaults(cmd, repo.state.value.protocols)
        else CommandRules.Applied(cmd, emptyList())

    /**
     * One line of qdl output (any thread, no trailing newline). Progress comes
     * as `@@progress <value> <max> <bytesPerUnit> <task>` and drives the bar
     * instead of being logged; everything else goes to the terminal.
     */
    private fun onQdlOutput(line: String) {
        if (line.startsWith("@@progress ")) {
            val p = line.split(' ', limit = 5)
            val value = p.getOrNull(1)?.toLongOrNull()
            val max = p.getOrNull(2)?.toLongOrNull()
            val unit = p.getOrNull(3)?.toLongOrNull() ?: 0L
            if (value != null && max != null && max > 0) {
                val scale = if (unit > 0) unit else 1L
                UiBridge.reportProgress(value * scale, max * scale, p.getOrNull(4)?.trim().orEmpty().ifEmpty { "Working" })
            }
            return
        }
        onLog(line + "\n")
    }

    private fun runQdl(command: String): String {
        val plan = QdlRules.plan(command, qdlSession.state.value, dumpsDir().parentFile!!, DumpBridge::realInput)
        if (plan !is QdlRules.Plan.Run) {
            val msg = (plan as QdlRules.Plan.Reject).message
            log("$msg\n")
            _lastError.value = ErrorInfo(msg, "Command: $command")
            return RESULT_ERROR
        }
        val prog = qdlSession.state.value.programmer
        // Programmer path can be the file name only in anything that gets shared.
        runArgv = plan.argv.map { if (prog.isNotBlank() && it == prog) File(it).name else it }
        // Dry run is a sticky session switch; say so before argv so a "successful
        // flash" that sent nothing is obvious from the log alone.
        if ("--dry-run" in plan.argv) log("dry run \u2014 nothing will be sent\n")
        log("\u2192 qdl ${plan.argv.joinToString(" ")}\n")
        qdlCancelled = userStopped
        val rc = qdlEngine.run(plan) { onQdlOutput(it) }
        return when {
            qdlCancelled -> RESULT_CANCELLED
            rc == 0 -> RESULT_OK
            else -> {
                _lastError.value = ErrorInfo(
                    qdlEngine.describe(rc),
                    "Command: qdl ${plan.argv.joinToString(" ")}\nExit code: $rc"
                )
                RESULT_ERROR
            }
        }
    }

    /** Lists attached devices and requests USB access for MediaTek ones. */
    fun detectDevice() {
        stopReconnect()
        val devices = usb.attachedDevices()
        ConnectionDoctor.lines(
            devices.map { ConnectionDoctor.Seen(it.vendorId, it.productId, usb.hasPermission(it)) },
            _mode.value
        ).forEach { log("$it\n") }
        if (devices.isEmpty()) {
            _device.value = null
            _target.value = TargetInfo()
            if (!_running.value) _status.value = ConsoleStatus()
            return
        }
        if (!_running.value) _status.value = ConsoleStatus(LinkState.Detecting)
        usb.logAndRequest(devices)
        refreshIdle(keepDetecting = true)
    }

    /** Sets the resting status from what is currently attached. */
    private fun refreshIdle(keepDetecting: Boolean = false) {
        if (_running.value) return
        val mtk = usb.mtkDevices()
        val ready = mtk.firstOrNull { usb.hasPermission(it) }
        when {
            ready != null -> {
                _device.update {
                    it ?: DeviceInfo(
                        ready.vendorId, ready.productId,
                        hwCode = _target.value.hwCode, targetConfig = _target.value.targetConfig
                    )
                }
                _status.update { ConsoleStatus(LinkState.Connected, mode = it.mode) }
            }
            mtk.isNotEmpty() && keepDetecting -> _status.value = ConsoleStatus(LinkState.Detecting)
            else -> {
                _device.value = null
                _status.value = ConsoleStatus()
            }
        }
    }

    // ---- command execution ---------------------------------------------

    private val dumpsArg = Regex("""(^|\s)['"]?dumps(/|['"]?(\s|$))""")

    /** True when the command names a path under `dumps/`, i.e. it saves or reads a file there. */
    private fun writesToDumps(cmd: String) = dumpsArg.containsMatchIn(cmd)

    /** Entry point for typed commands, pinned actions and the Commands tab. */
    fun requestRun(command: String, onResult: ((String) -> Unit)? = null) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        if (cmd == "clear") { clearTerminal(); return }
        if (cmd == "identify") { identify(); return }
        if (_running.value) {
            post("A command is already running.")
            onResult?.invoke(RESULT_BUSY)
            return
        }
        if (!Catalogs.isKnown(_mode.value, cmd)) {
            val first = cmd.substringBefore(' ')
            log("\"$first\" is not a valid command.\n")
            _lastError.value = ErrorInfo(
                "\"$first\" is not a valid command.",
                "Command: $cmd"
            )
            onResult?.invoke(RESULT_ERROR)
            return
        }
        if (_running.value || verifyBusy) {
            post("A command is already running.")
            onResult?.invoke(RESULT_BUSY)
            return
        }
        if (refuseIfReadOnly(cmd, onResult)) return
        if (_mode.value == EngineMode.MTK && writesToDumps(cmd) &&
            !DumpBridge.isEnabled() && !DumpBridge.hasPicked()
        ) {
            pendingFolderCmd = cmd
            pendingFolderCallback = onResult
            _switchToConsole.tryEmit(Unit)
            _requestDumpFolder.tryEmit(Unit)
            return
        }
        val dumps = dumpsDir()
        if (_mode.value == EngineMode.QDL) {
            // Fail fast (missing programmer, unsupported verb, bad arguments)
            // before a destructive confirmation is shown for a command that
            // could never run.
            val plan = QdlRules.plan(cmd, qdlSession.state.value, dumps.parentFile!!, DumpBridge::realInput)
            if (plan is QdlRules.Plan.Reject) {
                _switchToConsole.tryEmit(Unit)
                log("${plan.message}\n")
                _lastError.value = ErrorInfo(plan.message, "Command: $cmd")
                onResult?.invoke(RESULT_ERROR)
                return
            }
        }
        // A compare-only read against the device writes nothing, so free space and FAT32 limits do not apply.
        val sinkRun = deviceVerifyActive && cmd.contains(deviceVerifyRel)
        val space: DumpSpaceGuard.Verdict? = if (sinkRun) {
            DumpSpaceGuard.Verdict.Ok
        } else if (_mode.value == EngineMode.MTK) {
            DumpSpaceGuard.check(cmd, dumps, repo.state.value.protocols.sectorSize, _partitions.value)
        } else {
            val q = qdlSession.state.value
            DumpSpaceGuard.checkQdl(cmd, dumps, q.storage) { name ->
                when {
                    name.startsWith("/") -> File(name)
                    name.startsWith("dumps/") -> DumpBridge.realInput(name.removePrefix("dumps/"))
                    else -> DumpBridge.realInput(name) ?: File(q.includeDir.ifBlank { dumps.parent.orEmpty() }, name)
                }
            }.verdict
        }
        if (space is DumpSpaceGuard.Verdict.Blocked) {
            // Jump to Console so a dump started from Commands still shows
            // the snackbar; the command never starts, so no USB handshake.
            _switchToConsole.tryEmit(Unit)
            log("${space.userMessage()}\n")
            _lastError.value = ErrorInfo(space.userMessage(), space.detail(cmd, dumps))
            onResult?.invoke(RESULT_ERROR)
            return
        }
        if (_mode.value == EngineMode.MTK && !sinkRun) {
            when (val fat = Fat32Guard.check(cmd, repo.state.value.protocols.sectorSize, _partitions.value)) {
                is Fat32Guard.Verdict.Blocked -> {
                    _switchToConsole.tryEmit(Unit)
                    log("${fat.message}\n")
                    _lastError.value = ErrorInfo(fat.message, "Command: $cmd")
                    onResult?.invoke(RESULT_ERROR)
                    return
                }
                is Fat32Guard.Verdict.Maybe -> log("${fat.message}\n")
                Fat32Guard.Verdict.Fine -> Unit
            }
        }
        if (Catalogs.isDestructive(_mode.value, cmd)) {
            val applied = applyDefaults(cmd)
            val check = WriteGuard.evaluate(_mode.value == EngineMode.QDL, applied.command, _partitions.value)
            if (check?.blocked != null) {
                _switchToConsole.tryEmit(Unit)
                log("${check.blocked}\n")
                _lastError.value = ErrorInfo(check.blocked, "Command: ${applied.command}")
                onResult?.invoke(RESULT_ERROR)
                return
            }
            pendingCallback = onResult
            _pending.value = PendingCommand(cmd, applied.command, check)
            // The confirmation dialog only exists in the Console tab's UI, so
            // without this, confirming a destructive command from Commands
            // would set this state with nothing composed to show it — it
            // would just silently hang. Jump to Console immediately, before
            // confirmation, not only once the command actually starts.
            _switchToConsole.tryEmit(Unit)
        } else {
            execute(cmd, onResult)
        }
    }

    /** The read-only lock refuses every write, erase and flash. Returns true when [cmd] was refused. */
    private fun refuseIfReadOnly(cmd: String, onResult: ((String) -> Unit)?): Boolean {
        if (!Extras.readOnlyLock.value || !Catalogs.isDestructive(_mode.value, cmd)) return false
        val msg = "Read-only lock is on, so nothing was written. Turn it off in Settings to write, erase or flash."
        _switchToConsole.tryEmit(Unit)
        log("$msg\n")
        _lastError.value = ErrorInfo(msg, "Command: $cmd")
        onResult?.invoke(RESULT_ERROR)
        return true
    }

    fun confirmPending(opts: WriteOptions = WriteOptions()) {
        val p = _pending.value ?: return
        val cb = pendingCallback
        _pending.value = null
        pendingCallback = null
        val offer = SafetyNet.offer(_mode.value, p, DumpBridge.isEnabled(), DumpBridge.availableBytes())
        val part = offer.partition
        val doBackup = opts.backupFirst && offer.backupAvailable && part != null
        val doVerify = opts.verifyAfter && offer.verifyAvailable && part != null

        val write = {
            execute(p.command) { r ->
                if (r == RESULT_OK && doVerify && _lastError.value == null) verifyWrite(p, cb)
                else cb?.invoke(r)
            }
        }
        if (!doBackup || part == null) { write(); return }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val rel = "backups/${part}_$stamp.img"
        val readCmd = "r $part dumps/$rel"
        if (Fat32Guard.check(readCmd, repo.state.value.protocols.sectorSize, _partitions.value) is Fat32Guard.Verdict.Blocked) {
            val msg = "The backup of $part cannot be saved to a FAT32 folder, so nothing was written."
            log("$msg\n")
            _lastError.value = ErrorInfo(msg, "Command: $readCmd")
            cb?.invoke(RESULT_ERROR)
            return
        }
        log("Backing up $part before writing...\n")
        execute(readCmd) { r ->
            if (r == RESULT_OK && _lastError.value == null) {
                log("Backup saved as $rel.\n")
                write()
            } else {
                log("The backup of $part did not finish, so nothing was written.\n")
                cb?.invoke(if (r == RESULT_CANCELLED) RESULT_CANCELLED else RESULT_ERROR)
            }
        }
    }

    /** Reads the partition back and compares its SHA-256 with the image that was written. */
    private fun verifyWrite(p: PendingCommand, cb: ((String) -> Unit)?) {
        if (_mode.value == EngineMode.QDL) { verifyWriteQdl(p, cb); return }
        val pair = SafetyNet.singleWrite(p.finalCommand)
        if (pair == null) { cb?.invoke(RESULT_OK); return }
        val (part, file) = pair
        val imageBytes = p.check?.imageBytes ?: -1L
        val rel = "verify_tmp/$part.img"
        log("Reading $part back to compare it with the image...\n")
        execute("r $part dumps/$rel") { r ->
            if (r != RESULT_OK) {
                DumpBridge.deleteRel("verify_tmp")
                setVerify("The write finished, but reading $part back failed, so it was not verified.", ok = null)
                cb?.invoke(RESULT_OK)
                return@execute
            }
            thread(name = "verify-hash") {
                val limit = if (imageBytes > 0L) imageBytes else Long.MAX_VALUE
                val want = runCatching {
                    val s: java.io.InputStream? =
                        if (file.startsWith("/")) java.io.FileInputStream(file)
                        else DumpBridge.openRead(file.removePrefix("dumps/"))
                    s?.use { DumpIntegrity.sha256(it, limit) }
                }.getOrNull()
                val got = DumpIntegrity.sha256Rel(rel, limit)
                DumpBridge.deleteRel("verify_tmp")
                mainHandler.post {
                    when {
                        want == null || got == null -> {
                            log("Could not hash the image or the read-back, so $part was not verified.\n")
                            setVerify("Not verified: could not hash the image or the read-back.", ok = null)
                            cb?.invoke(RESULT_OK)
                        }
                        want == got -> {
                            log("Verified: $part read back with the same SHA-256 as the image ($want).\n")
                            setVerify("Verified: read-back SHA-256 matches the image.", ok = true)
                            cb?.invoke(RESULT_OK)
                        }
                        else -> {
                            val msg = "MISMATCH: $part does not match the image. Image $want, device $got. Write it again before rebooting."
                            log("$msg\n")
                            _lastError.value = ErrorInfo("The partition read back different from the image.", msg)
                            setVerify(msg, ok = false)
                            // Keep the unfinished-write marker: the partition is not known good.
                            WriteMarker.begin(_mode.value.short, part, p.command)
                            WriteMarker.end("error")
                            _writeMarker.value = WriteMarker.current()
                            cb?.invoke(RESULT_ERROR)
                        }
                    }
                }
            }
        }
    }

    private fun setVerify(text: String, ok: Boolean?) {
        _summary.update { it?.copy(verify = text, verifyOk = ok) }
    }

    fun dismissPending() {
        val cb = pendingCallback
        _pending.value = null
        pendingCallback = null
        cb?.invoke(RESULT_CANCELLED)
    }

    private fun execute(cmd: String, onResult: ((String) -> Unit)?) {
        if (_running.value) { onResult?.invoke(RESULT_BUSY); return }
        if (refuseIfReadOnly(cmd, onResult)) return
        stopReconnect()
        if (!resumeInFlight) autoResumeRel = null
        _switchToConsole.tryEmit(Unit)
        repo.recordCommand(_mode.value, cmd)
        val applied = applyDefaults(cmd)
        val runMode = _mode.value
        currentCommandLabel = applied.command
        _lastCommand.value = applied.command
        _lastError.value = null
        // Snapshot of the terminal right before this command's own output
        // starts, so a failure can be reported with everything this run
        // actually printed -- not just whatever detail Python's own error
        // path happened to capture (see captureErrorDetail in finish()).
        commandLogStartLen = terminal.text.value.length
        detachedDuringRun = false
        userStopped = false
        val myRunId = ++runSeq
        runWallStartMs = System.currentTimeMillis()
        runArgv = null
        runUsbId = _device.value?.let { "%04x:%04x".format(it.vid, it.pid) }
        AppLog.activeEngine = engineSource()
        AppLog.log(
            LogLevel.Info, engineSource(), "run",
            "START ${applied.command}  [engine=${runMode.short}, usb=${runUsbId ?: "none"}]"
        )
        _summary.value = null
        runDigests = emptyList()
        if (Catalogs.isDestructive(runMode, cmd)) {
            // Saved before anything is sent; cleared only when the command succeeds.
            val info = JobInfo.describe(applied.command)
            WriteMarker.begin(runMode.short, info.third.ifBlank { info.first }, cmd)
            _writeMarker.value = WriteMarker.current()
        }
        runTrack = if (runMode == EngineMode.MTK) PartialDumps.trackable(applied.command, _partitions.value) else null
        runTrack?.let { PartialDumps.put(it) }
        runTracks = if (runMode == EngineMode.MTK && runTrack == null) PartialDumps.trackableAll(applied.command, _partitions.value) else emptyList()
        runTracks.forEach { PartialDumps.put(it) }
        if (runMode == EngineMode.MTK) {
            DumpIntegrity.takeStreamed() // drop digests left over from an earlier run
            Preflight.lowBatteryNote(ctx)?.let { log("$it\n") }
        }
        _running.value = true
        commandStartedAt = SystemClock.elapsedRealtime()
        speedSampleAt = 0L
        speedSampleDone = 0L
        speedBps = 0.0
        runSpeedSum = 0.0
        runSpeedN = 0
        _jobResult.value = null
        JobInfo.describe(applied.command).let { (title, kind, target) ->
            _job.value = JobProgress(title = title, kind = kind, target = target)
        }
        _status.value = ConsoleStatus(LinkState.Running, mode = _status.value.mode)
        log("$ ${applied.command}\n")
        // Hold a partial wake lock for the whole command so a long dump is
        // not throttled once the screen idles.
        JobInfo.describe(applied.command).let { (title, jk, target) ->
            liveId = LiveHub.begin(
                liveKindFor(applied.command, jk), liveEngineFor(runMode),
                liveTitle(applied.command, title),
                target, liveDevice()
            )
        }

        thread(name = "mtk-command") {
            // Pin this worker (Python + every libusb call runs on it) to the
            // foreground scheduling group with a real priority boost.
            // Process.setThreadPriority() with a foreground-class value also
            // moves the thread into the foreground cgroup; a plain
            // thread{} inherits whatever group its creator was in. Without
            // this the transfer thread can be scheduled like background work
            // and starve at low clocks.
            runCatching {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            }
            // Fast-core affinity + ADPF hint session for this thread, so
            // throughput does not depend on the UI generating load.
            runCatching { com.bareflash.app.PerfBoost.begin(ctx) }
            var result = RESULT_ERROR
            val recordNote = RunSidecar.shouldRecord(runMode == EngineMode.QDL, applied.command)
            val before = if (recordNote) RunSidecar.snapshot() else emptyMap()
            try {
                result = if (userStopped) {
                    // Stop was pressed before the worker got going; nothing to run.
                    RESULT_CANCELLED
                } else if (runMode == EngineMode.QDL) {
                    runQdl(applied.command)
                } else {
                    Python.getInstance().getModule("android_entry")
                        .callAttr("run_command", applied.command, dumpsDir().parentFile!!.absolutePath, myRunId)
                        .toString()
                }
            } catch (e: Exception) {
                if (userStopped && e is com.chaquo.python.PyException) {
                    // The cancel exception reached us after the command had already returned.
                    AppLog.log(LogLevel.Warn, engineSource(), "run", "Late exception after Stop (ignored): ${e.message}", null)
                    result = RESULT_CANCELLED
                } else {
                    AppLog.log(LogLevel.Error, engineSource(), "run", "The command could not be started: ${applied.command}", e)
                    _lastError.value = ErrorInfo(
                        "The command could not be started.",
                        e.stackTraceToString()
                    )
                    log("\nThe command could not be started.\n")
                }
            } finally {
                runCatching { com.bareflash.app.PerfBoost.end() }
                if (runMode == EngineMode.MTK) {
                    val streamedAll = DumpIntegrity.takeStreamed()
                    // A run that failed part-way still closed its finished files cleanly. Keep those
                    // whose size is exactly the size the table promised; a cut-short file never matches.
                    val streamed = if (result == RESULT_OK) streamedAll
                    else streamedAll.filter { st -> runTracks.any { it.rel == st.rel && it.total == st.bytes } }
                    if (streamed.isNotEmpty()) {
                        runCatching {
                            runDigests = DumpIntegrity.recordStreamed(
                                streamed, manifestContext(applied.command, runMode), ::partitionForRel
                            )
                        }.onFailure { AppLog.w("integrity", "Writing manifests failed", it) }
                    }
                }
                if (recordNote && result == RESULT_OK) runCatching {
                    val full = terminal.text.value
                    RunSidecar.record(
                        engine = runMode.short,
                        isQdl = runMode == EngineMode.QDL,
                        command = applied.command,
                        startedAtMs = runWallStartMs,
                        before = before,
                        target = _target.value,
                        gpt = _partitions.value,
                        slice = full.substring(commandLogStartLen.coerceIn(0, full.length))
                    )
                }
                mainHandler.post { finish(result, onResult) }
            }
        }
    }

    private fun appVersion(): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

    private fun manifestContext(command: String, m: EngineMode) = DumpIntegrity.Context(
        appVersion = appVersion(), engine = m.short, command = command, usb = runUsbId,
        target = _target.value, partition = null, gptCount = _partitions.value.size, method = "streamed",
    )

    private fun partitionForRel(rel: String): GptPartition? {
        runTracks.firstOrNull { it.rel == rel }?.let { rec ->
            return _partitions.value.firstOrNull { it.offset == rec.baseOffset && it.length == rec.total }
        }
        val t = runTrack ?: return null
        if (t.rel != rel || t.total <= 0L) return null
        return _partitions.value.firstOrNull { it.offset == t.baseOffset && it.length == t.total }
    }

    private fun finish(result: String, onResult: ((String) -> Unit)?) {
        _running.value = false
        if (result == RESULT_ERROR) captureErrorDetail()
        val finishedJob = _job.value
        _job.value?.let { j ->
            _jobResult.value = JobResult(++jobSeq, j.title, j.kind, j.target, result, if (result == RESULT_OK) 1f else j.fraction)
        }
        _job.value = null
        _lastOutcome.value = result
        val clean = result == RESULT_OK && _lastError.value == null
        if (Catalogs.isDestructive(_mode.value, currentCommandLabel)) {
            if (clean) {
                WriteMarker.clear()
                _writeMarker.value = null
            } else {
                WriteMarker.end(if (result == RESULT_CANCELLED) "stopped" else "error")
                _writeMarker.value = WriteMarker.current()
            }
        }
        var resumable: String? = null
        runTrack?.let { t ->
            if (clean) {
                PartialDumps.remove(t.rel)
            } else {
                val total = if (t.total > 0L) t.total else (finishedJob?.bytesTotal ?: 0L)
                PartialDumps.put(t.copy(total = total))
                if (total > 0L && DumpBridge.size(t.rel) >= 0L) resumable = t.rel
            }
        }
        runTrack = null
        if (runTracks.isNotEmpty()) {
            for (t in runTracks) {
                val have = DumpBridge.size(t.rel)
                if (clean || (t.total > 0L && have == t.total)) PartialDumps.remove(t.rel)
                else if (resumable == null && t.total > 0L) resumable = t.rel
            }
            runTracks = emptyList()
        }
        // A cut-short read whose cable dropped continues by itself when the device comes back.
        val resumeRel = resumable
        if (result == RESULT_ERROR && detachedDuringRun && resumeRel != null && Extras.autoResume.value &&
            !userStopped && _mode.value == EngineMode.MTK
        ) {
            if ((autoAttempts[resumeRel] ?: 0) < MAX_AUTO_RESUME) {
                autoResumeRel = resumeRel
            } else {
                autoAttempts.remove(resumeRel)
                log("Auto-resume gave up after $MAX_AUTO_RESUME attempts. Resume it from the Dumps tab when the cable is steady.\n")
            }
        }
        val g = repo.state.value.general
        val outcome = when (result) {
            RESULT_OK -> CommandCompletionCue.Outcome.Success
            RESULT_CANCELLED -> CommandCompletionCue.Outcome.Cancelled
            else -> CommandCompletionCue.Outcome.Error
        }
        completionCue.play(outcome, vibrate = g.vibrateOnCommandEnd, sound = g.soundOnCommandEnd)
        // One-line summary: result, time, optional bytes + rate.
        val elapsed = SystemClock.elapsedRealtime() - commandStartedAt
        val base = durationLogLine(result, elapsed)
        val bytes = finishedJob?.bytesDone ?: 0L
        val rate = finishedJob?.speedBps ?: 0.0
        val extra = buildString {
            if (bytes > 0L) append(" ").append(formatBytes(bytes))
            val r = formatRate(rate)
            if (r.isNotEmpty()) append(" @ ").append(r)
            val folder = DumpBridge.displayName()
            if (bytes > 0L && DumpBridge.isEnabled() && !deviceVerifyActive) append(" → ").append(folder)
        }
        log("$base$extra\n")
        if (result == RESULT_ERROR) _lastError.value?.hint?.let { log("Hint: $it\n") }
        val blankWarnings = DumpIntegrity.blankWarnings(runDigests)
        blankWarnings.forEach { log("Warning: $it\n") }
        finishedJob?.let { j ->
            if (j.kind != JobKind.Control || result != RESULT_OK) {
                _summary.value = JobSummary(
                    id = jobSeq, title = j.title, kind = j.kind, target = j.target, outcome = result,
                    elapsedMs = elapsed, bytes = bytes,
                    avgBps = if (elapsed > 0L && bytes > 0L) bytes * 1000.0 / elapsed else 0.0,
                    destination = if (bytes > 0L && DumpBridge.isEnabled() && !deviceVerifyActive) DumpBridge.displayName() else null,
                    hashes = runDigests.map { it.rel to it.sha256 },
                    warnings = blankWarnings,
                    error = if (result == RESULT_ERROR) _lastError.value?.friendly else null,
                    hint = if (result == RESULT_ERROR) _lastError.value?.hint else null,
                    resumable = resumable,
                )
            }
        }
        AppLog.log(
            if (result == RESULT_ERROR) LogLevel.Error else LogLevel.Info,
            engineSource(), "run",
            "END ${currentCommandLabel.trim()} -> $result after ${SystemClock.elapsedRealtime() - commandStartedAt} ms" +
                (runUsbId?.let { " [usb=$it]" } ?: "") + (if (detachedDuringRun) " [device detached during run]" else "")
        )
        _dumpsEpoch.value = _dumpsEpoch.value + 1
        val first = currentCommandLabel.trim().substringBefore(' ').lowercase()
        if (_mode.value == EngineMode.MTK && first.isNotEmpty() && first != "devices") {
            beginReconnect()
        } else if (result == RESULT_ERROR) {
            _status.value = ConsoleStatus(
                LinkState.Error,
                message = if (detachedDuringRun) "Connection lost" else "Command failed"
            )
        } else {
            if (detachedDuringRun) _device.value = null
            refreshIdle()
            if (_mode.value == EngineMode.QDL) logEdlState(result)
        }
        if (_mode.value == EngineMode.QDL && result == RESULT_OK) reportProgrammerMatch()

        // ---- live notification: end it, or hold it open while a chained step takes over ----
        val lid = liveId
        liveId = -1
        val liveRes = LiveResult(
            kind = liveKindFor(currentCommandLabel, finishedJob?.kind ?: JobKind.Control),
            engine = liveEngineFor(_mode.value),
            outcome = when (result) {
                RESULT_OK -> LiveOutcome.Success
                RESULT_CANCELLED -> LiveOutcome.Cancelled
                else -> LiveOutcome.Failure
            },
            title = finishedJob?.title ?: currentCommandLabel,
            target = finishedJob?.target.orEmpty(),
            elapsedMs = elapsed, bytes = bytes,
            avgBps = if (elapsed > 0L && bytes > 0L) bytes * 1000.0 / elapsed else 0.0,
            detail = when {
                result == RESULT_ERROR -> _lastError.value?.friendly ?: "The command failed."
                result == RESULT_CANCELLED -> "Stopped before it finished."
                runDigests.isNotEmpty() -> "SHA-256 saved"
                else -> ""
            },
            hint = when {
                result == RESULT_ERROR -> _lastError.value?.hint.orEmpty()
                result == RESULT_CANCELLED && resumable != null -> "Resume it from the Dumps tab."
                else -> ""
            },
            hasDumps = bytes > 0L && DumpBridge.isEnabled(),
        )
        val waiting = autoResumeRel != null
        if (lid >= 0) {
            when {
                waiting -> LiveHub.end(
                    lid, liveRes, keepOpen = true,
                    note = "Device disconnected. Waiting for it to come back (resume ${(autoAttempts[autoResumeRel!!] ?: 0) + 1} of $MAX_AUTO_RESUME)."
                )
                onResult != null -> LiveHub.end(lid, liveRes, keepOpen = true, note = "Next step")
                else -> LiveHub.end(lid, liveRes)
            }
        }
        onResult?.invoke(result)
        // Nobody chained a new step: close the held notification with this run's own result.
        if (lid >= 0 && !waiting && onResult != null && !_running.value && !verifyBusy) LiveHub.end(lid, liveRes)
    }

    private fun liveKindFor(cmd: String, jk: JobKind): LiveKind {
        val first = cmd.trim().substringBefore(' ').lowercase()
        return when {
            speedTestActive -> LiveKind.Test
            cmd.contains("verify_tmp") -> LiveKind.Verify
            first == "rl" || first == "rf" -> LiveKind.Backup
            jk == JobKind.Dump -> LiveKind.Read
            jk == JobKind.Flash -> if (first in setOf("wl", "wf", "flash", "xml")) LiveKind.Flash else LiveKind.Write
            jk == JobKind.Erase -> LiveKind.Erase
            else -> LiveKind.Other
        }
    }

    private fun liveTitle(cmd: String, catalogTitle: String): String = when {
        speedTestActive -> "Cable and port speed test"
        fullBackupActive && cmd.trim().startsWith("rl") -> "Full backup"
        else -> catalogTitle
    }

    private fun liveEngineFor(m: EngineMode) = when (m) {
        EngineMode.MTK -> LiveEngine.Mtk
        EngineMode.QDL -> LiveEngine.Qdl
        EngineMode.BROKKR -> LiveEngine.Samsung
    }

    private fun liveDevice(): String =
        listOfNotNull(_target.value.cpu, _status.value.mode.label).joinToString(" \u00b7 ")

    /** After a QDL run: is the phone still in EDL (05c6:9008) or did it leave? */
    private fun logEdlState(result: String) {
        if (result == RESULT_BUSY) return
        val edl = usb.attachedDevices().firstOrNull { it.vendorId == 0x05C6 }
        if (edl != null && edl.productId == 0x9008) {
            log("Device is still in EDL (05c6:9008).\n")
        } else if (edl != null) {
            log("Device is on USB as %04x:%04x, not EDL (9008).\n".format(edl.vendorId, edl.productId))
        } else {
            log("Device left EDL. Put it back in EDL to continue.\n")
        }
    }

    /** Cooperative cancel: interrupts the Python worker and fails pending USB I/O. */
    fun cancelCommand() {
        if (!_running.value) {
            // A queued verify step runs without a command, but Stop must still end it.
            if (verifyBusy || queueActive) queueAbortSilently()
            return
        }
        // Stop pressed twice (console button and notification, or a nervous thumb):
        // the first one is already stopping the run; stacking more only adds races.
        if (userStopped) return
        userStopped = true
        autoResumeRel = null
        queueAbortSilently()
        log("\nCancelling command...\n")
        if (_mode.value == EngineMode.QDL) {
            qdlCancelled = true
            thread(name = "qdl-cancel") { qdlEngine.cancel() }
            return
        }
        val stopRunId = runSeq
        thread(name = "mtk-cancel") {
            runCatching { Python.getInstance().getModule("android_entry").callAttr("cancel", stopRunId) }
        }
    }

    /**
     * On a failed command, makes sure [_lastError] holds everything this run
     * printed, not just what Python's own error path captured. Python's
     * `_report_error` only sees its own stdout/stderr (mtkclient's
     * Preloader/DAXFlash logging); it never sees the USB transport
     * diagnostics UsbBridge.kt logs (short reads, endpoint stalls, async
     * transfer errors). Both funnel into the terminal via onLog(), so this
     * run's slice of the terminal is a superset of Python's detail and is
     * always safe to prefer.
     */
    private fun captureErrorDetail() {
        val full = terminal.text.value
        val start = commandLogStartLen.coerceIn(0, full.length)
        val runOutput = full.substring(start).trim()
        if (runOutput.isEmpty()) return
        val current = _lastError.value?.friendly
        val advice = RunDoctor.advise(runOutput, detachedDuringRun)
        // Keep a specific message the engine already gave; use the doctor's when it was generic.
        val friendly = if (current.isNullOrBlank() || current == "Command failed.") advice?.friendly ?: "Command failed."
        else current
        _lastError.value = ErrorInfo(friendly, runOutput, advice?.hint)
    }

    fun errorReport(): String? = _lastError.value?.let { "${it.friendly}\n\n${it.detail}" }

    /**
     * Result of the dump-folder picker opened because a command needed one.
     * [uri] null means the user dismissed the picker.
     */
    fun onDumpFolderPicked(uri: Uri?) {
        val cmd = pendingFolderCmd
        val cb = pendingFolderCallback
        pendingFolderCmd = null
        pendingFolderCallback = null

        if (uri == null) {
            if (cmd != null) {
                val msg = "No dump folder selected."
                log("$msg\n")
                _lastError.value = ErrorInfo(msg, "Command: $cmd")
                cb?.invoke(RESULT_ERROR)
            }
            return
        }
        val name = DumpBridge.takeTree(uri)
        if (name == null) {
            val msg = "Could not use that folder."
            log("$msg\n")
            if (cmd != null) {
                _lastError.value = ErrorInfo(msg, "Command: $cmd")
                cb?.invoke(RESULT_ERROR)
            }
            return
        }
        log("Dump folder: $name\n")
        notifyDumpsChanged()
        if (cmd != null) requestRun(cmd, cb)
    }

    /** Re-run the last command that actually started. */
    fun retryLastCommand() {
        val cmd = _lastCommand.value ?: return
        if (_running.value) {
            post("A command is already running.")
            return
        }
        requestRun(cmd)
    }

    /** Open the system dump-folder picker without a pending command. */
    fun pickDumpFolder() {
        pendingFolderCmd = null
        pendingFolderCallback = null
        _requestDumpFolder.tryEmit(Unit)
    }

    /**
     * One read-only pass on an unknown phone: MTK runs gettargetconfig then
     * printgpt in a single handshake, QDL runs chipinfo only. No payload, no
     * DA write, no firehose write. The result is saved as passport.txt in the
     * dump folder, and the parsed GPT feeds the partition table as usual.
     */
    fun identify() {
        if (_running.value) { post("A command is already running."); return }
        val isQdl = _mode.value == EngineMode.QDL
        val cmd = if (isQdl) "chipinfo" else "multi \"gettargetconfig; printgpt\""
        requestRun(cmd) { result ->
            if (result != RESULT_OK) return@requestRun
            // The Python engine can return ok after printing a failure, so only
            // write the passport when the output really carried the identity.
            val full = terminal.text.value
            val slice = full.substring(commandLogStartLen.coerceIn(0, full.length))
            val target = _target.value
            val gpt = _partitions.value
            val usbId = runUsbId
            val good = if (isQdl) RunSidecar.qdlIdentity(slice).isNotEmpty()
            else gpt.isNotEmpty() && (target.hwCode != null || target.targetConfig != null)
            if (!good) {
                log("Identify failed; passport not written.\n")
                return@requestRun
            }
            thread(name = "passport") {
                val ok = RunSidecar.writePassport(
                    engine = _mode.value.short, command = cmd, usbId = usbId,
                    target = target, gpt = gpt, qdlSlice = if (isQdl) slice else null
                )
                if (!ok) log("Could not save passport.\n")
                _dumpsEpoch.value = _dumpsEpoch.value + 1
            }
        }
    }

    // ---- resume an interrupted dump -----------------------------------------

    /** True when [rel] is a dump that was cut short and its size is known, so it can be continued. */
    fun canResume(rel: String): Boolean {
        val rec = PartialDumps.get(rel) ?: return false
        if (_mode.value != EngineMode.MTK || rec.total <= 0L) return false
        if (rec.group.isNotEmpty()) return true
        val have = DumpBridge.size(rel)
        return have in 0 until rec.total
    }

    private fun autoResume(rel: String) {
        val n = (autoAttempts[rel] ?: 0) + 1
        autoAttempts[rel] = n
        log("Auto-resume $n of $MAX_AUTO_RESUME: continuing $rel.\n")
        resumeDump(rel, auto = true)
    }

    /**
     * Continues a dump from where its file ends: reads only the missing bytes with the
     * byte-range read and appends them to the same file, then hashes the finished image.
     * Restarts a megabyte earlier than the file ends so a torn last write is never kept.
     * A file that belongs to a multi-file read continues the whole set ([resumeGroup]).
     */
    fun resumeDump(rel: String, auto: Boolean = false) {
        if (_running.value || verifyBusy) { post("A command is already running."); return }
        if (_mode.value != EngineMode.MTK) { post("Switch to MTK Client mode to resume this dump."); return }
        val rec = PartialDumps.get(rel)
        if (rec == null) { post("There is nothing to resume for $rel."); return }
        if (rec.group.isNotEmpty()) { resumeGroup(rec.group, auto); return }
        val have = DumpBridge.size(rel)
        if (have < 0L) { PartialDumps.remove(rel); post("$rel is no longer in the dump folder."); notifyDumpsChanged(); return }
        if (rec.total <= 0L) { post("The full size of $rel is not known, so it cannot be resumed. Run the read again."); return }
        val mib = 1024L * 1024L
        val start = (have / mib) * mib
        if (start >= rec.total) {
            PartialDumps.remove(rel)
            post("$rel already holds all its data. Use the hash button in Dumps to check it.")
            notifyDumpsChanged()
            return
        }
        if (!auto) autoAttempts.remove(rel)
        val absolute = rec.baseOffset + start
        val remaining = rec.total - start
        val cmd = "ro 0x${absolute.toString(16)} 0x${remaining.toString(16)} dumps/$rel"
        log("Resuming $rel from ${formatBytes(start)} (${formatBytes(remaining)} left).\n")
        DumpBridge.armResume(rel, start)
        resumeInFlight = true
        requestRun(cmd) { result ->
            resumeInFlight = false
            DumpBridge.clearResume()
            val ok = result == RESULT_OK && _lastError.value == null
            if (!ok) return@requestRun
            autoAttempts.remove(rel)
            PartialDumps.remove(rel)
            hashResumed(rec, showInSummary = true)
        }
    }

    /** Hashes a finished resumed file on a worker thread and writes its manifest. */
    private fun hashResumed(rec: PartialDumps.Record, showInSummary: Boolean) {
        thread(name = "resume-hash") {
            val rel = rec.rel
            val size = DumpBridge.size(rel)
            val scan = DumpIntegrity.scanRel(rel)
            val sha = scan?.sha256
            if (scan == null || sha == null) {
                log("Resumed $rel, but it could not be hashed.\n")
            } else {
                DumpIntegrity.writeManifest(
                    rel, sha, size,
                    manifestContext(rec.command, EngineMode.MTK).copy(
                        method = "post-pass (resumed)",
                        partition = _partitions.value.firstOrNull { it.offset == rec.baseOffset && it.length == rec.total },
                    ),
                    scan
                )
                log("Resumed $rel complete. SHA-256 $sha\n")
                val note = DumpIntegrity.blankNote(rel, size, scan.blocks, scan.zeroBlocks, scan.ffBlocks)
                note?.let { log("Warning: $it\n") }
                if (showInSummary) mainHandler.post {
                    _summary.update { it?.copy(hashes = listOf(rel to sha), warnings = listOfNotNull(note)) }
                }
            }
            _dumpsEpoch.value = _dumpsEpoch.value + 1
        }
    }

    /** Continues a multi-file read (`r a,b,c ...` or `rl <folder>`): finished files are skipped, the rest are read in order. */
    fun resumeGroup(group: String, auto: Boolean = false) {
        if (_running.value || verifyBusy) { post("A command is already running."); return }
        if (_mode.value != EngineMode.MTK) { post("Switch to MTK Client mode to resume this backup."); return }
        val recs = PartialDumps.inGroup(group).sortedBy { it.baseOffset }
        recs.filter { it.total > 0L && DumpBridge.size(it.rel) >= it.total }.forEach { PartialDumps.remove(it.rel) }
        val todo = recs.filter { it.total > 0L && DumpBridge.size(it.rel) < it.total }
        if (todo.isEmpty()) {
            post("Every file of this read is already complete.")
            notifyDumpsChanged()
            return
        }
        if (!auto) autoAttempts.remove(todo.first().rel)
        log("Resuming: ${todo.size} of ${recs.size} files still to read.\n")
        resumeNext(todo, 0, group)
    }

    private fun resumeNext(todo: List<PartialDumps.Record>, i: Int, group: String) {
        if (i >= todo.size) { finishGroupResume(group); return }
        val rec = todo[i]
        val have = DumpBridge.size(rec.rel)
        val mib = 1024L * 1024L
        val start = if (have > 0L) (have / mib) * mib else 0L
        if (start > 0L) DumpBridge.armResume(rec.rel, start)
        val cmd = "ro 0x${(rec.baseOffset + start).toString(16)} 0x${(rec.total - start).toString(16)} dumps/${rec.rel}"
        log("Resuming ${rec.rel}, file ${i + 1} of ${todo.size}, from ${formatBytes(start)}.\n")
        resumeInFlight = true
        requestRun(cmd) { result ->
            resumeInFlight = false
            DumpBridge.clearResume()
            if (result != RESULT_OK || _lastError.value != null) return@requestRun
            PartialDumps.remove(rec.rel)
            autoAttempts.remove(todo.first().rel)
            hashResumed(rec, showInSummary = false)
            resumeNext(todo, i + 1, group)
        }
    }

    private fun finishGroupResume(group: String) {
        val folder = DumpSpaceGuard.positionals(DumpSpaceGuard.tokenize(group)).getOrNull(0)
            ?.trim('"')?.removePrefix("dumps/")?.takeIf { group.trim().startsWith("rl") && it.isNotBlank() }
        log("All files of the interrupted read are complete.\n")
        if (folder == null) { notifyDumpsChanged(); return }
        val target = _target.value
        val usb = runUsbId
        val gpt = _partitions.value
        thread(name = "resume-manifest") {
            val digests = DumpBridge.listEntries()
                .filter { it.name.startsWith("$folder/") && !DumpIntegrity.isSidecar(it.name) }
                .mapNotNull { e -> DumpIntegrity.readManifest(e.name)?.let { DumpIntegrity.Streamed(e.name, it.sha256, it.bytes) } }
            if (digests.isNotEmpty()) writeFolderManifest(folder, digests, target, usb, gpt)
            _dumpsEpoch.value = _dumpsEpoch.value + 1
        }
    }

    // ---- full backup preset -------------------------------------------------

    /** Why the full backup cannot start right now, or null when it can. */
    fun fullBackupBlocker(): String? = when {
        _mode.value != EngineMode.MTK -> "Full backup is for MediaTek devices."
        !DumpBridge.isEnabled() -> "Choose a dump folder first (Dumps tab)."
        _running.value -> "A command is already running."
        else -> null
    }

    /**
     * One reviewed action for the backup the README recommends: read the partition table,
     * check the space, dump every partition into one new folder, then save a MANIFEST.json
     * and SHA256SUMS for the whole set.
     */
    /** Regions `rl` does not cover, read after the main backup. */
    data class FullBackupExtras(val preloader: Boolean = false, val footer: Boolean = false, val rpmb: Boolean = false) {
        val any: Boolean get() = preloader || footer || rpmb
    }

    fun startFullBackup(extras: FullBackupExtras = FullBackupExtras()) {
        fullBackupBlocker()?.let { post(it); return }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val folder = "full_backup_$stamp"
        log("Full backup: reading the partition table first.\n")
        execute("printgpt") { r ->
            val gpt = _partitions.value
            if (r != RESULT_OK || gpt.isEmpty()) {
                log("Full backup stopped: the partition table could not be read.\n")
                return@execute
            }
            val total = gpt.sumOf { it.length }
            val free = DumpBridge.availableBytes()
            if (free in 0 until total + DumpSpaceGuard.HEADROOM_BYTES) {
                val msg = "Not enough free space for a full backup: needs ${formatBytes(total)}, ${DumpBridge.displayName()} has ${formatBytes(free)} free."
                log("$msg\n")
                _lastError.value = ErrorInfo(msg, "Full backup of ${gpt.size} partitions")
                return@execute
            }
            val readCmd = "rl dumps/$folder"
            (Fat32Guard.check(readCmd, repo.state.value.protocols.sectorSize, gpt) as? Fat32Guard.Verdict.Blocked)?.let {
                log("${it.message}\n")
                _lastError.value = ErrorInfo(it.message, "Command: $readCmd")
                return@execute
            }
            log("Full backup: ${gpt.size} partitions, ${formatBytes(total)}, into $folder.\n")
            fullBackupActive = true
            execute(readCmd) { r2 ->
                fullBackupActive = false
                if (r2 != RESULT_OK || _lastError.value != null) {
                    log("Full backup did not finish. Files already written are in $folder. Resume it from the Dumps tab.\n")
                    return@execute
                }
                val acc = ArrayList<DumpIntegrity.Streamed>(runDigests)
                val target = _target.value
                val usb = runUsbId
                // Extra files get a prefix that is not a partition name, so a later `wl` of this folder skips them.
                val jobs = buildList {
                    if (extras.preloader) add(Triple("the Preloader", "dumppreloader --filename dumps/$folder/extra_preloader.bin", "$folder/extra_preloader.bin"))
                    if (extras.footer) add(Triple("the crypto footer", "footer dumps/$folder/extra_footer.bin", "$folder/extra_footer.bin"))
                    if (extras.rpmb) add(Triple("RPMB", "da rpmb r dumps/$folder/extra_rpmb.bin", "$folder/extra_rpmb.bin"))
                }
                fun finishBackup() {
                    thread(name = "backup-manifest") {
                        // An extra whose digest was not streamed is hashed from the file.
                        jobs.forEach { (_, _, rel) ->
                            if (DumpBridge.size(rel) > 0L && acc.none { it.rel == rel }) {
                                DumpIntegrity.scanRel(rel)?.let { acc += DumpIntegrity.Streamed(rel, it.sha256, DumpBridge.size(rel), it) }
                            }
                        }
                        writeFolderManifest(folder, acc, target, usb, gpt)
                        _dumpsEpoch.value = _dumpsEpoch.value + 1
                    }
                }
                fun runExtra(i: Int) {
                    if (i >= jobs.size) { finishBackup(); return }
                    val (what, cmd, rel) = jobs[i]
                    log("Full backup: reading $what.\n")
                    execute(cmd) { r3 ->
                        if (r3 == RESULT_OK && _lastError.value == null && DumpBridge.size(rel) > 0L) {
                            acc += runDigests.filter { it.rel == rel }
                        } else {
                            log("Full backup: $what could not be read, so it is not in the backup.\n")
                            _lastError.value = null
                            DumpBridge.deleteRel(rel)
                        }
                        runExtra(i + 1)
                    }
                }
                runExtra(0)
            }
        }
    }

    private fun writeFolderManifest(
        folder: String, digests: List<DumpIntegrity.Streamed>, target: TargetInfo, usb: String?, gpt: List<GptPartition>,
    ) {
        if (digests.isEmpty()) {
            log("Full backup finished. Hashing is off, so no checksums were saved.\n")
            return
        }
        val j = org.json.JSONObject().apply {
            put("schema", 1)
            put("app", "Bareflash ${appVersion()}")
            put("kind", "full-backup")
            put("date", System.currentTimeMillis())
            usb?.let { put("usb", it) }
            put("chip", org.json.JSONObject().apply {
                target.cpu?.let { put("cpu", it) }
                target.chipset?.let { put("chipset", it) }
                target.hwCode?.let { put("hwCode", it) }
                target.hwVer?.let { put("hwVer", it) }
                target.swVer?.let { put("swVer", it) }
                target.blVer?.let { put("blVer", it) }
            })
            put("partitions", org.json.JSONArray().apply {
                gpt.forEach { put(org.json.JSONObject().put("name", it.name).put("offset", it.offset).put("length", it.length)) }
            })
            put("files", org.json.JSONArray().apply {
                digests.forEach {
                    val file = it.rel.removePrefix("$folder/")
                    put(
                        org.json.JSONObject().put("file", file).put("bytes", it.bytes).put("sha256", it.sha256)
                            .also { o -> if (file.startsWith("extra_")) o.put("kind", "extra") }
                    )
                }
            })
        }
        val sums = digests.joinToString("") { "${it.sha256}  ${it.rel.removePrefix("$folder/")}\n" }
        val a = DumpBridge.writeText("$folder/MANIFEST.json", j.toString(2))
        val b = DumpBridge.writeText("$folder/SHA256SUMS", sums)
        log(if (a && b) "Full backup finished: ${digests.size} images, checksums saved in $folder.\n"
        else "Full backup finished, but the manifest could not be saved.\n")
    }

    // ---- job queue ------------------------------------------------------------

    /** One step of the queue: a command, or a re-hash of a backup against its saved checksums. */
    data class QueueItem(val id: Long, val label: String, val command: String? = null, val verifyRel: String? = null)

    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue

    private val _queueStatus = MutableStateFlow<String?>(null)
    /** "Step 2 of 4: printgpt" while the queue runs, else null. */
    val queueStatus: StateFlow<String?> = _queueStatus

    @Volatile private var queueActive = false
    private var queueIndex = 0
    private var queueTotal = 0
    private var queueSeq = 0L

    /** Adds a command. Returns a reason when it cannot be queued, else null. */
    fun queueAdd(command: String): String? {
        val c = command.trim()
        if (c.isEmpty()) return "Type a command first."
        if (!Catalogs.isKnown(_mode.value, c)) return "\"${c.substringBefore(' ')}\" is not a valid command."
        if (Catalogs.isDestructive(_mode.value, c)) {
            return "Writes and erases need a confirmation tap, so they cannot run unattended. Run them from Commands."
        }
        _queue.update { it + QueueItem(++queueSeq, c, command = c) }
        return null
    }

    /** Adds a re-hash of the newest full backup. */
    fun queueAddVerifyNewest() {
        thread(name = "queue-find-backup") {
            val newest = DumpBridge.listEntries()
                .filter { it.name.endsWith("/MANIFEST.json") }
                .maxByOrNull { it.modified }
                ?.name?.substringBeforeLast('/')
            mainHandler.post {
                if (newest == null) post("There is no full backup in the dump folder yet.")
                else _queue.update { it + QueueItem(++queueSeq, "Verify $newest", verifyRel = newest) }
            }
        }
    }

    fun queueRemove(id: Long) { if (!queueActive) _queue.update { l -> l.filterNot { it.id == id } } }
    fun queueClear() { if (!queueActive) _queue.value = emptyList() }

    fun queueStart() {
        if (queueActive) return
        if (_queue.value.isEmpty()) { post("The queue is empty."); return }
        if (_running.value || verifyBusy) { post("A command is already running."); return }
        queueActive = true
        queueTotal = _queue.value.size
        queueIndex = 0
        queueNext()
    }

    fun queueStop() {
        if (_running.value) cancelCommand() else queueAbortSilently()
    }

    private fun queueAbortSilently() {
        if (!queueActive) return
        queueActive = false
        LiveHub.setQueue(0, 0)
        _queueStatus.value = null
        log("Queue stopped.\n")
    }

    private fun queueFinish(ok: Boolean) {
        if (!queueActive) return
        queueActive = false
        LiveHub.setQueue(0, 0)
        _queueStatus.value = null
        log(if (ok) "Queue finished: $queueTotal steps.\n"
        else "Queue stopped at step $queueIndex of $queueTotal. The steps after it were not run.\n")
    }

    private fun queueNext() {
        if (!queueActive) return
        val item = _queue.value.firstOrNull()
        if (item == null) { queueFinish(true); return }
        _queue.update { it.drop(1) }
        queueIndex++
        LiveHub.setQueue(queueIndex, queueTotal)
        _queueStatus.value = "Step $queueIndex of $queueTotal: ${item.label}"
        log("Queue step $queueIndex of $queueTotal: ${item.label}\n")
        if (item.verifyRel != null) { runVerifyStep(item.verifyRel); return }
        requestRun(item.command.orEmpty()) { r ->
            if (!queueActive) return@requestRun
            if (r == RESULT_OK && _lastError.value == null) queueNext() else queueFinish(false)
        }
    }

    /** Re-hashes a backup folder or image against its manifest, off the command loop. */
    private fun runVerifyStep(rel: String) {
        verifyBusy = true
        val name = rel.substringAfterLast('/')
        val id = LiveHub.begin(LiveKind.Verify, LiveEngine.App, "Verify backup", name, "")
        thread(name = "queue-verify") {
            val t0 = SystemClock.elapsedRealtime()
            val plan = Restore.planFolder(rel) ?: Restore.planImage(rel)
            val grand = plan.items.sumOf { it.bytes.coerceAtLeast(0L) }
            var base = 0L
            val v = Restore.verify(
                plan,
                { file, done, tot ->
                    val d = base + done
                    val el = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
                    val sp = d * 1000.0 / el
                    LiveHub.progress(id, d, grand, sp, if (sp > 1.0 && grand > d) ((grand - d) / sp * 1000.0).toLong() else -1L, file)
                    if (done >= tot) base += tot
                },
                { !queueActive }
            )
            val elapsed = SystemClock.elapsedRealtime() - t0
            mainHandler.post {
                verifyBusy = false
                // Stop ends the queue, which is what the verify loop watches. That is not a bad backup.
                val stopped = !v.ok && !queueActive && v.problems.lastOrNull() == "Cancelled."
                if (v.ok) log("Verified ${v.checked} files against their saved SHA-256.\n")
                else if (stopped) log("Verify stopped.\n")
                else {
                    log("Verify failed:\n${v.problems.joinToString("\n")}\n")
                    _lastError.value = ErrorInfo("The backup does not match its saved checksums.", v.problems.joinToString("\n"))
                }
                val more = queueActive && v.ok && _queue.value.isNotEmpty()
                LiveHub.end(
                    id,
                    LiveResult(
                        LiveKind.Verify, LiveEngine.App,
                        if (v.ok) LiveOutcome.Success else if (stopped) LiveOutcome.Cancelled else LiveOutcome.Failure,
                        "Verify backup", name, elapsed, grand, if (elapsed > 0L) grand * 1000.0 / elapsed else 0.0,
                        detail = if (v.ok) "${v.checked} files match their SHA-256" else if (stopped) "Stopped before it finished." else v.problems.firstOrNull().orEmpty(),
                        hint = if (v.ok) "" else if (stopped) "Nothing was changed. Run the verify again when you are ready." else "Do not restore from this backup. Make a new one.",
                    ),
                    keepOpen = more, note = "Next step"
                )
                if (queueActive) { if (v.ok) queueNext() else queueFinish(false) }
            }
        }
    }

    // ---- cable and port speed test ------------------------------------------------

    data class SpeedTestResult(
        val ok: Boolean,
        val mibPerSec: Double,
        val bytes: Long,
        val elapsedMs: Long,
        val link: String?,
        val verdict: String,
    )

    private val _speedTest = MutableStateFlow<SpeedTestResult?>(null)
    val speedTest: StateFlow<SpeedTestResult?> = _speedTest
    fun clearSpeedTest() { _speedTest.value = null }

    fun speedTestBlocker(): String? = when {
        _mode.value != EngineMode.MTK -> "The speed test is for MediaTek devices."
        !DumpBridge.isEnabled() -> "Choose a dump folder first (Dumps tab)."
        _running.value || verifyBusy -> "A command is already running."
        _device.value == null -> "No device is connected. Run Detect first."
        else -> null
    }

    /** Reads 64 MiB from the start of flash into a temporary file, reports MiB/s, deletes the file. */
    fun startSpeedTest() {
        speedTestBlocker()?.let { post(it); return }
        val rel = "verify_tmp/speedtest.bin"
        _speedTest.value = null
        speedTestActive = true
        requestRun("ro 0x0 0x4000000 dumps/$rel") { r ->
            speedTestActive = false
            val got = DumpBridge.size(rel)
            val mean = if (runSpeedN > 0) runSpeedSum / runSpeedN else 0.0
            val elapsed = SystemClock.elapsedRealtime() - commandStartedAt
            thread(name = "speedtest-clean") { DumpBridge.deleteRel("verify_tmp") }
            _summary.value = null
            val link = UsbBridge.lastLinkName
            val mib = mean / (1024.0 * 1024.0)
            val ok = r == RESULT_OK && _lastError.value == null && mean > 0.0
            val verdict = when {
                !ok -> "The test did not finish, so no speed was measured. Check the cable and try again."
                UsbBridge.lastLinkSlow -> "The link negotiated USB 1.1 speed. Try another OTG adapter or cable, and no hub."
                mib < 4.0 -> "Very slow for this link. Try another cable, plug straight into the phone and avoid hubs."
                mib < 10.0 -> "Slower than usual. A better cable or a direct port usually helps. A slow SD card or USB stick as the dump folder can also limit it."
                else -> "Good. The cable and the port are not what limits the speed."
            }
            _speedTest.value = SpeedTestResult(ok, mib, got.coerceAtLeast(0L), elapsed, link, verdict)
            log(if (ok) "Speed test: ${"%.1f".format(Locale.US, mib)} MiB/s${link?.let { " on $it" } ?: ""}.\n" else "Speed test: not completed.\n")
        }
    }

    // ---- verify a dump against the device ----------------------------------------------

    /** Destination of the compare-only read. Python recognises it and never stores a byte. */
    private val deviceVerifyRel = "verify_tmp/devverify.bin"
    @Volatile private var deviceVerifyActive = false

    private fun parseNumber(s: String): Long? {
        val t = s.trim().trim('"')
        return if (t.startsWith("0x", ignoreCase = true)) t.substring(2).toLongOrNull(16) else t.toLongOrNull()
    }

    /** Where on the flash an image was read from, or null when its manifest does not say. */
    private fun verifyBase(m: DumpIntegrity.Manifest): Long? {
        if (m.partitionOffset >= 0L) return m.partitionOffset
        val t = DumpSpaceGuard.tokenize(m.command)
        return when (t.firstOrNull()) {
            "rf" -> 0L
            "ro" -> DumpSpaceGuard.positionals(t).getOrNull(0)?.let { parseNumber(it) }
            else -> null
        }
    }

    /** Why [rel] cannot be compared with the device right now, or null when it can. */
    fun deviceVerifyBlocker(rel: String): String? {
        val m = DumpIntegrity.readManifest(rel)
        return when {
            _mode.value != EngineMode.MTK -> "Checking against the device works in MTK Client mode."
            _running.value || verifyBusy -> "A command is already running."
            _device.value == null -> "No device is connected. Run Identify first."
            m == null -> "This image has no saved checksums, so there is nothing to compare the device with."
            DumpBridge.size(rel) != m.bytes -> "${rel.substringAfterLast('/')} changed since it was hashed, so it cannot be compared."
            verifyBase(m) == null -> "This image's manifest does not say where on the flash it came from."
            else -> (Restore.chipCheck(Restore.planImage(rel), _target.value) as? Restore.ChipCheck.Different)?.message
        }
    }

    /**
     * Reads the region this image came from and compares it, region by region, with the saved
     * hashes. The data is hashed as it arrives and discarded: nothing is written, so it works on
     * a phone with almost no free space and catches flaky cables or flash that a file hash cannot.
     */
    fun verifyOnDevice(rel: String) {
        deviceVerifyBlocker(rel)?.let { post(it); return }
        val m = DumpIntegrity.readManifest(rel) ?: return
        val base = verifyBase(m) ?: return
        val spec = org.json.JSONObject().apply {
            put("sha256", m.sha256)
            put("bytes", m.bytes)
            put("chunk", m.chunkSize)
            put("chunks", org.json.JSONArray(m.chunks))
        }
        val armed = runCatching {
            Python.getInstance().getModule("saf_fs").callAttr("arm_verify", deviceVerifyRel, spec.toString()).toBoolean()
        }.getOrDefault(false)
        if (!armed) { post("The check could not be set up."); return }
        _switchToConsole.tryEmit(Unit)
        deviceVerifyActive = true
        requestRun("ro 0x${base.toString(16)} 0x${m.bytes.toString(16)} dumps/$deviceVerifyRel") { r ->
            deviceVerifyActive = false
            finishDeviceVerify(rel, m, r)
        }
    }

    private fun finishDeviceVerify(rel: String, m: DumpIntegrity.Manifest, ran: String) {
        val raw = runCatching {
            val mod = Python.getInstance().getModule("saf_fs")
            val out = mod.callAttr("take_verify", deviceVerifyRel).toString()
            mod.callAttr("disarm_verify", deviceVerifyRel)
            out
        }.getOrDefault("")
        // Nothing should have been written, but never leave a stray file behind.
        thread(name = "devverify-clean") { DumpBridge.deleteRel("verify_tmp") }
        val leaf = rel.substringAfterLast('/')
        fun say(msg: String, ok: Boolean?) {
            log("$msg\n")
            setVerify(msg, ok)
        }
        val finished = ran == RESULT_OK && _lastError.value == null
        if (!finished || raw.isBlank()) {
            say("The read did not finish, so $leaf was not checked against the device.", null)
            return
        }
        val o = runCatching { org.json.JSONObject(raw) }.getOrNull()
        if (o == null || !o.optBoolean("valid", false)) {
            say("Could not check $leaf: the data did not arrive in order.", null)
            return
        }
        val got = o.optLong("bytes")
        val want = o.optLong("wantBytes")
        val badArr = o.optJSONArray("badChunks")
        val bad: List<Int> = if (badArr != null) List(badArr.length()) { badArr.getInt(it) } else emptyList()
        val chunk = o.optLong("chunk", m.chunkSize)
        when {
            o.optBoolean("match", false) ->
                say("Verified: the device still reads back exactly like $leaf (SHA-256 ${m.sha256.take(12)}\u2026).", true)
            got != want ->
                say("MISMATCH: the device returned ${formatBytes(got)}, $leaf is ${formatBytes(want)}.", false)
            m.chunks.isNotEmpty() && bad.isNotEmpty() -> {
                val where = DumpIntegrity.describeRegions(bad, chunk, want)
                say(
                    "MISMATCH: ${bad.size} of ${m.chunks.size} regions differ ($where); the rest matches. " +
                        "Run the check again: regions that differ every time point to bad flash or a bad cable, " +
                        "regions that change between runs to an unreliable read. " +
                        "If the phone has booted since the dump, changed data is expected.",
                    false
                )
            }
            else ->
                say("MISMATCH: the device does not read back like $leaf. This image has no region hashes, so the difference cannot be located.", false)
        }
    }

    // ---- restore ---------------------------------------------------------------------

    /**
     * Writes a verified backup back through the normal write path (review, back-up-first and read-back
     * all apply). After a folder restore, partitions up to 256 MiB are read back and compared.
     */
    fun runRestore(plan: Restore.Plan, partition: String?, readBack: Boolean) {
        val cmd = Restore.command(plan, partition)
        if (cmd == null) { post("Choose the partition this image goes to."); return }
        requestRun(cmd) { r ->
            if (r == RESULT_OK && _lastError.value == null && plan.isFolder && readBack) verifyFolderRestore(plan)
        }
    }

    private fun verifyFolderRestore(plan: Restore.Plan) {
        val eligible = plan.items.filter { it.partition != null }
        val targets = eligible.filter { it.bytes in 1..SafetyNet.VERIFY_DEFAULT_MAX }
        val skipped = eligible.size - targets.size
        if (targets.isEmpty()) {
            setVerify("Restore finished. Nothing was read back: no partition is small enough to check automatically.", ok = null)
            return
        }
        var bad = 0
        var unchecked = 0
        val badNames = ArrayList<String>()
        fun step(i: Int) {
            if (i >= targets.size) {
                thread(name = "restore-clean") { DumpBridge.deleteRel("verify_tmp") }
                val tail = if (skipped > 0) " $skipped larger partitions were not read back." else ""
                if (bad > 0) {
                    val msg = "MISMATCH after restore: ${badNames.joinToString(", ")} read back different. Write the backup again before rebooting.$tail"
                    log("$msg\n")
                    _lastError.value = ErrorInfo("A restored partition read back different from the backup.", msg)
                    setVerify(msg, ok = false)
                    WriteMarker.begin(_mode.value.short, "restore of ${plan.label}", "")
                    WriteMarker.end("error")
                    _writeMarker.value = WriteMarker.current()
                } else {
                    val n = targets.size - unchecked
                    val msg = "Verified: $n partitions read back with the same SHA-256 as the backup.$tail"
                    log("$msg\n")
                    setVerify(msg, ok = if (n > 0) true else null)
                }
                return
            }
            val item = targets[i]
            val part = item.partition!!
            val tmp = "verify_tmp/$part.img"
            log("Reading $part back (${i + 1} of ${targets.size})...\n")
            execute("r $part dumps/$tmp") { r ->
                if (r != RESULT_OK) { unchecked++; step(i + 1); return@execute }
                verifyBusy = true
                thread(name = "restore-hash") {
                    val got = DumpIntegrity.sha256Rel(tmp, item.bytes)
                    val want = item.sha256 ?: DumpIntegrity.sha256Rel(item.rel, item.bytes)
                    mainHandler.post {
                        verifyBusy = false
                        when {
                            got == null || want == null -> unchecked++
                            !got.equals(want, ignoreCase = true) -> { bad++; badNames += part }
                        }
                        step(i + 1)
                    }
                }
            }
        }
        step(0)
    }

    // ---- presets with remembered files ---------------------------------------------

    /** Runs a saved preset, re-attaching the files it remembered. Names the file that is missing, if any. */
    fun runPreset(p: CommandPresets.Preset) {
        val restored = ArrayList<String>()
        val missing = ArrayList<String>()
        for ((name, uri) in p.attachments) {
            if (DumpBridge.restoreInput(name, Uri.parse(uri))) restored += name else missing += name
        }
        if (missing.isNotEmpty()) {
            restored.forEach { DumpBridge.forgetInput(it) }
            val msg = "The preset \"${p.name}\" needs ${missing.joinToString(", ")}, which can no longer be opened. " +
                "Attach it again and save the preset again."
            _switchToConsole.tryEmit(Unit)
            log("$msg\n")
            _lastError.value = ErrorInfo(msg, "Preset command: ${p.command}")
            post(msg)
            return
        }
        requestRun(p.command) { restored.forEach { DumpBridge.forgetInput(it) } }
    }

    // ---- Qualcomm: read-back and programmer match --------------------------------------

    private fun verifyWriteQdl(p: PendingCommand, cb: ((String) -> Unit)?) {
        val pair = SafetyNet.singleWriteQdl(p.finalCommand)
        if (pair == null) { cb?.invoke(RESULT_OK); return }
        val (part, file) = pair
        val imageBytes = p.check?.imageBytes ?: -1L
        val outRel = "dumps/verify_tmp/$part.img"
        val base = dumpsDir().parentFile!!
        log("Reading $part back to compare it with the image...\n")
        execute("read $part $outRel") { r ->
            val tmp = File(base, outRel)
            if (r != RESULT_OK) {
                tmp.parentFile?.deleteRecursively()
                setVerify("The write finished, but reading $part back failed, so it was not verified.", ok = null)
                cb?.invoke(RESULT_OK)
                return@execute
            }
            verifyBusy = true
            thread(name = "verify-hash-qdl") {
                val limit = if (imageBytes > 0L) imageBytes else Long.MAX_VALUE
                val src: File? = when {
                    file.startsWith("/") -> File(file)
                    file.startsWith("dumps/") -> DumpBridge.realInput(file.removePrefix("dumps/")) ?: File(base, file)
                    else -> DumpBridge.realInput(file)
                }
                val want = runCatching { src?.inputStream()?.use { DumpIntegrity.sha256(it, limit) } }.getOrNull()
                val got = runCatching { tmp.inputStream().use { DumpIntegrity.sha256(it, limit) } }.getOrNull()
                tmp.parentFile?.deleteRecursively()
                mainHandler.post {
                    verifyBusy = false
                    when {
                        want == null || got == null -> {
                            log("Could not hash the image or the read-back, so $part was not verified.\n")
                            setVerify("Not verified: could not hash the image or the read-back.", ok = null)
                            cb?.invoke(RESULT_OK)
                        }
                        want == got -> {
                            log("Verified: $part read back with the same SHA-256 as the image ($want).\n")
                            setVerify("Verified: read-back SHA-256 matches the image.", ok = true)
                            cb?.invoke(RESULT_OK)
                        }
                        else -> {
                            val msg = "MISMATCH: $part does not match the image. Image $want, device $got. Write it again before rebooting."
                            log("$msg\n")
                            _lastError.value = ErrorInfo("The partition read back different from the image.", msg)
                            setVerify(msg, ok = false)
                            WriteMarker.begin(_mode.value.short, part, p.command)
                            WriteMarker.end("error")
                            _writeMarker.value = WriteMarker.current()
                            cb?.invoke(RESULT_ERROR)
                        }
                    }
                }
            }
        }
    }

    /** After `chipinfo`: says whether the selected programmer matches the chip's hardware ID. */
    private fun reportProgrammerMatch() {
        if (!currentCommandLabel.trim().startsWith("chipinfo")) return
        val full = terminal.text.value
        val slice = full.substring(commandLogStartLen.coerceIn(0, full.length))
        val hw = QdlProgrammer.hwIdFromChipinfo(slice) ?: return
        val st = qdlSession.state.value
        log(QdlProgrammer.advice(hw, st.programmer, QdlProgrammer.candidates(st.includeDir, hw)) + "\n")
    }

    fun notifyDumpsChanged() { _dumpsEpoch.value = _dumpsEpoch.value + 1 }

    // ---- reconnect -----------------------------------------------------

    private fun stopReconnect() {
        reconnectGen++
        reconnectPermissionFor = null
    }

    /**
     * After a command, the target usually drops off USB and comes back as a
     * new device. If it's still there we mark Connected immediately; otherwise
     * we poll until it reappears (or time out and show the replug hint).
     */
    private fun beginReconnect() {
        if (_running.value) return
        UsbBridge.close(false)
        if (tryClaimReconnect()) return
        val gen = ++reconnectGen
        reconnectPermissionFor = null
        _status.value = ConsoleStatus(LinkState.Reconnecting, mode = _status.value.mode)
        thread(name = "mtk-reconnect") {
            val deadline = SystemClock.elapsedRealtime() + RECONNECT_WAIT_MS
            while (SystemClock.elapsedRealtime() < deadline && gen == reconnectGen && !_running.value) {
                if (tryClaimReconnect()) return@thread
                val pending = usb.mtkDevices().firstOrNull { !usb.hasPermission(it) }
                if (pending != null && reconnectPermissionFor != pending.deviceName) {
                    reconnectPermissionFor = pending.deviceName
                    usb.logAndRequest(listOf(pending))
                }
                try { Thread.sleep(250) } catch (_: InterruptedException) { break }
            }
            mainHandler.post {
                if (gen != reconnectGen || _running.value) return@post
                if (tryClaimReconnect()) return@post
                log("$REPLUG_HINT\n")
                // No toast: island shows WAIT/IDLE and the terminal already has the line.
                refreshIdle()
            }
        }
    }

    /** @return true if an MTK device with permission is attached. */
    private fun tryClaimReconnect(): Boolean {
        if (_running.value) return false
        val ready = usb.mtkDevices().firstOrNull { usb.hasPermission(it) } ?: return false
        val apply = Runnable {
            if (_running.value) return@Runnable
            reconnectGen++
            reconnectPermissionFor = null
            val already = _status.value.state == LinkState.Connected && _device.value != null
            _device.update {
                it ?: DeviceInfo(
                    ready.vendorId, ready.productId,
                    hwCode = _target.value.hwCode, targetConfig = _target.value.targetConfig
                )
            }
            _status.update { ConsoleStatus(LinkState.Connected, mode = it.mode) }
            if (!already) {
                val msg = if (detachedDuringRun) {
                    "Device reconnected. You can run another command."
                } else {
                    "Device still on USB. You can run another command."
                }
                log("$msg\n")
                // USB reconnect is visible on the island; skip the popup.
            }
            val ar = autoResumeRel
            if (ar != null && Extras.autoResume.value) {
                autoResumeRel = null
                mainHandler.postDelayed({ if (!_running.value && !verifyBusy) autoResume(ar) }, 800L)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply.run() else mainHandler.post(apply)
        return true
    }

    // ---- dump / flash --------------------------------------------------

    fun isValidPartitionName(name: String) = partitionNamePattern.matches(name)

    /** Local dumps directory: only the fallback destination when no dump folder is chosen. */
    private fun dumpsDir() = DumpBridge.localDumps()

    /**
     * Reads a partition to `dumps/<name>.img`. If a dump folder is set in
     * Settings, Python streams that file straight there — nothing is copied
     * through app-private storage.
     */
    fun dumpPartition(partition: String) {
        requestRun("r $partition dumps/$partition.img")
    }

    /** Dump several partitions in one multi command. */
    fun dumpPartitions(names: List<String>) {
        val list = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (list.isEmpty()) return
        if (list.size == 1) {
            dumpPartition(list[0])
            return
        }
        val body = list.joinToString("; ") { "r $it dumps/$it.img" }
        requestRun("multi \"$body\"")
    }

    /**
     * Flashes the picked file to [partition]. The file is read in place: the
     * command gets a name that Python's file layer resolves to the file's own
     * URI (or to its path inside the chosen dump folder), exactly like the
     * attach flow in the command review. Nothing is copied into app storage.
     */
    fun flashPartition(partition: String, source: Uri?) {
        if (source == null) { log("Flash cancelled.\n"); return }
        val attached = DumpBridge.attachInput(source)
        if (attached == null) {
            AppLog.log(LogLevel.Error, engineSource(), "flash-input", "Could not open the selected file: $source")
            log("The selected file could not be opened.\n")
            return
        }
        // Picked file names can contain spaces; the command line is split like a shell.
        val path = "dumps/${attached.value}"
        val arg = if (path.any { it.isWhitespace() || it == '\'' || it == '"' }) {
            "'" + path.replace("'", "'\"'\"'") + "'"
        } else path
        requestRun("w $partition $arg") {
            // A file picked from outside the dump folder is remembered only for this command.
            if (!attached.inChosenFolder) DumpBridge.forgetInput(attached.value)
        }
    }

}
