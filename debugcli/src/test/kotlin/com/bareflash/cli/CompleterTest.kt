package com.bareflash.cli

import com.bareflash.usb.DebugProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompleterTest {
    private val adb = CompletionContext(DebugProtocol.ADB, extraWords = listOf("help", "clear", "history"))
    private val fb = CompletionContext(
        DebugProtocol.FASTBOOT,
        partitions = listOf("boot_a", "boot_b", "userdata", "vendor_boot"),
        variables = listOf("partition-size:boot_a", "hw-revision"),
    )

    private fun c(line: String, ctx: CompletionContext) = CliCompleter.complete(line, ctx)

    @Test fun firstWordOffersTheToolsBuiltinsAndShellCommands() {
        assertTrue(c("", adb).candidates.containsAll(listOf("adb", "fastboot", "help", "ls", "pm")))
        assertTrue(c("", fb).candidates.containsAll(listOf("adb", "fastboot", "flash", "getvar")))
        assertEquals("adb ", c("ad", adb).line)
        assertEquals("fastboot ", c("fastb", adb).line)
        assertEquals("history ", c("hi", adb).line)
    }

    @Test fun aUniqueMatchGetsATrailingSpace() {
        assertEquals("adb push ", c("adb pus", adb).line)
        assertEquals("adb reboot ", c("adb reb", adb).line)
        assertEquals("adb shell getprop ", c("adb shell getp", adb).line)
    }

    @Test fun anAmbiguousMatchExtendsToTheCommonPrefixOrListsCandidates() {
        assertEquals("fastboot reboot", c("fastboot reb", fb).line)           // reboot, reboot-bootloader, ...
        val r = c("fastboot reboot-", fb)                                      // nothing more in common
        assertEquals("fastboot reboot-", r.line)
        assertEquals(listOf("reboot-bootloader", "reboot-fastboot", "reboot-recovery"), r.candidates)
        val pu = c("adb pu", adb)                                              // push, pull
        assertEquals("adb pu", pu.line)
        assertEquals(listOf("pull", "push"), pu.candidates)
    }

    @Test fun adbArguments() {
        assertEquals("adb reboot bootloader ", c("adb reboot boot", adb).line)
        assertTrue(c("adb reboot ", adb).candidates.containsAll(listOf("bootloader", "download", "edl", "recovery")))
        assertTrue(c("adb shell ", adb).candidates.contains("getprop"))
        assertEquals("adb logcat -", c("adb logcat ", adb).line) // every flag starts with a dash
        assertTrue(c("adb logcat -", adb).candidates.containsAll(listOf("-d", "-c", "-v")))
        assertTrue("remote paths cannot be completed", c("adb push ", adb).candidates.isEmpty())
    }

    @Test fun fastbootArgumentsUseThePartitionsAndVariables() {
        assertEquals("fastboot flash userdata ", c("fastboot flash user", fb).line)
        assertTrue(c("fastboot flash ", fb).candidates.containsAll(listOf("boot_a", "boot_b", "userdata")))
        assertEquals("fastboot flash boot_", c("fastboot flash boo", fb).line)
        assertTrue(c("fastboot getvar ", fb).candidates.containsAll(listOf("all", "current-slot", "hw-revision")))
        assertEquals("fastboot getvar hw-revision ", c("fastboot getvar hw-", fb).line)
        assertEquals("fastboot flashing unlock", c("fastboot flashing unl", fb).line) // unlock, unlock_critical
        assertEquals(listOf("a", "b"), c("fastboot set_active ", fb).candidates)
        assertEquals("fastboot reboot bootloader ", c("fastboot reboot boot", fb).line)
    }

    @Test fun bareFastbootVerbsCompleteOnlyWhenFastbootIsConnected() {
        assertEquals("flash", c("fla", fb).line)
        assertTrue(c("flash ", fb).candidates.contains("boot_a"))
        assertTrue(c("flash ", adb).candidates.isEmpty())
    }

    @Test fun helpOffersTopics() {
        assertTrue(c("help ", adb).candidates.containsAll(listOf("adb", "fastboot", "push", "flash")))
        assertEquals("help fastboot ", c("help fastb", adb).line)
    }

    @Test fun nothingMatchingLeavesTheLineAlone() {
        val r = c("adb zzz", adb)
        assertEquals("adb zzz", r.line)
        assertTrue(r.candidates.isEmpty())
    }

    @Test fun columnsLayout() {
        val text = CliCompleter.columns(listOf("alpha", "beta", "gamma", "delta", "epsilon"), width = 24)
        assertTrue(text.lines().size >= 2)
        assertTrue(text.lines().all { it.length <= 24 })
        assertEquals("", CliCompleter.columns(emptyList()))
    }
}
