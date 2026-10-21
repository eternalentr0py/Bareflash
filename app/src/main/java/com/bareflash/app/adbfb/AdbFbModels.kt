package com.bareflash.app.adbfb

import com.bareflash.cli.DeviceCommand
import com.bareflash.cli.Risk
import com.bareflash.usb.DebugProtocol

/** One attached device that offers ADB and/or fastboot. */
class AdbFbDevice(
    /** Android's name for the device, unique while it stays attached. */
    val name: String,
    val vid: Int,
    val pid: Int,
    val title: String,
    val protocols: List<DebugProtocol>,
    val permitted: Boolean,
) {
    val hasFastboot: Boolean get() = DebugProtocol.FASTBOOT in protocols
    val hasAdb: Boolean get() = DebugProtocol.ADB in protocols
}

enum class AdbFbLinkState { IDLE, CONNECTING, CONNECTED, FAILED }

/** State of the single device connection. [message] is shown to the user as-is. */
class AdbFbLink(
    val state: AdbFbLinkState = AdbFbLinkState.IDLE,
    val deviceName: String? = null,
    val protocol: DebugProtocol? = null,
    val message: String? = null,
)

/** A transfer in progress, for the console's progress bar. [total] below zero means unknown. */
class ConsoleProgress(val label: String, val done: Long, val total: Long) {
    /** 0f..1f, or null when the size is unknown. */
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

/** A command held back until the user confirms it. [risk] says how hard to ask. */
class PendingRun(val line: String, val command: DeviceCommand, val risk: Risk)
