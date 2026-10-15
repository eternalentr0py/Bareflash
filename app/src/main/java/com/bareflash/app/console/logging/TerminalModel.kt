package com.bareflash.app.console.logging

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Thread-safe terminal text with '\r' (redraw line) and '\b' handling.
 * Publishes a line list so Compose can virtualize instead of rebuilding
 * one 60k-char AnnotatedString on every coalesced tick.
 */
class TerminalModel {
    private val lock = Any()
    private val sb = StringBuilder()
    private var lineStart = 0
    private var publishQueued = false
    @Volatile private var maxLines = DEFAULT_LINES
    private val handler = Handler(Looper.getMainLooper())

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun append(chunk: String) {
        synchronized(lock) {
            var i = 0
            while (i < chunk.length) {
                when (chunk[i]) {
                    '\r' -> { if (sb.length > lineStart) sb.setLength(lineStart); i++ }
                    '\n' -> { sb.append('\n'); lineStart = sb.length; i++ }
                    '\b' -> { if (sb.length > lineStart) sb.setLength(sb.length - 1); i++ }
                    else -> {
                        var j = i + 1
                        while (j < chunk.length && chunk[j] != '\r' && chunk[j] != '\n' && chunk[j] != '\b') j++
                        sb.append(chunk, i, j)
                        i = j
                    }
                }
            }
            if (sb.length > maxChars()) {
                val drop = sb.length - trimTo()
                sb.delete(0, drop)
                lineStart = (lineStart - drop).coerceAtLeast(0)
            }
            if (!publishQueued) {
                publishQueued = true
                handler.postDelayed({ publish() }, PUBLISH_DELAY_MS)
            }
        }
    }

    /**
     * How many lines to keep (Settings > Terminal > Scrollback). The character budget follows it,
     * so a long scrollback is not cut short by the old fixed 60 000 character cap.
     */
    fun setScrollback(lines: Int) {
        val next = lines.coerceIn(100, 10_000)
        if (next == maxLines) return
        maxLines = next
        synchronized(lock) {
            if (sb.length > maxChars()) {
                val drop = sb.length - trimTo()
                sb.delete(0, drop)
                lineStart = (lineStart - drop).coerceAtLeast(0)
            }
        }
        publish()
    }

    private fun maxChars() = maxOf(MIN_CHARS, maxLines * CHARS_PER_LINE)
    private fun trimTo() = maxChars() * 3 / 4

    fun clear() {
        synchronized(lock) {
            sb.setLength(0)
            lineStart = 0
        }
        publish()
    }

    private fun publish() {
        val snapshot: String
        val split: List<String>
        synchronized(lock) {
            publishQueued = false
            snapshot = sb.toString()
            if (snapshot.isEmpty()) {
                split = emptyList()
            } else {
                val raw = snapshot.split('\n')
                val keep = maxLines
                split = if (raw.size <= keep) raw else raw.subList(raw.size - keep, raw.size)
            }
        }
        _text.value = snapshot
        _lines.value = split
    }

    private companion object {
        const val DEFAULT_LINES = 800
        const val MIN_CHARS = 60_000
        /** 800 lines * 75 = the old 60 000 character budget, so the default behaves as before. */
        const val CHARS_PER_LINE = 75
        /**
         * Coalesce UI republishes. 50ms rebuilt the entire colored log
         * ~20×/s and is what made USB throughput collapse until a
         * rotation (fresh composition) temporarily restored it.
         */
        const val PUBLISH_DELAY_MS = 200L
    }
}
