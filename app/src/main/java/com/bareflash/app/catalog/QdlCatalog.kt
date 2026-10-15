package com.bareflash.app.catalog

/**
 * Command catalog for QDL mode. Mirrors the CLI of qdl 2.8
 * (`qdl --help`): the classic `<prog.mbn> <xml>...` flow, the address
 * based read / write / erase / sha256 verbs, `reset`, `flash`,
 * `create-zip`, `ramdump` and `ks`.
 *
 * The first word is the app's own verb; [com.bareflash.app.engine.QdlRules]
 * turns it into the real qdl argv, adding the programmer and the
 * Session-tab options (storage, serial, slot, flags).
 */
object QdlCatalog {

    private val address = McArg(
        "Address", "name, <P>, <P/S> or <P/S+L>   e.g. boot_a  or  0/0+34"
    )

    val groups: List<McGroup> = listOf(

        McGroup("Flash", listOf(
            McCommand("flash", "Flash firmware package",
                "Flashes a flashmap JSON, flash ZIP or contents.xml. The programmer comes from the package itself, so no programmer needs to be set. Requires confirmation.",
                args = listOf(
                    McArg("Package file", "flashmap.json, firmware.zip or contents.xml", attach = true),
                    McArg("Specifier (optional)", "e.g. ufs  or  emmc/flavor", optional = true)
                )
            ),
            McCommand("xml", "Flash with rawprogram / patch XML",
                "Sends the Session programmer, then runs each program, patch and read XML in order. Requires confirmation.",
                args = listOf(McArg("XML files", "rawprogram0.xml,patch0.xml  (comma-separated, in order)", attachMulti = true))
            )
        )),

        McGroup("Partition I/O", listOf(
            McCommand("read", "Read to file",
                "Reads a partition or sector range to a file in the dump folder.",
                args = listOf(address, McArg("Output filename", "boot_a.img", pathUnderDumps = true))
            ),
            McCommand("readxml", "Read via XML",
                "Runs a read XML (<read> directives) and saves what it reads.",
                args = listOf(McArg("Read XML file", "read.xml", attach = true))
            ),
            McCommand("write", "Write from file",
                "Overwrites a partition or sector range from a file. Requires confirmation.",
                args = listOf(address, McArg("Input filename", "boot_a.img", pathUnderDumps = true))
            ),
            McCommand("sha256", "Hash partition",
                "Computes the SHA-256 of a partition or sector range on the device.",
                args = listOf(address)
            )
        )),

        McGroup("Erase (destructive)", listOf(
            McCommand("erase", "Erase partition",
                "Erases a partition or sector range. Requires confirmation.",
                args = listOf(address)
            )
        )),

        McGroup("Device control", listOf(
            McCommand("reset", "Reset device",
                "Loads the programmer and reboots the device out of EDL mode."),
            McCommand("chipinfo", "Read chip info",
                "Reads the Sahara chip info (serial, HW id, OEM PK hash) from a device in EDL mode. Needs no programmer."),
            McCommand("version", "QDL version",
                "Prints the version of the bundled qdl engine. Needs no device.")
        )),

        McGroup("Advanced", listOf(
            McCommand("ramdump", "Collect RAM dump",
                "Pulls a crash RAM dump from a device sitting in Sahara memory-debug mode.",
                args = listOf(
                    McArg("Output folder", "ramdump", default = "ramdump", pathUnderDumps = true),
                    McArg("Segment filter (optional)", "glob list, e.g. DDR*,OCIMEM*", optional = true)
                )
            ),
            McCommand("digests", "Create VIP digest tables",
                "Generates a table of digests for the given XML files into a folder (for signed / VIP flashing).",
                args = listOf(
                    McArg("Output folder", "digests", default = "digests", pathUnderDumps = true),
                    McArg("XML files", "rawprogram0.xml,patch0.xml  (comma-separated)", attachMulti = true)
                )
            ),
            McCommand("createzip", "Create flash ZIP",
                "Bundles a contents.xml and everything it references into a single flashable ZIP.",
                args = listOf(
                    McArg("Output ZIP", "firmware.zip", pathUnderDumps = true),
                    McArg("Contents file", "contents.xml", attach = true),
                    McArg("Specifier (optional)", "e.g. ufs/flavor", optional = true)
                )
            ),
            McCommand("ks", "Sahara keystore (PCIe)",
                "Provisions Sahara images over an MHI device node. This is for PCIe cards and cannot run from an Android USB host.",
                args = listOf(
                    McArg("Device node", "/dev/mhi0_QAIC_SAHARA"),
                    McArg("Image map", "id:file-path")
                ),
                unsupported = "Needs a Linux MHI device node (/dev/mhi*), which Android does not expose."
            )
        ))
    )

    val all: List<McCommand> = groups.flatMap { it.commands }
}
