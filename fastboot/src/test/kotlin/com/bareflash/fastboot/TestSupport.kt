package com.bareflash.fastboot

import com.bareflash.usb.CancelToken
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/** Builds sparse files for tests, byte for byte from the format description, not via production code. */
class SparseBuilder(private val blockSize: Int) {
    private val body = ByteArrayOutputStream()
    private var chunks = 0
    private var blocks = 0L

    private fun header(type: Int, chunkBlocks: Long, totalSz: Long) {
        val b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(type.toShort()).putShort(0).putInt(chunkBlocks.toInt()).putInt(totalSz.toInt())
        body.write(b.array()); chunks++; blocks += chunkBlocks
    }

    fun raw(data: ByteArray) = apply {
        require(data.size % blockSize == 0)
        header(0xCAC1, (data.size / blockSize).toLong(), 12L + data.size); body.write(data)
    }
    fun fill(value: Int, nBlocks: Long) = apply {
        header(0xCAC2, nBlocks, 16); body.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    }
    fun dontCare(nBlocks: Long) = apply { header(0xCAC3, nBlocks, 12) }
    fun crc32(value: Int = 0) = apply {
        val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        header(0xCAC4, 0, 16); body.write(b)
    }

    fun build(): ByteArray {
        val h = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(0xED26FF3A.toInt()).putShort(1).putShort(0).putShort(28).putShort(12)
            .putInt(blockSize).putInt(blocks.toInt()).putInt(chunks).putInt(0)
        return h.array() + body.toByteArray()
    }
}

/**
 * Independent reference decoder: applies a sparse image onto [target]
 * (RAW and FILL write; DONT_CARE leaves the target untouched). Returns the
 * header's total block count.
 */
fun decodeSparseInto(sparse: ByteArray, target: ByteArray): Long {
    val b = ByteBuffer.wrap(sparse).order(ByteOrder.LITTLE_ENDIAN)
    check(b.getInt(0) == 0xED26FF3A.toInt()) { "bad magic" }
    val blk = b.getInt(12)
    val totalBlocks = b.getInt(16).toLong() and 0xFFFFFFFFL
    val nChunks = b.getInt(20)
    var p = b.getShort(8).toInt()
    var block = 0L
    repeat(nChunks) {
        val type = b.getShort(p).toInt() and 0xFFFF
        val n = b.getInt(p + 4).toLong() and 0xFFFFFFFFL
        val total = b.getInt(p + 8)
        val bodyAt = p + 12
        val dst = (block * blk).toInt()
        when (type) {
            0xCAC1 -> System.arraycopy(sparse, bodyAt, target, dst, (n * blk).toInt())
            0xCAC2 -> for (i in 0 until (n * blk).toInt() step 4) System.arraycopy(sparse, bodyAt, target, dst + i, 4)
            0xCAC3, 0xCAC4 -> Unit
            else -> error("unknown chunk")
        }
        block += n
        p += total
    }
    check(block == totalBlocks) { "chunks cover $block blocks, header says $totalBlocks" }
    return totalBlocks
}

fun Payload.toBytes(): ByteArray {
    val o = ByteArrayOutputStream()
    writeTo({ b, off, len -> o.write(b, off, len) }, CancelToken.NEVER)
    return o.toByteArray()
}

fun randomBytes(n: Int, seed: Long = 1): ByteArray = ByteArray(n).also { Random(seed).nextBytes(it) }
