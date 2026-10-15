package com.bareflash.app.engine

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager

/**
 * Runs one qdl invocation. Android never lets an app open /dev/bus/usb
 * itself, so the Qualcomm device is opened through UsbManager and the
 * resulting file descriptor is handed to native code, which wraps it with
 * libusb_wrap_sys_device() (same technique the MTK engine already uses).
 */
internal class QdlEngine(private val ctx: Context) {

    private val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    @Volatile private var conn: UsbDeviceConnection? = null

    fun qdlDevice(): UsbDevice? =
        usb.deviceList.values.firstOrNull { it.vendorId in EngineMode.QDL.vendorIds }

    /** @return qdl's exit code, or a negative app-level code (see [Codes]). */
    fun run(plan: QdlRules.Plan.Run, onLine: (String) -> Unit): Int {
        if (!QdlNative.loaded) {
            onLine("The QDL native library could not be loaded: ${QdlNative.loadError}\n")
            return Codes.NO_LIBRARY
        }
        if (!QdlNative.ready) {
            onLine("The Qualcomm engine is missing from this build.\n")
            return Codes.NOT_EMBEDDED
        }
        var fd = -1
        if (plan.needsDevice) {
            val dev = qdlDevice()
            if (dev == null) {
                onLine("No Qualcomm USB device found. Put the device in EDL (9008) mode and press Detect.\n")
                return Codes.NO_DEVICE
            }
            if (!usb.hasPermission(dev)) {
                onLine("USB permission has not been granted for ${dev.deviceName}. Press Detect and accept the prompt.\n")
                return Codes.NO_PERMISSION
            }
            val c = usb.openDevice(dev)
            if (c == null) {
                onLine("Android refused to open ${dev.deviceName}.\n")
                return Codes.OPEN_FAILED
            }
            conn = c
            fd = c.fileDescriptor
        }
        return try {
            QdlNative.nativeRun(fd, plan.argv.toTypedArray(), QdlNative.Sink { line -> onLine(line) })
        } finally {
            runCatching { conn?.close() }
            conn = null
        }
    }

    /** Kills the qdl child process; the run loop then returns [Codes.CANCELLED]. */
    fun cancel() {
        if (QdlNative.loaded) runCatching { QdlNative.nativeCancel() }
    }

    /** Human-readable meaning of a non-zero result from [run]. */
    fun describe(rc: Int): String = when (rc) {
        Codes.FORK_FAILED -> "Could not start the qdl process."
        Codes.PIPE_FAILED -> "Could not create the output pipe for qdl."
        Codes.CANCELLED -> "Cancelled."
        Codes.CRASHED -> "qdl crashed (killed by a signal). Check the log above."
        Codes.NO_LIBRARY, Codes.NOT_EMBEDDED -> "The Qualcomm engine is not available in this build."
        Codes.NO_DEVICE -> "No Qualcomm device in EDL mode was found."
        Codes.NO_PERMISSION -> "USB permission was not granted."
        Codes.OPEN_FAILED -> "Android refused to open the USB device."
        Codes.JNI_FAILED, Codes.OOM -> "Internal error starting qdl."
        else -> "qdl exited with code $rc."
    }

    object Codes {
        // from the native runner (android/qdl_runner.h)
        const val FORK_FAILED = -1
        const val PIPE_FAILED = -2
        const val CANCELLED = -3
        const val CRASHED = -4
        const val JNI_FAILED = -110
        const val OOM = -111
        // app level
        const val NO_LIBRARY = -100
        const val NOT_EMBEDDED = -101
        const val NO_DEVICE = -102
        const val NO_PERMISSION = -103
        const val OPEN_FAILED = -104
    }
}
