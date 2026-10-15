package com.bareflash.app.console

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.bareflash.app.UsbBridge

/**
 * Facts about the host phone and the link worth knowing before a write. The host
 * powers the target over OTG, so a flat host mid-write leaves a half-written partition.
 */
object Preflight {
    enum class Level { Info, Warn, Stop }

    data class Finding(val level: Level, val text: String)

    data class Host(val percent: Int, val charging: Boolean, val tempC: Float)

    fun host(ctx: Context): Host? {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = plugged || status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        return Host((level * 100f / scale).toInt(), charging, temp)
    }

    /** Findings shown in the write confirmation. A [Level.Stop] needs an explicit "run anyway". */
    fun forWrite(ctx: Context, deviceAttached: Boolean, showLink: Boolean): List<Finding> {
        val out = ArrayList<Finding>()
        val h = host(ctx)
        if (h != null) {
            val state = if (h.charging) "charging" else "not charging"
            when {
                h.percent < 10 && !h.charging -> out += Finding(
                    Level.Stop,
                    "This phone's battery is ${h.percent}% and not charging. If it dies mid-write the partition is left half-written."
                )
                h.percent < 25 && !h.charging -> out += Finding(
                    Level.Warn,
                    "This phone's battery is ${h.percent}% and not charging. Charge it before a long write."
                )
                else -> out += Finding(Level.Info, "Host battery ${h.percent}%, $state.")
            }
            if (h.tempC >= 43f) out += Finding(
                Level.Warn,
                "This phone is hot (%.0f\u00b0C). Let it cool first; a thermal shutdown mid-write is destructive.".format(h.tempC)
            )
        }
        if (!deviceAttached) out += Finding(Level.Warn, "No device is detected right now. Run Detect before writing.")
        if (showLink) {
            val link = UsbBridge.lastLinkName
            if (link != null) {
                if (UsbBridge.lastLinkSlow) {
                    out += Finding(Level.Warn, "USB link: $link. This is USB 1.1 speed; try another OTG adapter or cable, no hubs.")
                } else {
                    out += Finding(Level.Info, "USB link: $link.")
                }
            }
        }
        return out
    }

    /** A one-line heads-up for the terminal when a long read starts on a low battery, else null. */
    fun lowBatteryNote(ctx: Context): String? {
        val h = host(ctx) ?: return null
        if (h.charging || h.percent >= 15) return null
        return "This phone's battery is ${h.percent}% and not charging. If it dies the dump stops, but it can be resumed from the Dumps tab."
    }
}
