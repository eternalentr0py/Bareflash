package com.bareflash.fastboot

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android sparse image format (the one fastboot's `download:` accepts when an
 * image is larger than max-download-size).
 *
 *   file header, 28 bytes, little-endian:
 *     magic u32 = 0xED26FF3A | major u16 = 1 | minor u16 | file_hdr_sz u16 = 28
 *     chunk_hdr_sz u16 = 12 | blk_sz u32 | total_blks u32 | total_chunks u32 | image_checksum u32
 *   each chunk: 12-byte header { type u16, reserved u16, chunk_sz u32 (blocks), total_sz u32 (bytes incl. header) }
 *     RAW 0xCAC1: body = chunk_sz * blk_sz bytes      FILL 0xCAC2: body = 4 bytes, repeated
 *     DONT_CARE 0xCAC3: no body                       CRC32 0xCAC4: body = 4 bytes
 */
internal object SparseFormat {
    const val MAGIC: Int = 0xED26FF3A.toInt()
    const val FILE_HEADER_SIZE = 28
    const val CHUNK_HEADER_SIZE = 12
    const val TYPE_RAW = 0xCAC1
    const val TYPE_FILL = 0xCAC2
    const val TYPE_DONT_CARE = 0xCAC3
    const val TYPE_CRC32 = 0xCAC4
    const val MAX_BLOCKS = 0xFFFFFFFFL
    const val MAX_BLOCK_SIZE = 1 shl 24

    fun looksSparse(src: ByteSource): Boolean {
        if (src.size < FILE_HEADER_SIZE) return false
        val b = ByteArray(4)
        src.readFullyAt(0, b, 0, 4)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int == MAGIC
    }

    fun fileHeader(blockSize: Int, totalBlocks: Long, totalChunks: Long): ByteArray =
        ByteBuffer.allocate(FILE_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(MAGIC).putShort(1).putShort(0)
            .putShort(FILE_HEADER_SIZE.toShort()).putShort(CHUNK_HEADER_SIZE.toShort())
            .putInt(blockSize).putInt(totalBlocks.toInt()).putInt(totalChunks.toInt()).putInt(0)
            .array()

    fun chunkHeader(type: Int, blocks: Long, totalBytes: Long): ByteArray =
        ByteBuffer.allocate(CHUNK_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(type.toShort()).putShort(0).putInt(blocks.toInt()).putInt(totalBytes.toInt())
            .array()
}

/** One logical chunk, independent of which file it came from. */
internal class Chunk(val type: Int, val blocks: Long, val body: List<Segment>)

internal class SparseImage(val blockSize: Int, val totalBlocks: Long, val chunks: List<Chunk>)

/** Thrown for any sparse file that is malformed or inconsistent. */
public class SparseFormatException(message: String) : FastbootException(message)

internal object SparseParser {

    fun parse(src: ByteSource): SparseImage {
        if (src.size < SparseFormat.FILE_HEADER_SIZE) throw SparseFormatException("Too short for a sparse header")
        val h = ByteArray(SparseFormat.FILE_HEADER_SIZE)
        src.readFullyAt(0, h, 0, h.size)
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.int != SparseFormat.MAGIC) throw SparseFormatException("Bad sparse magic")
        val major = bb.short.toInt() and 0xFFFF
        bb.short // minor: any value is accepted
        val fileHdr = bb.short.toInt() and 0xFFFF
        val chunkHdr = bb.short.toInt() and 0xFFFF
        val blockSize = bb.int
        val totalBlocks = bb.int.toLong() and 0xFFFFFFFFL
        val totalChunks = bb.int.toLong() and 0xFFFFFFFFL
        if (major != 1) throw SparseFormatException("Unsupported sparse major version $major")
        if (fileHdr < SparseFormat.FILE_HEADER_SIZE || chunkHdr < SparseFormat.CHUNK_HEADER_SIZE) {
            throw SparseFormatException("Header sizes too small")
        }
        if (blockSize <= 0 || blockSize % 4 != 0 || blockSize > SparseFormat.MAX_BLOCK_SIZE) {
            throw SparseFormatException("Invalid block size $blockSize")
        }

        val chunks = ArrayList<Chunk>()
        var pos = fileHdr.toLong()
        var blocksSeen = 0L
        val ch = ByteArray(SparseFormat.CHUNK_HEADER_SIZE)
        for (i in 0 until totalChunks) {
            if (pos + chunkHdr > src.size) throw SparseFormatException("Truncated chunk header")
            src.readFullyAt(pos, ch, 0, ch.size)
            val c = ByteBuffer.wrap(ch).order(ByteOrder.LITTLE_ENDIAN)
            val type = c.short.toInt() and 0xFFFF
            c.short // reserved
            val blocks = c.int.toLong() and 0xFFFFFFFFL
            val totalSz = c.int.toLong() and 0xFFFFFFFFL
            val bodyStart = pos + chunkHdr
            val bodyLen = totalSz - chunkHdr
            if (bodyLen < 0 || pos + totalSz > src.size) throw SparseFormatException("Chunk $i exceeds file")

            when (type) {
                SparseFormat.TYPE_RAW -> {
                    if (bodyLen != blocks * blockSize) throw SparseFormatException("Chunk $i: raw size mismatch")
                    chunks += Chunk(type, blocks, listOf(Segment.Range(src, bodyStart, bodyLen)))
                }
                SparseFormat.TYPE_FILL -> {
                    if (bodyLen != 4L) throw SparseFormatException("Chunk $i: bad fill size")
                    val fill = ByteArray(4)
                    src.readFullyAt(bodyStart, fill, 0, 4)
                    chunks += Chunk(type, blocks, listOf(Segment.Bytes(fill)))
                }
                SparseFormat.TYPE_DONT_CARE -> {
                    if (bodyLen != 0L) throw SparseFormatException("Chunk $i: bad don't-care size")
                    chunks += Chunk(type, blocks, emptyList())
                }
                SparseFormat.TYPE_CRC32 -> {
                    if (bodyLen != 4L || blocks != 0L) throw SparseFormatException("Chunk $i: bad CRC chunk")
                    // The checksum is dropped: re-emitted images carry none.
                }
                else -> throw SparseFormatException("Chunk $i: unknown type 0x${type.toString(16)}")
            }
            blocksSeen += blocks
            pos += totalSz
        }
        if (blocksSeen != totalBlocks) {
            throw SparseFormatException("Block count mismatch: header $totalBlocks, chunks $blocksSeen")
        }
        return SparseImage(blockSize, totalBlocks, chunks)
    }
}

/**
 * Splits an image into sparse pieces that each fit in [maxSize] bytes. Every
 * piece describes the WHOLE image (same total_blks) and uses don't-care chunks
 * to skip the parts that belong to other pieces, so the device can apply the
 * pieces one after another.
 */
internal object Resparser {
    private const val FH = SparseFormat.FILE_HEADER_SIZE
    private const val CH = SparseFormat.CHUNK_HEADER_SIZE

