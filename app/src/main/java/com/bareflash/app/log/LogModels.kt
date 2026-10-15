package com.bareflash.app.log

import org.json.JSONObject

/** Severity, lowest to highest. [letter] is the logcat-style single character. */
enum class LogLevel(val letter: Char, val label: String, val androidPriority: Int) {
    Verbose('V', "Verbose", 2),
    Debug('D', "Debug", 3),
    Info('I', "Info", 4),
    Warn('W', "Warning", 5),
    Error('E', "Error", 6),
    Fatal('F', "Fatal", 6);

    companion object {
        fun fromLetter(c: Char): LogLevel = entries.firstOrNull { it.letter == c } ?: Info
    }
}

/** Which part of the app (or which engine) an entry came from. */
enum class LogSource(val label: String) {
    App("App"),
    MediaTek("MediaTek"),
    Qualcomm("Qualcomm"),
    Samsung("Samsung"),
    Usb("USB"),
    Python("Python"),
    Native("Native"),
    System("System"),
    Crash("Crash")
}

/**
 * One log record. [seq] is unique within a session and doubles as the stable
 * list key in the UI; [stack] holds a stack trace or any long multi-line
 * detail that should stay collapsed until asked for.
 */
data class LogEntry(
    val seq: Long,
    val timeMs: Long,
    val level: LogLevel,
    val source: LogSource,
    val tag: String,
    val message: String,
    val stack: String?,
    val thread: String
)

/** One app launch worth of log files. */
data class LogSession(
    val id: String,
    val startMs: Long,
    val sizeBytes: Long,
    val isCurrent: Boolean
)

internal fun LogEntry.toJsonLine(): String {
    val o = JSONObject()
    o.put("t", timeMs)
    o.put("l", level.letter.toString())
    o.put("s", source.name)
    o.put("g", tag)
    o.put("m", message)
    if (stack != null) o.put("x", stack)
    o.put("th", thread)
    return o.toString()
}

internal fun parseJsonLine(line: String, seq: Long): LogEntry? = try {
    val o = JSONObject(line)
    LogEntry(
        seq = seq,
        timeMs = o.getLong("t"),
        level = LogLevel.fromLetter(o.optString("l", "I").firstOrNull() ?: 'I'),
        source = runCatching { LogSource.valueOf(o.optString("s", "App")) }.getOrDefault(LogSource.App),
        tag = o.optString("g"),
        message = o.optString("m"),
        stack = if (o.has("x")) o.getString("x") else null,
        thread = o.optString("th")
    )
} catch (e: Exception) {
    null
}
