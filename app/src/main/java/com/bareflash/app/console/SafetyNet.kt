package com.bareflash.app.console

import com.bareflash.app.engine.EngineMode

/** What the user ticked in the write confirmation. */
data class WriteOptions(
    val backupFirst: Boolean = false,
    val verifyAfter: Boolean = false,
)

/**
 * Decides which extra safety steps a destructive command can offer:
 * a backup of the partition before it is overwritten or erased, and a
 * read-back check after a write. Pure logic, no device I/O.
 */
object SafetyNet {

    /** Read-back is on by default only for small partitions (boot, vbmeta, preloader ...). */
    const val VERIFY_DEFAULT_MAX = 256L * 1024 * 1024

    data class Offer(
        val partition: String? = null,
        val partitionBytes: Long = -1L,
        val backupAvailable: Boolean = false,
        val backupNote: String = "",
        val backupDefault: Boolean = false,
        val verifyAvailable: Boolean = false,
        val verifyNote: String = "",
        val verifyDefault: Boolean = false,
    )

    private fun fmt(n: Long) = ConsoleViewModel.formatBytes(n)

    fun offer(
        mode: EngineMode,
        p: PendingCommand,
        folderReady: Boolean,
        freeBytes: Long,
    ): Offer {
        val check = p.check
        val part = check?.partition
        if (mode == EngineMode.QDL && check != null && part != null) {
            // QDL has no partition table, so no automatic backup; a read-back check still works.
            val first = DumpSpaceGuard.tokenize(p.finalCommand).firstOrNull()
            if (first != "write") return Offer(partition = part)
            val ok = !check.sparse && check.imageBytes > 0L && folderReady
            val note = when {
                check.sparse -> "Read-back is not available for sparse images."
                check.imageBytes <= 0L -> "Read-back needs a readable image."
                !folderReady -> "Choose a dump folder to read the partition back after writing."
                else -> "Reads $part back after writing and compares its SHA-256 with the image."
            }
            return Offer(
                partition = part,
                backupNote = "QDL does not report partition sizes, so no automatic backup is offered. Read $part first if you need one.",
                verifyAvailable = ok, verifyNote = note,
                verifyDefault = ok && check.imageBytes <= VERIFY_DEFAULT_MAX,
            )
        }
        if (mode != EngineMode.MTK || part == null) return Offer()
        val t = DumpSpaceGuard.tokenize(p.finalCommand)
        val first = t.firstOrNull() ?: return Offer()
        val isWrite = first == "w"
        val isErase = first == "e" || first == "es" || first == "ess"
        if (!isWrite && !isErase) return Offer()
        val size = check.gptBytes
        if (size <= 0L) {
            return Offer(
                partition = part,
                backupNote = "Run Identify or printgpt first so the size of $part is known; then it can be backed up automatically."
            )
        }
        val enough = freeBytes < 0L || freeBytes >= size + DumpSpaceGuard.HEADROOM_BYTES
        val backupOk = folderReady && enough
        val backupNote = when {
            !folderReady -> "Choose a dump folder (Settings) to save a backup of $part first."
            !enough -> "Not enough free space to back up $part (${fmt(size)} needed)."
            else -> "Saves the current ${fmt(size)} of $part to backups/ in your dump folder, then continues."
        }
        val verifyOk = isWrite && !check.sparse && check.imageBytes > 0L && folderReady && enough
        val verifyNote = when {
            !isWrite -> ""
            check.sparse -> "Read-back is not available for sparse images."
            check.imageBytes <= 0L -> "Read-back needs a readable image."
            !folderReady -> "Choose a dump folder to read the partition back after writing."
            !enough -> "Not enough free space to read $part back."
            else -> "Reads ${fmt(size)} back after writing and compares its SHA-256 with the image."
        }
        return Offer(
            partition = part,
            partitionBytes = size,
            backupAvailable = backupOk,
            backupNote = backupNote,
            backupDefault = backupOk,
            verifyAvailable = verifyOk,
            verifyNote = verifyNote,
            verifyDefault = verifyOk && size <= VERIFY_DEFAULT_MAX,
        )
    }

    /** QDL `write <partition> <file>`: the pair (partition, file argument). */
    fun singleWriteQdl(command: String): Pair<String, String>? {
        val t = com.bareflash.app.engine.QdlRules.tokenize(command)
        if (t.firstOrNull() != "write" || t.size < 3) return null
        return t[1] to t[2]
    }

    /** `w <partition> <file>` with exactly one partition: the pair (partition, file argument). */
    fun singleWrite(command: String): Pair<String, String>? {
        val t = DumpSpaceGuard.tokenize(command)
        if (t.firstOrNull() != "w") return null
        val pos = DumpSpaceGuard.positionals(t)
        val name = pos.getOrNull(0)?.takeIf { ',' !in it } ?: return null
        val file = pos.getOrNull(1)?.takeIf { ',' !in it } ?: return null
        return name to file
    }
}
