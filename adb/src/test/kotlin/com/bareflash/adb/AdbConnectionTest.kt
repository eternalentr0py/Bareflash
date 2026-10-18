package com.bareflash.adb

import com.bareflash.usb.UsbTimeoutException
import com.bareflash.usb.UsbTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AdbConnectionTest {

    @Test(timeout = 20_000) fun connectsToADeviceThatNeedsNoAuth() = withDevice { h ->
        assertEquals("device", h.conn.info.state)
        assertEquals("Fake Phone", h.conn.info.model)
        assertEquals("fakedev", h.conn.info.device)
        assertEquals("fake", h.conn.info.product)
        assertTrue(h.conn.supports("shell_v2"))
        assertFalse(h.conn.supports("cmd")) // the device has it, this host does not use it
        assertEquals(AdbProtocol.HOST_MAX_PAYLOAD, h.conn.maxPayload)
        assertTrue(h.conn.isOpen)
        val first = h.transport.hostMessages.first()
        assertEquals(AdbCommand.CNXN, first.command)
        assertEquals(AdbProtocol.VERSION_SKIP_CHECKSUM, first.arg0)
        assertEquals("host::features=shell_v2,stat_v2,ls_v2", String(first.data).trimEnd('\u0000'))
    }

    @Test(timeout = 20_000) fun aKnownKeyIsAcceptedBySignatureAlone() {
        val core = FakeAdbdCore(requireAuth = true)
        core.authorizedKeys += FakeAdbdCore.parseAndroidKey(TestKeys.pair.publicKeyMessage("x"))
        Harness(core).use {
            assertTrue(core.offeredKeyMessages.isEmpty())
            assertEquals(listOf(AdbProtocol.AUTH_SIGNATURE), it.transport.hostMessages.filter { m -> m.command == AdbCommand.AUTH }.map { m -> m.arg0 })
        }
    }

    @Test(timeout = 20_000) fun anUnknownKeyIsOfferedAndTheListenerIsTold() {
        val core = FakeAdbdCore(requireAuth = true)
        var seen: String? = null
        val t = FakeAdbUsbTransport(core)
        AdbConnection.connect(t, TestKeys.pair, listener = object : AdbAuthListener {
            override fun onWaitingForUserApproval(fingerprint: String) { seen = fingerprint }
        }).close()
        assertEquals(TestKeys.pair.fingerprint(), seen)
        assertEquals(1, core.offeredKeyMessages.size)
        assertEquals("bareflash@android", String(core.offeredKeyMessages[0]).trimEnd('\u0000').substringAfter(' '))
        assertEquals(1, core.authorizedKeys.size)
    }

    @Test(timeout = 20_000) fun ignoringThePromptTimesOutButLeavesTheTransportToTheCaller() {
        val core = FakeAdbdCore(requireAuth = true, userApprovesPrompt = false)
        val t = FakeAdbUsbTransport(core)
        assertThrows<AdbTimeoutException> {
            AdbConnection.connect(t, TestKeys.pair, AdbConnectionConfig(approvalTimeoutMs = 300))
        }
        assertEquals(0, t.closeCount)
    }

    @Test(timeout = 20_000) fun aRejectedKeyIsAnAuthError() {
        val t = FakeAdbUsbTransport(FakeAdbdCore(requireAuth = true, rejectOfferedKey = true))
        assertThrows<AdbAuthException> { AdbConnection.connect(t, TestKeys.pair) }
    }

    @Test(timeout = 20_000) fun aSilentDeviceTimesOut() {
        val dead = object : UsbTransport {
            override fun write(data: ByteArray, offset: Int, length: Int, timeoutMs: Int) {}
            override fun read(buffer: ByteArray, timeoutMs: Int): Int { Thread.sleep(minOf(timeoutMs, 20).toLong()); throw UsbTimeoutException("t") }
            override fun close() {}
        }
        assertThrows<AdbTimeoutException> { AdbConnection.connect(dead, TestKeys.pair, AdbConnectionConfig(connectTimeoutMs = 300)) }
    }

    @Test(timeout = 20_000) fun cancellingDuringTheHandshakeStops() {
        val core = FakeAdbdCore(requireAuth = true, userApprovesPrompt = false)
        var calls = 0
        assertThrows<AdbCancelledException> {
            AdbConnection.connect(FakeAdbUsbTransport(core), TestKeys.pair, cancel = { ++calls > 2 })
        }
    }

    @Test(timeout = 20_000) fun tlsRequestsAreRefused() {
        val core = FakeAdbdCore()
        val t = object : UsbTransport {
            val inner = FakeAdbUsbTransport(core)
            override fun write(data: ByteArray, offset: Int, length: Int, timeoutMs: Int) {}
            override fun read(buffer: ByteArray, timeoutMs: Int): Int {
                val h = AdbWire.encodeHeader(AdbMessage(AdbCommand.STLS, 1, 0), false)
                System.arraycopy(h, 0, buffer, 0, h.size); return h.size
            }
            override fun close() { inner.close() }
        }
        assertThrows<AdbProtocolException> { AdbConnection.connect(t, TestKeys.pair) }
    }

    @Test(timeout = 20_000) fun oldDevicesGetChecksumsBothWays() {
        val core = FakeAdbdCore(version = AdbProtocol.VERSION, maxPayload = 4096, shellHandler = { ShellOutcome(stdout = "ok\n".toByteArray()) })
        withDevice(core) { h ->
            assertEquals("ok\n", h.conn.shell("x").stdoutText())
            assertEquals(4096, h.conn.maxPayload)
        }
    }

    @Test(timeout = 20_000) fun deviceMaxPayloadLimitsWrites() {
        val core = FakeAdbdCore(maxPayload = 4096)
        withDevice(core) { h ->
            h.conn.openSync().use { it.push(pattern(30_000).inputStream(), "/sdcard/a.bin") }
            val writes = h.transport.hostMessages.filter { it.command == AdbCommand.WRTE }
            assertTrue(writes.size > 7)
            assertTrue(writes.all { it.data.size <= 4096 })
            assertTrue(core.fs.files["/sdcard/a.bin"]!!.data.contentEquals(pattern(30_000)))
        }
    }

    @Test(timeout = 30_000) fun usbStyleTransfersAreReassembled() {
        // 512-byte transfers with zero-length packets after exact multiples, like real USB.
        val big = pattern(100 * 512)
        val core = FakeAdbdCore(shellHandler = { ShellOutcome(stdout = big) })
        withDevice(core, transferSize = 512, zeroLengthPackets = true) { h ->
            assertTrue(h.conn.shell("x").stdout.contentEquals(big))
        }
    }

    @Test(timeout = 20_000) fun anUnknownServiceIsRejected() = withDevice { h ->
        assertEquals("bogus:", assertThrows<AdbStreamRejectedException> { h.conn.open("bogus:") }.destination)
        // The connection is still fine afterwards.
        assertEquals(0, h.conn.shell("x").exitCode)
    }

    @Test(timeout = 20_000) fun badServiceNamesAreRejectedLocally() = withDevice { h ->
        assertThrows<IllegalArgumentException> { h.conn.open("") }
        assertThrows<IllegalArgumentException> { h.conn.open("a\u0000b") }
        assertThrows<IllegalArgumentException> { h.conn.open("shell:" + "x".repeat(5000)) }
        assertEquals(1, h.transport.hostMessages.size) // only the CNXN went out
    }

    @Test(timeout = 20_000) fun rebootAsksForTheRightTarget() = withDevice { h ->
        h.conn.reboot(AdbRebootTarget.BOOTLOADER)
        assertEquals("bootloader", h.core.rebootRequested)
        h.conn.reboot()
        assertEquals("system", h.core.rebootRequested)
    }

    @Test(timeout = 20_000) fun closingTheConnectionClosesTheTransportAndFailsReaders() = withDevice { h ->
        val session = h.conn.openShell("cat")
        val ex = Executors.newSingleThreadExecutor()
        try {
            val started = CountDownLatch(1)
            val outcome = ex.submit<Throwable?> {
                started.countDown()
                try { session.readEvent(10_000); null } catch (t: Throwable) { t }
            }
            started.await()
            Thread.sleep(100)
            h.conn.close()
            val t = outcome.get(5, TimeUnit.SECONDS)
            assertTrue("got $t", t is AdbClosedException)
            assertTrue(h.transport.closed)
            assertFalse(h.conn.isOpen)
            assertThrows<AdbClosedException> { h.conn.open("shell:x") }
        } finally {
            ex.shutdownNow()
        }
    }

    @Test(timeout = 20_000) fun pullingTheCableFailsPendingAndFutureOperations() = withDevice { h ->
        val session = h.conn.openShell("cat")
        val ex = Executors.newSingleThreadExecutor()
        try {
            val outcome = ex.submit<Throwable?> { try { session.readEvent(10_000); null } catch (t: Throwable) { t } }
            Thread.sleep(100)
            h.transport.detach()
            assertTrue(outcome.get(5, TimeUnit.SECONDS) is AdbClosedException)
            assertThrows<AdbClosedException> { h.conn.open("shell:x") }
            assertFalse(h.conn.isOpen)
        } finally {
            ex.shutdownNow()
        }
    }

    @Test(timeout = 20_000) fun garbageOnTheWireFailsTheConnection() = withDevice { h ->
        val session = h.conn.openShell("cat")
        val ex = Executors.newSingleThreadExecutor()
        try {
            val outcome = ex.submit<Throwable?> { try { session.readEvent(10_000); null } catch (t: Throwable) { t } }
            Thread.sleep(100)
            h.transport.injectRaw(ByteArray(24) { 0x55 })
            assertTrue(outcome.get(5, TimeUnit.SECONDS) is AdbProtocolException)
            assertFalse(h.conn.isOpen)
        } finally {
            ex.shutdownNow()
        }
    }

    @Test(timeout = 60_000) fun concurrentStreamsDoNotMixTheirOutput() {
        val core = FakeAdbdCore(shellHandler = { cmd -> ShellOutcome(stdout = (cmd + "\n").toByteArray().let { b -> ByteArray(20_000) { b[it % b.size] } }) })
        withDevice(core) { h ->
            val pool = Executors.newFixedThreadPool(8)
            try {
                val jobs = (0 until 8).map { t ->
                    pool.submit<Boolean> {
                        (0 until 15).all { i ->
                            val cmd = "worker-$t-job-$i"
                            val unit = (cmd + "\n").toByteArray()
                            h.conn.shell(cmd).stdout.contentEquals(ByteArray(20_000) { unit[it % unit.size] })
                        }
                    }
                }
                assertTrue(jobs.all { it.get(40, TimeUnit.SECONDS) })
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test fun bannerParsing() {
        val i = AdbDeviceInfo.parse("recovery::ro.product.model=Pixel 7;features=a,b,;ro.x=1=2")
        assertEquals("recovery", i.state)
        assertEquals("Pixel 7", i.model)
        assertEquals(setOf("a", "b"), i.features)
        assertEquals("1=2", i.properties["ro.x"])
        val bare = AdbDeviceInfo.parse("bootloader")
        assertEquals("bootloader", bare.state)
        assertTrue(bare.properties.isEmpty())
        assertNull(bare.model)
        assertNotNull(AdbDeviceInfo.parse("device::"))
    }
}
