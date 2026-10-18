package com.bareflash.fastboot

/** Helpers for `getvar` output. */
public object FastbootVars {

    /**
     * Turns the INFO lines of `getvar:all` into a map. Each line is
     * `name: value`; variable names can themselves contain colons
     * (`partition-size:boot_a: 0x4000000`), so the split is at the LAST ": ".
     * Lines without a separator are ignored. A later line wins over an earlier
     * one with the same name.
     */
    public fun parseAll(infoLines: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in infoLines) {
            val line = raw.removePrefix("(bootloader) ")
            val i = line.lastIndexOf(": ")
            if (i <= 0) continue
            out[line.substring(0, i)] = line.substring(i + 2)
        }
        return out
    }

    /** Parses `0x...` hex or plain decimal. Returns null if it is neither or is negative/overflowing. */
    public fun parseSize(text: String): Long? {
        val t = text.trim()
        return try {
            if (t.startsWith("0x") || t.startsWith("0X")) t.substring(2).toLong(16)
            else t.toLong(10)
        } catch (_: NumberFormatException) {
            null
        }?.takeIf { it >= 0 }
    }
}
