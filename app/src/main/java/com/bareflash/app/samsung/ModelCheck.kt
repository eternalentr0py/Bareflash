package com.bareflash.app.samsung

/**
 * Compares the model a phone reports in download mode with the model coded into the firmware
 * file names. Samsung builds are named like `AP_A107FXXU5BUB1_...tar.md5`: the leading letters
 * and digits (`A107F`) are the model. A mismatch is the classic way to flash the wrong firmware.
 *
 * Heuristic: only names that carry a recognisable build code are compared, and a package is
 * compatible when either code is a prefix of the other (A107F vs A107FD).
 */
object ModelCheck {
    private val buildCode = Regex("^([A-Z]\\d{3,4}[A-Z]?\\d?)[A-Z0-9]{5,}$")

    /** "SM-A107F/DS" -> "A107F". */
    fun deviceCode(model: String): String? {
        val m = model.trim().uppercase().substringBefore('/').removePrefix("SM-").removePrefix("SM_")
        return Regex("^[A-Z]\\d{3,4}[A-Z]?\\d?").find(m)?.value
    }

    private fun packageCodes(fileName: String): Set<String> =
        fileName.uppercase().split('_', '-', '.', ' ')
            .mapNotNull { tok -> buildCode.find(tok)?.groupValues?.get(1) }
            .toSet()

    /**
     * A user-facing message when every package that names a model names a different one than
     * [deviceModel], else null (no mismatch, or nothing to compare).
     */
    fun mismatch(deviceModel: String, packageNames: List<String>): String? {
        val dev = deviceCode(deviceModel) ?: return null
        val codes = packageNames.flatMap { packageCodes(it) }.toSet()
        if (codes.isEmpty()) return null
        if (codes.any { it.startsWith(dev) || dev.startsWith(it) }) return null
        return "This firmware is built for ${codes.sorted().joinToString("/")}, but the phone reports $deviceModel. " +
            "Flashing another model's firmware can brick it, so the flash was stopped before anything was written."
    }
}
