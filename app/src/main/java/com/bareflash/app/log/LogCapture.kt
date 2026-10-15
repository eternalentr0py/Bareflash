package com.bareflash.app.log

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Sources of log data that are not "someone called AppLog":
 *  - the process's own system log (everything written through android.util.Log,
 *    native `__android_log_print`, ART warnings, "Skipped N frames", Chaquopy's
 *    Python stdout/stderr, libusb ...);
 *  - a main-thread watchdog that records UI stalls with the blocked stack;
 *  - the OS's own account of how the previous process ended (Java crash,
 *    native crash, ANR, OOM kill ...), which is the only way to see a native
 *    crash after the fact.
 */
internal object LogCapture {

    // ---- system log ------------------------------------------------------

    @Volatile private var capturing = false
    @Volatile private var logcatProc: java.lang.Process? = null
    private var logcatThread: Thread? = null

    private val line = Regex(
        "^(\\d\\d)-(\\d\\d) (\\d\\d):(\\d\\d):(\\d\\d)\\.(\\d{3})\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEFA])\\s+(.*?)\\s*: (.*)$"
    )

    @Synchronized
    fun startSystemCapture() {
        if (capturing && logcatThread?.isAlive == true) return
        capturing = true
        logcatThread = thread(name = "applog-logcat", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val myPid = Process.myPid()
            val year = LocalDateTime.now().year
            var failures = 0
            while (capturing) {
                var proc: java.lang.Process? = null
                try {
                    proc = ProcessBuilder("logcat", "-v", "threadtime").redirectErrorStream(true).start()
                    logcatProc = proc
                    proc.inputStream.bufferedReader().use { r ->
                        while (capturing) {
                            val l = r.readLine() ?: break
                            ingest(l, myPid, year)
                        }
                    }
                    failures = 0
                } catch (t: Throwable) {
                    if (failures++ == 0) {
                        AppLog.log(LogLevel.Warn, LogSource.System, "logcat", "System log capture unavailable: ${t.message}", mirrorToLogcat = false)
                    }
                } finally {
                    runCatching { proc?.destroy() }
                    logcatProc = null
                }
                if (!capturing) break
                runCatching { Thread.sleep(if (failures > 3) 60_000L else 5_000L) }
            }
        }
    }

    @Synchronized
    fun stopSystemCapture() {
        capturing = false
        runCatching { logcatProc?.destroy() }
    }

    private fun ingest(l: String, myPid: Int, year: Int) {
        val m = line.matchEntire(l) ?: return
        val g = m.groupValues
        if (g[7].toIntOrNull() != myPid) return
        val tag = g[10]
        if (tag.startsWith("BF.")) return // our own mirrored entries
        val level = LogLevel.fromLetter(if (g[9][0] == 'A') 'F' else g[9][0])
        val source = sourceFor(tag, level)
        // Unrelated system chatter below Info is noise; our own subsystems keep everything.
        if (source == LogSource.System && level.ordinal < LogLevel.Info.ordinal) return
        // Android UI framework spam that buried everything else (11k lines in one session).
        if (source == LogSource.System && level.ordinal < LogLevel.Warn.ordinal && isUiChatter(tag)) return
        val time = runCatching {
            LocalDateTime.of(year, g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt() * 1_000_000)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrDefault(System.currentTimeMillis())
        if (level.ordinal < AppLog.config.value.minLevel.ordinal) return
        AppLog.enqueue(AppLog.make(level, source, tag, g[11], null, thread = "tid ${g[8]}", timeMs = time))
    }

    private val chatterTags = setOf(
        "View", "ViewPostIme", "InsetsSourceConsumer", "BLASTBufferQueue_Java", "NotificationManager",
        "InsetsController", "ImeTracker", "InputMethodManager", "SurfaceView", "ViewRootImpl",
        "AccessibilityDelegate", "SemInputMethodManager"
    )

    private fun isUiChatter(tag: String): Boolean =
        tag in chatterTags || tag.startsWith("VRI[") || tag.startsWith("ViewRootImpl")

    private fun sourceFor(tag: String, level: LogLevel): LogSource {
        val t = tag.lowercase()
        return when {
            t == "androidruntime" || t == "libc" || t == "debug" || t.contains("crash") ||
                (t == "art" && level.ordinal >= LogLevel.Error.ordinal) -> LogSource.Crash
            t.contains("usb") -> LogSource.Usb
            t.startsWith("python") || t.contains("chaquopy") -> LogSource.Python
            t.contains("qdl") || t.contains("edl") -> LogSource.Qualcomm
            t.contains("brokkr") || t.contains("odin") || t.contains("samsung") -> LogSource.Samsung
            t.contains("mtk") -> LogSource.MediaTek
            t.contains("jni") || t.contains("native") -> LogSource.Native
            t.contains("perfboost") || t.contains("bareflash") || t.contains("dump") || t.contains("console") -> LogSource.App
            else -> LogSource.System
        }
    }

    // ---- main-thread watchdog ---------------------------------------------

    @Volatile private var watching = false
    private var watchThread: Thread? = null
    private const val STALL_MS = 5_000L

    @Synchronized
    fun startWatchdog() {
        if (watching && watchThread?.isAlive == true) return
        watching = true
        watchThread = thread(name = "applog-watchdog", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val main = Handler(Looper.getMainLooper())
            while (watching) {
                val answered = AtomicBoolean(false)
                val sent = SystemClock.uptimeMillis()
                main.post { answered.set(true) }
                var reported = false
                while (watching && !answered.get()) {
                    runCatching { Thread.sleep(250) }
                    val waited = SystemClock.uptimeMillis() - sent
                    if (!reported && waited >= STALL_MS && !answered.get()) {
                        reported = true
                        val stack = Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "    at $it" }
                        AppLog.logDetail(
                            LogLevel.Warn, LogSource.App, "watchdog",
                            "Main thread has not responded for ${waited / 1000}s (UI is frozen). Its current stack is attached.",
                            stack
                        )
                    }
                }
                if (reported) {
                    AppLog.log(
                        LogLevel.Info, LogSource.App, "watchdog",
                        "Main thread responsive again after ${SystemClock.uptimeMillis() - sent} ms", mirrorToLogcat = false
                    )
                }
                runCatching { Thread.sleep(1_000) }
            }
        }
    }