    /** Smallest limit that can hold a header, a trailing gap and one block of data. */
    fun minimumPieceSize(blockSize: Int): Long = FH + CH + 2L * CH + blockSize

    fun resparse(chunks: List<Chunk>, blockSize: Int, totalBlocks: Long, maxSize: Long): List<SegmentPayload> {
        require(totalBlocks in 1..SparseFormat.MAX_BLOCKS) { "Image has no blocks or is too large" }
        if (maxSize < minimumPieceSize(blockSize)) {
            throw FastbootException("max-download-size $maxSize is too small to split this image")
        }
        // Body budget per piece: everything except the file header and the one
        // trailing don't-care chunk that is always reserved.
        val budget = maxSize - FH - CH
        val out = ArrayList<SegmentPayload>()
        var cur = PieceBuilder(blockSize, totalBlocks)
        var pos = 0L

        fun flush() {
            out += cur.finish()
            cur = PieceBuilder(blockSize, totalBlocks)
        }

        for (c in chunks) {
            when (c.type) {
                SparseFormat.TYPE_DONT_CARE -> pos += c.blocks
                SparseFormat.TYPE_FILL -> {
                    val cost = 2L * CH + 4
                    if (cur.used + cost > budget) flush()
                    cur.addChunk(pos, c.type, c.blocks, c.body, 4, cost)
                    pos += c.blocks
                }
                SparseFormat.TYPE_RAW -> {
                    var remaining = c.blocks
                    var bodyOffset = 0L
                    while (remaining > 0) {
                        var avail = (budget - cur.used - 2L * CH) / blockSize
                        if (avail <= 0) {
                            flush()
                            avail = (budget - 2L * CH) / blockSize
                        }
                        val take = minOf(remaining, avail)
                        val bytes = take * blockSize
                        cur.addChunk(pos, c.type, take, slice(c.body, bodyOffset, bytes), bytes, 2L * CH + bytes)
                        pos += take
                        bodyOffset += bytes
                        remaining -= take
                    }
                }
            }
        }
        if (cur.hasData || out.isEmpty()) flush()
        return out
    }

