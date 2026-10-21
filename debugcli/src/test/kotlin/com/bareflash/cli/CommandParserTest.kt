package com.bareflash.cli

import com.bareflash.adb.AdbRebootTarget
import com.bareflash.cli.DeviceCommand.AdbExecOut
import com.bareflash.cli.DeviceCommand.AdbInfo
import com.bareflash.cli.DeviceCommand.AdbPull
import com.bareflash.cli.DeviceCommand.AdbPush
import com.bareflash.cli.DeviceCommand.AdbReboot
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
import com.bareflash.cli.DeviceCommand.Unsupported
import com.bareflash.fastboot.FlashingCommand
import com.bareflash.fastboot.RebootTarget
import com.bareflash.usb.DebugProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {
    private fun ok(line: String, p: DebugProtocol? = null): DeviceCommand {
        val r = CommandParser.parse(line, p)
        assertTrue("expected a command for '$line' but got $r", r is ParseResult.Ok)
        return (r as ParseResult.Ok).command
    }

    private fun error(line: String, p: DebugProtocol? = null): String {
        val r = CommandParser.parse(line, p)
        assertTrue("expected an error for '$line' but got $r", r is ParseResult.Error)
        return (r as ParseResult.Error).message
    }

    @Test fun blankLinesAreEmpty() {
        assertEquals(ParseResult.Empty, CommandParser.parse("   ", null))
        assertEquals(ParseResult.Empty, CommandParser.parse("", DebugProtocol.ADB))
    }

    @Test fun syntaxErrorsAreReported() {
        assertTrue(error("adb shell 'oops").contains("quote"))
    }

    @Test fun shellKeepsTheRawRemainder() {
        assertEquals(AdbShell("ls -la \"/sdcard/My Files\" | grep  x"), ok("adb shell ls -la \"/sdcard/My Files\" | grep  x"))
        assertEquals(AdbShell("echo 'a  b'"), ok("adb   shell   echo 'a  b'  "))
    }

    @Test fun shellFlagsAreSkippedAndEmptyShellIsKept() {
        assertEquals(AdbShell("ls"), ok("adb shell -t ls"))
        assertEquals(AdbShell(""), ok("adb shell"))
    }

    @Test fun globalOptionsAreIgnored() {
        assertEquals(AdbShell("id"), ok("adb -s ABC123 shell id"))
        assertEquals(AdbShell("id"), ok("adb -d shell id"))
    }

    @Test fun bareWordsDependOnWhatIsConnected() {
        assertEquals(AdbShell("ls /sdcard"), ok("ls /sdcard", DebugProtocol.ADB))
        assertEquals(FbGetvar("product"), ok("getvar product", DebugProtocol.FASTBOOT))
        assertTrue(error("ls", null).contains("Not connected"))
        assertTrue(error("lsx", DebugProtocol.FASTBOOT).contains("unknown command"))
    }

    @Test fun prefixesWorkWhateverIsConnected() {
        assertEquals(FbGetvar("all"), ok("fastboot getvar all", DebugProtocol.ADB))
        assertEquals(AdbShell("ls"), ok("adb shell ls", DebugProtocol.FASTBOOT))
    }

    @Test fun execOutWithAndWithoutRedirect() {
        assertEquals(AdbExecOut("screencap -p", null), ok("adb exec-out screencap -p"))
        assertEquals(AdbExecOut("screencap -p", "shot.png"), ok("adb exec-out screencap -p > shot.png"))
        assertEquals(AdbExecOut("screencap -p", "my shot.png"), ok("adb exec-out screencap -p > 'my shot.png'"))
        assertEquals(AdbExecOut("echo a", "out"), ok("adb exec-out echo a >out"))
        assertTrue(error("adb exec-out").contains("usage"))
    }

    @Test fun pushAndPull() {
        assertEquals(AdbPush("a.zip", "/sdcard/"), ok("adb push a.zip /sdcard/"))
        assertEquals(AdbPush("my file.zip", "/sdcard/x"), ok("adb push 'my file.zip' /sdcard/x"))
        assertEquals(AdbPush("a", "/b"), ok("adb push --sync a /b"))
        assertEquals(AdbPull("/sdcard/DCIM", null), ok("adb pull /sdcard/DCIM"))
        assertEquals(AdbPull("/sdcard/DCIM", "pics"), ok("adb pull -a /sdcard/DCIM pics"))
        assertTrue(error("adb push a").contains("usage"))
        assertTrue(error("adb push a b c").contains("usage"))
        assertTrue(error("adb pull").contains("usage"))
        assertTrue(error("adb push --weird a /b").contains("not supported"))
    }

    @Test fun rebootTargets() {
        assertEquals(AdbReboot(AdbRebootTarget.SYSTEM), ok("adb reboot"))
        assertEquals(AdbReboot(AdbRebootTarget.BOOTLOADER), ok("adb reboot bootloader"))
        assertEquals(AdbReboot(AdbRebootTarget.FASTBOOTD), ok("adb reboot fastboot"))
        assertEquals(AdbReboot(AdbRebootTarget.DOWNLOAD), ok("adb reboot download"))
        assertEquals(AdbReboot(AdbRebootTarget.EDL), ok("adb reboot edl"))
        assertTrue(error("adb reboot sideways").contains("unknown target"))
        assertTrue(error("adb reboot a b").contains("usage"))
    }

    @Test fun logcatAndUninstallBecomeShellCommands() {
        assertEquals(AdbShell("logcat"), ok("adb logcat"))
        assertEquals(AdbShell("logcat -d -t 50"), ok("adb logcat -d -t 50"))
        assertEquals(AdbShell("pm uninstall com.example.app"), ok("adb uninstall com.example.app"))
        assertEquals(AdbShell("pm uninstall 'we ird'"), ok("adb uninstall -k 'we ird'"))
        assertTrue(error("adb uninstall").contains("usage"))
    }

    @Test fun infoCommands() {
        assertEquals(AdbInfo(AdbInfo.Info.DEVICES), ok("adb devices -l".replace(" -l", "")))
        assertEquals(AdbInfo(AdbInfo.Info.GET_STATE), ok("adb get-state"))
        assertEquals(AdbInfo(AdbInfo.Info.GET_SERIALNO), ok("adb get-serialno"))
        assertEquals(AdbInfo(AdbInfo.Info.VERSION), ok("adb version"))
        assertEquals(AdbInfo(AdbInfo.Info.NO_SERVER), ok("adb kill-server"))
    }

    @Test fun unsupportedAdbCommandsExplainThemselves() {
        for (verb in listOf("install", "root", "forward", "reverse", "tcpip", "connect", "bugreport", "sideload")) {
            val c = ok("adb $verb x")
            assertTrue(verb, c is Unsupported && c.tool == "adb" && c.hint.isNotBlank())
        }
        assertTrue((ok("adb install a.apk") as Unsupported).hint.contains("pm install"))
        assertTrue((ok("adb forward tcp:1 tcp:2") as Unsupported).hint.contains("network"))
    }

    @Test fun unknownAdbCommand() {
        assertTrue(error("adb frobnicate").contains("unknown command 'frobnicate'"))
        assertTrue(error("adb").contains("usage"))
    }

    @Test fun fastbootBasics() {
        assertEquals(FbGetvar("all"), ok("fastboot getvar all"))
        assertEquals(FbDevices, ok("fastboot devices"))
        assertEquals(FbFlash("boot", "boot.img"), ok("fastboot flash boot boot.img"))
        assertEquals(FbErase("userdata"), ok("fastboot erase userdata"))
        assertEquals(FbBoot("a b.img"), ok("fastboot boot 'a b.img'"))
        assertEquals(FbContinue, ok("fastboot continue"))
        assertEquals(FbVersion, ok("fastboot --version"))
        assertEquals(FbSetActive("b"), ok("fastboot set_active b"))
        assertEquals(FbSetActive("a"), ok("fastboot --set-active=a"))
    }

    @Test fun fastbootReboot() {
        assertEquals(FbReboot(RebootTarget.SYSTEM), ok("fastboot reboot"))
        assertEquals(FbReboot(RebootTarget.BOOTLOADER), ok("fastboot reboot bootloader"))
        assertEquals(FbReboot(RebootTarget.FASTBOOTD), ok("fastboot reboot-fastboot"))
        assertEquals(FbReboot(RebootTarget.RECOVERY), ok("fastboot reboot-recovery"))
        assertTrue(error("fastboot reboot x").contains("unknown target"))
    }

    @Test fun fastbootFlashingOemAndLogical() {
        assertEquals(FbFlashing(FlashingCommand.UNLOCK), ok("fastboot flashing unlock"))
        assertEquals(FbFlashing(FlashingCommand.GET_UNLOCK_ABILITY), ok("fastboot flashing get_unlock_ability"))
        assertTrue(error("fastboot flashing explode").contains("unknown subcommand"))
        assertEquals(FbOem("device-info  x"), ok("fastboot oem device-info  x"))
        assertTrue(error("fastboot oem").contains("usage"))
        assertEquals(FbLogical(FbLogical.Op.CREATE, "foo", 4096), ok("fastboot create-logical-partition foo 4096"))
        assertEquals(FbLogical(FbLogical.Op.RESIZE, "foo", 8192), ok("fastboot resize-logical-partition foo 8192"))
        assertEquals(FbLogical(FbLogical.Op.DELETE, "foo", null), ok("fastboot delete-logical-partition foo"))
        assertTrue(error("fastboot create-logical-partition foo big").contains("not a size"))
        assertTrue(error("fastboot create-logical-partition foo -1").contains("not a size"))
    }

    @Test fun fastbootUsageErrors() {
        assertTrue(error("fastboot flash boot").contains("usage"))
        assertTrue(error("fastboot getvar").contains("usage"))
        assertTrue(error("fastboot erase a b").contains("usage"))
        assertTrue(error("fastboot").contains("usage"))
        assertTrue(error("fastboot yodel").contains("unknown command"))
    }

    @Test fun unsupportedFastbootCommandsExplainThemselves() {
        for (verb in listOf("format", "-w", "update", "flashall")) {
            val c = ok("fastboot $verb")
            assertTrue(verb, c is Unsupported && c.hint.isNotBlank())
        }
        assertTrue((ok("fastboot -w") as Unsupported).hint.contains("erase userdata"))
    }
}
