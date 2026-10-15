package com.bareflash.app.console

import android.os.ParcelFileDescriptor
import com.bareflash.app.dump.DumpBridge
import com.bareflash.app.dump.DumpIntegrity
import com.bareflash.app.engine.QdlRules
import java.io.File
import java.io.FileInputStream

/**
 * Pre-flight look at a destructive command: how big is the image being sent,
 * how big is the partition it targets (MTK has a live GPT; QDL does not), and
 * does this command deserve more than a tap on Run. Pure facts, no device I/O.
 */
object WriteGuard {

    data class Check(
        /** Partition the command names, when it names exactly one. */
        val partition: String? = null,
        /** Size of the image on disk, or -1 when unknown / not applicable. */
        val imageBytes: Long = -1L,
        val sparse: Boolean = false,
        /** Size a sparse image expands to, or -1 when the image is not sparse. */
        val expandedBytes: Long = -1L,
        /** Partition size from the last printgpt, or -1 when not in the table. */
        val gptBytes: Long = -1L,
        /** Non-null: the command must not run (it would overwrite the next partition). */
        val blocked: String? = null,
        val notes: List<String> = emptyList(),
        /** Non-null: the operator must type this exactly to confirm. */
        val typedPhrase: String? = null,
    )

    private val hardPartitions = setOf("userdata")

    fun evaluate(isQdl: Boolean, command: String, gpt: List<GptPartition>): Check? =
        if (isQdl) evaluateQdl(command) else evaluateMtk(command, gpt)

    // ---- MTK ----------------------------------------------------------

    private fun evaluateMtk(command: String, gpt: List<GptPartition>): Check? {
        val t = DumpSpaceGuard.tokenize(command)
        val first = t.firstOrNull() ?: return null
        val pos = DumpSpaceGuard.positionals(t)
        return when {
            first == "w" -> {
                val rawNames = pos.getOrNull(0)
                val rawFiles = pos.getOrNull(1)
                if (rawNames != null && rawFiles != null && ',' in rawNames) {
                    evaluateMany(rawNames, rawFiles, gpt)
                } else {
                    val name = rawNames?.takeIf { ',' !in it }
                    compare(name, rawFiles, gpt, typed = name?.takeIf { it.lowercase() in hardPartitions })
                }
            }
            first == "e" || first == "es" || first == "ess" -> {
                val name = pos.getOrNull(0)?.takeIf { ',' !in it }
                Check(
                    partition = name,
                    gptBytes = name?.let { n -> gpt.firstOrNull { it.name.equals(n, true) }?.length } ?: -1L,
                    typedPhrase = name?.takeIf { it.lowercase() in hardPartitions }
                )
            }
            first == "wf" || first == "wl" -> {
                val file = if (first == "wf") pos.getOrNull(0) else null
                Check(
                    imageBytes = file?.let(::sizeOf) ?: -1L,
                    notes = listOf("This writes more than one partition. Check the source is the right firmware."),
                    typedPhrase = first
                )
            }
            first == "da" && t.getOrNull(1) == "rpmb" && t.getOrNull(2) in setOf("w", "e") ->
                Check(typedPhrase = "da rpmb ${t[2]}")
            first == "da" && t.getOrNull(1) == "seccfg" -> Check(typedPhrase = "da seccfg")
            else -> null
        }
    }

    /** `w a,b,c x,y,z`: every image is compared with its own partition; one that does not fit blocks the whole write. */
    private fun evaluateMany(rawNames: String, rawFiles: String, gpt: List<GptPartition>): Check {
        val names = rawNames.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val files = rawFiles.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (names.size != files.size) {
            return Check(blocked = "${names.size} partitions but ${files.size} files were given. Each partition needs exactly one file.")
        }
        var blocked: String? = null
        var total = 0L
        val notes = mutableListOf<String>()
        var hard: String? = null
        for (i in names.indices) {
            val c = compare(names[i], files[i], gpt, typed = null)
            if (blocked == null && c.blocked != null) blocked = c.blocked
            if (c.imageBytes > 0L) total += c.imageBytes
            c.notes.forEach { notes += "${names[i]}: $it" }
            if (hard == null && names[i].lowercase() in hardPartitions) hard = names[i]
        }
        notes += "This writes ${names.size} partitions: ${names.joinToString(", ")}."
        return Check(imageBytes = total, blocked = blocked, notes = notes, typedPhrase = hard ?: "w")
    }

