package com.bareflash.cli

import com.bareflash.adb.AdbRebootTarget
import com.bareflash.cli.DeviceCommand.AdbExecOut
import com.bareflash.cli.DeviceCommand.AdbInfo
import com.bareflash.cli.DeviceCommand.AdbPull
import com.bareflash.cli.DeviceCommand.AdbPush
import com.bareflash.cli.DeviceCommand.AdbReboot
import com.bareflash.cli.DeviceCommand.AdbShell
import com.bareflash.cli.DeviceCommand.FbGetvar
import com.bareflash.cli.DeviceCommand.Unsupported
import com.bareflash.fakes.ShellOutcome
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AdbRunnerTest {

    private inline fun harness(block: (RunnerHarness) -> Unit) = RunnerHarness().use(block)

    // ---- shell ------------------------------------------------------------------------------

    @Test(timeout = 30_000) fun shellOutputStderrAndExitCodeAreSeparated() = harness { h ->
        h.core.shellHandler = { ShellOutcome("hi\n".toByteArray(), "warn\n".toByteArray(), 3) }
        val r = h.run(AdbShell("whatever"))
        assertEquals("hi\n", h.sink.outText.toString())
        assertEquals("warn\n", h.sink.errText.toString())
        assertTrue(h.sink.infos.contains("[exit 3]"))
        assertEquals(3, r.exitCode)
        assertFalse(r.linkLost)
    }

    @Test(timeout = 30_000) fun anEmptyShellExplainsItself() = harness { h ->
        assertEquals(0, h.run(AdbShell("")).exitCode)
        assertTrue(h.sink.infos.single().contains("already runs in the device shell"))
        assertTrue(h.seenShell.isEmpty())
    }

    @Test(timeout = 30_000) fun cdIsRememberedAndAppliedToLaterCommands() = harness { h ->
        assertEquals(0, h.run(AdbShell("cd /sdcard")).exitCode)
        assertEquals("/sdcard", h.session.cwd)
        assertEquals(0, h.run(AdbShell("cd Docs")).exitCode)
        assertEquals("/sdcard/Docs", h.session.cwd)
        h.run(AdbShell("ls"))
        assertEquals("cd /sdcard/Docs && ls", h.seenShell.last())
        assertTrue(h.sink.outText.contains("ran: cd /sdcard/Docs && ls"))
        h.run(AdbShell("cd .."))
        assertEquals("/sdcard", h.session.cwd)
        h.run(AdbShell("cd -"))
        assertEquals("/sdcard/Docs", h.session.cwd)
        assertTrue(h.sink.outText.contains("/sdcard/Docs\n"))
        h.run(AdbShell("cd"))
        assertEquals("/", h.session.cwd)
        h.run(AdbShell("ls"))
        assertEquals("ls", h.seenShell.last()) // no wrapper at /
    }

    @Test(timeout = 30_000) fun aFailedCdKeepsTheDirectoryAndShowsTheReason() = harness { h ->
        h.run(AdbShell("cd /sdcard"))
        val r = h.run(AdbShell("cd nowhere"))
        assertEquals(1, r.exitCode)
        assertEquals("/sdcard", h.session.cwd)
        assertTrue(h.sink.errText.contains("No such file or directory"))
    }

    @Test(timeout = 30_000) fun cdWithOtherShellSyntaxIsJustACommand() = harness { h ->
        h.run(AdbShell("cd /sdcard; ls"))
        assertEquals("/", h.session.cwd)
        assertEquals("cd /sdcard; ls", h.seenShell.last())
    }

    @Test(timeout = 30_000) fun terminalEscapesAreRemovedAndSplitCharactersSurvive() = harness { h ->
        // 3-byte characters in 32 KiB packets: boundaries fall inside characters.
        val body = "€".repeat(40_000)
        h.core.shellHandler = { ShellOutcome(("\u001B[31m" + body + "\u001B[0m\r\n").toByteArray()) }
        h.run(AdbShell("x"))
        assertEquals(body + "\n", h.sink.outText.toString())
    }

    @Test(timeout = 30_000) fun cancellingStopsAShellCommand() = harness { h ->
        h.core.shellHandler = { ShellOutcome(ByteArray(2_000_000) { 'a'.code.toByte() }) }
        h.sink.onProgress = { _, _ -> }
        h.cancelled = true
        val r = h.run(AdbShell("endless"))
        assertEquals(130, r.exitCode)
        assertFalse(r.linkLost)
        assertTrue(h.sink.infos.contains("Cancelled."))
    }

    // ---- exec-out ------------------------------------------------------------------------------

    @Test(timeout = 30_000) fun execOutSavesBinaryDataExactly() = harness { h ->
        val data = bytes(100_000, 5)
        h.core.shellHandler = { ShellOutcome(data) }
        val r = h.run(AdbExecOut("screencap -p", "shots/one.png"))
        assertEquals(0, r.exitCode)
        assertArrayEquals(data, h.file("shots/one.png").readBytes())
        assertTrue(h.sink.infos.single().startsWith("Saved 97.7 KB to "))
    }

    @Test(timeout = 30_000) fun execOutIntoAFolderUsesADefaultName() = harness { h ->
        h.core.shellHandler = { ShellOutcome(byteArrayOf(1, 2, 3)) }
        h.run(AdbExecOut("x", "Download"))
        assertArrayEquals(byteArrayOf(1, 2, 3), h.file("Download/output.bin").readBytes())
    }

    @Test(timeout = 30_000) fun aCancelledSaveLeavesNoPartialFile() = harness { h ->
        h.core.shellHandler = { ShellOutcome(bytes(50_000)) }
        h.cancelled = true
        assertEquals(130, h.run(AdbExecOut("x", "part.bin")).exitCode)
        assertFalse(h.file("part.bin").exists())
    }

    @Test(timeout = 30_000) fun execOutWithoutAFilePrintsText() = harness { h ->
        h.core.shellHandler = { ShellOutcome("plain text\n".toByteArray()) }
        h.run(AdbExecOut("cat /x", null))
        assertEquals("plain text\n", h.sink.outText.toString())
    }

    // ---- push ----------------------------------------------------------------------------------

    @Test(timeout = 30_000) fun pushAFileIntoAFolderKeepsItsNameModeAndTime() = harness { h ->
        h.write("Download/a.txt", "hello".toByteArray()).also { it.setLastModified(1_600_000_000_000); it.setExecutable(false) }
        assertEquals(0, h.run(AdbPush("Download/a.txt", "/sdcard/")).exitCode)
        val e = h.core.fs.files["/sdcard/a.txt"]!!
        assertEquals("hello", String(e.data))
        assertEquals(0x81A4, e.mode)
        assertEquals(1_600_000_000, e.mtime)
        assertTrue(h.sink.infos.last().contains("1 file pushed"))
        assertTrue("progress ends cleared", h.sink.progress.last().second < 0)
    }

    @Test(timeout = 30_000) fun executableFilesKeepTheirExecuteBit() = harness { h ->
        h.write("run.sh", "#!/bin/sh".toByteArray()).setExecutable(true)
        h.run(AdbPush("run.sh", "/sdcard/run.sh"))
        assertEquals(0x81ED, h.core.fs.files["/sdcard/run.sh"]!!.mode)
    }

    @Test(timeout = 30_000) fun pushTargetsAnExistingFolderOrANewName() = harness { h ->
        h.write("f.bin", bytes(70_000, 2))
        h.run(AdbPush("f.bin", "/sdcard/Docs"))
        assertArrayEquals(bytes(70_000, 2), h.core.fs.files["/sdcard/Docs/f.bin"]!!.data)
        h.run(AdbPush("f.bin", "/sdcard/renamed.bin"))
        assertTrue(h.core.fs.files.containsKey("/sdcard/renamed.bin"))
    }

    @Test(timeout = 30_000) fun relativeRemotePathsUseTheShellDirectory() = harness { h ->
        h.write("f.txt", "x".toByteArray())
        h.run(AdbShell("cd /sdcard"))
        h.run(AdbPush("f.txt", "copy.txt"))
        assertTrue(h.core.fs.files.containsKey("/sdcard/copy.txt"))
    }

    @Test(timeout = 30_000) fun pushingAMissingFileFails() = harness { h ->
        val r = h.run(AdbPush("nope.txt", "/sdcard/"))
        assertEquals(1, r.exitCode)
        assertTrue(h.sink.errors.single().contains("cannot stat 'nope.txt'"))
    }

    @Test(timeout = 30_000) fun pushingAFolderCopiesItRecursivelyAndSkipsSymlinks() = harness { h ->
        h.write("proj/a.txt", "A".toByteArray())
        h.write("proj/sub/b.bin", bytes(100_000, 9))
        h.write("proj/sub/deeper/c.txt", "C".toByteArray())
        Files.createSymbolicLink(h.file("proj/link").toPath(), h.file("proj/a.txt").toPath())
        val r = h.run(AdbPush("proj", "/sdcard"))
        assertEquals(0, r.exitCode)
        assertEquals("A", String(h.core.fs.files["/sdcard/proj/a.txt"]!!.data))
        assertArrayEquals(bytes(100_000, 9), h.core.fs.files["/sdcard/proj/sub/b.bin"]!!.data)
        assertEquals("C", String(h.core.fs.files["/sdcard/proj/sub/deeper/c.txt"]!!.data))
        assertFalse(h.core.fs.files.containsKey("/sdcard/proj/link"))
        assertTrue(h.sink.infos.any { it.contains("symbolic link") })
        assertTrue(h.sink.infos.last().contains("3 files pushed, 1 skipped"))
        assertTrue(h.seenShell.any { it == "mkdir -p /sdcard/proj/sub/deeper" })
    }

    @Test(timeout = 30_000) fun deviceRefusalOnPushIsReportedAndLeavesTheLinkAlone() = harness { h ->
        h.core.failedSends += "/sdcard/full.bin"
        h.write("full.bin", bytes(1000))
        val r = h.run(AdbPush("full.bin", "/sdcard/full.bin"))
        assertEquals(1, r.exitCode)
        assertFalse(r.linkLost)
        assertTrue(h.sink.errors.single().contains("No space left"))
        assertEquals(0, h.run(AdbShell("echo still alive")).exitCode)
    }

    // ---- pull ----------------------------------------------------------------------------------

    @Test(timeout = 30_000) fun pullGoesToDownloadsByDefault() = harness { h ->
        h.core.fs.put("/sdcard/hello.txt", "hello device".toByteArray())
        assertEquals(0, h.run(AdbPull("/sdcard/hello.txt", null)).exitCode)
        assertEquals("hello device", h.file("Download/hello.txt").readText())
        assertTrue(h.sink.infos.last().contains("1 file pulled"))
    }

    @Test(timeout = 30_000) fun pullToANameOrIntoAFolder() = harness { h ->
        h.core.fs.put("/sdcard/hello.txt", "x".toByteArray())
        h.run(AdbPull("/sdcard/hello.txt", "copy.txt"))
        assertEquals("x", h.file("copy.txt").readText())
        h.run(AdbPull("/sdcard/hello.txt", "Download"))
        assertEquals("x", h.file("Download/hello.txt").readText())
    }

    @Test(timeout = 30_000) fun pullALargeFileExactly() = harness { h ->
        h.core.fs.put("/sdcard/big.bin", bytes(300_000, 4))
        h.run(AdbPull("/sdcard/big.bin", "big.out"))
        assertArrayEquals(bytes(300_000, 4), h.file("big.out").readBytes())
        assertTrue(h.sink.progress.any { it.second > 0 && it.third == 300_000L })
    }

    @Test(timeout = 30_000) fun pullAFolderRecursively() = harness { h ->
        h.core.fs.put("/sdcard/DCIM/a.jpg", "A".toByteArray())
        h.core.fs.put("/sdcard/DCIM/2024/b.jpg", "B".toByteArray())
        h.core.fs.put("/sdcard/DCIM/2024/05/c.jpg", "C".toByteArray())
        assertEquals(0, h.run(AdbPull("/sdcard/DCIM", "out")).exitCode)
        assertEquals("A", h.file("out/a.jpg").readText())
        assertEquals("B", h.file("out/2024/b.jpg").readText())
        assertEquals("C", h.file("out/2024/05/c.jpg").readText())
        assertTrue(h.sink.infos.last().contains("3 files pulled"))
        h.run(AdbPull("/sdcard/DCIM", null))
        assertEquals("A", h.file("Download/DCIM/a.jpg").readText())
    }

    @Test(timeout = 30_000) fun pullingAMissingFileFails() = harness { h ->
        val r = h.run(AdbPull("/sdcard/missing", null))
        assertEquals(1, r.exitCode)
        assertTrue(h.sink.errors.single().contains("failed to stat remote object"))
    }

    @Test(timeout = 30_000) fun aCancelledPullLeavesNoPartialFile() = harness { h ->
        h.core.fs.put("/sdcard/big.bin", bytes(300_000))
        h.sink.onProgress = { done, _ -> if (done > 0) h.cancelled = true }
        val r = h.run(AdbPull("/sdcard/big.bin", "partial.bin"))
        assertEquals(130, r.exitCode)
        assertFalse(h.file("partial.bin").exists())
    }

    // ---- everything else ---------------------------------------------------------------------------

    @Test(timeout = 30_000) fun rebootAsksTheDeviceAndEndsTheSession() = harness { h ->
        val r = h.run(AdbReboot(AdbRebootTarget.BOOTLOADER))
        assertEquals("bootloader", h.core.rebootRequested)
        assertTrue(r.linkLost)
        assertEquals("The device is restarting.", r.note)
    }

    @Test(timeout = 30_000) fun infoCommands() = harness { h ->
        h.run(AdbInfo(AdbInfo.Info.DEVICES))
        assertTrue(h.sink.outText.contains("model:Fake_Phone") && h.sink.outText.contains("device"))
        h.run(AdbInfo(AdbInfo.Info.GET_STATE))
        assertTrue(h.sink.outText.endsWith("device\n"))
        h.run(AdbInfo(AdbInfo.Info.GET_SERIALNO))
        assertTrue(h.sink.outText.endsWith("SN123\n"))
        h.run(AdbInfo(AdbInfo.Info.VERSION))
        assertTrue(h.sink.outText.contains("native ADB client"))
        h.run(AdbInfo(AdbInfo.Info.NO_SERVER))
        assertTrue(h.sink.infos.last().contains("no adb server"))
    }

    @Test(timeout = 30_000) fun unsupportedCommandsPrintTheirHint() = harness { h ->
        val r = h.run(Unsupported("adb", "install", "do this instead"))
        assertEquals(1, r.exitCode)
        assertEquals("adb install: do this instead", h.sink.errors.single())
    }

    @Test(timeout = 30_000) fun pullingTheCableMarksTheLinkAsLost() = harness { h ->
        h.adbTransport.detach()
        val r = h.run(AdbShell("ls"))
        assertTrue(r.linkLost)
        assertEquals(1, r.exitCode)
        assertTrue(h.sink.errors.single().contains("ADB connection failed"))
    }

    @Test(timeout = 30_000) fun usingAdbWithoutAConnectionExplainsWhatToDo() {
        RunnerHarness(withAdb = false, withFastboot = false).use { h ->
            val r = h.run(AdbShell("ls"))
            assertEquals(2, r.exitCode)
            assertTrue(h.sink.errors.single().contains("not connected"))
        }
        RunnerHarness(withAdb = false, withFastboot = true).use { h ->
            h.run(AdbPull("/x", null))
            assertTrue(h.sink.errors.single().contains("fastboot mode"))
        }
    }

    @Test(timeout = 30_000) fun usingFastbootOnAnAndroidDeviceSaysSo() = harness { h ->
        val r = h.run(FbGetvar("all"))
        assertEquals(2, r.exitCode)
        assertTrue(h.sink.errors.single().contains("adb reboot bootloader"))
    }

    @Test(timeout = 30_000) fun relativeAdbReboot() = harness { h ->
        h.run(AdbReboot(AdbRebootTarget.SYSTEM))
        assertEquals("system", h.core.rebootRequested)
    }
}
