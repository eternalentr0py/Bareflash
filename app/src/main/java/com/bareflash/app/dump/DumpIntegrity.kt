package com.bareflash.app.dump

import com.bareflash.app.console.GptPartition
import com.bareflash.app.console.TargetInfo
import com.bareflash.app.log.AppLog
import com.chaquo.python.Python
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest

/**
 * Checksums and manifests for dump images, and the Android sparse-image header.
 *
 * Every image the app dumps gets two small files next to it, hidden from the Dumps
 * list: `<name>.sha256` (standard `sha256sum -c` format) and `<name>.manifest.json`
 * (what was dumped, from which chip, with which command, when).
 */
object DumpIntegrity {
    const val MANIFEST_SUFFIX = ".manifest.json"
    const val SHA_SUFFIX = ".sha256"

    /** Smaller files (partition tables, text) do not get a manifest. */
    private const val MIN_MANIFEST_BYTES = 4096L

    /** Size of one hashed region. Must match CHUNK in saf_fs.py. */
    const val CHUNK_BYTES = 64L * 1024 * 1024

    /** Blank-block detection works on 4 KiB blocks (BLK in saf_fs.py). */
    const val BLOCK_BYTES = 4096

    /** Images smaller than this are not checked for being blank. */
    private const val MIN_BLANK_CHECK_BYTES = 64L * 1024

    /** Partitions that are legitimately empty on many devices; never flagged as blank. */
    private val OFTEN_EMPTY = setOf("misc", "para", "expdb", "frp", "userdata2")

    /**
     * Everything one pass over an image learns: the whole-file SHA-256, a SHA-256 for every
     * [chunkSize] bytes (so a mismatch can be pinned to a region) and how many 4 KiB blocks
     * are entirely 0x00 or entirely 0xFF, overall and per region.
     */
    data class Scan(
        val sha256: String,
        val bytes: Long,
        val chunkSize: Long,
        val chunks: List<String>,
        val chunkZero: List<Int>,
        val chunkFf: List<Int>,
        val blocks: Long,
        val zeroBlocks: Long,
        val ffBlocks: Long,
    )

    /** A digest the Python side computed while the file was written. */
    data class Streamed(val rel: String, val sha256: String, val bytes: Long, val scan: Scan? = null)

    /** Everything a manifest records besides the hash. */
    data class Context(
        val appVersion: String,
        val engine: String,
        val command: String,
        val usb: String?,
        val target: TargetInfo?,
        val partition: GptPartition?,
        val gptCount: Int,
        val method: String,
    )

    data class Manifest(
        val sha256: String,
        val bytes: Long,
        val dateMs: Long,
        val method: String,
        val command: String,
        /** Region hashes; empty for manifests written before schema 2. */
        val chunkSize: Long = 0L,
        val chunks: List<String> = emptyList(),
        val blocks: Long = 0L,
        val zeroBlocks: Long = 0L,
        val ffBlocks: Long = 0L,
        /** Where on the flash the image came from, when the manifest says so. */
        val partitionName: String? = null,
        val partitionOffset: Long = -1L,
    )

    fun isSidecar(name: String) = name.endsWith(MANIFEST_SUFFIX) || name.endsWith(SHA_SUFFIX)

    // ---- hashing ----------------------------------------------------------

    fun sha256(input: InputStream, limit: Long = Long.MAX_VALUE, onProgress: ((Long) -> Unit)? = null): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        var left = limit
        var done = 0L
        while (left > 0L) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            md.update(buf, 0, n)
            left -= n
            done += n
            onProgress?.invoke(done)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** SHA-256 of the first [limit] bytes of a dumps-relative file, or null if it cannot be read. */
    fun sha256Rel(rel: String, limit: Long = Long.MAX_VALUE, onProgress: ((Long) -> Unit)? = null): String? =
        runCatching { DumpBridge.openRead(rel)?.use { sha256(it, limit, onProgress) } }.getOrNull()

