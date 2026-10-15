package com.bareflash.app.console

import android.os.ParcelFileDescriptor
import com.bareflash.app.dump.DumpBridge
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plain-text notes that sit in the dump folder next to the images, so a week
 * later `boot.img` is still identifiable:
 *
 *  - `session.txt`: one block appended per successful dump / read / identify
 *    command (date, engine, chip ids, command line, partition, files written).
 *  - `passport.txt`: the latest Identify result (chip ids + partition table).
 *
 * Everything goes through [DumpBridge], so it lands in the chosen folder (or the
 * app folder) exactly like a dump. Nothing leaves the device.
 */
object RunSidecar {

    const val SESSION_FILE = "session.txt"
    const val PASSPORT_FILE = "passport.txt"
    private val notes = setOf(SESSION_FILE, PASSPORT_FILE)

    private val reQdlSerial = Regex("Chip serial number:\\s+(0x[0-9a-fA-F]+)")
    private val reQdlHw = Regex("HW ID:\\s+(0x[0-9a-fA-F]+)")
    private val rePk = Regex("OEM PK hash:\\s+(0x[0-9a-fA-F]+)")

    private val readOnlyIdentify = setOf("printgpt", "gettargetconfig", "chipinfo", "multi")
    private val qdlReads = setOf("read", "readxml", "ramdump")

    /** Dumps and read-only identity commands are worth a note; writes and erases are not. */
    fun shouldRecord(isQdl: Boolean, command: String): Boolean {
        val tokens = DumpSpaceGuard.tokenize(command)
        val first = tokens.firstOrNull() ?: return false
        return if (isQdl) first in qdlReads || first == "chipinfo"
        else DumpSpaceGuard.isDump(tokens) || first in readOnlyIdentify
    }

    data class FileState(val size: Long, val modified: Long)

    /** Dump folder listing before a run; diffed afterwards to find the files it produced. */
    fun snapshot(): Map<String, FileState> =
        runCatching { DumpBridge.listEntries().associate { it.name to FileState(it.size, it.modified) } }
            .getOrDefault(emptyMap())

    private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    /** Facts parsed from qdl's chipinfo output. */
    fun qdlIdentity(slice: String): List<Pair<String, String>> = buildList {
        reQdlSerial.find(slice)?.let { add("sahara serial" to it.groupValues[1]) }
        reQdlHw.find(slice)?.let { add("hw id" to it.groupValues[1]) }
        rePk.find(slice)?.let { add("oem pk hash" to it.groupValues[1]) }
    }

    private fun chipLines(t: TargetInfo): List<Pair<String, String>> = buildList {
        if (t.cpu != null) add("chip" to listOfNotNull(t.cpu, t.chipset?.let { "($it)" }).joinToString(" "))
        t.hwCode?.let { add("hw code" to it) }
        t.targetConfig?.let { add("target config" to it) }
    }

    /** Appends one block to session.txt. [before] is the snapshot taken when the run started. */
    fun record(
        engine: String,
        isQdl: Boolean,
        command: String,
        startedAtMs: Long,
        before: Map<String, FileState>,
        target: TargetInfo,
        gpt: List<GptPartition>,
        slice: String,
    ) {
        val produced = snapshot().filter { (name, st) -> name !in notes && before[name] != st }
        val tokens = DumpSpaceGuard.tokenize(command)
        val ids = if (isQdl) qdlIdentity(slice) else chipLines(target)
        val partition = tokens.getOrNull(1)?.takeIf { !isQdl || tokens[0] == "read" }
            ?.let { name -> gpt.firstOrNull { it.name.equals(name, true) } }
        val block = buildString {
            appendLine("== ${stamp(startedAtMs)} \u00b7 $engine ==")
            appendLine("command: $command")
            ids.forEach { (k, v) -> appendLine("$k: $v") }
            if (partition != null) appendLine("partition: ${partition.name} (${partition.length} bytes)")
            produced.forEach { (name, st) -> appendLine("file: $name (${st.size} bytes)") }
            appendLine()
        }
        append(SESSION_FILE, block)
    }

    /** Overwrites passport.txt. Returns false when nothing could be written. */
    fun writePassport(
        engine: String,
        command: String,
        usbId: String?,
        target: TargetInfo,
        gpt: List<GptPartition>,
        qdlSlice: String?,
    ): Boolean {
        val text = buildString {
            appendLine("Bareflash device passport")
            appendLine("Date: ${stamp(System.currentTimeMillis())}")
            appendLine("Engine: $engine")
            appendLine("Command: $command")
            appendLine("USB: ${usbId ?: "unknown"}")
            if (qdlSlice != null) qdlIdentity(qdlSlice).forEach { (k, v) -> appendLine("$k: $v") }
            else {
                chipLines(target).forEach { (k, v) -> appendLine("$k: $v") }
                target.hwVer?.let { appendLine("hw version: $it") }
                target.swVer?.let { appendLine("sw version: $it") }
                target.blVer?.let { appendLine("bl version: $it") }
                target.bromVer?.let { appendLine("brom version: $it") }
            }
            if (gpt.isNotEmpty()) {
                appendLine()
                appendLine("Partition table (${gpt.size} partitions): name, offset, length in bytes")
                gpt.forEach { appendLine("${it.name}, ${it.offset}, ${it.length}") }
            }
        }
        return write(PASSPORT_FILE, text, append = false)
    }

    /** file name -> one-line identity ("2026-10-08 09:41 \u00b7 MTK \u00b7 boot \u00b7 hw 0x907"), newest block wins. */
    fun subtitles(): Map<String, String> {
        val text = read(SESSION_FILE) ?: return emptyMap()
        val out = HashMap<String, String>()
        var head = ""
        var partition: String? = null
        var hw: String? = null
        val pending = mutableListOf<String>()
        fun flush() {
            if (pending.isEmpty()) return
            val summary = listOfNotNull(head, partition, hw?.let { "hw $it" }).joinToString(" \u00b7 ")
            pending.forEach { out[it] = summary }
            pending.clear()
        }
        for (line in text.lineSequence()) {
            when {
                line.startsWith("== ") -> {
                    flush()
                    val inner = line.removePrefix("== ").removeSuffix(" ==")
                    // "2026-10-08 09:41:02 \u00b7 MTK" -> drop seconds
                    head = inner.replace(Regex("(\\d{2}:\\d{2}):\\d{2}"), "$1")
                    partition = null; hw = null
                }
                line.startsWith("partition: ") -> partition = line.removePrefix("partition: ").substringBefore(" (")
                line.startsWith("hw code: ") -> hw = line.removePrefix("hw code: ")
                line.startsWith("hw id: ") -> hw = line.removePrefix("hw id: ")
                line.startsWith("file: ") -> pending += line.removePrefix("file: ").substringBefore(" (")
            }
        }
        flush()
        return out
    }

    private fun append(name: String, text: String) { write(name, text, append = true) }

    private fun write(name: String, text: String, append: Boolean): Boolean = runCatching {
        val fd = DumpBridge.openFd(name, if (append) "a" else "w")
        if (fd < 0) return false
        ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(fd)).use { out: OutputStream ->
            out.write(text.toByteArray())
        }
        true
    }.getOrDefault(false)

    private fun read(name: String): String? = runCatching {
        if (DumpBridge.size(name) < 0L) return null
        val fd = DumpBridge.openFd(name, "r")
        if (fd < 0) return null
        ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(fd)).use { it.readBytes().decodeToString() }
    }.getOrNull()
}
