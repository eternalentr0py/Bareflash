package com.bareflash.app.console

import com.bareflash.app.dump.DumpBridge

/**
 * FAT32 cannot hold a file of 4 GiB or more. Dumping a big partition to an SD card or stick
 * formatted that way fails part-way through, after a long read. When the volume is known
 * to be FAT32 and the file will be that big, say so before anything starts.
 */
object Fat32Guard {
    private const val LIMIT = 4L * 1024 * 1024 * 1024 - 1

    sealed class Verdict {
        data object Fine : Verdict()
        data class Blocked(val message: String) : Verdict()
        data class Maybe(val message: String) : Verdict()
    }

    fun check(command: String, sectorSizeHex: String, gpt: List<GptPartition>): Verdict {
        val (bytes, exact) = DumpSpaceGuard.largestFile(command, sectorSizeHex, gpt) ?: return Verdict.Fine
        if (bytes < LIMIT) return Verdict.Fine
        val fs = DumpBridge.volumeFsType() ?: return Verdict.Fine
        if (fs != "vfat" && fs != "msdos") return Verdict.Fine
        val size = DumpSpaceGuard.formatBytes(bytes)
        return if (exact) {
            Verdict.Blocked(
                "${DumpBridge.displayName()} is formatted as FAT32, which cannot hold a file of $size. " +
                    "Pick a dump folder on internal storage or on an exFAT / ext4 card, or read the partition in smaller pieces with Read sectors."
            )
        } else {
            Verdict.Maybe(
                "${DumpBridge.displayName()} is formatted as FAT32, which cannot hold a file of 4 GiB or more. " +
                    "This read may be larger than that; if it fails near 4 GiB, use another folder."
            )
        }
    }
}