    /**
     * One pass over [input]: whole-file SHA-256, per-region SHA-256 and blank-block counts.
     * Reads in 1 MiB steps that are always filled completely, so a 4 KiB block never straddles
     * two reads and a region boundary (a multiple of 1 MiB) always falls between reads.
     */
    fun scan(input: InputStream, limit: Long = Long.MAX_VALUE, onProgress: ((Long) -> Unit)? = null): Scan {
        val whole = MessageDigest.getInstance("SHA-256")
        var chunkMd = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        val chunks = ArrayList<String>()
        val chunkZero = ArrayList<Int>()
        val chunkFf = ArrayList<Int>()
        var chunkLen = 0L
        var z = 0
        var f = 0
        var blocks = 0L
        var zeroTotal = 0L
        var ffTotal = 0L
        var left = limit
        var done = 0L
        fun closeChunk() {
            chunks += chunkMd.digest().joinToString("") { "%02x".format(it) }
            chunkZero += z
            chunkFf += f
            zeroTotal += z
            ffTotal += f
            chunkMd = MessageDigest.getInstance("SHA-256")
            chunkLen = 0L
            z = 0
            f = 0
        }
        while (left > 0L) {
            val want = minOf(buf.size.toLong(), left).toInt()
            var n = 0
            while (n < want) {
                val r = input.read(buf, n, want - n)
                if (r < 0) break
                n += r
            }
            if (n <= 0) break
            whole.update(buf, 0, n)
            chunkMd.update(buf, 0, n)
            var i = 0
            while (i < n) {
                val end = minOf(i + BLOCK_BYTES, n)
                val first = buf[i]
                if (first == 0.toByte() || first == 0xFF.toByte()) {
                    var same = true
                    var j = i + 1
                    while (j < end) {
                        if (buf[j] != first) { same = false; break }
                        j++
                    }
                    if (same) {
                        if (first == 0.toByte()) z++ else f++
                    }
                }
                blocks++
                i = end
            }
            chunkLen += n
            if (chunkLen >= CHUNK_BYTES) closeChunk()
            left -= n
            done += n
            onProgress?.invoke(done)
            if (n < want) break
        }
        if (chunkLen > 0L) closeChunk()
        return Scan(
            sha256 = whole.digest().joinToString("") { "%02x".format(it) },
            bytes = done, chunkSize = CHUNK_BYTES, chunks = chunks, chunkZero = chunkZero, chunkFf = chunkFf,
            blocks = blocks, zeroBlocks = zeroTotal, ffBlocks = ffTotal,
        )
    }

    /** [scan] of the first [limit] bytes of a dumps-relative file, or null if it cannot be read. */
    fun scanRel(rel: String, limit: Long = Long.MAX_VALUE, onProgress: ((Long) -> Unit)? = null): Scan? =
        runCatching { DumpBridge.openRead(rel)?.use { scan(it, limit, onProgress) } }.getOrNull()

    private fun parseScan(d: JSONObject): Scan? = runCatching {
        val ch = d.getJSONArray("chunks")
        val cz = d.getJSONArray("chunkZero")
        val cf = d.getJSONArray("chunkFf")
        Scan(
            sha256 = d.getString("sha256"),
            bytes = d.getLong("bytes"),
            chunkSize = d.getLong("chunk"),
            chunks = List(ch.length()) { ch.getString(it) },
            chunkZero = List(cz.length()) { cz.getInt(it) },
            chunkFf = List(cf.length()) { cf.getInt(it) },
            blocks = d.getLong("blocks"),
            zeroBlocks = d.getLong("zero"),
            ffBlocks = d.getLong("ff"),
        )
    }.getOrNull()

