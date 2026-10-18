package com.bareflash.app.adbfb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.bareflash.usb.BulkBackend
import com.bareflash.usb.BulkTransport
import com.bareflash.usb.BulkTransportConfig
import com.bareflash.usb.DebugInterface
import com.bareflash.usb.DebugInterfaceFinder
import com.bareflash.usb.DebugProtocol
import com.bareflash.usb.UsbDirection
import com.bareflash.usb.UsbEndpointInfo
import com.bareflash.usb.UsbEndpointType
import com.bareflash.usb.UsbInterfaceInfo
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** The device could not be opened or its interface could not be claimed. */
internal class AdbFbOpenException(message: String) : IOException(message)

/**
 * Android glue for ADB and fastboot over USB OTG. All interface selection logic
 * lives in :usbcore ([DebugInterfaceFinder]); this object only converts
 * between Android's USB classes and those plain descriptors and opens the
 * connection. Nothing here touches the network.
 */
internal object AdbFbUsb {

    /** Copies a device's interface descriptors into plain objects. */
    fun interfacesOf(device: UsbDevice): List<UsbInterfaceInfo> =
        (0 until device.interfaceCount).map { index ->
            val itf = device.getInterface(index)
            UsbInterfaceInfo(
                id = itf.id,
                alternateSetting = itf.alternateSetting,
                interfaceClass = itf.interfaceClass,
                interfaceSubclass = itf.interfaceSubclass,
                interfaceProtocol = itf.interfaceProtocol,
                endpoints = (0 until itf.endpointCount).map { e -> endpointInfo(itf.getEndpoint(e)) },
            )
        }

    private fun endpointInfo(ep: UsbEndpoint) = UsbEndpointInfo(
        address = ep.address,
        direction = if (ep.direction == UsbConstants.USB_DIR_IN) UsbDirection.IN else UsbDirection.OUT,
        type = when (ep.type) {
            UsbConstants.USB_ENDPOINT_XFER_CONTROL -> UsbEndpointType.CONTROL
            UsbConstants.USB_ENDPOINT_XFER_ISOC -> UsbEndpointType.ISOCHRONOUS
            UsbConstants.USB_ENDPOINT_XFER_BULK -> UsbEndpointType.BULK
            else -> UsbEndpointType.INTERRUPT
        },
        maxPacketSize = ep.maxPacketSize,
    )

    /** ADB and fastboot interfaces this device offers (usually zero or one). */
    fun debugInterfaces(device: UsbDevice): List<DebugInterface> =
        DebugInterfaceFinder.find(interfacesOf(device))

    /** True if the device exposes an ADB or fastboot interface, whatever its vendor. */
    fun isDebugDevice(device: UsbDevice): Boolean = debugInterfaces(device).isNotEmpty()

    /** Attached devices that expose an ADB or fastboot interface. */
    fun scan(usb: UsbManager): List<UsbDevice> =
        usb.deviceList.values.filter { isDebugDevice(it) }.sortedBy { it.deviceName }

    /**
     * Opens [device] and claims its interface for [protocol]. Needs USB
     * permission already granted. The returned connection must be closed.
     */
    fun open(
        usb: UsbManager,
        device: UsbDevice,
        protocol: DebugProtocol,
        config: BulkTransportConfig = BulkTransportConfig(),
    ): AdbFbConnection {
        val wanted = DebugInterfaceFinder.find(interfacesOf(device), protocol)
            ?: throw AdbFbOpenException("The device has no $protocol interface.")
        val itf = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.id == wanted.usbInterface.id && it.alternateSetting == wanted.usbInterface.alternateSetting }
            ?: throw AdbFbOpenException("The $protocol interface disappeared while opening.")
        val epIn = (0 until itf.endpointCount).map { itf.getEndpoint(it) }
            .firstOrNull { it.address == wanted.bulkIn.address }
            ?: throw AdbFbOpenException("Bulk IN endpoint not found.")
        val epOut = (0 until itf.endpointCount).map { itf.getEndpoint(it) }
            .firstOrNull { it.address == wanted.bulkOut.address }
            ?: throw AdbFbOpenException("Bulk OUT endpoint not found.")

        if (!usb.hasPermission(device)) throw AdbFbOpenException("USB access has not been granted for this device.")
        val connection = usb.openDevice(device)
            ?: throw AdbFbOpenException("The system refused to open the device (is it still connected?).")
        try {
            // force = true detaches a kernel driver that holds the interface.
            if (!connection.claimInterface(itf, true)) {
                throw AdbFbOpenException("Could not claim the $protocol interface. Another app may be using it.")
            }
            if (itf.alternateSetting != 0 && !connection.setInterface(itf)) {
                connection.releaseInterface(itf)
                throw AdbFbOpenException("Could not select alternate setting ${itf.alternateSetting}.")
            }
        } catch (t: Throwable) {
            connection.close()
            throw t
        }
        val detached = AtomicBoolean(false)
        val backend = AndroidBulkBackend(usb, device, connection, itf, epIn, epOut, detached)
        return AdbFbConnection(device, protocol, BulkTransport(backend, config), detached)
    }
}

/** An open, claimed ADB or fastboot interface. */
internal class AdbFbConnection(
    val device: UsbDevice,
    val protocol: DebugProtocol,
    val transport: BulkTransport,
    private val detached: AtomicBoolean,
) : AutoCloseable {

    /** Called when Android reports the device was unplugged, so failures read as a disconnect. */
    fun markDetached() {
        detached.set(true)
    }

    override fun close() {
        transport.close()
    }
}

/** [BulkBackend] over Android's [UsbDeviceConnection]. No logic beyond forwarding. */
private class AndroidBulkBackend(
    private val usb: UsbManager,
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    private val detached: AtomicBoolean,
) : BulkBackend {

    override val maxPacketSizeOut: Int = epOut.maxPacketSize

    override fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
        connection.bulkTransfer(epOut, buffer, offset, length, timeoutMs)

    override fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
        connection.bulkTransfer(epIn, buffer, offset, length, timeoutMs)

    override fun isDeviceAttached(): Boolean =
        !detached.get() && usb.deviceList.containsKey(device.deviceName)

    override fun close() {
        runCatching { connection.releaseInterface(usbInterface) }
        connection.close()
    }
}
