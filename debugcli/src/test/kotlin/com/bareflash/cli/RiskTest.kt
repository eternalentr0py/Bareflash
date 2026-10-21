package com.bareflash.cli

import com.bareflash.adb.AdbRebootTarget
import com.bareflash.cli.DeviceCommand.AdbPull
import com.bareflash.cli.DeviceCommand.AdbPush
import com.bareflash.cli.DeviceCommand.AdbReboot
import com.bareflash.cli.DeviceCommand.AdbShell
import com.bareflash.cli.DeviceCommand.FbBoot
import com.bareflash.cli.DeviceCommand.FbErase
import com.bareflash.cli.DeviceCommand.FbFlash
import com.bareflash.cli.DeviceCommand.FbFlashing
import com.bareflash.cli.DeviceCommand.FbGetvar
import com.bareflash.cli.DeviceCommand.FbLogical
import com.bareflash.cli.DeviceCommand.FbOem
import com.bareflash.cli.DeviceCommand.FbReboot
import com.bareflash.cli.DeviceCommand.FbSetActive
import com.bareflash.fastboot.FlashingCommand
import com.bareflash.fastboot.RebootTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskTest {
    private fun shell(text: String) = RiskAssessor.assess(AdbShell(text))

    private fun level(text: String) = shell(text).level

    @Test fun everydayShellCommandsAreSafe() {
        for (s in listOf("ls -la /sdcard", "cat /proc/cpuinfo", "getprop ro.product.model", "dumpsys battery", "pm list packages -3",
            "am start -n a/.B", "input keyevent 3", "logcat -d", "ps -A | grep zygote", "echo hello > /sdcard/a.txt", "cd /sdcard && ls",
            "df -h", "settings get global airplane_mode_on", "wm size", "reboot", "su", "pm path com.x", "echo x 2>/dev/null"))
            assertEquals(s, RiskLevel.SAFE, level(s))
    }

    @Test fun deletingIsCautionOrDangerous() {
        assertEquals(RiskLevel.CAUTION, level("rm /sdcard/a.txt"))
        assertEquals(RiskLevel.DANGEROUS, level("rm -rf /sdcard/Pictures"))
        assertEquals(RiskLevel.DANGEROUS, level("rm -r /sdcard/x"))
        assertEquals(RiskLevel.DANGEROUS, level("rm -f x"))
        assertEquals(RiskLevel.DANGEROUS, level("rm --recursive x"))
        assertEquals("rm", shell("rm -rf /x").phrase)
        assertEquals(RiskLevel.CAUTION, level("rmdir x"))
        assertEquals(RiskLevel.CAUTION, level("mv a b"))
    }

    @Test fun lowLevelDiskToolsAreDangerous() {
        for (s in listOf("dd if=/dev/zero of=/dev/block/sda1", "mkfs.ext4 /dev/block/x", "mke2fs x", "make_ext4fs x", "wipe data", "blkdiscard /dev/x", "sgdisk -Z /dev/x", "shred f", "fdisk /dev/x"))
            assertEquals(s, RiskLevel.DANGEROUS, level(s))
        assertEquals("dd", shell("dd if=a of=b").phrase)
    }

    @Test fun redirectsIntoDeviceNodes() {
        assertEquals(RiskLevel.DANGEROUS, level("cat boot.img > /dev/block/by-name/boot"))
        assertEquals(RiskLevel.DANGEROUS, level("echo x >/dev/block/sda"))
        assertEquals(RiskLevel.SAFE, level("echo x > /dev/null"))
        assertEquals(RiskLevel.SAFE, level("cmd 2>/dev/null"))
        assertEquals(RiskLevel.SAFE, level("echo x > /dev/zero"))
        assertEquals(RiskLevel.CAUTION, level("echo 1 > /proc/sys/vm/drop_caches"))
        assertEquals(RiskLevel.CAUTION, level("echo 0 > /sys/class/leds/x/brightness"))
    }

    @Test fun everyCommandInALineIsChecked() {
        assertEquals(RiskLevel.DANGEROUS, level("ls; rm -rf /x"))
        assertEquals(RiskLevel.DANGEROUS, level("true && rm -rf /x"))
        assertEquals(RiskLevel.DANGEROUS, level("false || dd if=a of=b"))
        assertEquals(RiskLevel.DANGEROUS, level("ls | xargs rm -rf"))
        assertEquals(RiskLevel.DANGEROUS, level("sleep 1 & rm -r x"))
        assertEquals(RiskLevel.DANGEROUS, level("echo \$(rm -rf /x)"))
        assertEquals(RiskLevel.DANGEROUS, level("echo `rm -rf /x`"))
        assertEquals(RiskLevel.DANGEROUS, level("ls\nrm -rf x"))
    }

    @Test fun wrappersAndEnvAreSeenThrough() {
        assertEquals(RiskLevel.DANGEROUS, level("su -c 'rm -rf /data/x'"))
        assertEquals(RiskLevel.DANGEROUS, level("sh -c \"dd if=a of=b\""))
        assertEquals(RiskLevel.DANGEROUS, level("FOO=1 rm -rf x"))
        assertEquals(RiskLevel.DANGEROUS, level("env A=b rm -rf x"))
        assertEquals(RiskLevel.DANGEROUS, level("nohup rm -rf x"))
        assertEquals(RiskLevel.DANGEROUS, level("/system/bin/rm -rf x"))
        assertEquals(RiskLevel.DANGEROUS, level("busybox dd if=a of=b"))
        assertEquals(RiskLevel.CAUTION, level("su 0 rm x"))
    }

    @Test fun quotedSeparatorsDoNotSplit() {
        assertEquals(RiskLevel.SAFE, level("echo 'a; rm -rf /x'"))
        assertEquals(RiskLevel.SAFE, level("echo \"a && dd if=a of=b\""))
    }

    @Test fun packageAndSettingsChanges() {
        assertEquals(RiskLevel.CAUTION, level("pm uninstall com.x"))
        assertEquals(RiskLevel.CAUTION, level("pm clear com.x"))
        assertEquals(RiskLevel.CAUTION, level("pm disable-user --user 0 com.x"))
        assertEquals(RiskLevel.CAUTION, level("cmd package uninstall com.x"))
        assertEquals(RiskLevel.SAFE, level("cmd package list packages"))
        assertEquals(RiskLevel.SAFE, level("pm list packages"))
        assertEquals(RiskLevel.CAUTION, level("settings put system screen_brightness 10"))
        assertEquals(RiskLevel.SAFE, level("settings list system"))
        assertEquals(RiskLevel.CAUTION, level("setprop persist.x 1"))
        assertEquals(RiskLevel.CAUTION, level("wm size 1080x1920"))
        assertEquals(RiskLevel.SAFE, level("wm size"))
        assertEquals(RiskLevel.CAUTION, level("chmod -R 777 /sdcard/x"))
        assertEquals(RiskLevel.SAFE, level("chmod 644 a"))
        assertEquals(RiskLevel.CAUTION, level("svc wifi disable"))
        assertEquals(RiskLevel.CAUTION, level("kill 123"))
    }

    @Test fun wipeFromRecovery() {
        assertEquals(RiskLevel.DANGEROUS, level("recovery --wipe_data"))
        assertEquals(RiskLevel.SAFE, level("recovery --version"))
    }

    @Test fun pushIntoProtectedLocations() {
        assertEquals(RiskLevel.DANGEROUS, RiskAssessor.assess(AdbPush("a", "/system/bin/x")).level)
        assertEquals(RiskLevel.DANGEROUS, RiskAssessor.assess(AdbPush("a", "/vendor")).level)
        assertEquals("push", RiskAssessor.assess(AdbPush("a", "/boot/x")).phrase)
        assertEquals(RiskLevel.SAFE, RiskAssessor.assess(AdbPush("a", "/sdcard/Download/")).level)
        assertEquals(RiskLevel.SAFE, RiskAssessor.assess(AdbPush("a", "/systemx/y")).level)
        assertEquals(RiskLevel.SAFE, RiskAssessor.assess(AdbPull("/system/build.prop", null)).level)
    }

    @Test fun rebootsAreSafe() {
        assertEquals(RiskLevel.SAFE, RiskAssessor.assess(AdbReboot(AdbRebootTarget.BOOTLOADER)).level)
        assertEquals(RiskLevel.SAFE, RiskAssessor.assess(FbReboot(RebootTarget.SYSTEM)).level)
    }

    @Test fun fastbootRisks() {
        assertEquals(RiskLevel.SAFE, RiskAssessor.assess(FbGetvar("all")).level)
        val flash = RiskAssessor.assess(FbFlash("boot_a", "x.img"))
        assertEquals(RiskLevel.DANGEROUS, flash.level)
        assertEquals("boot_a", flash.phrase)
        assertTrue(flash.reason.contains("boot_a"))
        assertEquals("userdata", RiskAssessor.assess(FbErase("userdata")).phrase)
        assertEquals(RiskLevel.DANGEROUS, RiskAssessor.assess(FbOem("unlock")).level)
        assertEquals(RiskLevel.CAUTION, RiskAssessor.assess(FbBoot("x.img")).level)
        assertEquals(RiskLevel.CAUTION, RiskAssessor.assess(FbSetActive("b")).level)
    }

    @Test fun bootloaderLockStates() {
        assertEquals("unlock", RiskAssessor.assess(FbFlashing(FlashingCommand.UNLOCK)).phrase)
        assertEquals("unlock", RiskAssessor.assess(FbFlashing(FlashingCommand.UNLOCK_CRITICAL)).phrase)
        assertEquals("lock", RiskAssessor.assess(FbFlashing(FlashingCommand.LOCK)).phrase)
        assertEquals(RiskLevel.SAFE, RiskAssessor.assess(FbFlashing(FlashingCommand.GET_UNLOCK_ABILITY)).level)
    }

    @Test fun logicalPartitions() {
        assertEquals(RiskLevel.CAUTION, RiskAssessor.assess(FbLogical(FbLogical.Op.CREATE, "x", 1)).level)
        assertEquals("x", RiskAssessor.assess(FbLogical(FbLogical.Op.DELETE, "x", null)).phrase)
        assertEquals(RiskLevel.DANGEROUS, RiskAssessor.assess(FbLogical(FbLogical.Op.RESIZE, "x", 1)).level)
    }

    @Test fun everyDangerousFastbootCommandHasAPhrase() {
        val cmds = listOf(FbFlash("p", "f"), FbErase("p"), FbOem("x"), FbFlashing(FlashingCommand.UNLOCK), FbFlashing(FlashingCommand.LOCK),
            FbLogical(FbLogical.Op.DELETE, "n", null), FbLogical(FbLogical.Op.RESIZE, "n", 1), AdbPush("a", "/system/x"))
        for (c in cmds) {
            val r = RiskAssessor.assess(c)
            assertEquals(c.toString(), RiskLevel.DANGEROUS, r.level)
            assertTrue(c.toString(), !r.phrase.isNullOrBlank() && r.reason.isNotBlank())
        }
    }
}
