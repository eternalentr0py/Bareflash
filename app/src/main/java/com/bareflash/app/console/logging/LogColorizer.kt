package com.bareflash.app.console.logging

/**
 * Classifies a terminal line for coloring. Avoids allocating a lowercased
 * copy of every line: patterns are matched with regionMatches(ignoreCase).
 */
object LogColorizer {

    private val errorPatterns = arrayOf(
        "error", "failed", "exception", "traceback", "could not", "not accepted",
        "not valid", "denied", "rejected", "timeout", "timed out",
        "crash report", "closed unexpectedly"
    )
    private val connectedPatterns = arrayOf(
        "device connected", "device detected", "access already granted",
        "access granted", "connected to", "device is unprotected",
        "successfully", "detected regular mode", "boot to succeeded",
        "connected)", "saved "
    )
    private val disconnectedPatterns = arrayOf(
        "device disconnected", "connection lost", "waiting for device",
        "access was denied", "usb open failed"
    )
    private val warningPatterns = arrayOf("warning", "hint:", "cancelled", "cancelling")

    private fun containsIgnoreCase(hay: String, needle: String): Boolean {
        val n = needle.length
        if (n == 0) return true
        val last = hay.length - n
        if (last < 0) return false
        var i = 0
        while (i <= last) {
            if (hay.regionMatches(i, needle, 0, n, ignoreCase = true)) return true
            i++
        }
        return false
    }

    private fun anyOf(hay: String, needles: Array<String>): Boolean {
        for (n in needles) if (containsIgnoreCase(hay, n)) return true
        return false
    }

    /** [line] should be a single line, without its trailing newline. */
    fun classify(line: String): LogCategory {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return LogCategory.DEFAULT
        if (trimmed.startsWith("$ ")) return LogCategory.COMMAND
        return when {
            anyOf(trimmed, errorPatterns) -> LogCategory.ERROR
            anyOf(trimmed, disconnectedPatterns) -> LogCategory.DISCONNECTED
            anyOf(trimmed, connectedPatterns) -> LogCategory.CONNECTED
            anyOf(trimmed, warningPatterns) -> LogCategory.WARNING
            else -> LogCategory.DEFAULT
        }
    }
}
