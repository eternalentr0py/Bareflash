package com.bareflash.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbShellTest {

    @Test(timeout = 20_000) fun shellV2SeparatesStdoutStderrAndExitCode() {
        val core = FakeAdbdCore(shellHandler = { ShellOutcome("out\n".toByteArray(), "err\n".toByteArray(), 7) })
        withDevice(core) { h ->
            val r = h.conn.shell("anything")
            assertEquals("out\n", r.stdoutText())
            assertEquals("err\n", r.stderrText())
            assertEquals(7, r.exitCode)
            assertFalse(r.truncated)
            assertEquals("shell,v2,raw:anything", core.openedServices.single())
        }
    }

    @Test(timeout = 20_000) fun exitCodesAreUnsigned() {
        withDevice(FakeAdbdCore(shellHandler = { ShellOutcome(exit = 255) })) { assertEquals(255, it.conn.shell("x").exitCode) }
    }

    @Test(timeout = 60_000) fun largeOutputFlowsThroughOneChunkAtATime() {
        val big = pattern(1_500_000)
        withDevice(FakeAdbdCore(maxPayload = 4096, shellHandler = { ShellOutcome(stdout = big) })) { h ->
            // 4 KiB device chunks mean shell packets straddle message boundaries.
            assertArrayEquals(big, h.conn.shell("x", maxOutputBytes = 2_000_000).stdout)
        }
    }

    @Test(timeout = 20_000) fun outputBeyondTheLimitIsCutOff() {
        withDevice(FakeAdbdCore(shellHandler = { ShellOutcome(stdout = ByteArray(100_000)) })) { h ->
            val r = h.conn.shell("x", maxOutputBytes = 1000)
            assertTrue(r.truncated)
            assertNull(r.exitCode)
            assertTrue(r.stdout.size <= 1000)
        }
    }

    @Test(timeout = 20_000) fun legacyShellMergesOutputAndHasNoExitCode() {
        val core = FakeAdbdCore(
            banner = "device::ro.product.model=Old;features=cmd",
            shellHandler = { ShellOutcome("out\n".toByteArray(), "err\n".toByteArray(), 3) },
        )
        withDevice(core) { h ->
            val r = h.conn.shell("ls")
            assertEquals("out\nerr\n", r.stdoutText())
            assertNull(r.exitCode)
            assertEquals("shell:ls", core.openedServices.single())
            assertThrows<AdbUnsupportedException> { h.conn.openShell("ls") }
        }
    }

    @Test(timeout = 20_000) fun execIsBinarySafe() {
        val bytes = ByteArray(1024) { it.toByte() }
        val core = FakeAdbdCore(shellHandler = { ShellOutcome(stdout = bytes) })
        withDevice(core) { h ->
            assertArrayEquals(bytes, h.conn.exec("screencap -p"))
            assertEquals("exec:screencap -p", core.openedServices.single())
        }
    }

    @Test(timeout = 20_000) fun execOutputLimitIsEnforced() {
        withDevice(FakeAdbdCore(shellHandler = { ShellOutcome(stdout = ByteArray(10_000)) })) { h ->
            assertThrows<AdbException> { h.conn.exec("x", maxOutputBytes = 100) }
        }
    }

    @Test(timeout = 20_000) fun aCommandThatNeverEndsTimesOut() = withDevice { h ->
        assertThrows<AdbTimeoutException> { h.conn.shell("cat", timeoutMs = 300) }
        // The connection survives.
        assertEquals(0, h.conn.shell("x").exitCode)
    }

    @Test(timeout = 20_000) fun interactiveSessionEchoesStdinAndEnds() = withDevice { h ->
        h.conn.openShell("cat").use { s ->
            s.writeStdin("hello".toByteArray())
            val e = s.readEvent(5_000) as ShellEvent.Stdout
            assertEquals("hello", String(e.data))
            s.closeStdin()
            assertEquals(0, (s.readEvent(5_000) as ShellEvent.Exit).code)
            assertNull(s.readEvent(5_000)) // stream ended
        }
    }

    @Test(timeout = 20_000) fun largeStdinIsSplitIntoPackets() = withDevice { h ->
        val data = pattern(300_000)
        h.conn.openShell("cat").use { s ->
            s.writeStdin(data)
            val echoed = java.io.ByteArrayOutputStream()
            while (echoed.size() < data.size) echoed.write((s.readEvent(5_000) as ShellEvent.Stdout).data)
            assertArrayEquals(data, echoed.toByteArray())
        }
    }

    @Test(timeout = 20_000) fun ptySessionsAskForAPtyAndCanBeResized() {
        val core = FakeAdbdCore()
        withDevice(core) { h ->
            h.conn.openShell("", pty = true, term = "vt100").use { s ->
                s.resize(24, 80)
                Thread.sleep(100)
            }
            assertEquals("shell,v2,pty,TERM=vt100:", core.openedServices.single())
            val last = h.transport.hostMessages.last { it.command == AdbCommand.WRTE }.data
            assertEquals(5, last[0].toInt())
            assertEquals("24x80,0x0", String(last, 5, ByteBuffer.wrap(last, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int))
        }
    }

    @Test(timeout = 20_000) fun unknownPacketTypesAreSkippedAndBadLengthsRejected() {
        // Drive the decoder directly through a stream the fake never answers.
        withDevice { h ->
            val s = h.conn.open("shell,v2,raw:cat")
            val session = AdbShellSession(s, 1000)
            // Hostile device: unknown id 9, then an absurd length.
            h.core.apply { }
            h.transport.injectRaw(AdbWire.encodeHeader(AdbMessage(AdbCommand.WRTE, 100, 1, ByteArray(8)), false))
            h.transport.injectRaw(byteArrayOf(9, 3, 0, 0, 0, 1, 2, 3))
            h.transport.injectRaw(AdbWire.encodeHeader(AdbMessage(AdbCommand.WRTE, 100, 1, ByteArray(5)), false))
            h.transport.injectRaw(byteArrayOf(1, -1, -1, -1, 127))
            assertThrows<AdbProtocolException> { session.readEvent(5_000) }
        }
    }

    @Test(timeout = 20_000) fun commandAndTermAreValidatedLocally() = withDevice { h ->
        assertThrows<IllegalArgumentException> { h.conn.shell("a\u0000b") }
        assertThrows<IllegalArgumentException> { h.conn.openShell("x", pty = true, term = "a;b") }
        assertEquals(1, h.transport.hostMessages.size) // nothing but the CNXN has gone out
        h.conn.openShell("x").use { assertThrows<IllegalArgumentException> { it.resize(0, 10) } }
    }
}
