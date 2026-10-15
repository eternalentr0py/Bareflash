package com.bareflash.app.console.logging

/**
 * Collapses Kotlin-originated connection chatter the same way
 * android_entry.py's _WAIT_NOISE already collapses Python's reconnect-wait
 * spam: repeats become one [WAITING_LINE] that stays put instead of a new
 * line per event, and decorative blank lines are dropped.
 *
 * This exists because "USB device connected."/"USB device disconnected."
 * (from the attach/detach broadcast receiver) and the "Found device /
 * iface / access granted" block (from detectDevice()) are logged directly
 * from Kotlin and never pass through the Python-side filter at all — a
 * device flapping in and out of Preloader mode during the exploit chain's
 * crash-and-reconnect step can fire either of these many times a second.
 *
 * Purely a display-layer filter: it only decides what gets appended to the
 * terminal, never anything about USB connection or permission handling.
 */
class TerminalNoiseFilter {
    private var waitingShown = false

    // Whether the last thing this filter returned ended with a real
    // newline. CPython's print() calls write() twice per line — once for
    // the message, once for its separate `end` ("\n") argument — so a
    // chunk that is *exactly* "\n" is ambiguous on its own: it might be
    // the second half of a print() call closing out a hanging line (must
    // be kept, or the next message glues onto this one with no separator
    // at all), or it might be a genuinely decorative extra blank line
    // (safe to drop). Tracking whether the previous output already ended
    // in "\n" disambiguates the two correctly.
    private var lastEndedWithNewline = true

    private fun isNoise(line: String): Boolean {
        val t = line.trim()
        if (t in COLLAPSIBLE_EXACT) return true
        if (t.startsWith(USB_BRIDGE_PREFIX)) {
            // "[UsbBridge] connected vid=..." is the one genuinely
            // meaningful status UsbBridge reports — every other
            // "[UsbBridge] ..." line is a transient failure/waiting state
            // (no permission yet, device not found, open failed, ...),
            // safe to collapse the same as the rest of this bucket.
            return !t.startsWith(USB_BRIDGE_CONNECTED_PREFIX)
        }
        return COLLAPSIBLE_PREFIXES.any { t.startsWith(it) }
    }

    /**
     * [chunk] may contain multiple lines and/or a '\r' progress redraw.
     * A trailing '\n' on the input is preserved on the output whenever
     * anything survives the filter, so this never glues one log() call's
     * text onto the next — and a chunk that collapses to nothing (e.g. a
     * decorative blank line) returns "" with no stray newline of its own.
     */
    fun filter(chunk: String): String {
        if (chunk.isEmpty()) return chunk

        if (chunk == "\n") {
            return if (lastEndedWithNewline) {
                "" // already terminated — this is an extra decorative blank
            } else {
                lastEndedWithNewline = true
                "\n" // closes out a genuinely hanging (unterminated) line
            }
        }

        val endsWithNewline = chunk.endsWith("\n")
        val allSegments = chunk.split("\n")
        val segments = if (endsWithNewline) allSegments.dropLast(1) else allSegments

        val emitted = mutableListOf<String>()
        for (seg in segments) {
            if ("\r" in seg) {
                waitingShown = false
                emitted.add(seg)
                continue
            }
            if (seg.isEmpty()) continue // decorative blank inside a larger chunk
            if (isNoise(seg)) {
                if (!waitingShown) {
                    emitted.add(WAITING_LINE)
                    waitingShown = true
                }
                continue
            }
            waitingShown = false
            emitted.add(seg)
        }

        if (emitted.isEmpty()) return ""
        val joined = emitted.joinToString("\n")
        val result = if (endsWithNewline) "$joined\n" else joined
        lastEndedWithNewline = result.endsWith("\n")
        return result
    }

    /** Called when the terminal itself is cleared, so a fresh screen can
     *  show its own first status line right away. */
    fun reset() {
        waitingShown = false
        lastEndedWithNewline = true
    }

    companion object {
        /** Single line shown while connection noise is being collapsed. */
        const val WAITING_LINE = "Waiting for device..."

        private const val USB_BRIDGE_PREFIX = "[UsbBridge]"
        private const val USB_BRIDGE_CONNECTED_PREFIX = "[UsbBridge] connected"

        private val COLLAPSIBLE_EXACT = setOf(
            "USB device connected.",
            "USB device disconnected.",
            WAITING_LINE,
        )
        private val COLLAPSIBLE_PREFIXES = listOf(
            "Found device vid=",
            "iface[",
            "USB access already granted for",
            "[usb-xfer]",
        )
    }
}
