package com.bareflash.app.engine

/**
 * The device engines the app can drive. Everything mode-specific
 * (catalog, rules, USB vendor filter, runner, tab set) hangs off this enum
 * so the rest of the app never branches on chipset names.
 */
enum class EngineMode(
    val id: String,
    val label: String,
    val short: String,
    val subtitle: String,
    val vendorIds: Set<Int>,
    val connectHint: String
) {
    MTK(
        "mtk", "MediaTek", "MEDIATEK",
        "MediaTek \u00b7 BROM / Preloader / DA",
        setOf(0x0E8D),
        "Power the device fully off, then connect via OTG (hold Volume Up/Down for BootROM)."
    ),
    QDL(
        "qdl", "Qualcomm", "QUALCOMM",
        "Qualcomm \u00b7 EDL / Sahara / Firehose",
        setOf(0x05C6),
        "Boot the device into EDL (9008) mode, then connect via OTG."
    ),
    /**
     * Samsung download mode (Odin protocol). It has no command console, so its
     * vendor filter is empty on purpose: the console's USB handling must not
     * touch Samsung devices. [com.bareflash.app.samsung.SamsungViewModel]
     * owns USB for this mode.
     */
    BROKKR(
        "brokkr", "Samsung", "SAMSUNG",
        "Samsung \u00b7 Odin / Download mode",
        emptySet(),
        "Hold Volume Down + Volume Up while plugging in the cable, then confirm download mode."
    ),
    /**
     * ADB and fastboot over USB OTG. Devices are recognised by USB interface
     * (class 0xFF, subclass 0x42), not by vendor, so any manufacturer works and
     * the vendor filter is empty on purpose, as for Samsung: the MediaTek/Qualcomm
     * console must not touch these devices.
     * [com.bareflash.app.adbfb.AdbFbViewModel] owns USB for this mode.
     */
    ADB_FASTBOOT(
        "adbfb", "ADB", "ADB/FASTBOOT",
        "ADB · Fastboot · Android debug protocols",
        emptySet(),
        "Turn on USB debugging on the other device, or boot it into fastboot mode, then connect via OTG."
    );

    companion object {
        fun fromId(id: String?): EngineMode = entries.firstOrNull { it.id == id } ?: MTK
    }
}
