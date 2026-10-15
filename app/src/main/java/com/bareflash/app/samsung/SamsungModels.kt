package com.bareflash.app.samsung

import android.net.Uri

enum class SamsungLink(val label: String) {
    USB("USB"), WIRELESS("Wireless")
}

/** A firmware package (.tar / .md5 / .tar.md5) picked by the user. */
data class SamsungPackage(
    val uri: Uri,
    val name: String,
    val size: Long,
    val role: String
) {
    companion object {
        private val ROLE_ORDER = listOf("BL", "AP", "CP", "CSC", "HOME_CSC", "USERDATA", "PKG")

        fun roleOf(fileName: String): String {
            val n = fileName.uppercase()
            return when {
                n.startsWith("BL_") -> "BL"
                n.startsWith("AP_") -> "AP"
                n.startsWith("CP_") -> "CP"
                n.startsWith("HOME_CSC") -> "HOME_CSC"
                n.startsWith("CSC_") -> "CSC"
                n.startsWith("USERDATA") -> "USERDATA"
                else -> "PKG"
            }
        }

        fun rank(role: String): Int = ROLE_ORDER.indexOf(role).let { if (it < 0) ROLE_ORDER.size else it }

        /** Same rule as the original app: only .tar and .md5 are accepted. */
        fun isValidName(fileName: String): Boolean {
            val n = fileName.lowercase()
            return n.endsWith(".tar") || n.endsWith(".md5")
        }
    }
}

data class SamsungPit(val uri: Uri, val name: String)

/** A Samsung device on the USB bus. */
data class SamsungDevice(
    val id: String,
    val vid: Int,
    val pid: Int,
    val product: String,
    val permitted: Boolean,
    val inDownloadMode: Boolean
) {
    val ready: Boolean get() = permitted && inDownloadMode
}

enum class RunPhase { Idle, Preparing, Flashing, Success, Failed, Cancelled }

enum class PlanState { Pending, Active, Done }

data class PlanEntry(
    val index: Int,
    val isPit: Boolean,
    val partition: String,
    val fileName: String,
    val source: String,
    val size: Long,
    val state: PlanState = PlanState.Pending
)

data class RunState(
    val phase: RunPhase = RunPhase.Idle,
    val stage: String = "",
    val model: String = "",
    val deviceIds: List<String> = emptyList(),
    val plan: List<PlanEntry> = emptyList(),
    val overallDone: Long = 0,
    val overallTotal: Long = 0,
    val itemDone: Long = 0,
    val itemTotal: Long = 0,
    val speedBps: Double = 0.0,
    val etaMs: Long = -1,
    val deviceErrors: Map<Int, String> = emptyMap(),
    val message: String = "",
    val explanation: String? = null,
    val startedAt: Long = 0,
    val elapsedMs: Long = 0
) {
    val active: Boolean get() = phase == RunPhase.Preparing || phase == RunPhase.Flashing
    val fraction: Float
        get() = if (overallTotal > 0) (overallDone.toDouble() / overallTotal).coerceIn(0.0, 1.0).toFloat() else 0f
    val activeItem: PlanEntry? get() = plan.firstOrNull { it.state == PlanState.Active }
}

data class SamsungLogLine(val level: Int, val text: String)

/** Maps native failure text to the plain-language advice the original app gives. */
object SamsungErrors {
    fun explain(message: String): String? {
        val m = message.lowercase()
        return when {
            "md5 mismatch" in m || "md5 trailer" in m || "tar:" in m || "tararchive" in m || "lz4" in m ->
                "One or more files appear to be corrupt. Get a clean download from a legit source."
            "bootloader error" in m || "operation failed" in m ->
                "The device's bootloader denied the binary. Possible reasons: flashing the wrong firmware, " +
                    "an older bootloader revision than the one installed (binary downgrade), or a custom " +
                    "binary on a locked bootloader."
            "handshake" in m ->
                "Could not establish a connection with the device. Restart back into download mode, " +
                    "try another cable, or try another host device."
            "not connected" in m || "send failed" in m || "receive failed" in m || "no_device" in m ||
                "disconnected" in m || "peer closed" in m ->
                "The device was disconnected. Restart download mode, try another USB cable, and don't touch " +
                    "the device while it flashes."
            "pit" in m && ("mismatch" in m || "differs" in m) ->
                "The connected devices don't share the same partition table, so they can't be flashed together. " +
                    "Flash them one at a time."
            else -> null
        }
    }
}
