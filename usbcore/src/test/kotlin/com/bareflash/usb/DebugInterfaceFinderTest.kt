package com.bareflash.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugInterfaceFinderTest {

    private fun ep(addr: Int, dir: UsbDirection, type: UsbEndpointType = UsbEndpointType.BULK, mps: Int = 512) =
        UsbEndpointInfo(addr, dir, type, mps)

    private val bulkIn = ep(0x81, UsbDirection.IN)
    private val bulkOut = ep(0x01, UsbDirection.OUT)

    private fun itf(id: Int, cls: Int, sub: Int, proto: Int, vararg eps: UsbEndpointInfo, alt: Int = 0) =
        UsbInterfaceInfo(id, alt, cls, sub, proto, eps.toList())

    private fun adb(id: Int = 0, alt: Int = 0) = itf(id, 0xFF, 0x42, 0x01, bulkIn, bulkOut, alt = alt)
    private fun fastboot(id: Int = 0, alt: Int = 0) = itf(id, 0xFF, 0x42, 0x03, bulkIn, bulkOut, alt = alt)

    @Test fun findsAdbInterfaceAndItsEndpoints() {
        val found = DebugInterfaceFinder.find(listOf(adb()))
        assertEquals(1, found.size)
        assertEquals(DebugProtocol.ADB, found[0].protocol)
        assertSame(bulkIn, found[0].bulkIn)
        assertSame(bulkOut, found[0].bulkOut)
    }

    @Test fun findsFastbootInterface() {
        val found = DebugInterfaceFinder.find(listOf(fastboot()))
        assertEquals(listOf(DebugProtocol.FASTBOOT), found.map { it.protocol })
    }

    @Test fun ignoresOtherInterfacesOnACompositeDevice() {
        val mtp = itf(0, 0x06, 0x01, 0x01, bulkIn, bulkOut)
        val vendorOther = itf(1, 0xFF, 0x42, 0x02, bulkIn, bulkOut)   // not ADB (1) or fastboot (3)
        val wrongSubclass = itf(2, 0xFF, 0x00, 0x01, bulkIn, bulkOut)
        val found = DebugInterfaceFinder.find(listOf(mtp, vendorOther, wrongSubclass, adb(id = 3)))
        assertEquals(listOf(3), found.map { it.usbInterface.id })
    }

    @Test fun skipsInterfaceMissingABulkEndpoint() {
        val noOut = itf(0, 0xFF, 0x42, 0x01, bulkIn)
        val noIn = itf(1, 0xFF, 0x42, 0x03, bulkOut)
        assertTrue(DebugInterfaceFinder.find(listOf(noOut, noIn)).isEmpty())
    }

    @Test fun nonBulkEndpointsDoNotCount() {
        val interrupt = ep(0x82, UsbDirection.IN, UsbEndpointType.INTERRUPT)
        val onlyInterrupt = itf(0, 0xFF, 0x42, 0x01, interrupt, bulkOut)
        assertTrue(DebugInterfaceFinder.find(listOf(onlyInterrupt)).isEmpty())
    }

    @Test fun usesFirstBulkEndpointOfEachDirection() {
        val in2 = ep(0x83, UsbDirection.IN)
        val out2 = ep(0x04, UsbDirection.OUT)
        val intr = ep(0x85, UsbDirection.IN, UsbEndpointType.INTERRUPT)
        val found = DebugInterfaceFinder.find(listOf(itf(0, 0xFF, 0x42, 0x03, intr, bulkIn, in2, bulkOut, out2)))
        assertSame(bulkIn, found.single().bulkIn)
        assertSame(bulkOut, found.single().bulkOut)
    }

    @Test fun lowestAlternateSettingWinsPerInterfaceId() {
        val alt0 = adb(id = 0, alt = 0)
        val alt1 = adb(id = 0, alt = 1)
        val found = DebugInterfaceFinder.find(listOf(alt1, alt0))
        assertEquals(1, found.size)
        assertSame(alt0, found[0].usbInterface)
    }

    @Test fun alternateSettingIsUsedWhenOnlyItQualifies() {
        val emptyAlt0 = itf(0, 0xFF, 0x42, 0x01, alt = 0)
        val alt1 = adb(id = 0, alt = 1)
        val found = DebugInterfaceFinder.find(listOf(emptyAlt0, alt1))
        assertEquals(1, found.size)
        assertEquals(1, found[0].usbInterface.alternateSetting)
    }

    @Test fun resultsAreOrderedByInterfaceId() {
        val found = DebugInterfaceFinder.find(listOf(fastboot(id = 2), adb(id = 0), adb(id = 1)))
        assertEquals(listOf(0, 1, 2), found.map { it.usbInterface.id })
    }

    @Test fun findByProtocol() {
        val list = listOf(adb(id = 0), fastboot(id = 1))
        assertEquals(1, DebugInterfaceFinder.find(list, DebugProtocol.FASTBOOT)?.usbInterface?.id)
        assertNull(DebugInterfaceFinder.find(listOf(adb()), DebugProtocol.FASTBOOT))
    }

    @Test fun emptyInput() {
        assertTrue(DebugInterfaceFinder.find(emptyList()).isEmpty())
    }
}
