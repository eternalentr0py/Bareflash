package com.bareflash.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class AdbSyncTest {
    private val v1Banner = "device::ro.product.model=Old;features=shell_v2"

    private fun core(v2: Boolean) = FakeAdbdCore(banner = if (v2) "device::ro.product.model=New;features=shell_v2,stat_v2,ls_v2" else v1Banner).also {
        it.fs.put("/sdcard/hello.txt", "hi there".toByteArray(), mode = 0x81A0, mtime = 1_650_000_000)
        it.fs.put("/sdcard/Docs/a.txt", "a".toByteArray())
        it.fs.put("/sdcard/big.bin", pattern(300_000))
    }

    private fun both(block: (AdbSyncSession, FakeAdbdCore) -> Unit) {
        for (v2 in listOf(true, false)) {
            val c = core(v2)
            withDevice(c) { h -> h.conn.openSync().use { block(it, c) } }
        }
    }

    @Test(timeout = 30_000) fun statFileDirectoryAndMissing() = both { s, _ ->
        val f = s.stat("/sdcard/hello.txt")!!
        assertTrue(f.isRegularFile)
        assertFalse(f.isDirectory)
        assertEquals(8, f.size)
        assertEquals(0x1A0, f.permissions)
        assertEquals(1_650_000_000, f.mtimeSeconds)
        assertTrue(s.stat("/sdcard/Docs")!!.isDirectory)
        assertNull(s.stat("/sdcard/missing"))
    }

    @Test(timeout = 30_000) fun listOmitsDotEntriesAndKeepsMetadata() = both { s, _ ->
        val entries = s.list("/sdcard")
        assertEquals(listOf("Docs", "big.bin", "hello.txt"), entries.map { it.name })
        assertTrue(entries.first { it.name == "Docs" }.stat.isDirectory)
        assertEquals(300_000, entries.first { it.name == "big.bin" }.stat.size)
    }

    @Test(timeout = 30_000) fun listingAMissingDirectoryFailsButTheSessionLives() = both { s, _ ->
        assertThrows<AdbSyncException> { s.list("/nope") }
        assertNotNull(s.stat("/sdcard/hello.txt"))
    }

    @Test(timeout = 30_000) fun pushStoresBytesModeAndTimeAndReportsProgress() = both { s, c ->
        val data = pattern(300_000, seed = 3)
        val progress = ArrayList<Long>()
        val sent = s.push(data.inputStream(), "/sdcard/new.bin", mode = 0x81B6, mtimeSeconds = 1_600_000_000) { progress += it }
        assertEquals(300_000, sent)
        val e = c.fs.files["/sdcard/new.bin"]!!
        assertArrayEquals(data, e.data)
        assertEquals(0x81B6, e.mode)
        assertEquals(1_600_000_000, e.mtime)
        assertEquals(300_000, progress.last())
        assertEquals(progress.sorted(), progress)
        assertTrue(progress.size >= 4) // 64 KiB blocks
    }

    @Test(timeout = 30_000) fun pushOfAnEmptyFileWorks() = both { s, c ->
        assertEquals(0, s.push(ByteArray(0).inputStream(), "/sdcard/empty"))
        assertEquals(0, c.fs.files["/sdcard/empty"]!!.data.size)
    }

    @Test(timeout = 30_000) fun deviceFailureOnPushIsReportedAndTheSessionLives() = both { s, c ->
        c.failedSends += "/sdcard/full.bin"
        val e = assertThrows<AdbSyncException> { s.push(pattern(1000).inputStream(), "/sdcard/full.bin") }
        assertTrue(e.message!!.contains("No space"))
        assertNotNull(s.stat("/sdcard/hello.txt"))
    }

    @Test(timeout = 30_000) fun pullReturnsTheExactBytes() = both { s, _ ->
        val out = ByteArrayOutputStream()
        val progress = ArrayList<Long>()
        assertEquals(300_000, s.pull("/sdcard/big.bin", out) { progress += it })
        assertArrayEquals(pattern(300_000), out.toByteArray())
        assertEquals(300_000, progress.last())
        val small = ByteArrayOutputStream()
        s.pull("/sdcard/hello.txt", small)
        assertEquals("hi there", small.toString())
    }

    @Test(timeout = 30_000) fun pullingAMissingFileFailsButTheSessionLives() = both { s, _ ->
        val e = assertThrows<AdbSyncException> { s.pull("/sdcard/missing", ByteArrayOutputStream()) }
        assertTrue(e.message!!.contains("No such file"))
        assertNotNull(s.stat("/sdcard/hello.txt"))
    }

    @Test(timeout = 30_000) fun cancellingAPushClosesTheSession() {
        val c = core(true)
        withDevice(c) { h ->
            val s = h.conn.openSync()
            var polls = 0
            assertThrows<AdbCancelledException> { s.push(pattern(400_000).inputStream(), "/sdcard/x.bin", cancel = { ++polls > 2 }) }
            assertThrows<AdbClosedException> { s.stat("/sdcard/hello.txt") }
            assertFalse(c.fs.files.containsKey("/sdcard/x.bin"))
            // A fresh session on the same connection works.
            h.conn.openSync().use { assertNotNull(it.stat("/sdcard/hello.txt")) }
        }
    }

    @Test(timeout = 30_000) fun cancellingAPullClosesTheSession() {
        withDevice(core(true)) { h ->
            val s = h.conn.openSync()
            var polls = 0
            assertThrows<AdbCancelledException> { s.pull("/sdcard/big.bin", ByteArrayOutputStream(), cancel = { ++polls > 1 }) }
            assertThrows<AdbClosedException> { s.stat("/sdcard/hello.txt") }
        }
    }

    @Test(timeout = 30_000) fun hostileDirectoryNamesAreRefused() {
        for (bad in listOf("../evil", "a/b", "")) {
            val c = core(true)
            c.fs.injectedNames["/sdcard/Docs"] = listOf(bad)
            withDevice(c) { h -> h.conn.openSync().use { assertThrows<AdbProtocolException> { it.list("/sdcard/Docs") } } }
        }
    }

    @Test(timeout = 30_000) fun pathsAreValidatedBeforeAnythingIsSent() = withDevice(core(true)) { h ->
        h.conn.openSync().use { s ->
            assertThrows<IllegalArgumentException> { s.stat("") }
            assertThrows<IllegalArgumentException> { s.stat("a\u0000b") }
            assertThrows<IllegalArgumentException> { s.list("/" + "x".repeat(2000)) }
            assertThrows<IllegalArgumentException> { s.push(ByteArray(1).inputStream(), "") }
            assertNotNull(s.stat("/sdcard/hello.txt"))
        }
    }

    @Test(timeout = 30_000) fun syncWorksWhenTheDeviceOnlyAccepts4KiBMessages() {
        val c = core(true).also { it.maxPayload = 4096 }
        withDevice(c) { h ->
            h.conn.openSync().use { s ->
                s.push(pattern(100_000).inputStream(), "/sdcard/small-chunks.bin")
                val out = ByteArrayOutputStream()
                s.pull("/sdcard/small-chunks.bin", out)
                assertArrayEquals(pattern(100_000), out.toByteArray())
            }
        }
    }
}
