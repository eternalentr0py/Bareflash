package com.bareflash.app.log

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * The app's one logging system.
 *
 * Everything funnels into [log] (or a helper below): Kotlin code, the
 * terminal streams of the MediaTek / Qualcomm engines, Samsung's native
 * flasher, Python tracebacks, the process's own system log (see
 * [LogCapture]), main-thread stalls and the way the previous process died.
 * Entries are written as JSON lines to `files/logs/`, one file set per app
 * launch, and a bounded in-memory ring feeds the live view.
 *
 * Calling [log] is cheap and never blocks: it enqueues. A single low-priority
 * writer thread does the I/O, so logging cannot slow the USB transfer path.
 * Uncaught exceptions are the one exception to "async": they are written
 * synchronously before the process dies.
 */
object AppLog {

    private const val TAG = "BF.AppLog"
    private const val PART_MAX_BYTES = 6L * 1024 * 1024
    private const val RING_MAX = 15_000
    private const val QUEUE_MAX = 30_000
    private const val MAX_MESSAGE = 16_000
    private const val MAX_STACK = 96_000
    private const val PREFS = "applog_prefs"

    // ---- configuration ---------------------------------------------------

    data class Config(
        val minLevel: LogLevel = LogLevel.Debug,
        val captureSystem: Boolean = true,
        val watchdog: Boolean = true,
        val maxTotalMb: Int = 50
    )

    private val _config = MutableStateFlow(Config())
    val config: StateFlow<Config> = _config.asStateFlow()

    @Volatile private var minLevel = LogLevel.Debug

    /** The engine whose command is running; Python errors are attributed to it. */
    @Volatile var activeEngine: LogSource = LogSource.MediaTek

    // ---- state -----------------------------------------------------------

    private lateinit var appCtx: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var dir: File
    @Volatile private var ready = false

    lateinit var sessionId: String
        private set

    private val seq = AtomicLong(0)
    private val queue = LinkedBlockingQueue<LogEntry>(QUEUE_MAX)
    private val dropped = AtomicLong(0)

    private val ring = ArrayDeque<LogEntry>()
    private val ringLock = Any()

    private val fileLock = ReentrantLock()
    private var writer: BufferedWriter? = null
    private var partIndex = 1
    private var partBytes = 0L

    private val _revision = MutableStateFlow(0L)
    /** Bumps whenever new entries have been persisted; the UI watches it. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val _crashNotice = MutableStateFlow<String?>(null)
    /** Set when the previous run is known to have died badly. */
    val crashNotice: StateFlow<String?> = _crashNotice.asStateFlow()

    private val timeFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    // ---- lifecycle -------------------------------------------------------

    fun init(app: Application) {
        synchronized(this) {
            if (ready) return
            appCtx = app.applicationContext
            prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            loadConfig()
            dir = File(appCtx.filesDir, "logs").apply { mkdirs() }
            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
            sessionId = "$stamp-${android.os.Process.myPid()}"
            prune()
            openPart()
            ready = true
        }
        installCrashHandler()
        thread(name = "applog-writer", isDaemon = true) { writerLoop() }
        writeSessionHeader()
        registerLifecycle(app)
        thread(name = "applog-previous-run", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runCatching { LogCapture.ingestPreviousRun(appCtx, prefs) }
                .onFailure { w("applog", "Could not read the previous run's exit record", it) }
        }
        applyConfig()
    }

    private fun loadConfig() {
        val level = LogLevel.fromLetter(prefs.getString("min_level", "D")?.firstOrNull() ?: 'D')
        val c = Config(
            minLevel = level,
            captureSystem = prefs.getBoolean("capture_system", true),
            watchdog = prefs.getBoolean("watchdog", true),
            maxTotalMb = prefs.getInt("max_total_mb", 50)
        )
        _config.value = c
        minLevel = c.minLevel
    }

    fun updateConfig(block: (Config) -> Config) {
        val c = block(_config.value)
        _config.value = c
        minLevel = c.minLevel
        prefs.edit()
            .putString("min_level", c.minLevel.letter.toString())
            .putBoolean("capture_system", c.captureSystem)
            .putBoolean("watchdog", c.watchdog)
            .putInt("max_total_mb", c.maxTotalMb)
            .apply()
        applyConfig()
        thread(name = "applog-prune", isDaemon = true) { prune() }
    }

    private fun applyConfig() {
        val c = _config.value
        if (c.captureSystem) LogCapture.startSystemCapture() else LogCapture.stopSystemCapture()
        if (c.watchdog) LogCapture.startWatchdog() else LogCapture.stopWatchdog()
    }

