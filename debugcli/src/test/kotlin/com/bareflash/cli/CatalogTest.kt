package com.bareflash.cli

import com.bareflash.usb.DebugProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    private fun sample(a: CatalogArg): String = when {
        a.default.isNotEmpty() -> a.default
        a.kind == ArgKind.NUMBER -> "1"
        a.kind == ArgKind.LOCAL_PATH -> "file.bin"
        a.kind == ArgKind.REMOTE_PATH -> "/sdcard/x"
        a.kind == ArgKind.PACKAGE -> "com.example.app"
        a.kind == ArgKind.PARTITION -> "boot"
        a.kind == ArgKind.CHOICE -> a.choices.first()
        else -> "sample"
    }

    private fun filled(c: CatalogCommand): String {
        val r = TemplateFiller.fill(c, c.args.associate { it.name to sample(it) })
        assertTrue("${c.id}: $r", r is FillResult.Ok)
        return (r as FillResult.Ok).line
    }

    @Test fun idsAreUniqueAndEveryEntryIsComplete() {
        val ids = CommandCatalog.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (c in CommandCatalog.ALL) {
            assertTrue(c.id, c.title.isNotBlank() && c.category.isNotBlank())
            assertTrue(c.id, c.template.startsWith(if (c.requires == DebugProtocol.ADB) "adb " else "fastboot "))
            if (c.risk != RiskLevel.SAFE) assertTrue("${c.id} needs a description", c.description.isNotBlank())
        }
        assertTrue(CommandCatalog.ALL.size >= 70)
    }

    @Test fun everyTemplateParsesAndItsDeclaredRiskMatchesTheAssessor() {
        for (c in CommandCatalog.ALL) {
            val line = filled(c)
            val parsed = CommandParser.parse(line, c.requires)
            assertTrue("${c.id}: '$line' -> $parsed", parsed is ParseResult.Ok)
            val command = (parsed as ParseResult.Ok).command
            assertFalse("${c.id} must not be unsupported", command is DeviceCommand.Unsupported)
            val risk = RiskAssessor.assess(command)
            assertEquals("${c.id}: '$line'", c.risk, risk.level)
            if (risk.level == RiskLevel.DANGEROUS) assertFalse("${c.id} needs a phrase", risk.phrase.isNullOrBlank())
        }
    }

    @Test fun optionalBlanksCanBeLeftEmpty() {
        for (c in CommandCatalog.ALL.filter { cmd -> cmd.args.any { it.optional } }) {
            val values = c.args.associate { it.name to (if (it.optional) "" else sample(it)) }
            val r = TemplateFiller.fill(c, values)
            assertTrue("${c.id}: $r", r is FillResult.Ok)
            val line = (r as FillResult.Ok).line
            assertFalse(line.endsWith(" "))
            assertTrue("${c.id}: '$line'", CommandParser.parse(line, c.requires) is ParseResult.Ok)
        }
    }

    @Test fun categoriesComeInOrder() {
        assertEquals(listOf("Device", "Apps", "Files", "Screen & input", "Logs", "Power"), CommandCatalog.categories(DebugProtocol.ADB))
        assertEquals(listOf("Info", "Reboot", "Flash", "Bootloader", "Logical partitions"), CommandCatalog.categories(DebugProtocol.FASTBOOT))
        assertTrue(CommandCatalog.forProtocol(DebugProtocol.FASTBOOT).all { it.requires == DebugProtocol.FASTBOOT })
    }

    private val uninstall = CommandCatalog.ALL.first { it.id == "adb-app-uninstall" }
    private val tap = CommandCatalog.ALL.first { it.id == "adb-tap" }
    private val oem = CommandCatalog.ALL.first { it.id == "fb-oem" }
    private val wifi = CommandCatalog.ALL.first { it.id == "adb-wifi" }

    @Test fun valuesAreQuotedSoTheyCannotInjectCommands() {
        val r = TemplateFiller.fill(uninstall, mapOf("package" to "x; rm -rf /sdcard")) as FillResult.Ok
        assertEquals("adb shell pm uninstall 'x; rm -rf /sdcard'", r.line)
        val parsed = (CommandParser.parse(r.line, DebugProtocol.ADB) as ParseResult.Ok).command
        // The whole value stays one word of the pm command: it is not a second command.
        assertEquals(DeviceCommand.AdbShell("pm uninstall 'x; rm -rf /sdcard'"), parsed)
        assertEquals(RiskLevel.CAUTION, RiskAssessor.assess(parsed).level)
        val quoteInside = TemplateFiller.fill(uninstall, mapOf("package" to "it's")) as FillResult.Ok
        assertEquals("adb shell pm uninstall 'it'\\''s'", quoteInside.line)
    }

    @Test fun rawBlanksAreInsertedAsTyped() {
        assertEquals("fastboot oem device-info x", (TemplateFiller.fill(oem, mapOf("command" to "device-info x")) as FillResult.Ok).line)
    }

    @Test fun missingAndInvalidValues() {
        assertEquals(FillResult.Missing("Package name"), TemplateFiller.fill(uninstall, emptyMap()))
        assertEquals(FillResult.Missing("Package name"), TemplateFiller.fill(uninstall, mapOf("package" to "   ")))
        assertTrue(TemplateFiller.fill(tap, mapOf("x" to "ten", "y" to "5")) is FillResult.Invalid)
        assertTrue(TemplateFiller.fill(tap, mapOf("x" to "1\n2", "y" to "5")) is FillResult.Invalid)
        assertTrue(TemplateFiller.fill(wifi, mapOf("state" to "maybe")) is FillResult.Invalid)
        assertEquals("adb shell svc wifi enable", (TemplateFiller.fill(wifi, emptyMap()) as FillResult.Ok).line) // default
        assertEquals("adb shell input tap 10 -5", (TemplateFiller.fill(tap, mapOf("x" to "10", "y" to "-5")) as FillResult.Ok).line)
    }

    @Test fun anInvalidChoiceListsTheOptions() {
        val r = TemplateFiller.fill(wifi, mapOf("state" to "maybe")) as FillResult.Invalid
        assertTrue(r.why.contains("enable") && r.why.contains("disable"))
    }
}
