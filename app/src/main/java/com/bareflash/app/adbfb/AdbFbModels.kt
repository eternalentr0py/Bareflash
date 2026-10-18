package com.bareflash.app.adbfb

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