    private fun compare(name: String?, file: String?, gpt: List<GptPartition>, typed: String?): Check {
        val image = file?.let(::sizeOf) ?: -1L
        val expanded = file?.let(::sparseExpanded)
        val sparse = expanded != null
        val effective = expanded ?: image
        val part = name?.let { n -> gpt.firstOrNull { it.name.equals(n, true) } }
        val gptBytes = part?.length ?: -1L
        val notes = mutableListOf<String>()
        var blocked: String? = null
        when {
            name == null -> Unit
            part == null -> notes += "\"$name\" is not in the partition table loaded from the device, so the size was not compared. Run Identify or printgpt first."
            effective < 0 -> notes += "The image size could not be read."
            effective > gptBytes -> blocked =
                "The image ${if (sparse) "expands to" else "is"} ${effective} bytes but ${part.name} is only ${gptBytes} bytes. " +
                    "Writing it would overwrite the next partition."
            sparse -> notes += "Sparse image: expands to $effective bytes, which fits ${part.name}."
            effective * 2 < gptBytes -> notes += "The image is much smaller than the partition ($image of $gptBytes bytes)."
        }
        return Check(name, image, sparse, expanded ?: -1L, gptBytes, blocked, notes, typed)
    }

    // ---- QDL ----------------------------------------------------------

    private fun evaluateQdl(command: String): Check? {
        val t = QdlRules.tokenize(command)
        val verb = t.firstOrNull() ?: return null
        val a = t.drop(1)
        return when (verb) {
            "write" -> {
                val target = a.getOrNull(0)
                val file = a.getOrNull(1)
                val expanded = file?.let(::sparseExpanded)
                val sparse = expanded != null
                Check(
                    partition = target,
                    imageBytes = file?.let(::sizeOf) ?: -1L,
                    sparse = sparse,
                    expandedBytes = expanded ?: -1L,
                    notes = buildList {
                        add("QDL has no partition table to compare against. Check the name and the size yourself.")
                        if (sparse) add("Sparse image: expands to $expanded bytes. Check that it fits the partition.")
                    },
                    typedPhrase = target?.takeIf { it.lowercase().removeSuffix("_a").removeSuffix("_b") in hardPartitions }
                )
            }
            "erase" -> a.getOrNull(0)?.let { target ->
                Check(
                    partition = target,
                    typedPhrase = target.takeIf { it.lowercase().removeSuffix("_a").removeSuffix("_b") in hardPartitions }
                )
            }
            "flash", "xml" -> Check(
                notes = listOf("This writes every partition the package names."),
                typedPhrase = verb
            )
            else -> null
        }
    }

    // ---- files ----------------------------------------------------------

    /** Size of an image path as it appears in a command (`dumps/x`, bare name, or absolute). */
    private fun sizeOf(p: String): Long = when {
        p.startsWith("/") -> File(p).takeIf { it.isFile }?.length() ?: -1L
        else -> DumpBridge.size(p.removePrefix("dumps/"))
    }

    /** Size a sparse image expands to, or null when [p] is not a sparse image (or cannot be read). */
    private fun sparseExpanded(p: String): Long? = runCatching {
        val head = ByteArray(28)
        val n = if (p.startsWith("/")) {
            FileInputStream(p).use { it.read(head) }
        } else {
            val fd = DumpBridge.openFd(p.removePrefix("dumps/"), "r")
            if (fd < 0) return null
            ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(fd)).use { it.read(head) }
        }
        DumpIntegrity.sparseExpandedBytes(head, n)
    }.getOrNull()
}
