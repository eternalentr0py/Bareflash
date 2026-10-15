package com.bareflash.app.export

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.bareflash.app.live.LiveEngine
import com.bareflash.app.live.LiveHub
import com.bareflash.app.live.LiveKind
import com.bareflash.app.live.LiveOutcome
import com.bareflash.app.live.LiveResult
import com.bareflash.app.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.concurrent.thread

data class ExportJob(
    val fileName: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val done: Boolean = false,
    val error: String? = null
) {
    val fraction: Float get() = if (bytesTotal <= 0) 0f else (bytesDone.toDouble() / bytesTotal).toFloat()
}

/**
 * Copies a dumped file out to a user-chosen SAF location with progress,
 * surfaced as an in-app progress bar (via [active]) and, while the app is in the
 * background, as a live notification through [LiveHub]. Large partition dumps
 * (userdata can be 25 GB and more) take long enough that "did this finish" needs
 * an answer outside the app too.
 */
class ExportManager(private val ctx: Context) {

    private val _active = MutableStateFlow<ExportJob?>(null)
    val active: StateFlow<ExportJob?> = _active

    /** [onLog] receives the final one-line result to print in the terminal. */
    fun export(source: File, dest: Uri, onLog: (String) -> Unit) {
        exportStream(source.name, source.length(), dest, onLog) { source.inputStream() }
    }

    fun export(source: Uri, fileName: String, size: Long, dest: Uri, onLog: (String) -> Unit) {
        exportStream(fileName, size, dest, onLog) {
            ctx.contentResolver.openInputStream(source)
                ?: throw IllegalStateException("could not open source")
        }
    }

    private fun exportStream(
        fileName: String,
        total: Long,
        dest: Uri,
        onLog: (String) -> Unit,
        openInput: () -> java.io.InputStream,
    ) {
        val id = LiveHub.begin(LiveKind.Export, LiveEngine.App, "Export", fileName)
        _active.value = ExportJob(fileName, 0, total)

        thread(name = "mtk-export-progress") {
            val t0 = SystemClock.elapsedRealtime()
            var lastPosted = 0L
            var copied = 0L
            val result = try {
                ctx.contentResolver.openOutputStream(dest)?.use { out ->
                    openInput().use { input ->
                        val buf = ByteArray(1 shl 20) // 1 MiB
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                            _active.value = ExportJob(fileName, copied, total)
                            if (copied - lastPosted > total / 100 + (1 shl 20)) {
                                val el = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
                                val speed = copied * 1000.0 / el
                                val eta = if (total > copied && speed > 1.0) ((total - copied) / speed * 1000.0).toLong() else -1L
                                LiveHub.progress(id, copied, total, speed, eta, fileName)
                                lastPosted = copied
                            }
                        }
                    }
                } ?: throw IllegalStateException("could not open destination")
                "Saved $fileName ($total bytes) to the selected location."
            } catch (e: Exception) {
                AppLog.e("export", "Export failed for $fileName", e)
                "The export could not be completed: ${e.message}"
            }
            val failed = result.startsWith("The export could not")
            _active.value = ExportJob(fileName, total, total, done = true, error = if (failed) result else null)
            val elapsed = SystemClock.elapsedRealtime() - t0
            LiveHub.end(
                id,
                LiveResult(
                    kind = LiveKind.Export, engine = LiveEngine.App,
                    outcome = if (failed) LiveOutcome.Failure else LiveOutcome.Success,
                    title = "Export", target = fileName, elapsedMs = elapsed,
                    bytes = copied, avgBps = if (elapsed > 0L) copied * 1000.0 / elapsed else 0.0,
                    detail = if (failed) result.removePrefix("The export could not be completed: ") else "Saved to the location you chose",
                    hint = if (failed) "Check that the destination has enough free space and try again." else "",
                )
            )
            onLog(result + "\n")
        }
    }
}
