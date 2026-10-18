package com.bareflash.usb

/** Direction of an endpoint as seen from the host. */
public enum class UsbDirection { IN, OUT }

/** USB transfer type of an endpoint. */
public enum class UsbEndpointType { CONTROL, ISOCHRONOUS, BULK, INTERRUPT }

/**
 * A plain copy of the descriptor fields the protocol layers care about.
 *
 * The Android app converts `UsbEndpoint` / `UsbInterface` into these so that
 * interface selection can be tested on a plain JVM.
 */
public class UsbEndpointInfo(
    public val address: Int,
    public val direction: UsbDirection,
    public val type: UsbEndpointType,
    public val maxPacketSize: Int,
) {
    override fun toString(): String =
        "ep 0x%02x %s %s mps=%d".format(address, direction, type, maxPacketSize)
}

/** One interface (alternate setting) of a USB device. */
public class UsbInterfaceInfo(
    public val id: Int,
    public val alternateSetting: Int,
    public val interfaceClass: Int,
    public val interfaceSubclass: Int,
    public val interfaceProtocol: Int,
    public val endpoints: List<UsbEndpointInfo>,
) {
    override fun toString(): String =
        "iface %d alt %d class 0x%02x/0x%02x/0x%02x, %d endpoint(s)".format(
            id, alternateSetting, interfaceClass, interfaceSubclass, interfaceProtocol, endpoints.size,
        )
}