    @Synchronized
    fun stopWatchdog() {
        watching = false
    }

    // ---- how the previous process ended ------------------------------------

    fun ingestPreviousRun(ctx: Context, prefs: SharedPreferences) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ingestExitInfo(ctx, prefs)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun ingestExitInfo(ctx: Context, prefs: SharedPreferences) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val last = prefs.getLong("exit_last_ts", 0L)
        val fresh = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 16)
            .filter { it.timestamp > last }
            .sortedBy { it.timestamp }
        var newest = last
        for (info in fresh) {
            newest = maxOf(newest, info.timestamp)
            val reason = info.reason
            // A clean self-exit is the normal case; not worth a line.
            if (reason == ApplicationExitInfo.REASON_EXIT_SELF && info.status == 0) continue
            val (level, name) = when (reason) {
                ApplicationExitInfo.REASON_CRASH -> LogLevel.Fatal to "Java/Kotlin crash"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> LogLevel.Fatal to "Native crash (signal)"
                ApplicationExitInfo.REASON_ANR -> LogLevel.Fatal to "App Not Responding (ANR)"
                ApplicationExitInfo.REASON_LOW_MEMORY -> LogLevel.Error to "Killed: low memory"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> LogLevel.Error to "Killed: excessive resource use"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> LogLevel.Error to "Failed to start"
                ApplicationExitInfo.REASON_SIGNALED -> LogLevel.Error to "Killed by signal ${info.status}"
                ApplicationExitInfo.REASON_DEPENDENCY_DIED -> LogLevel.Warn to "Dependency died"
                ApplicationExitInfo.REASON_PERMISSION_CHANGE -> LogLevel.Info to "Restarted: permission changed"
                ApplicationExitInfo.REASON_USER_REQUESTED -> LogLevel.Info to "Stopped by user"
                ApplicationExitInfo.REASON_USER_STOPPED -> LogLevel.Info to "Stopped by user (force stop)"
                ApplicationExitInfo.REASON_EXIT_SELF -> LogLevel.Warn to "Exited itself with status ${info.status}"
                ApplicationExitInfo.REASON_OTHER -> LogLevel.Info to "Ended (other)"
                else -> LogLevel.Warn to "Ended (reason code $reason)"
            }
            val message = buildString {
                append("Previous process (pid ${info.pid}) ended: $name")
                if (!info.description.isNullOrBlank()) append(" — ${info.description}")
                append("\nimportance=${info.importance}, status=${info.status}, rss=${info.rss / 1024} MB, pss=${info.pss / 1024} MB")
            }
            val isNative = reason == ApplicationExitInfo.REASON_CRASH_NATIVE
            val trace = if (reason == ApplicationExitInfo.REASON_CRASH_NATIVE || reason == ApplicationExitInfo.REASON_ANR) {
                readTrace(info, isNative)
            } else null
            AppLog.enqueue(AppLog.make(level, LogSource.Crash, "exit-info", message, trace, thread = "pid ${info.pid}", timeMs = info.timestamp))
            if (level == LogLevel.Fatal) {
                AppLog.setCrashNotice(name)
            }
        }
        if (newest > last) prefs.edit().putLong("exit_last_ts", newest).apply()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readTrace(info: ApplicationExitInfo, native: Boolean): String? = try {
        info.traceInputStream?.use { ins ->
            val bytes = readCapped(ins, 256 * 1024)
            if (native) {
                // Android 12+ writes native crashes as a protobuf tombstone; the
                // human-readable parts (signal, abort message, frames, build ids)
                // are plain strings inside it, so pull those out.
                printableRuns(bytes)
            } else {
                String(bytes, Charsets.UTF_8)
            }
        }
    } catch (t: Throwable) {
        null
    }

    private fun readCapped(ins: InputStream, cap: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < cap) {
            val n = ins.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun printableRuns(b: ByteArray): String {
        val sb = StringBuilder()
        var start = -1
        var lines = 0
        for (i in 0..b.size) {
            val c = if (i < b.size) b[i].toInt() and 0xFF else -1
            val printable = c in 0x20..0x7E || c == 0x09
            if (printable) {
                if (start < 0) start = i
            } else if (start >= 0) {
                if (i - start >= 5) {
                    sb.append(String(b, start, i - start, Charsets.US_ASCII)).append('\n')
                    if (++lines >= 600) break
                }
                start = -1
            }
        }
        return sb.toString().trimEnd()
    }
}
