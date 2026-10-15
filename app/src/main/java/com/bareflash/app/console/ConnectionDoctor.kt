package com.bareflash.app.console

import com.bareflash.app.engine.EngineMode

/**
 * Turns "what is on the USB bus" into one actionable sentence per case,
 * instead of a bare "No USB devices found". Pure logic.
 */
object ConnectionDoctor {

    data class Seen(val vid: Int, val pid: Int, val permitted: Boolean)

    private const val EDL_PID = 0x9008

    private fun id(s: Seen) = "%04x:%04x".format(s.vid, s.pid)

    fun lines(seen: List<Seen>, engine: EngineMode): List<String> {
        if (seen.isEmpty()) {
            return listOf("No device on the bus.")
        }
        val mine = seen.filter { it.vid in engine.vendorIds }
        if (mine.isEmpty()) {
            return seen.map { "Found ${id(it)}. Not a ${engine.label} device." }
        }
        return mine.flatMap { d ->
            when {
                !d.permitted -> listOf("${id(d)} needs USB permission. Tap Detect again if the dialog was dismissed.")
                engine == EngineMode.QDL && d.pid != EDL_PID -> listOf("${id(d)} is not in EDL (9008).")
                else -> emptyList()
            }
        }
    }
}