    /** Digests computed by Python since the last call. Empty when hashing is off. */
    fun takeStreamed(): List<Streamed> = runCatching {
        val raw = Python.getInstance().getModule("saf_fs").callAttr("take_digests").toString()
        val o = JSONObject(raw)
        val out = ArrayList<Streamed>()
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            val d = o.getJSONObject(k)
            if (d.optBoolean("valid", false)) out += Streamed(k, d.getString("sha256"), d.getLong("bytes"), parseScan(d))
        }
        out
    }.getOrElse {
        AppLog.w("integrity", "Could not read streamed digests", it)
        emptyList()
    }

    /**
     * Writes manifests for files whose streamed digest matches what is on disk.
     * Returns the digests that were accepted (file, hash) in order.
     */
    fun recordStreamed(streamed: List<Streamed>, ctx: Context, partitionFor: (String) -> GptPartition?): List<Streamed> {
        val accepted = ArrayList<Streamed>()
        for (s in streamed) {
            val onDisk = DumpBridge.size(s.rel)
            if (onDisk != s.bytes) {
                AppLog.w("integrity", "Skipping manifest for ${s.rel}: ${s.bytes} hashed, $onDisk on disk")
                continue
            }
            accepted += s
            if (s.bytes >= MIN_MANIFEST_BYTES) {
                writeManifest(s.rel, s.sha256, s.bytes, ctx.copy(partition = partitionFor(s.rel)), s.scan)
            }
        }
        return accepted
    }

    fun writeManifest(rel: String, sha: String, bytes: Long, c: Context, scan: Scan? = null): Boolean {
        if (bytes < MIN_MANIFEST_BYTES) return false
        val name = rel.substringAfterLast('/')
        val j = JSONObject().apply {
            put("schema", if (scan != null) 2 else 1)
            put("app", "Bareflash ${c.appVersion}")
            put("file", name)
            put("bytes", bytes)
            put("sha256", sha)
            put("hashMethod", c.method)
            put("date", System.currentTimeMillis())
            put("engine", c.engine)
            put("command", c.command)
            c.usb?.let { put("usb", it) }
            c.target?.let { t ->
                put("chip", JSONObject().apply {
                    t.cpu?.let { put("cpu", it) }
                    t.chipset?.let { put("chipset", it) }
                    t.hwCode?.let { put("hwCode", it) }
                    t.hwVer?.let { put("hwVer", it) }
                    t.swVer?.let { put("swVer", it) }
                    t.blVer?.let { put("blVer", it) }
                    t.targetConfig?.let { put("targetConfig", it) }
                })
            }
            c.partition?.let { p ->
                put("partition", JSONObject().apply {
                    put("name", p.name)
                    put("offset", p.offset)
                    put("length", p.length)
                })
            }
            if (c.gptCount > 0) put("partitionCount", c.gptCount)
            scan?.let { sc ->
                put("chunkSize", sc.chunkSize)
                put("chunks", JSONArray(sc.chunks))
                put("blank", JSONObject().apply {
                    put("blockSize", BLOCK_BYTES)
                    put("blocks", sc.blocks)
                    put("zero", sc.zeroBlocks)
                    put("ff", sc.ffBlocks)
                    put("chunkZero", JSONArray(sc.chunkZero))
                    put("chunkFf", JSONArray(sc.chunkFf))
                })
            }
        }
        val a = DumpBridge.writeText(rel + MANIFEST_SUFFIX, j.toString(2))
        val b = DumpBridge.writeText(rel + SHA_SUFFIX, "$sha  $name\n")
        return a && b
    }

    fun readManifest(rel: String): Manifest? = runCatching {
        val text = DumpBridge.readText(rel + MANIFEST_SUFFIX) ?: return null
        val j = JSONObject(text)
        val arr = j.optJSONArray("chunks")
        val blank = j.optJSONObject("blank")
        val part = j.optJSONObject("partition")
        Manifest(
            sha256 = j.getString("sha256"),
            bytes = j.getLong("bytes"),
            dateMs = j.optLong("date", 0L),
            method = j.optString("hashMethod", ""),
            command = j.optString("command", ""),
            chunkSize = j.optLong("chunkSize", 0L),
            chunks = if (arr != null) List(arr.length()) { arr.getString(it) } else emptyList(),
            blocks = blank?.optLong("blocks", 0L) ?: 0L,
            zeroBlocks = blank?.optLong("zero", 0L) ?: 0L,
            ffBlocks = blank?.optLong("ff", 0L) ?: 0L,
            partitionName = part?.optString("name")?.ifBlank { null },
            partitionOffset = part?.optLong("offset", -1L) ?: -1L,
        )
    }.getOrNull()

    // ---- regions and blank images ---------------------------------------------

    /**
     * Region indexes where [scan] differs from the manifest's region hashes, or null when the
     * manifest has none (older manifests) or they use another region size.
     */
    fun badChunks(m: Manifest, scan: Scan): List<Int>? {
        if (m.chunks.isEmpty() || m.chunkSize != scan.chunkSize) return null
        val bad = ArrayList<Int>()
        for (i in 0 until maxOf(m.chunks.size, scan.chunks.size)) {
            val w = m.chunks.getOrNull(i)
            val g = scan.chunks.getOrNull(i)
            if (w == null || g == null || !w.equals(g, ignoreCase = true)) bad += i
        }
        return bad
    }

    /** "128\u2013192 MiB, 960\u20131024 MiB" for region indexes; neighbours are merged. */
    fun describeRegions(bad: List<Int>, chunkSize: Long, totalBytes: Long, max: Int = 5): String {
        if (bad.isEmpty() || chunkSize <= 0L) return ""
        val runs = ArrayList<Pair<Int, Int>>()
        for (i in bad.sorted()) {
            val last = runs.lastOrNull()
            if (last != null && i == last.second + 1) {
                runs[runs.size - 1] = Pair(last.first, i)
            } else {
                runs.add(Pair(i, i))
            }
        }
        val shown = runs.take(max).map { (a, b) ->
            val from = a * chunkSize
            val end = (b + 1) * chunkSize
            val to = if (totalBytes > 0L) minOf(end, totalBytes) else end
            "${from shr 20}\u2013${(to + (1L shl 20) - 1) shr 20} MiB"
        }
        return shown.joinToString(", ") + if (runs.size > max) " and ${runs.size - max} more" else ""
    }

    /**
     * A warning when an image is practically all zeros or all 0xFF, which usually means the read
     * returned nothing (locked or protected region, erased flash). Null when it looks normal.
     */
    fun blankNote(file: String, bytes: Long, blocks: Long, zero: Long, ff: Long): String? {
        if (blocks <= 0L || bytes < MIN_BLANK_CHECK_BYTES) return null
        val leaf = file.substringAfterLast('/')
        if (leaf.substringBeforeLast('.').lowercase() in OFTEN_EMPTY) return null
        // 99.9% or more of the blocks are blank.
        if ((zero + ff) * 1000L < blocks * 999L) return null
        val what = when {
            ff == 0L -> "all zeros"
            zero == 0L -> "all 0xFF (erased flash)"
            else -> "blank (zeros and 0xFF)"
        }
        return "$leaf reads as $what. Either that partition really is empty, or the read returned no data " +
            "(locked or protected region)."
    }

    /** [blankNote] for each image a run produced. */
    fun blankWarnings(streamed: List<Streamed>): List<String> =
        streamed.mapNotNull { s -> s.scan?.let { blankNote(s.rel, s.bytes, it.blocks, it.zeroBlocks, it.ffBlocks) } }

    /** The same check from a saved manifest, for the Dumps list. */
    fun blankNote(file: String, m: Manifest): String? = blankNote(file, m.bytes, m.blocks, m.zeroBlocks, m.ffBlocks)

    // ---- Android sparse images -------------------------------------------

    /**
     * Size an Android sparse image expands to (block size x block count from its
     * header), or null when [head] is not a sparse image header.
     */
    fun sparseExpandedBytes(head: ByteArray, n: Int): Long? {
        if (n < 28) return null
        if (head[0] != 0x3A.toByte() || head[1] != 0xFF.toByte() ||
            head[2] != 0x26.toByte() || head[3] != 0xED.toByte()
        ) return null
        val blk = le32(head, 12)
        val blocks = le32(head, 16)
        if (blk <= 0L || blocks <= 0L) return null
        return blk * blocks
    }

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or
            ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or
            ((b[o + 3].toLong() and 0xFF) shl 24)
}
