package com.bareflash.usb

/**
 * USB interface descriptors that identify Android's debug protocols. Matching
 * on these instead of vendor IDs finds ADB and fastboot on any manufacturer's
 * device. Values come from AOSP (adb/ and fastboot/ USB code).
 */
public object UsbInterfaceIds {
    public const val CLASS_VENDOR_SPECIFIC: Int = 0xFF
    public const val SUBCLASS_ANDROID: Int = 0x42
    public const val PROTOCOL_ADB: Int = 0x01
    public const val PROTOCOL_FASTBOOT: Int = 0x03

    public fun isAdb(interfaceClass: Int, subclass: Int, protocol: Int): Boolean =
        interfaceClass == CLASS_VENDOR_SPECIFIC &&
            subclass == SUBCLASS_ANDROID &&
            protocol == PROTOCOL_ADB

    public fun isFastboot(interfaceClass: Int, subclass: Int, protocol: Int): Boolean =
        interfaceClass == CLASS_VENDOR_SPECIFIC &&
            subclass == SUBCLASS_ANDROID &&
            protocol == PROTOCOL_FASTBOOT
}
