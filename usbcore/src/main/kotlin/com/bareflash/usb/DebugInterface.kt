package com.bareflash.usb

/** The two Android debug protocols that share the 0xFF/0x42 interface signature. */
public enum class DebugProtocol { ADB, FASTBOOT }

/**
 * An interface that speaks ADB or fastboot, with the bulk endpoint pair to use.
 * [usbInterface] is the descriptor to claim; when its `alternateSetting` is not
 * 0 the caller must select that alternate setting after claiming.
 */
public class DebugInterface(
    public val protocol: DebugProtocol,
    public val usbInterface: UsbInterfaceInfo,
    public val bulkIn: UsbEndpointInfo,
    public val bulkOut: UsbEndpointInfo,
)

/** Picks ADB and fastboot interfaces out of a device's interface list. */
public object DebugInterfaceFinder {

    /**
     * Returns every usable ADB or fastboot interface, ordered by interface id.
     *
     * An interface qualifies when its class, subclass and protocol match
     * [UsbInterfaceIds] and it has at least one bulk IN and one bulk OUT
     * endpoint (the first of each is used). If one interface id offers
     * several alternate settings, the lowest qualifying one wins. Matching is
     * done on the interface, never the vendor id, so it works for any
     * manufacturer.
     */
    public fun find(interfaces: List<UsbInterfaceInfo>): List<DebugInterface> {
        val found = ArrayList<DebugInterface>()
        val claimed = HashSet<Int>()
        val ordered = interfaces.sortedWith(compareBy({ it.id }, { it.alternateSetting }))
        for (itf in ordered) {
            val protocol = protocolOf(itf) ?: continue
            val bulkIn = itf.endpoints.firstOrNull { it.type == UsbEndpointType.BULK && it.direction == UsbDirection.IN }
                ?: continue
            val bulkOut = itf.endpoints.firstOrNull { it.type == UsbEndpointType.BULK && it.direction == UsbDirection.OUT }
                ?: continue
            if (!claimed.add(itf.id)) continue
            found += DebugInterface(protocol, itf, bulkIn, bulkOut)
        }
        return found
    }

    /** The first interface speaking [protocol], or null. */
    public fun find(interfaces: List<UsbInterfaceInfo>, protocol: DebugProtocol): DebugInterface? =
        find(interfaces).firstOrNull { it.protocol == protocol }

    private fun protocolOf(itf: UsbInterfaceInfo): DebugProtocol? = when {
        UsbInterfaceIds.isAdb(itf.interfaceClass, itf.interfaceSubclass, itf.interfaceProtocol) -> DebugProtocol.ADB
        UsbInterfaceIds.isFastboot(itf.interfaceClass, itf.interfaceSubclass, itf.interfaceProtocol) -> DebugProtocol.FASTBOOT
        else -> null
    }
}