    // ---- public logging API ---------------------------------------------

    fun v(tag: String, msg: String) = log(LogLevel.Verbose, LogSource.App, tag, msg)
    fun d(tag: String, msg: String) = log(LogLevel.Debug, LogSource.App, tag, msg)
    fun i(tag: String, msg: String) = log(LogLevel.Info, LogSource.App, tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) = log(LogLevel.Warn, LogSource.App, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(LogLevel.Error, LogSource.App, tag, msg, t)

    fun log(
        level: LogLevel,
        source: LogSource,
        tag: String,
        message: String,
        throwable: Throwable? = null,
        mirrorToLogcat: Boolean = true
    ) {
        if (level.ordinal < minLevel.ordinal) return
        val stack = throwable?.let { cap(it.stackTraceToString(), MAX_STACK) }
        enqueue(make(level, source, tag, message, stack))
        if (mirrorToLogcat) {
            runCatching {
                val text = if (stack != null) "$message\n$stack" else message
                Log.println(level.androidPriority, "BF.$tag", text.take(3500))
            }
        }
    }

    /** Log with a pre-formatted stack/detail block (e.g. a Python traceback). */
    fun logDetail(level: LogLevel, source: LogSource, tag: String, message: String, detail: String?) {
        if (level.ordinal < minLevel.ordinal) return
        enqueue(make(level, source, tag, message, detail?.let { cap(it, MAX_STACK) }))
    }

    /** Entry point for Python (Chaquopy): level is one of V/D/I/W/E/F. */
    @JvmStatic
    fun fromPython(level: String, tag: String, message: String, stack: String?) {
        logDetail(
            LogLevel.fromLetter(level.firstOrNull() ?: 'I'),
            activeEngine,
            tag,
            message,
            stack?.takeIf { it.isNotBlank() }
        )
    }

    /** Samsung's native flasher: 0 trace, 1 debug, 2 info, 3 warn, 4 error, 5 critical. */
    fun samsung(nativeLevel: Int, text: String) {
        val level = when (nativeLevel) {
            0 -> LogLevel.Verbose
            1 -> LogLevel.Debug
            2 -> LogLevel.Info
            3 -> LogLevel.Warn
            4 -> LogLevel.Error
            else -> LogLevel.Fatal
        }
        if (level.ordinal < minLevel.ordinal) return
        for (line in text.split('\n')) {
            val l = line.trimEnd()
            if (l.isNotBlank()) enqueue(make(level, LogSource.Samsung, "brokkr", l, null))
        }
    }

    // -- terminal stream ---------------------------------------------------

    private val ansi = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    private val errorRe = Regex(
        "(?i)(\\berror\\b|\\bfail(ed|ure|s)?\\b|exception|traceback|\\bfatal\\b|denied|invalid|\\bunable\\b|could not|couldn't|cannot|can't|\\[LIB\\]:)"
    )
    private val warnRe = Regex("(?i)(\\bwarn(ing)?\\b|timeout|timed out|\\bretry|deprecated|no device on the bus|not found)")

    private val termLock = Any()
    private var lastLine = ""
    private var lastSource: LogSource? = null
    private var lastCount = 0

    /**
     * Everything the Console terminal receives, unfiltered by the display's
     * noise filter. Identical consecutive lines (the reconnect poll prints
     * "Waiting for device..." several times a second) collapse into one entry
     * plus a repeat count.
     */
    fun terminal(source: LogSource, text: String) {
        if (text.isEmpty()) return
        val clean = ansi.replace(text, "").replace("\r\n", "\n")
        if (clean.contains("Traceback (most recent call last)")) {
            synchronized(termLock) { flushRepeatLocked() }
            val trimmed = clean.trim()
            val last = trimmed.lines().lastOrNull { it.isNotBlank() } ?: "Python traceback"
            enqueue(make(LogLevel.Error, source, "terminal", last, cap(trimmed, MAX_STACK)))
            return
        }
        for (raw in clean.split('\n')) {
            val line = raw.substringAfterLast('\r').trimEnd()
            if (line.isBlank()) continue
            synchronized(termLock) {
                if (source == lastSource && line == lastLine) {
                    lastCount++
                } else {
                    flushRepeatLocked()
                    lastLine = line
                    lastSource = source
                    lastCount = 1
                    val level = when {
                        errorRe.containsMatchIn(line) -> LogLevel.Error
                        warnRe.containsMatchIn(line) -> LogLevel.Warn
                        else -> LogLevel.Info
                    }
                    if (level.ordinal >= minLevel.ordinal) {
                        enqueue(make(level, source, "terminal", line, null))
                    }
                }
                Unit
            }
        }
    }

    private fun flushRepeatLocked() {
        if (lastCount > 1) {
            enqueue(make(LogLevel.Info, lastSource ?: LogSource.App, "terminal", "(previous line repeated ${lastCount - 1} more times)", null))
        }
        lastCount = 0
        lastLine = ""
        lastSource = null
    }

    // ---- internals -------------------------------------------------------

    internal fun make(
        level: LogLevel,
        source: LogSource,
        tag: String,
        message: String,
        stack: String?,
        thread: String = Thread.currentThread().name,
        timeMs: Long = System.currentTimeMillis()
    ) = LogEntry(seq.incrementAndGet(), timeMs, level, source, tag, cap(message, MAX_MESSAGE), stack, thread)

    internal fun enqueue(e: LogEntry) {
        if (!queue.offer(e)) dropped.incrementAndGet()
    }

    private fun cap(s: String, max: Int) =
        if (s.length <= max) s else s.substring(0, max) + "\n… (truncated, ${s.length - max} more characters)"

    private fun writerLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        val batch = ArrayList<LogEntry>(512)
        while (true) {
            try {
                val first = queue.poll(750, TimeUnit.MILLISECONDS)
                if (first != null) {
                    batch.add(first)
                    queue.drainTo(batch, 511)
                }
                val lost = dropped.getAndSet(0)
                if (lost > 0) batch.add(make(LogLevel.Warn, LogSource.App, "applog", "Dropped $lost log entries (queue full)", null))
                if (batch.isNotEmpty()) {
                    persist(batch)
                    batch.clear()
                }
            } catch (ie: InterruptedException) {
                return
            } catch (t: Throwable) {
                batch.clear()
                Log.e(TAG, "log writer failed", t)
            }
        }
    }