    private class PieceBuilder(val blockSize: Int, val totalBlocks: Long) {
        private val parts = ArrayList<Segment>()
        private var chunkCount = 0L
        private var covered = 0L
        var used = 0L; private set
        var hasData = false; private set

        private fun gapTo(pos: Long) {
            if (pos > covered) {
                parts += Segment.Bytes(SparseFormat.chunkHeader(SparseFormat.TYPE_DONT_CARE, pos - covered, CH.toLong()))
                chunkCount++
                covered = pos
            }
        }

        fun addChunk(pos: Long, type: Int, blocks: Long, body: List<Segment>, bodyBytes: Long, reservedCost: Long) {
            gapTo(pos)
            parts += Segment.Bytes(SparseFormat.chunkHeader(type, blocks, CH + bodyBytes))
            parts += body
            chunkCount++
            covered = pos + blocks
            used += reservedCost
            hasData = true
        }

        fun finish(): SegmentPayload {
            gapTo(totalBlocks)
            val header = SparseFormat.fileHeader(blockSize, totalBlocks, chunkCount)
            return SegmentPayload(listOf<Segment>(Segment.Bytes(header)) + parts)
        }
    }

    /** The [length] bytes starting [start] bytes into the concatenation of [segments]. */
    fun slice(segments: List<Segment>, start: Long, length: Long): List<Segment> {
        val out = ArrayList<Segment>()
        var skip = start
        var left = length
        for (s in segments) {
            if (left == 0L) break
            if (skip >= s.length) { skip -= s.length; continue }
            val take = minOf(s.length - skip, left)
            out += when (s) {
                is Segment.Bytes -> Segment.Bytes(s.data.copyOfRange(skip.toInt(), (skip + take).toInt()))
                is Segment.Range -> Segment.Range(s.source, s.offset + skip, take)
            }
            left -= take
            skip = 0
        }
        check(left == 0L) { "slice out of range" }
        return out
    }
}

/** Decides how an image reaches the device: as-is if it fits, otherwise as sparse pieces. */
public object FlashPlanner {
    public const val DEFAULT_BLOCK_SIZE: Int = 4096
    private const val PROTOCOL_MAX = 0xFFFFFFFFL

    /**
     * Returns the payloads to download and flash, in order. An image no larger
     * than [maxDownloadSize] is sent unchanged (raw or already sparse). A
     * larger one is split into sparse pieces that each fit.
     */
    public fun plan(image: ByteSource, maxDownloadSize: Long): List<Payload> {
        if (image.size <= 0) throw FastbootException("Image is empty")
        val limit = minOf(maxDownloadSize, PROTOCOL_MAX)
        if (limit <= 0) throw FastbootException("Invalid max-download-size")
        if (image.size <= limit) return listOf(image.asPayload())

        val (blockSize, totalBlocks, chunks) = if (SparseFormat.looksSparse(image)) {
            val s = SparseParser.parse(image)
            Triple(s.blockSize, s.totalBlocks, s.chunks)
        } else {
            rawChunks(image, DEFAULT_BLOCK_SIZE)
        }
        return Resparser.resparse(chunks, blockSize, totalBlocks, limit)
    }

    private fun rawChunks(src: ByteSource, blockSize: Int): Triple<Int, Long, List<Chunk>> {
        val full = src.size / blockSize
        val tail = (src.size % blockSize).toInt()
        val chunks = ArrayList<Chunk>()
        if (full > 0) chunks += Chunk(SparseFormat.TYPE_RAW, full, listOf(Segment.Range(src, 0, full * blockSize)))
        if (tail > 0) {
            // The final partial block is zero-padded to a whole block.
            chunks += Chunk(
                SparseFormat.TYPE_RAW, 1,
                listOf(Segment.Range(src, full * blockSize, tail.toLong()), Segment.Bytes(ByteArray(blockSize - tail))),
            )
        }
        return Triple(blockSize, full + if (tail > 0) 1 else 0, chunks)
    }
}
