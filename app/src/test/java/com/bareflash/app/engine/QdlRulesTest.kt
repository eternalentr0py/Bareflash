package com.bareflash.app.engine

import com.bareflash.app.catalog.QdlCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * QdlRules turns the app's QDL verbs into the argv handed to the native qdl
 * engine, so a mistake here sends the wrong command to a device. Everything is
 * checked against a throwaway folder; no device or Android runtime is involved.
 */
class QdlRulesTest {

    private fun withDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("bareflash-qdl").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun rejectMessage(plan: QdlRules.Plan): String =
        (plan as QdlRules.Plan.Reject).message

    // ---- classification --------------------------------------------------

    @Test
    fun tokenizeSplitsOnWhitespaceAndHonoursQuotes() {
        assertEquals(listOf("read", "boot", "my file.img"), QdlRules.tokenize("read  boot \"my file.img\""))
        assertEquals(emptyList<String>(), QdlRules.tokenize("   "))
    }

    @Test
    fun onlyTheVerbsThatChangeTheDeviceAreDestructive() {
        for (verb in listOf("flash", "xml", "write", "erase")) {
            assertTrue(verb, QdlRules.isDestructive(verb))
            assertTrue(verb, QdlRules.isDestructive("  $verb something"))
        }
        for (verb in listOf("read", "readxml", "sha256", "chipinfo", "version", "reset", "ramdump", "digests", "createzip")) {
            assertFalse(verb, QdlRules.isDestructive(verb))
        }
    }

    @Test
    fun isKnownMatchesTheCatalog() {
        assertTrue(QdlRules.isKnown("version"))
        assertTrue(QdlRules.isKnown("clear"))
        assertFalse(QdlRules.isKnown("bogus"))
        assertFalse(QdlRules.isKnown(""))
        for (c in QdlCatalog.all) assertTrue(c.cmd, QdlRules.isKnown(c.cmd))
    }

    // ---- planning --------------------------------------------------------

    @Test
    fun emptyAndUnknownCommandsAreRejected() = withDir { dir ->
        assertEquals("Empty command.", rejectMessage(QdlRules.plan("", QdlSettings(), dir)))
        assertTrue(rejectMessage(QdlRules.plan("bogus", QdlSettings(), dir)).contains("not a QDL command"))
    }

    @Test
    fun verbsThatCannotRunOnAndroidAreRejectedWithTheirReason() = withDir { dir ->
        for (c in QdlCatalog.all.filter { it.unsupported != null }) {
            val msg = rejectMessage(QdlRules.plan(c.cmd, QdlSettings(), dir))
            assertEquals("${c.cmd}: ${c.unsupported}", msg)
        }
    }

    @Test
    fun versionNeedsNoDevice() = withDir { dir ->
        assertEquals(QdlRules.Plan.Run(listOf("--version"), needsDevice = false), QdlRules.plan("version", QdlSettings(), dir))
    }

    @Test
    fun missingArgumentsAreReported() = withDir { dir ->
        assertTrue(rejectMessage(QdlRules.plan("read boot", QdlSettings(), dir)).contains("needs 2 argument(s)"))
        assertTrue(rejectMessage(QdlRules.plan("erase", QdlSettings(), dir)).contains("needs 1 argument(s)"))
    }

    @Test
    fun commandsThatNeedAProgrammerRefuseWithoutOne() = withDir { dir ->
        val msg = rejectMessage(QdlRules.plan("read boot out.img", QdlSettings(), dir))
        assertTrue(msg, msg.startsWith("No programmer selected"))
    }

    @Test
    fun readPlacesTheProgrammerFirstAndWritesUnderTheDumpFolder() = withDir { dir ->
        val prog = File(dir, "prog.elf").apply { writeText("x") }
        val plan = QdlRules.plan("read boot out.img", QdlSettings(programmer = prog.absolutePath), dir)
        assertEquals(
            QdlRules.Plan.Run(
                listOf(prog.absolutePath, "read", "boot", File(dir, "out.img").absolutePath),
                needsDevice = true
            ),
            plan
        )
    }

