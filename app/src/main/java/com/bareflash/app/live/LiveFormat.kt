package com.bareflash.app.live

import java.util.Locale

/** Text helpers shared by the ongoing and the result notification. */
internal object LiveFormat {

    fun bytes(n: Long): String {
        if (n < 1024L) return "$n B"
        val kib = n / 1024.0
        if (kib < 1024.0) return String.format(Locale.US, "%.0f KiB", kib)
        val mib = kib / 1024.0
        if (mib < 1024.0) return String.format(Locale.US, if (mib < 10) "%.1f MiB" else "%.0f MiB", mib)
        val gib = mib / 1024.0
        return String.format(Locale.US, "%.2f GiB", gib)
    }

    fun speed(bps: Double): String {
        if (bps < 1.0) return ""
        val mib = bps / (1024.0 * 1024.0)
        return if (mib >= 1.0) String.format(Locale.US, "%.1f MiB/s", mib)
        else String.format(Locale.US, "%.0f KiB/s", bps / 1024.0)
    }

    /** "5 min left", "1 h 12 min left", "40 s left". */
    fun eta(ms: Long): String {
        if (ms < 0L) return ""
        val s = (ms + 500L) / 1000L
        return when {
            s < 60L -> "$s s left"
            s < 3600L -> "${s / 60L} min left"
            else -> "${s / 3600L} h ${(s % 3600L) / 60L} min left"
        }
    }

    /** "4m 12s", "1h 03m". */
    fun elapsed(ms: Long): String {
        val s = (ms.coerceAtLeast(0L)) / 1000L
        return when {
            s < 60L -> "${s}s"
            s < 3600L -> String.format(Locale.US, "%dm %02ds", s / 60L, s % 60L)
            else -> String.format(Locale.US, "%dh %02dm", s / 3600L, (s % 3600L) / 60L)
        }
    }

    fun verb(kind: LiveKind): String = when (kind) {
        LiveKind.Read -> "Reading"
        LiveKind.Write -> "Writing"
        LiveKind.Erase -> "Erasing"
        LiveKind.Flash -> "Flashing"
        LiveKind.Verify -> "Verifying"
        LiveKind.Backup -> "Backing up"
        LiveKind.Export -> "Exporting"
        LiveKind.Test -> "Testing"
        LiveKind.Other -> "Running"
    }

    fun pastVerb(kind: LiveKind): String = when (kind) {
        LiveKind.Read -> "Read"
        LiveKind.Write -> "Write"
        LiveKind.Erase -> "Erase"
        LiveKind.Flash -> "Flash"
        LiveKind.Verify -> "Verify"
        LiveKind.Backup -> "Backup"
        LiveKind.Export -> "Export"
        LiveKind.Test -> "Test"
        LiveKind.Other -> "Command"
    }

    /** The headline: "Reading userdata", "Flashing firmware", "Full backup". */
    fun headline(s: LiveSnapshot): String {
        val what = s.target.ifBlank { s.title }
        return when {
            s.kind == LiveKind.Other -> s.title.ifBlank { "Working" }
            s.kind == LiveKind.Backup || s.kind == LiveKind.Test -> s.title.ifBlank { verb(s.kind) }
            what.isBlank() -> verb(s.kind)
            else -> "${verb(s.kind)} $what"
        }
    }

    /** One line under the headline. Always says something useful, never "Running...". */
    fun detailLine(s: LiveSnapshot): String = when (s.status) {
        LiveStatus.Starting -> s.note.ifBlank { "Starting" }
        LiveStatus.Waiting -> s.note.ifBlank { "Waiting for the device" }
        LiveStatus.Between -> s.note.ifBlank { "Next step" }
        else -> {
            val parts = ArrayList<String>(4)
            if (s.measurable) parts += "${bytes(s.bytesDone)} of ${bytes(s.bytesTotal)}"
            speed(s.speedBps).takeIf { it.isNotEmpty() }?.let { parts += it }
            eta(s.etaMs).takeIf { it.isNotEmpty() }?.let { parts += it }
            if (parts.isEmpty()) parts += (s.stage.ifBlank { "Working" })
            parts.joinToString(" \u00b7 ")
        }
    }

    /** Header text beside the app name: chip and step. */
    fun subText(s: LiveSnapshot): String {
        val parts = ArrayList<String>(3)
        if (s.queueTotal > 1) parts += "Step ${s.queueIndex} of ${s.queueTotal}"
        parts += s.engine.label
        if (s.device.isNotBlank()) parts += s.device
        return parts.joinToString(" \u00b7 ")
    }

    /**
     * What the status bar chip says before there is a percentage to show: the running
     * command, in the words the Flash and Dump tabs use ("Flashing", "Dumping").
     */
    fun chipVerb(kind: LiveKind): String = when (kind) {
        LiveKind.Read, LiveKind.Backup -> "Dumping"
        LiveKind.Write, LiveKind.Flash -> "Flashing"
        LiveKind.Erase -> "Erasing"
        LiveKind.Verify -> "Verifying"
        LiveKind.Export -> "Exporting"
        LiveKind.Test -> "Testing"
        LiveKind.Other -> "Running"
    }

    /**
     * The few characters that sit in the status bar chip: the command name until the
     * engine has moved its first byte, then the percentage.
     */
    fun chip(s: LiveSnapshot): String = when (s.status) {
        LiveStatus.Waiting -> "WAIT"
        LiveStatus.Between -> "NEXT"
        LiveStatus.Starting -> chipVerb(s.kind)
        else -> if (s.measurable && s.bytesDone > 0L) "${s.percent}%" else chipVerb(s.kind)
    }
}
