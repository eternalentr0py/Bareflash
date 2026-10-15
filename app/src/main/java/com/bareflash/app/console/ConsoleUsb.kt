package com.bareflash.app.console

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * USB attach/detach, permission requests and MediaTek device listing.
 * [ConsoleViewModel] owns the resulting status; this class only talks to
 * UsbManager and reports events through [Host].
 */
internal class ConsoleUsb(
    private val ctx: Context,
    private val host: Host
) {
    interface Host {
        fun log(msg: String)
        fun onPermissionGranted()
        fun onPermissionDenied()
        fun onAttached()
        fun onDetached()
    }

    companion object {
        private const val MTK_VID = 0x0E8D
        private const val ACTION_USB_PERMISSION = "com.bareflash.app.USB_PERMISSION"
    }

    private val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    fun attachedDevices(): Collection<UsbDevice> = usb.deviceList.values

    /** Vendor IDs the active engine talks to (MTK by default; QDL swaps in Qualcomm). */
    @Volatile var vendorIds: Set<Int> = setOf(MTK_VID)

    /** Devices matching the active engine. Name kept from the MTK-only days. */
    fun mtkDevices(): List<UsbDevice> = usb.deviceList.values.filter { it.vendorId in vendorIds }

    fun hasPermission(device: UsbDevice): Boolean = usb.hasPermission(device)

    /**
     * Logs every attached device (and its bulk endpoints) and requests USB
     * access for MediaTek ones that do not already have it.
     */
    fun logAndRequest(devices: Collection<UsbDevice>) {
        for (d in devices) {
            host.log("Found device vid=0x%04x pid=0x%04x name=%s\n".format(d.vendorId, d.productId, d.deviceName))
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                var bulkIn = 0; var bulkOut = 0
                for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn++ else bulkOut++
                    }
                }
                host.log("  iface[$i] class=0x%02x bulkIN=$bulkIn bulkOUT=$bulkOut\n".format(itf.interfaceClass))
            }
            if (d.vendorId !in vendorIds) continue
            if (usb.hasPermission(d)) {
                host.log("USB access already granted for ${d.deviceName}.\n")
            } else {
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                // Explicit package + EXTRA_DEVICE: Android 12+ UsbManager
                // refuses to fill a USB-permission PendingIntent that is
                // implicit, and some hosts drop the grant if the original
                // intent doesn't already identify the device.
                val permissionIntent = Intent(ACTION_USB_PERMISSION).apply {
                    setPackage(ctx.packageName)
                    putExtra(UsbManager.EXTRA_DEVICE, d)
                }
                usb.requestPermission(d, PendingIntent.getBroadcast(ctx, d.deviceId, permissionIntent, flags))
            }
        }
    }

    fun register() {
        val filterIntent = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(usbReceiver, filterIntent, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(usbReceiver, filterIntent)
        }
    }

    fun unregister() {
        ctx.unregisterReceiver(usbReceiver)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        host.log("USB access granted.\n")
                        host.onPermissionGranted()
                    } else {
                        host.log("USB access was denied. The device cannot be used until access is granted.\n")
                        host.onPermissionDenied()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    host.log("USB device connected.\n")
                    host.onAttached()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    host.log("USB device disconnected.\n")
                    // Do NOT call UsbBridge.close() here. This broadcast fires on
                    // the main thread the instant Android sees the device drop
                    // off the bus -- including the routine mid-command
                    // re-enumerations that are a normal part of the DA protocol
                    // (and, on some devices, brief unrequested blips while a
                    // command is mid-transfer). If a command is currently
                    // running, its worker thread can be blocked inside
                    // conn.bulkTransfer()/UsbRequest.queue() on the exact
                    // UsbDeviceConnection/endpoints this would null out from
                    // under it -- turning a recoverable blip into a
                    // "genuine-looking" 0-byte short read (and, once
                    // connection/epIn/epOut are null, every subsequent
                    // bulkReadExact/bulkWriteExact call fails instantly without
                    // even attempting the transfer), which then cascades into
                    // "Wrong magic"/garbled-status errors as the DA protocol
                    // desyncs. UsbBridge.connect() already calls close(false)
                    // itself before opening a new connection, and
                    // ConsoleViewModel.beginReconnect() already calls
                    // UsbBridge.close(false) once a command has actually
                    // finished -- so there is nothing this call was doing that
                    // isn't already handled safely, later, by code that knows
                    // whether a command is running.
                    host.onDetached()
                }
            }
        }
    }
}