    @Test
    fun dryRunNeedsNoDeviceAndPutsTheFlagBeforeTheProgrammer() = withDir { dir ->
        val prog = File(dir, "prog.elf").apply { writeText("x") }
        val plan = QdlRules.plan("reset", QdlSettings(programmer = prog.absolutePath, dryRun = true), dir)
        assertEquals(QdlRules.Plan.Run(listOf("--dry-run", prog.absolutePath, "reset"), needsDevice = false), plan)
    }

    @Test
    fun sessionOptionsAreAddedInAFixedOrderAndTrimmed() = withDir { dir ->
        val prog = File(dir, "prog.elf").apply { writeText("x") }
        val s = QdlSettings(programmer = prog.absolutePath, storage = "ufs", serial = " abc ", debug = true)
        val plan = QdlRules.plan("reset", s, dir)
        assertEquals(
            QdlRules.Plan.Run(listOf("--debug", "--storage=ufs", "--serial=abc", prog.absolutePath, "reset"), needsDevice = true),
            plan
        )
    }

    @Test
    fun flashPutsOptionsBeforeTheVerbBecauseQdlTreatsTheFirstBareWordAsTheVerb() = withDir { dir ->
        val fw = File(dir, "fw.zip").apply { writeText("x") }
        val plan = QdlRules.plan("flash fw.zip", QdlSettings(dryRun = true), dir)
        assertEquals(QdlRules.Plan.Run(listOf("--dry-run", "flash", fw.absolutePath), needsDevice = false), plan)
    }

    @Test
    fun flashAppendsTheSpecifier() = withDir { dir ->
        val fw = File(dir, "fw.zip").apply { writeText("x") }
        val plan = QdlRules.plan("flash fw.zip ufs", QdlSettings(), dir)
        assertEquals(QdlRules.Plan.Run(listOf("flash", fw.absolutePath + "::ufs"), needsDevice = true), plan)
    }

    @Test
    fun flashAcceptsAFolderAndUsesTheManifestInside() = withDir { dir ->
        val pkg = File(dir, "pkg").apply { mkdirs() }
        val manifest = File(pkg, "contents.xml").apply { writeText("x") }
        val plan = QdlRules.plan("flash pkg", QdlSettings(), dir)
        assertEquals(QdlRules.Plan.Run(listOf("flash", manifest.absolutePath), needsDevice = true), plan)
    }

    @Test
    fun unreadableInputsAreRejectedInsteadOfSentToTheDevice() = withDir { dir ->
        val msg = rejectMessage(QdlRules.plan("flash missing.zip", QdlSettings(dryRun = true), dir))
        assertTrue(msg, msg.contains("Cannot read missing.zip"))
    }

    @Test
    fun everyUsableCatalogVerbIsHandledByThePlanner() = withDir { dir ->
        for (c in QdlCatalog.all.filter { it.unsupported == null }) {
            val plan = QdlRules.plan(c.cmd, QdlSettings(), dir)
            if (plan is QdlRules.Plan.Reject) {
                assertFalse("${c.cmd}: ${plan.message}", plan.message.contains("is not implemented"))
                assertFalse("${c.cmd}: ${plan.message}", plan.message.contains("is not a QDL command"))
            }
        }
    }

    // ---- notes -----------------------------------------------------------

    @Test
    fun noteListIsEmptyForDefaultSettings() {
        assertEquals(emptyList<String>(), QdlRules.notes(QdlSettings()))
    }

    @Test
    fun notesDescribeWhatTheSessionTabAdds() {
        val s = QdlSettings(
            programmer = "/x/prog.elf", storage = "ufs", serial = "s1", slot = "a",
            dryRun = true, skipReset = true, allowMissing = true, finalizeProvisioning = true,
        )
        assertEquals(
            listOf(
                "Programmer: prog.elf",
                "Storage: ufs",
                "Serial: s1",
                "Slot: a",
                "Dry run (nothing is sent)",
                "Skip reset after flashing",
                "Allow missing files",
                "Finalize provisioning",
            ),
            QdlRules.notes(s)
        )
    }
}
