package com.bareflash.app.dump

import java.io.InputStream

/** Streams two dump files side by side and reports whether, and where, they first differ. */
object DumpCompare {

    data class Result(
        val identical: Boolean,
        /** Offset of the first differing byte, or -1 when identical. */
        val firstDiff: Long,
        val sizeA: Long,
        val sizeB: Long,
        val message: String,
    )

    private fun fill(s: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = s.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    fun compare(a: String, b: String, onProgress: (Long, Long) -> Unit, cancelled: () -> Boolean): Result {
        val sizeA = DumpBridge.size(a)
        val sizeB = DumpBridge.size(b)
        val sa = DumpBridge.openRead(a)
        val sb = DumpBridge.openRead(b)
        if (sa == null || sb == null) {
            runCatching { sa?.close() }; runCatching { sb?.close() }
            return Result(false, -1L, sizeA, sizeB, "One of the files could not be opened.")
        }
        val total = maxOf(sizeA, sizeB)
        return try {
            val ba = ByteArray(1 shl 20)
            val bb = ByteArray(1 shl 20)
            var pos = 0L
            while (true) {
                if (cancelled()) return Result(false, -1L, sizeA, sizeB, "Cancelled.")
                val na = fill(sa, ba)
                val nb = fill(sb, bb)
                val m = minOf(na, nb)
                for (i in 0 until m) {
                    if (ba[i] != bb[i]) {
                        val off = pos + i
                        return Result(false, off, sizeA, sizeB, "First difference at byte $off (0x${off.toString(16)}).")
                    }
                }
                if (na != nb) {
                    val off = pos + m
                    return Result(
                        false, off, sizeA, sizeB,
                        "The files are the same for the first $off bytes, then ${if (na < nb) "the first" else "the second"} ends. " +
                            "Sizes differ: $sizeA and $sizeB bytes."
                    )
                }
                if (na == 0) break
                pos += na
                onProgress(pos, total)
            }
            Result(true, -1L, sizeA, sizeB, "Identical: $sizeA bytes.")
        } finally {
            runCatching { sa.close() }
            runCatching { sb.close() }
        }
    }
}
