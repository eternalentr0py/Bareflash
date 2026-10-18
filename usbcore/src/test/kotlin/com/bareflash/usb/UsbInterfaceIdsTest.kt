package com.bareflash.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbInterfaceIdsTest {
    @Test fun adbMatchesOnlyAdbTriple() {
        assertTrue(UsbInterfaceIds.isAdb(0xFF, 0x42, 0x01))
        assertFalse(UsbInterfaceIds.isAdb(0xFF, 0x42, 0x03))
        assertFalse(UsbInterfaceIds.isAdb(0x08, 0x42, 0x01))
        assertFalse(UsbInterfaceIds.isAdb(0xFF, 0x06, 0x01))
    }

    @Test fun fastbootMatchesOnlyFastbootTriple() {
        assertTrue(UsbInterfaceIds.isFastboot(0xFF, 0x42, 0x03))
        assertFalse(UsbInterfaceIds.isFastboot(0xFF, 0x42, 0x01))
        assertFalse(UsbInterfaceIds.isFastboot(0x00, 0x42, 0x03))
    }

    @Test fun cancelTokenNeverIsNeverCancelled() {
        assertFalse(CancelToken.NEVER.isCancelled())
    }
}
