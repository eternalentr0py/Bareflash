package com.bareflash.app.engine

import com.bareflash.app.catalog.McCatalog
import com.bareflash.app.catalog.McCommand
import com.bareflash.app.catalog.McGroup
import com.bareflash.app.catalog.QdlCatalog
import com.bareflash.app.console.CommandRules
import com.bareflash.app.ui.NcCategories
import com.bareflash.app.ui.NcCategory

/** Mode-aware front door to the two catalogs and their command rules. */
object Catalogs {

    fun groups(mode: EngineMode): List<McGroup> = when (mode) {
        EngineMode.MTK -> McCatalog.groups
        EngineMode.QDL -> QdlCatalog.groups
        EngineMode.BROKKR, EngineMode.ADB_FASTBOOT -> emptyList()
    }

    fun all(mode: EngineMode): List<McCommand> = groups(mode).flatMap { it.commands }

    fun find(mode: EngineMode, cmd: String): McCommand? = all(mode).firstOrNull { it.cmd == cmd }

    /** Title lookup used by history chips / job labels; checks both catalogs. */
    fun titleFor(cmd: String): String {
        val first = cmd.trim().substringBefore(' ')
        val both = McCatalog.all + QdlCatalog.all
        return both.firstOrNull { it.cmd == cmd }?.title
            ?: both.firstOrNull { it.cmd == first }?.title
            ?: cmd
    }

    fun isKnown(mode: EngineMode, cmd: String): Boolean = when (mode) {
        EngineMode.MTK -> CommandRules.isKnownCommand(cmd)
        EngineMode.QDL -> QdlRules.isKnown(cmd)
        EngineMode.BROKKR, EngineMode.ADB_FASTBOOT -> false
    }

    fun isDestructive(mode: EngineMode, cmd: String): Boolean = when (mode) {
        EngineMode.MTK -> CommandRules.isDestructive(cmd)
        EngineMode.QDL -> QdlRules.isDestructive(cmd)
        EngineMode.BROKKR, EngineMode.ADB_FASTBOOT -> false
    }

    /** Category deck entries for [mode], "All" first. */
    fun categories(mode: EngineMode): List<NcCategory> = when (mode) {
        EngineMode.MTK -> NcCategories
        EngineMode.QDL -> listOf(
            NcCategory(null, "All verbs", "ALL", "Every flash, read and device verb in one deck."),
            NcCategory("Flash", "Flash", "FLASH", "Firmware packages and rawprogram / patch XML."),
            NcCategory("Partition I/O", "Partition I/O", "I/O", "Read, write and hash partitions or sector ranges."),
            NcCategory("Erase (destructive)", "Erase", "ERASE", "Destructive wipes. Review twice before you run."),
            NcCategory("Device control", "Device", "DEVICE", "Reset out of EDL and engine info."),
            NcCategory("Advanced", "Advanced", "ADVANCED", "RAM dumps, VIP digests, flash ZIPs, keystore.")
        )
        EngineMode.BROKKR, EngineMode.ADB_FASTBOOT -> emptyList()
    }
}
