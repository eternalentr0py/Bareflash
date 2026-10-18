package com.bareflash.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbStreamTest {

    @Test(timeout = 20_000) fun readTimeoutLeavesTheStreamUsable() = withDevice { h ->
        val s = h.conn.open("shell,v2,raw:cat")
        assertThrows<AdbTimeoutException> { s.read(ByteArray(10), 0, 10, 100) }
        val session = AdbShellSession(s, 1000)
        session.writeStdin("x".toByteArray())
        assertEquals("x", String((session.readEvent(5_000) as ShellEvent.Stdout).data))
        s.close()
    }

    @Test(timeout = 20_000) fun theDeviceIsNotAckedUntilTheReaderConsumesData() {
        val core = FakeAdbdCore(maxPayload = 4096, shellHandler = { ShellOutcome(stdout = pattern(20_000)) })
        withDevice(core) { h ->
            val s = h.conn.open("exec:big")
            Thread.sleep(300)
            // One 4 KiB chunk is waiting; the device may not send more until we ack it.
            assertEquals(0, h.transport.hostMessages.count { it.command == AdbCommand.OKAY })
            val buf = ByteArray(4096)
            assertEquals(4096, s.readFully(buf, 0, 4096, 5_000))
            Thread.sleep(300)
            assertEquals(1, h.transport.hostMessages.count { it.command == AdbCommand.OKAY })
            s.close()
        }
    }

    @Test(timeout = 20_000) fun smallReadsOfOneChunkSendASingleAck() {
        val core = FakeAdbdCore(shellHandler = { ShellOutcome(stdout = pattern(1000)) })
        withDevice(core) { h ->
            val s = h.conn.open("exec:x")
            val buf = ByteArray(100)
            var total = 0
            while (true) { val n = s.read(buf, 0, 100, 5_000); if (n < 0) break; total += n }
            assertEquals(1000, total)
            assertEquals(1, h.transport.hostMessages.count { it.command == AdbCommand.OKAY })
        }
    }

    @Test(timeout = 20_000) fun writingToAStreamTheDeviceClosedFails() = withDevice { h ->
        val s = h.conn.open("exec:x") // the fake has nothing to say and closes at once
        assertEquals(0, s.readAll(10, 5_000).size)
        assertThrows<AdbClosedException> { s.write(ByteArray(10)) }
    }

    @Test(timeout = 20_000) fun closingAStreamSendsCloseOnceAndFreesIt() = withDevice { h ->
        val s = h.conn.open("shell,v2,raw:cat")
        s.close()
        s.close()
        assertEquals(1, h.transport.hostMessages.count { it.command == AdbCommand.CLSE })
        assertTrue(s.isClosed)
        assertThrows<AdbClosedException> { s.write(ByteArray(1)) }
        assertEquals(-1, s.read(ByteArray(1), 0, 1, 100))
    }

    @Test(timeout = 20_000) fun rangeChecks() = withDevice { h ->
        val s = h.conn.open("shell,v2,raw:cat")
        assertThrows<IllegalArgumentException> { s.write(ByteArray(4), 2, 5) }
        assertThrows<IllegalArgumentException> { s.read(ByteArray(4), 3, 5, 10) }
        s.close()
    }
}
