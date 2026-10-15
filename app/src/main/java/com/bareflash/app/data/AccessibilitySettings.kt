package com.bareflash.app.data

import org.json.JSONObject

/**
 * Accessibility preferences for TalkBack and low-vision use.
 * Stored separately so backups and upgrades stay stable.
 */
data class AccessibilitySettings(
    /** Force high scheme contrast regardless of Appearance. */
    val highContrast: Boolean = false,
    /** Larger type and touch targets app-wide. */
    val largeControls: Boolean = false,
    /** Force motion off (in addition to system animator scale). */
    val reduceMotion: Boolean = false,
    /** Announce when a flash, dump, or erase starts and ends. */
    val announceJobs: Boolean = true,
    /** Announce progress milestones (25 / 50 / 75 / 100). */
    val announceProgress: Boolean = true,
    /** Announce USB connect and disconnect. */
    val announceConnections: Boolean = true,
    /** Prefer longer content descriptions where both short and long exist. */
    val detailedLabels: Boolean = true,
)

internal fun AccessibilitySettings.toJson(): JSONObject = JSONObject().apply {
    put("highContrast", highContrast)
    put("largeControls", largeControls)
    put("reduceMotion", reduceMotion)
    put("announceJobs", announceJobs)
    put("announceProgress", announceProgress)
    put("announceConnections", announceConnections)
    put("detailedLabels", detailedLabels)
}

internal fun accessibilityFromJson(o: JSONObject?, cur: AccessibilitySettings): AccessibilitySettings {
    if (o == null) return cur
    return AccessibilitySettings(
        highContrast = o.optBoolean("highContrast", cur.highContrast),
        largeControls = o.optBoolean("largeControls", cur.largeControls),
        reduceMotion = o.optBoolean("reduceMotion", cur.reduceMotion),
        announceJobs = o.optBoolean("announceJobs", cur.announceJobs),
        announceProgress = o.optBoolean("announceProgress", cur.announceProgress),
        announceConnections = o.optBoolean("announceConnections", cur.announceConnections),
        detailedLabels = o.optBoolean("detailedLabels", cur.detailedLabels),
    )
}
