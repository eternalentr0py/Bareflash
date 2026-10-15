package com.bareflash.app.console

/**
 * Turns the text a failed command printed into one plain sentence and one next step,
 * the way [ConnectionDoctor] does for attach problems. Pure logic; the first rule that
 * matches wins, so the more specific causes come first.
 */
object RunDoctor {

    data class Advice(val friendly: String, val hint: String)

    private class Rule(val needles: List<String>, val advice: Advice)

    private val rules = listOf(
        Rule(
            listOf("no space left", "enospc", "disk full"),
            Advice("The dump folder ran out of space.", "Free some space or pick another folder in Settings, then resume the dump from the Dumps tab.")
        ),
        Rule(
            listOf("libusb_error_access", "access denied", "usb permission"),
            Advice("Android did not give Bareflash access to the USB device.", "Unplug the cable, plug it in again and accept the USB permission dialog.")
        ),
        Rule(
            listOf("da loading failed", "error on loading da", "failed to load da", "couldn't load da", "could not load da", "da_loader", "load da failed"),
            Advice("The Download Agent would not load on this device.", "Try the stock DA or a matching custom DA file in Settings, MediaTek. Some devices also need an auth file.")
        ),
        Rule(
            listOf("write protect", "write-protect", "is protected", "sla is enabled", "daa is enabled", "auth required", "permission denied by"),
            Advice("The device refused the write: it is protected or wants authentication.", "Attach an auth file in Settings, MediaTek, or unlock the bootloader first. Nothing was changed if the write never started.")
        ),
        Rule(
            listOf("incomplete flash read", "failed to dump sector", "short read", "transfer error"),
            Advice("The transfer was cut short, usually by the cable or the port.", "Use a short cable straight into the phone, no hub. A cut-short read can be resumed from the Dumps tab.")
        ),
        Rule(
            listOf("timeout", "timed out"),
            Advice("The device stopped answering.", "Unplug it, power it fully off and connect again. Try another cable or OTG adapter.")
        ),
        Rule(
            listOf("handshake failed", "handshake error", "no handshake"),
            Advice("The handshake with the device did not complete.", "Keep the device fully powered off, then plug it in while holding the BootROM key combination and tap Detect.")
        ),
        Rule(
            listOf("device not found", "no device found", "not found after", "could not find device"),
            Advice("The device was not found after the command.", "Power it off, reconnect it and tap Detect. Many devices drop off USB and return as a new device.")
        ),
    )

    /**
     * [output] is what the run printed. [detached] is true when the device left USB during it,
     * which on its own is enough to point at the cable.
     */
    fun advise(output: String, detached: Boolean): Advice? {
        val low = output.lowercase()
        for (r in rules) if (r.needles.any { it in low }) return r.advice
        if (detached) {
            return Advice(
                "The device disconnected during the run.",
                "Check the cable and the OTG adapter, reconnect, and try again. A cut-short read can be resumed from the Dumps tab."
            )
        }
        return null
    }
}
