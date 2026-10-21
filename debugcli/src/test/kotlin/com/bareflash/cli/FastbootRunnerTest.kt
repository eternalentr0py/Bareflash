package com.bareflash.cli

import com.bareflash.cli.DeviceCommand.AdbShell
import com.bareflash.cli.DeviceCommand.FbBoot
import com.bareflash.cli.DeviceCommand.FbContinue
import com.bareflash.cli.DeviceCommand.FbDevices
import com.bareflash.cli.DeviceCommand.FbErase
import com.bareflash.cli.DeviceCommand.FbFlash
import com.bareflash.cli.DeviceCommand.FbFlashing
import com.bareflash.cli.DeviceCommand.FbGetvar
import com.bareflash.cli.DeviceCommand.FbLogical
import com.bareflash.cli.DeviceCommand.FbOem
import com.bareflash.cli.DeviceCommand.FbReboot
import com.bareflash.cli.DeviceCommand.FbSetActive
import com.bareflash.cli.DeviceCommand.FbVersion
import com.bareflash.fastboot.FlashingCommand
import com.bareflash.fastboot.RebootTarget
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastbootRunnerTest {

    private inline fun harness(block: (RunnerHarness) -> Unit) = RunnerHarness(withAdb = false, withFastboot = true).use(block)

    @Test(timeout = 30_000) fun getvarAllPrintsSortedVariables() = harness { h ->
        h.fbDevice.vars["zeta"] = "1"
        h.fbDevice.vars["alpha"] = "2"
        assertEquals(0, h.run(FbGetvar("all")).exitCode)
        val lines = h.sink.outText.lines().filter { it.isNotEmpty() }
        assertEquals(lines.sorted(), lines)
        assertTrue(lines.contains("alpha: 2") && lines.contains("product: fake"))
    }

    @Test(timeout = 30_000) fun getvarOneAndUnknown() = harness { h ->
        assertEquals(0, h.run(FbGetvar("product")).exitCode)
        assertEquals("product: fake\n", h.sink.outText.toString())
        val r = h.run(FbGetvar("nope"))
        assertEquals(1, r.exitCode)
        assertFalse(r.linkLost)
        assertEquals("getvar:nope FAILED (remote: 'unknown variable')", h.sink.errors.single())
    }

    @Test(timeout = 30_000) fun flashASmallImageWithProgressMessages() = harness { h ->
        val img = bytes(5000, 3)
        h.write("Download/boot.img", img)
        val r = h.run(FbFlash("boot", "Download/boot.img"))
        assertEquals(0, r.exitCode)
        assertEquals("boot", h.fbDevice.flashed.single().first)
        assertArrayEquals(img, h.fbDevice.flashed.single().second)
        assertTrue(h.sink.infos.any { it == "Sending 'boot' (4.9 KB)" })
        assertTrue(h.sink.infos.contains("Writing 'boot'"))
        assertTrue(h.sink.infos.last().startsWith("OKAY. Total time: "))
        assertTrue(h.sink.progress.any { it.first == "boot" && it.third == 5000L })
        assertTrue("progress ends cleared", h.sink.progress.last().second < 0)
    }

    @Test(timeout = 30_000) fun flashALargeImageInPieces() = harness { h ->
        h.write("big.img", bytes(400_000, 8))
        assertEquals(0, h.run(FbFlash("system", "big.img")).exitCode)
        assertTrue(h.fbDevice.flashed.size > 5)
        assertTrue(h.fbDevice.flashed.all { it.first == "system" && it.second.size <= 0x10000 })
        assertTrue(h.sink.infos.any { it.startsWith("Sending 'system' (1/") })
        assertTrue(h.sink.infos.any { it.startsWith("Writing 'system' (${h.fbDevice.flashed.size}/") })
    }

    @Test(timeout = 30_000) fun flashingAMissingFileFails() = harness { h ->
        val r = h.run(FbFlash("boot", "nope.img"))
        assertEquals(1, r.exitCode)
        assertTrue(h.sink.errors.single().contains("cannot load 'nope.img'"))
        assertTrue(h.fbDevice.commands.isEmpty())
    }

    @Test(timeout = 30_000) fun anUnusableSparseImageIsAnErrorNotALostLink() = harness { h ->
        // Starts with the sparse magic, is larger than the device accepts, and is garbage after the header.
        val bad = ByteArray(100_000).also { b -> byteArrayOf(0x3A, 0xFF.toByte(), 0x26, 0xED.toByte()).copyInto(b) }
        h.write("bad.img", bad)
        val r = h.run(FbFlash("boot", "bad.img"))
        assertEquals(1, r.exitCode)
        assertFalse(r.linkLost)
        assertTrue(h.sink.errors.single().startsWith("The image cannot be used"))
    }

    @Test(timeout = 30_000) fun aDeviceFailIsReportedLikeFastbootDoes() = harness { h ->
        h.fbDevice.failures["flash:"] = "partition table doesn't exist"
        h.write("boot.img", bytes(100))
        val r = h.run(FbFlash("boot", "boot.img"))
        assertEquals(1, r.exitCode)
        assertFalse(r.linkLost)
        assertEquals("FAILED (remote: 'partition table doesn't exist')", h.sink.errors.single())
        assertEquals(0, h.run(FbGetvar("product")).exitCode) // the link is still usable
    }

    @Test(timeout = 30_000) fun eraseSlotsAndLogicalPartitions() = harness { h ->
        assertEquals(0, h.run(FbErase("userdata")).exitCode)
        assertEquals(0, h.run(FbSetActive("b")).exitCode)
        assertEquals(0, h.run(FbLogical(FbLogical.Op.CREATE, "foo", 4096)).exitCode)
        assertEquals(0, h.run(FbLogical(FbLogical.Op.RESIZE, "foo", 8192)).exitCode)
        assertEquals(0, h.run(FbLogical(FbLogical.Op.DELETE, "foo", null)).exitCode)
        assertEquals(
            listOf("erase:userdata", "set_active:b", "create-logical-partition:foo:4096", "resize-logical-partition:foo:8192", "delete-logical-partition:foo"),
            h.fbDevice.commands,
        )
        assertEquals(5, h.sink.infos.count { it == "OKAY" })
    }

    @Test(timeout = 30_000) fun flashingAndOemPrintTheBootloadersReply() = harness { h ->
        h.fbDevice.infoLines["oem hello"] = listOf("line one", "line two")
        assertEquals(0, h.run(FbOem("hello")).exitCode)
        assertEquals("(bootloader) line one\n(bootloader) line two\n", h.sink.outText.toString())
        assertEquals(0, h.run(FbFlashing(FlashingCommand.GET_UNLOCK_ABILITY)).exitCode)
        assertEquals("flashing get_unlock_ability", h.fbDevice.commands.last())
    }

    @Test(timeout = 30_000) fun rebootAndContinueEndTheSession() = harness { h ->
        val r = h.run(FbReboot(RebootTarget.BOOTLOADER))
        assertTrue(r.linkLost)
        assertEquals("reboot-bootloader", h.fbDevice.commands.last())
        val c = h.run(FbContinue)
        assertTrue(c.linkLost)
        assertEquals("continue", h.fbDevice.commands.last())
    }

    @Test(timeout = 30_000) fun bootDownloadsThenBoots() = harness { h ->
        h.write("test.img", bytes(64))
        val r = h.run(FbBoot("test.img"))
        assertTrue(r.linkLost)
        assertEquals(listOf("download:00000040", "boot"), h.fbDevice.commands)
    }

    @Test(timeout = 30_000) fun devicesAndVersion() = harness { h ->
        h.fbDevice.vars["serialno"] = "FB123"
        h.run(FbDevices)
        assertEquals("FB123\tfastboot\n", h.sink.outText.toString())
        h.run(FbVersion)
        assertTrue(h.sink.outText.contains("native fastboot client"))
    }

    @Test(timeout = 30_000) fun cancellingMidFlashClosesTheLink() = harness { h ->
        h.write("boot.img", bytes(2000))
        h.cancelled = true
        val r = h.run(FbFlash("boot", "boot.img"))
        assertEquals(130, r.exitCode)
        assertTrue(r.linkLost)
        assertTrue(r.note!!.contains("half-way"))
    }

    @Test(timeout = 30_000) fun invalidArgumentsAreUsageErrors() = harness { h ->
        val r = h.run(FbErase("bad:name"))
        assertEquals(2, r.exitCode)
        assertFalse(r.linkLost)
        assertTrue(h.sink.errors.single().contains("Invalid partition name"))
        assertTrue(h.fbDevice.commands.isEmpty())
    }

    @Test(timeout = 30_000) fun aSilentDeviceMeansALostLink() = harness { h ->
        h.fbDevice.silent = true
        // A clock that jumps five seconds per reading makes the client's timeout expire at once.
        var now = 0L
        val client = com.bareflash.fastboot.FastbootClient(h.fbDevice, nanoClock = { now += 5_000_000_000L; now })
        val runner = CommandRunner(DeviceSession(null, NativeFastbootOps(client)), h.files, h.sink, { false })
        val r = runner.run(FbGetvar("all"))
        assertTrue(r.linkLost)
        assertEquals(1, r.exitCode)
        assertTrue(h.sink.errors.single().contains("fastboot connection failed"))
    }

    @Test(timeout = 30_000) fun adbCommandsOnAFastbootDeviceAreExplained() = harness { h ->
        val r = h.run(AdbShell("ls"))
        assertEquals(2, r.exitCode)
        assertTrue(h.sink.errors.single().contains("fastboot mode"))
    }
}