    private fun persist(batch: List<LogEntry>) {
        synchronized(ringLock) {
            for (e in batch) {
                ring.addLast(e)
                if (ring.size > RING_MAX) ring.removeFirst()
            }
        }
        fileLock.withLock {
            val w = writer ?: return@withLock
            for (e in batch) {
                val line = e.toJsonLine()
                w.write(line)
                w.write("\n")
                partBytes += line.length + 1
            }
            w.flush()
            if (partBytes > PART_MAX_BYTES) {
                partIndex++
                openPart()
                prune()
            }
        }
        _revision.update { it + 1 }
    }

    /** Called from the crash handler: write everything pending, right now, on this thread. */
    private fun persistNow(extra: LogEntry) {
        val batch = ArrayList<LogEntry>()
        queue.drainTo(batch)
        batch.add(extra)
        val got = fileLock.tryLock(2, TimeUnit.SECONDS)
        try {
            // If the writer thread is wedged we still try; a torn line is
            // better than a lost crash.
            persist(batch)
        } finally {
            if (got) fileLock.unlock()
        }
    }

    private fun openPart() {
        runCatching { writer?.close() }
        val f = File(dir, "s_${sessionId}_$partIndex.jsonl")
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8), 32 * 1024)
        partBytes = f.length()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            try {
                val summary = "${t.javaClass.name}: ${t.message}"
                persistNow(
                    make(
                        LogLevel.Fatal, LogSource.Crash, "uncaught",
                        "Uncaught exception on thread \"${thread.name}\": $summary",
                        cap(t.stackTraceToString(), MAX_STACK), thread.name
                    )
                )
                prefs.edit().putString("crash_notice", summary.take(300)).commit()
            } catch (_: Throwable) {
                // Never let reporting stop the normal crash path.
            }
            previous?.uncaughtException(thread, t)
        }
    }

    private fun writeSessionHeader() {
        val pm = appCtx.packageManager
        val info = runCatching { pm.getPackageInfo(appCtx.packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        val code = info?.versionCode
        val mem = (appCtx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
            ?.let { am -> android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) } }
        val sb = StringBuilder()
        sb.append("Session $sessionId started\n")
        sb.append("App: ${appCtx.packageName} ${info?.versionName} (code $code)\n")
        sb.append("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), patch ${Build.VERSION.SECURITY_PATCH}\n")
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), board ${Build.BOARD}, hardware ${Build.HARDWARE}\n")
        sb.append("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}; cores: ${Runtime.getRuntime().availableProcessors()}\n")
        if (mem != null) sb.append("Memory: ${mem.totalMem / 1048576} MB total, ${mem.availMem / 1048576} MB free, low=${mem.lowMemory}\n")
        sb.append("Storage free in app dir: ${appCtx.filesDir.usableSpace / 1048576} MB\n")
        sb.append("Build: ${Build.FINGERPRINT}")
        enqueue(make(LogLevel.Info, LogSource.App, "session", sb.toString(), null))
    }

    private fun registerLifecycle(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private fun ev(a: Activity, what: String, level: LogLevel = LogLevel.Debug) =
                log(level, LogSource.App, "lifecycle", "${a.javaClass.simpleName} $what", mirrorToLogcat = false)
            override fun onActivityCreated(a: Activity, b: Bundle?) = ev(a, if (b == null) "created" else "recreated", LogLevel.Info)
            override fun onActivityStarted(a: Activity) = ev(a, "started")
            override fun onActivityResumed(a: Activity) = ev(a, "resumed")
            override fun onActivityPaused(a: Activity) = ev(a, "paused")
            override fun onActivityStopped(a: Activity) = ev(a, "stopped")
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) =
                ev(a, if (a.isChangingConfigurations) "destroyed (configuration change)" else "destroyed", LogLevel.Info)
        })
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onConfigurationChanged(c: Configuration) {
                log(LogLevel.Debug, LogSource.App, "lifecycle", "Configuration changed", mirrorToLogcat = false)
            }
            override fun onLowMemory() {
                log(LogLevel.Warn, LogSource.App, "memory", "System reports low memory")
            }
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    log(LogLevel.Warn, LogSource.App, "memory", "Trim memory requested (level $level)")
                }
            }
        })
    }

    // ---- reading ---------------------------------------------------------

    private val fileRe = Regex("^s_([0-9]{8}-[0-9]{6}-[0-9]+)_([0-9]+)\\.jsonl$")

    private fun logFiles(): List<Pair<String, File>> =
        (dir.listFiles() ?: emptyArray()).mapNotNull { f ->
            fileRe.matchEntire(f.name)?.let { m -> m.groupValues[1] to f }
        }

    private fun partOf(f: File) = fileRe.matchEntire(f.name)?.groupValues?.get(2)?.toIntOrNull() ?: 0

    fun sessions(): List<LogSession> {
        if (!ready) return emptyList()
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        return logFiles().groupBy({ it.first }, { it.second }).map { (id, files) ->
            val start = runCatching {
                LocalDateTime.parse(id.substring(0, 15), fmt).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrDefault(files.minOf { it.lastModified() })
            LogSession(id, start, files.sumOf { it.length() }, id == sessionId)
        }.sortedByDescending { it.startMs }
    }

    fun totalBytes(): Long = if (!ready) 0 else logFiles().sumOf { it.second.length() }

    /** Live entries of the running session (last [RING_MAX]), oldest first. */
    fun snapshot(): List<LogEntry> = synchronized(ringLock) { ring.toList() }

    fun load(id: String, maxEntries: Int = 60_000): List<LogEntry> {
        if (!ready) return emptyList()
        if (id == sessionId) return snapshot()
        val out = ArrayDeque<LogEntry>()
        var n = 0L
        for (f in logFiles().filter { it.first == id }.map { it.second }.sortedBy { partOf(it) }) {
            runCatching {
                f.useLines { lines ->
                    for (line in lines) {
                        parseJsonLine(line, n++)?.let {
                            out.addLast(it)
                            if (out.size > maxEntries) out.removeFirst()
                        }
                    }
                }
            }
        }
        return out.toList()
    }

    // ---- maintenance -----------------------------------------------------

    fun deleteSession(id: String) {
        if (id == sessionId) return clearCurrent()
        logFiles().filter { it.first == id }.forEach { it.second.delete() }
        _revision.update { it + 1 }
    }

    /** Deletes every past session and empties the running one. */
    fun clearAll() {
        logFiles().filter { it.first != sessionId }.forEach { it.second.delete() }
        clearCurrent()
    }

    private fun clearCurrent() {
        fileLock.withLock {
            runCatching { writer?.close() }
            logFiles().filter { it.first == sessionId }.forEach { it.second.delete() }
            partIndex = 1
            openPart()
        }
        synchronized(ringLock) { ring.clear() }
        enqueue(make(LogLevel.Info, LogSource.App, "applog", "Logs cleared by user", null))
    }

    private fun prune() {
        if (!::dir.isInitialized) return
        val limit = _config.value.maxTotalMb.toLong() * 1024 * 1024
        val all = logFiles()
        var total = all.sumOf { it.second.length() }
        if (total <= limit) return
        // Oldest finished sessions first, then the oldest parts of the current one.
        val victims = all.sortedWith(
            compareBy<Pair<String, File>>({ it.first == sessionId }, { it.second.lastModified() })
        )
        for ((id, f) in victims) {
            if (total <= limit) break
            if (id == sessionId && f.name.endsWith("_$partIndex.jsonl")) continue // never the open part
            total -= f.length()
            f.delete()
        }
    }

    // ---- export ----------------------------------------------------------

    fun formatTime(ms: Long): String = timeFmt.format(Instant.ofEpochMilli(ms))

    fun format(e: LogEntry): String {
        val sb = StringBuilder()
        sb.append(formatTime(e.timeMs)).append(' ')
            .append(e.level.letter).append('/').append(e.source.label)
            .append(" [").append(e.tag).append("] ")
            .append(e.message)
        if (!e.stack.isNullOrBlank()) {
            sb.append('\n')
            e.stack.lines().forEach { sb.append("    ").append(it).append('\n') }
            sb.setLength(sb.length - 1)
        }
        return sb.toString()
    }

    fun deviceReport(): String = buildString {
        append("Bareflash diagnostic export\n")
        append("Created: ${formatTime(System.currentTimeMillis())}\n")
        append("Session: $sessionId\n")
        append("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("Build: ${Build.FINGERPRINT}\n")
        append("Config: ${_config.value}\n")
    }

    /**
     * Zips every kept log file, plus a readable text copy of the running
     * session and a device summary. Written to the cache dir; the caller
     * shares it through the FileProvider. Returns null on failure.
     */
    /** Extra text files for the bug-report zip (file name to contents), supplied by the Console. */
    @Volatile var extraExport: (() -> Map<String, String>)? = null

    fun exportZip(): File? = try {
        // Make sure everything queued so far is on disk first.
        val pending = ArrayList<LogEntry>()
        queue.drainTo(pending)
        if (pending.isNotEmpty()) fileLock.withLock { persist(pending) }
        val outDir = File(appCtx.cacheDir, "log_export").apply { mkdirs() }
        outDir.listFiles()?.forEach { it.delete() }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        val zip = File(outDir, "bareflash-logs-$stamp.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry("device.txt"))
            z.write(deviceReport().toByteArray())
            z.closeEntry()
            runCatching {
                val entry = com.bareflash.app.dump.DumpBridge.listEntries()
                    .firstOrNull { it.name == com.bareflash.app.console.RunSidecar.PASSPORT_FILE }
                if (entry != null) {
                    appCtx.contentResolver.openInputStream(entry.uri)?.use { input ->
                        z.putNextEntry(ZipEntry("passport.txt"))
                        input.copyTo(z)
                        z.closeEntry()
                    }
                }
            }
            runCatching {
                val entry = com.bareflash.app.dump.DumpBridge.listEntries()
                    .firstOrNull { it.name == com.bareflash.app.console.RunSidecar.SESSION_FILE }
                if (entry != null) {
                    appCtx.contentResolver.openInputStream(entry.uri)?.use { input ->
                        z.putNextEntry(ZipEntry("session.txt"))
                        input.copyTo(z)
                        z.closeEntry()
                    }
                }
            }
            // Console state: last error, recent terminal output, unfinished writes and dumps.
            runCatching {
                extraExport?.invoke()?.forEach { (name, text) ->
                    z.putNextEntry(ZipEntry(name))
                    z.write(text.toByteArray())
                    z.closeEntry()
                }
            }
            z.putNextEntry(ZipEntry("current-session.log"))
            val w = z.bufferedWriter()
            snapshot().forEach { w.write(format(it)); w.write("\n") }
            w.flush()
            z.closeEntry()
            for ((_, f) in logFiles().sortedBy { it.second.name }) {
                z.putNextEntry(ZipEntry("sessions/${f.name}"))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        zip
    } catch (t: Throwable) {
        e("applog", "Export failed", t)
        null
    }

    // ---- crash notice ----------------------------------------------------

    internal fun setCrashNotice(text: String) {
        prefs.edit().putString("crash_notice", text.take(300)).apply()
        _crashNotice.value = text
    }

    /** One-shot: the summary of how the previous run died, if it did. */
    fun consumeCrashNotice(): String? {
        if (!ready) return null
        val s = prefs.getString("crash_notice", null)
        if (s != null) prefs.edit().remove("crash_notice").apply()
        return s
    }

    fun dismissCrashBanner() {
        _crashNotice.value = null
    }

    internal fun logDirPath(): String = dir.absolutePath
}
