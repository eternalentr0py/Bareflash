package com.bareflash.app.live

/** What a live notification is about. Drives the verb, the icon and the accent. */
enum class LiveKind { Read, Write, Erase, Flash, Verify, Backup, Export, Test, Other }

/** Where a run is in its life. */
enum class LiveStatus { Starting, Running, Waiting, Between, Done, Failed, Stopped }

/** Whether the system actually promoted the last live notification (Android 16 chip, lock screen card). */
enum class PromotionCheck { Unknown, Promoted, NotPromoted }

/** How a run ended. */
enum class LiveOutcome { Success, Failure, Cancelled }

/** Which engine runs it; only used for the label and the accent. */
enum class LiveEngine(val label: String, val color: Int) {
    Mtk("MediaTek", 0xFF5AA9FF.toInt()),
    Qdl("Qualcomm", 0xFFFF8A3D.toInt()),
    Samsung("Samsung", 0xFF7BD88F.toInt()),
    Adb("ADB / Fastboot", 0xFFB48CFF.toInt()),
    App("Bareflash", 0xFF5AA9FF.toInt()),
}

/**
 * Everything one live notification shows. The hub owns one of these per
 * running job and renders it; nothing in here touches USB or the engines.
 */
data class LiveSnapshot(
    val id: Int,
    val kind: LiveKind,
    val engine: LiveEngine,
    /** Short catalog title, e.g. "Read partitions". */
    val title: String,
    /** Partition / file the job is about, e.g. "userdata". May be blank. */
    val target: String = "",
    /** What the engine reports it is doing right now (file or partition). */
    val stage: String = "",
    /** Chip or phone, e.g. "MT6765 · Preloader". May be blank. */
    val device: String = "",
    val status: LiveStatus = LiveStatus.Starting,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val speedBps: Double = 0.0,
    val etaMs: Long = -1L,
    /** Wall-clock start, for the chronometer. */
    val startedAtWallMs: Long = System.currentTimeMillis(),
    /** "Step 2 of 4" for queued jobs; 0 when not queued. */
    val queueIndex: Int = 0,
    val queueTotal: Int = 0,
    /** Free text for Waiting / Between ("Reconnecting, resume 1 of 3"). */
    val note: String = "",
) {
    val measurable: Boolean get() = bytesTotal > 0L
    val fraction: Float
        get() = if (bytesTotal > 0L) (bytesDone.toDouble() / bytesTotal.toDouble()).coerceIn(0.0, 1.0).toFloat() else 0f
    val percent: Int get() = (fraction * 100f).toInt().coerceIn(0, 100)
    val needsService: Boolean get() = kind != LiveKind.Export
}

/** What the result notification says after a job ends. */
data class LiveResult(
    val kind: LiveKind,
    val engine: LiveEngine,
    val outcome: LiveOutcome,
    val title: String,
    val target: String,
    val elapsedMs: Long,
    val bytes: Long,
    val avgBps: Double,
    /** One plain line: what went wrong, or what the result was. */
    val detail: String = "",
    /** A next step, shown under a failure. */
    val hint: String = "",
    /** True when the files are in the dump folder, so "Open dumps" is useful. */
    val hasDumps: Boolean = false,
)
