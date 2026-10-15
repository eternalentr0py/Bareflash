package com.bareflash.app.export

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.bareflash.app.UsbBridge
import com.bareflash.app.console.ConsoleViewModel
import com.bareflash.app.console.RunSidecar
import com.bareflash.app.dump.DumpBridge
import com.bareflash.app.console.DumpSpaceGuard
import com.bareflash.app.dump.WriteMarker
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A one-page Markdown record for a repair log: the phone running Bareflash, the chip that was
 * identified, the partition table, the latest passport and session notes, and the manifests of
 * the newest full backup. It never contains dump data, only names, sizes and checksums.
 */
object DeviceReport {

    fun build(ctx: Context, vm: ConsoleViewModel): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        val t = vm.target.value
        val parts = vm.partitions.value
        return buildString {
            appendLine("# Bareflash device report")
            appendLine()
            appendLine("Generated $stamp by Bareflash $version on ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}).")
            appendLine("This report lists names, sizes and checksums only. It contains no dump data.")
            appendLine()
            appendLine("## Chip")
            val rows = listOf(
                "CPU" to t.cpu, "Chipset" to t.chipset, "Hardware code" to t.hwCode, "Hardware version" to t.hwVer,
                "Software version" to t.swVer, "Bootloader version" to t.blVer, "BootROM version" to t.bromVer,
                "Target config" to t.targetConfig, "USB link" to UsbBridge.lastLinkName,
            ).filter { !it.second.isNullOrBlank() }
            if (rows.isEmpty()) appendLine("No chip has been identified in this session.")
            else rows.forEach { appendLine("- ${it.first}: ${it.second}") }
            appendLine()
            appendLine("## Partition table")
            if (parts.isEmpty()) appendLine("Not read in this session.")
            else {
                appendLine("${parts.size} partitions, ${DumpSpaceGuard.formatBytes(parts.sumOf { it.length })} in total.")
                appendLine()
                appendLine("| Partition | Offset | Size |")
                appendLine("|---|---|---|")
                parts.forEach { appendLine("| ${it.name} | 0x${it.offset.toString(16)} | ${DumpSpaceGuard.formatBytes(it.length)} |") }
            }
            val passport = DumpBridge.readText(RunSidecar.PASSPORT_FILE)
            if (!passport.isNullOrBlank()) {
                appendLine()
                appendLine("## Latest passport")
                appendLine("```")
                appendLine(passport.trim().take(4000))
                appendLine("```")
            }
            val session = DumpBridge.readText(RunSidecar.SESSION_FILE)
            if (!session.isNullOrBlank()) {
                appendLine()
                appendLine("## Recent session notes")
                appendLine("```")
                appendLine(session.trim().takeLast(3000))
                appendLine("```")
            }
            val newest = runCatching {
                DumpBridge.listEntries().filter { it.name.endsWith("/MANIFEST.json") }.maxByOrNull { it.modified }
            }.getOrNull()
            if (newest != null) {
                appendLine()
                appendLine("## Newest full backup: ${newest.name.substringBeforeLast('/')}")
                val j = runCatching { JSONObject(DumpBridge.readText(newest.name).orEmpty()) }.getOrNull()
                val files = j?.optJSONArray("files")
                if (j == null || files == null) appendLine("The manifest could not be read.")
                else {
                    appendLine("Made ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(j.optLong("date", newest.modified)))}.")
                    appendLine()
                    appendLine("| File | Size | SHA-256 |")
                    appendLine("|---|---|---|")
                    for (i in 0 until files.length()) {
                        val f = files.getJSONObject(i)
                        appendLine("| ${f.optString("file")} | ${DumpSpaceGuard.formatBytes(f.optLong("bytes"))} | ${f.optString("sha256")} |")
                    }
                }
            }
            WriteMarker.current()?.let {
                appendLine()
                appendLine("## Last write")
                appendLine("${it.engine}: ${it.target}, state ${it.state}.")
            }
        }
    }

    /** Writes the report to a file and returns the share-sheet intent. Does file work, so call it off the main thread. */
    fun shareIntent(ctx: Context, vm: ConsoleViewModel): Intent {
        val text = build(ctx, vm)
        val dir = File(ctx.cacheDir, "log_export").apply { mkdirs() }
        val name = "bareflash-report-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.md"
        val f = File(dir, name)
        f.writeText(text)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Bareflash device report")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share device report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
