package com.bareflash.app.catalog

/**
 * Command catalog for the Commands tab. Mirrors the CLI defined in
 * app/src/main/python/mtk.py; when that parser changes, update the matching
 * [McCommand] here. Commands are grouped into five categories for browsing.
 */
object McCatalog {

    private val partitionsArg = McArg(
        "Partition names", "boot,recovery  (comma-separated)"
    )

    val groups: List<McGroup> = listOf(

        McGroup("DA Bypass", listOf(
            McCommand("crash", "Crash Preloader to BootROM",
                "Forces a device in Preloader mode into BootROM mode so that a payload can be executed."),
            McCommand("payload", "Run bypass payload",
                "Disables serial link and download agent authorization so that protected devices can be accessed.",
                args = listOf(
                    McArg("Exploit type (optional)", "kamakiri, kamakiri2 or carbonara",
                        optional = true, flag = "--ptype",
                        choices = pickOf("kamakiri", "kamakiri2", "carbonara")),
                    McArg("var1 override (hex, optional)", "0xa",
                        optional = true, flag = "--var1"),
                    McArg("Watchdog address override (hex, optional)", "0x10007000",
                        optional = true, flag = "--wdt"),
                    McArg("Crash mode override (optional)", "0, 1 or 2",
                        optional = true, flag = "--mode",
                        choices = pickOf("0", "1", "2"))
                )
            ),
            McCommand("brute", "Brute-force var1",
                "Automatically tries var1 values when the default value does not work."),
            McCommand("stage", "Run stage 2 (BootROM)",
                "Loads and executes the stage 2 payload through BootROM mode."),
            McCommand("plstage", "Run stage 2 (Preloader)",
                "Loads and executes the stage 2 payload through the Preloader."),
            McCommand("da efuse", "Read eFuses",
                "Reads the device eFuse contents through the Download Agent."),
            McCommand("da generatekeys", "Generate hardware keys",
                "Derives the device hardware keys through the Download Agent."),
            McCommand("da keyserver", "Start key server",
                "Starts the Download Agent key server."),
            McCommand("da dumpbrom", "Dump BootROM (DA)",
                "Dumps the BootROM through the Download Agent."),
            McCommand("da seccfg", "Unlock or lock bootloader",
                "Sets the seccfg lock state. Requires confirmation.",
                args = listOf(McArg("Lock state", "unlock or lock", choices = listOf(
                    McChoice("unlock", "Unlock", "Unlocks the bootloader. This wipes user data on most devices."),
                    McChoice("lock", "Lock", "Locks the bootloader again.")
                )))
            ),
            McCommand("da vbmeta", "Patch vbmeta",
                "Sets the vbmeta verification mode. Requires confirmation.",
                args = listOf(McArg("Verification mode", "0 = locked, 1 = disable verity, 2 = disable verification, 3 = both", choices = listOf(
                    McChoice("0", "0", "Locked: verified boot fully enforced."),
                    McChoice("1", "1", "Disable verity."),
                    McChoice("2", "2", "Disable verification."),
                    McChoice("3", "3", "Disable both verity and verification.")
                )))
            ),
            McCommand("da poke", "Write memory (DA)",
                "Writes data to a memory address through the Download Agent. Requires confirmation.",
                args = listOf(McArg("Address (hex)", "0x..."), McArg("Data", ""))
            ),
            McCommand("da peek", "Read memory (DA)",
                "Reads memory through the Download Agent.",
                args = listOf(
                    McArg("Address (hex)", "0x..."),
                    McArg("Length", "0x100"),
                    McArg("Filename (optional)", "peek.bin", optional = true, flag = "--filename",
                        pathUnderDumps = true)
                )
            ),
            McCommand("da rpmb r", "Read RPMB",
                "Reads the Replay Protected Memory Block to a file.",
                args = listOf(McArg("Output filename", "rpmb.bin", default = "rpmb.bin", pathUnderDumps = true))
            ),
            McCommand("da rpmb w", "Write RPMB",
                "Writes the Replay Protected Memory Block from a file. Requires confirmation.",
                args = listOf(McArg("Input filename", "rpmb.bin", default = "rpmb.bin", pathUnderDumps = true))
            ),
            McCommand("da rpmb e", "Erase RPMB",
                "Erases the Replay Protected Memory Block. Requires confirmation."),
            McCommand("da rpmb a", "Auth RPMB key",
                "Authenticates an RPMB key (hex, 32 bytes).",
                args = listOf(McArg("RPMB key (hex)", "32-byte key", flag = "--rpmbkey"))),
            McCommand("da meta", "META mode tools",
                "Runs META mode tools through the Download Agent.",
                args = listOf(McArg("META mode", "off, usb or uart", choices = pickOf("off", "usb", "uart")))),
            McCommand("da nvitem", "NV item crypto",
                "Encrypt or decrypt an NV item file (LD0B_001.bin style). Add --encrypt on the command line to encrypt.",
                args = listOf(
                    McArg("Filename", "LD0B_001.bin", pathUnderDumps = true, attach = true),
                )),
            McCommand("da memdump", "Dump memory areas",
                "Dumps whole memory areas through the Download Agent.",
                args = listOf(McArg("Output directory", "memdump", pathUnderDumps = true))),
            McCommand("da memdram", "Dump DRAM",
                "Dumps DRAM through the Download Agent.",
                args = listOf(McArg("Output directory", "dramdump", pathUnderDumps = true))),
            McCommand("da imei", "Read or write IMEI",
                "Reads IMEI data through the Download Agent. Enter values to write them.",
                args = listOf(McArg("IMEI values (optional, comma-separated)", "", optional = true))
            )
        )),

        McGroup("Partition I/O", listOf(
            McCommand("gpt", "Save partition table",
                "Saves the raw partition table to a folder.",
                args = listOf(McArg("Folder name", "gpt", default = "gpt", pathUnderDumps = true))
            ),
            McCommand("r", "Read partitions",
                "Reads one or more partitions to files.",
                args = listOf(
                    McArg("Partition names", "boot,recovery  (comma-separated)"),
                    McArg("Output filenames", "boot.img,recovery.img  (one per partition)",
                        pathUnderDumps = true)
                )
            ),
            McCommand("rl", "Read all partitions",
                "Reads every accessible partition into a single folder.",
                args = listOf(McArg("Folder name", "backup", default = "backup",
                    pathUnderDumps = true))
            ),
            McCommand("rf", "Read full flash",
                "Reads the entire raw flash to a single file.",
                args = listOf(McArg("Output filename", "full_flash.bin", pathUnderDumps = true))
            ),
            McCommand("rs", "Read sectors",
                "Reads a sector range to a file.",
                args = listOf(
                    McArg("Start sector", "0"),
                    McArg("Sector count", "2048"),
                    McArg("Output filename", "sectors.bin", pathUnderDumps = true)
                )
            ),
            McCommand("ro", "Read byte range",
                "Reads a byte range, given an offset and length, to a file.",
                args = listOf(
                    McArg("Byte offset", "0x0"),
                    McArg("Length", "0x100000"),
                    McArg("Output filename", "range.bin", pathUnderDumps = true)
                )
            ),
            McCommand("footer", "Read crypto footer",
                "Reads the userdata encryption footer to a file.",
                args = listOf(McArg("Output filename", "footer.bin", pathUnderDumps = true))
            ),
            McCommand("w", "Write partitions",
                "Overwrites partitions from local files. Requires confirmation.",
                args = listOf(
                    partitionsArg,
                    McArg("Input filenames", "boot.img,recovery.img  (one per partition)", attachMulti = true,
                        pathUnderDumps = true)
                )
            ),
            McCommand("wl", "Write folder",
                "Writes each image in a folder to its matching partition. Requires confirmation.",
                args = listOf(McArg("Folder name", "restore", pathUnderDumps = true))
            ),
            McCommand("wf", "Write full flash",
                "Overwrites the entire raw flash from a single file. Requires confirmation.",
                args = listOf(McArg("Input filename", "full_flash.bin", pathUnderDumps = true))
            ),
            McCommand("wo", "Write byte range",
                "Writes a file to a byte range, given an offset and length. Requires confirmation.",
                args = listOf(
                    McArg("Byte offset", "0x0"),
                    McArg("Length", "0x100000"),
                    McArg("Input filename", "range.bin", pathUnderDumps = true)
                )
            )
        )),

        McGroup("Erase (destructive)", listOf(
            McCommand("e", "Erase partitions",
                "Erases the named partitions. Requires confirmation.",
                args = listOf(partitionsArg)),
            McCommand("es", "Erase partition sectors",
                "Erases a given number of sectors from a partition. Requires confirmation.",
                args = listOf(partitionsArg, McArg("Sectors", "2048"))
            ),
            McCommand("ess", "Erase sector range",
                "Erases a raw sector range. Requires confirmation.",
                args = listOf(McArg("Start sector", "0"), McArg("Sector count", "2048"))
            )
        )),

        McGroup("Device control", listOf(
            McCommand("reset", "Reset device",
                "Restarts the device."),
            McCommand("meta", "Enter META mode",
                "Switches the device into META mode.",
                args = listOf(McArg("META mode (optional)", "off, usb or uart", optional = true, choices = pickOf("off", "usb", "uart")))
            ),
            McCommand("meta2", "Enter META mode (watchdog)",
                "Switches the device into META mode using a watchdog reset."),
            McCommand("printgpt", "Print partition table",
                "Lists the partitions present on the device."),
            McCommand("gettargetconfig", "Get target configuration",
                "Displays the secure boot, serial link authentication and download agent authentication state."),
            McCommand("devices", "List supported devices",
                "Lists the chipsets and devices in the built-in database.")
        )),

        McGroup("Advanced", listOf(
            McCommand("peek", "Read memory",
                "Reads memory directly after the Preloader has been patched.",
                args = listOf(
                    McArg("Address", "0x..."),
                    McArg("Length", "0x100"),
                    McArg("Filename (optional)", "peek.bin", optional = true, flag = "--filename",
                        pathUnderDumps = true)
                )
            ),
            McCommand("script", "Run script",
                "Runs a file containing one command per line.",
                args = listOf(McArg("Script filename", "commands.txt", pathUnderDumps = true, attach = true))
            ),
            McCommand("multi", "Run multiple commands",
                "Runs several commands in sequence, separated by semicolons.",
                args = listOf(McArg("Commands", "printgpt; gettargetconfig"))
            ),
            McCommand("dumppreloader", "Dump Preloader",
                "Saves the device Preloader image to a file.",
                args = listOf(McArg("Filename (optional)", "preloader.bin", optional = true,
                    flag = "--filename", pathUnderDumps = true))
            ),
            McCommand("dumpbrom", "Dump BootROM",
                "Saves the on-chip BootROM to a file, where the chipset allows it.",
                args = listOf(McArg("Filename (optional)", "bootrom.bin", optional = true,
                    flag = "--filename", pathUnderDumps = true))
            ),
            McCommand("dumpsram", "Dump SRAM",
                "Saves the on-chip SRAM contents to a file, where the chipset allows it.",
                args = listOf(McArg("Filename (optional)", "sram.bin", optional = true,
                    flag = "--filename", pathUnderDumps = true))
            ),
            McCommand("logs", "Read target logs",
                "Reads the diagnostic log buffer exposed by the target.")
        ))
    )

    /** Every command, in catalog order. */
    val all: List<McCommand> = groups.flatMap { it.commands }

    private val byCmd: Map<String, McCommand> = all.associateBy { it.cmd }

    fun find(cmd: String): McCommand? = byCmd[cmd]

    fun titleFor(cmd: String): String = byCmd[cmd]?.title ?: cmd
}
