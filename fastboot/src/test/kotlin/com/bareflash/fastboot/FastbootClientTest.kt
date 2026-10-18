package com.bareflash.fastboot

import com.bareflash.usb.CancelToken
import com.bareflash.usb.UsbDisconnectedException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FastbootClientTest {
    private val dev = FakeFastbootDevice()
    private fun client(cancel: CancelToken = CancelToken.NEVER) = FastbootClient(dev, cancel)

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try { block() } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("expected ${T::class.simpleName} but got $t", t)
        }
        throw AssertionError("expected ${T::class.simpleName}")
    }

    @Test fun getvarReturnsValueAndUnknownIsNull() {
        assertEquals("fake", client().getvar("product"))
        assertNull(client().getvarOrNull("nope"))
        assertThrows<FastbootFailException> { client().getvar("nope") }
    }

    @Test fun getvarAllCollectsInfoLines() {
        dev.vars["partition-size:boot_a"] = "0x4000000"
        val all = client().getvarAll()
        assertEquals("fake", all["product"])
        assertEquals("0x4000000", all["partition-size:boot_a"])
    }

    @Test fun maxDownloadSizeHexDecimalAndMissing() {
        assertEquals(0x10000L, client().maxDownloadSize())
        dev.vars["max-download-size"] = "65536"
        assertEquals(65536L, client().maxDownloadSize())
        dev.vars.remove("max-download-size")
        assertNull(client().maxDownloadSize())
    }

    @Test fun infoLinesArriveWithTheResult() {
        dev.infoLines["oem hello"] = listOf("one", "two")
        val r = client().oem("hello")
        assertEquals(listOf("one", "two"), r.info)
    }

    @Test fun failBecomesException() {
        dev.failures["erase:"] = "not allowed"
        assertEquals("not allowed", assertThrows<FastbootFailException> { client().erase("userdata") }.message)
    }

    @Test fun smallImageIsDownloadedRawAndFlashed() {
        val data = randomBytes(5000)
        client().flash("boot", ByteArraySource(data))
        assertEquals(listOf("getvar:max-download-size", "download:00001388", "flash:boot"), dev.commands)
        assertEquals("boot", dev.flashed.single().first)
        assertArrayEquals(data, dev.flashed.single().second)
    }

    @Test fun largeImageIsSplitAndTheDeviceEndsUpWithEveryByte() {
        val data = randomBytes(400_000, 11)
        val progress = ArrayList<String>()
        client().flash("system", ByteArraySource(data), progress = object : FlashProgress {
            override fun onPieceStart(index: Int, count: Int, pieceBytes: Long) { progress += "start $index/$count" }
            override fun onWriting(index: Int, count: Int) { progress += "write $index/$count" }
        })
        assertTrue(dev.flashed.size > 5)
        assertTrue(dev.flashed.all { it.first == "system" && it.second.size <= 0x10000 })
        val target = ByteArray(((data.size + 4095) / 4096) * 4096)
        dev.flashed.forEach { decodeSparseInto(it.second, target) }
        assertArrayEquals(data.copyOf(target.size), target)
        assertEquals("start 0/${dev.flashed.size}", progress.first())
        assertEquals("write ${dev.flashed.size - 1}/${dev.flashed.size}", progress.last())
    }

    @Test fun explicitLimitSkipsTheGetvarRoundTrip() {
        client().flash("boot", ByteArraySource(randomBytes(100)), maxDownloadSize = 1 shl 20)
        assertTrue(dev.commands.none { it.startsWith("getvar") })
    }

    @Test fun deviceWithoutLimitFallsBackToTheConservativeDefault() {
        dev.vars.remove("max-download-size")
        val logs = ArrayList<String>()
        FastbootClient(dev, log = { logs += it }).flash("boot", ByteArraySource(randomBytes(100)))
        assertTrue(logs.any { "assuming" in it })
    }

    @Test fun deviceAcceptingADifferentLengthIsAProtocolError() {
        dev.announceWrongLength = true
        assertThrows<FastbootProtocolException> { client().download(ByteArraySource(randomBytes(64)).asPayload()) }
    }

    @Test fun downloadProgressIsReported() {
        val seen = ArrayList<Long>()
        client().download(ByteArraySource(randomBytes(3_000_000)).asPayload()) { sent, total ->
            assertEquals(3_000_000L, total); seen += sent
        }
        assertEquals(3_000_000L, seen.last())
        assertEquals(seen.sorted(), seen)
    }

    @Test fun cancelStopsADownloadBetweenChunks() {
        var writes = 0
        val c = FastbootClient(dev, CancelToken { writes > 1 })
        val big = ByteArraySource(randomBytes(5_000_000)).asPayload()
        assertThrows<FastbootCancelledException> {
            c.download(object : Payload {
                override val size = big.size
                override fun writeTo(sink: ByteSink, cancel: CancelToken) = big.writeTo({ b, o, l -> writes++; sink.write(b, o, l) }, cancel)
            })
        }
    }

    @Test fun silentDeviceTimesOut() {
        dev.silent = true
        assertThrows<FastbootTimeoutException> { client().command("getvar:product", 60) }
    }

    @Test fun zeroLengthPacketsAreSkipped() {
        dev.queueRaw(ByteArray(0))
        dev.silent = true
        dev.queue("OKAYok")
        assertEquals("ok", client().command("getvar:product", 1000).message)
    }

    @Test fun badInputIsRejectedBeforeAnythingIsSent() {
        assertThrows<IllegalArgumentException> { client().command("x".repeat(65)) }
        assertThrows<IllegalArgumentException> { client().command("getvar:\u00e9") }
        assertThrows<IllegalArgumentException> { client().erase("boot:evil") }
        assertThrows<IllegalArgumentException> { client().erase("") }
        assertThrows<IllegalArgumentException> { client().setActive("ab") }
        assertThrows<IllegalArgumentException> { client().getvar("a b") }
        assertThrows<IllegalArgumentException> { client().flash("../x", ByteArraySource(randomBytes(4))) }
        assertTrue(dev.commands.isEmpty())
    }

    @Test fun sixtyFourByteCommandIsAccepted() {
        client().command("oem " + "a".repeat(60))
        assertEquals(64, dev.commands.single().length)
    }

    @Test fun rebootToleratesALinkThatDropsButNotAFail() {
        dev.throwOnWrite["reboot"] = UsbDisconnectedException("gone")
        client().reboot(RebootTarget.BOOTLOADER)
        dev.throwOnWrite.clear()
        dev.failures["reboot"] = "refused"
        assertThrows<FastbootFailException> { client().reboot() }
    }

    @Test fun rebootToleratesSilence() {
        dev.silent = true
        client().reboot(RebootTarget.RECOVERY, answerTimeoutMs = 50)
        assertEquals("reboot-recovery", dev.commands.single())
    }

    @Test fun commandStringsMatchTheProtocol() {
        val c = client()
        c.setActive("b"); c.erase("userdata"); c.flashing(FlashingCommand.UNLOCK)
        c.createLogicalPartition("my_part", 4096); c.deleteLogicalPartition("my_part"); c.resizeLogicalPartition("my_part", 8192)
        c.continueBoot(); c.reboot(RebootTarget.FASTBOOTD)
        assertEquals(
            listOf(
                "set_active:b", "erase:userdata", "flashing unlock",
                "create-logical-partition:my_part:4096", "delete-logical-partition:my_part", "resize-logical-partition:my_part:8192",
                "continue", "reboot-fastboot",
            ),
            dev.commands,
        )
    }

    @Test fun bootDownloadsThenBoots() {
        client().boot(ByteArraySource(randomBytes(16)))
        assertEquals(listOf("download:00000010", "boot"), dev.commands)
    }

    @Test fun unexpectedDataInAPlainCommandIsAProtocolError() {
        dev.queue("DATA00000010")
        dev.silent = true
        assertThrows<FastbootProtocolException> { client().command("getvar:product", 1000) }
    }

    @Test fun cancelledBeforeStartSendsNothing() {
        assertThrows<FastbootCancelledException> { client(CancelToken { true }).command("getvar:product") }
        assertTrue(dev.commands.isEmpty())
    }

    @Test fun unreadableImageSurfacesAsAnError() {
        val lying = object : ByteSource {
            override val size = 10_000L
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int) = -1
        }
        try { client().flash("boot", lying, maxDownloadSize = 1 shl 20); fail() } catch (_: java.io.IOException) { assertTrue(true) }
    }
}
