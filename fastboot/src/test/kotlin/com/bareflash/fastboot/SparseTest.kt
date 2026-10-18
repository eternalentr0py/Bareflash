package com.bareflash.fastboot

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SparseTest {
    private val blk = 4096

    private fun expectSparseError(bytes: ByteArray) {
        try { SparseParser.parse(ByteArraySource(bytes)); fail("expected SparseFormatException") }
        catch (_: SparseFormatException) { assertTrue(true) }
    }

    @Test fun parsesAHandBuiltImage() {
        val raw = randomBytes(8)
        val img = SparseBuilder(4).raw(raw).fill(0x11223344, 3).dontCare(1).crc32().build()
        val parsed = SparseParser.parse(ByteArraySource(img))
        assertEquals(4, parsed.blockSize)
        assertEquals(6L, parsed.totalBlocks)
        assertEquals(listOf(SparseFormat.TYPE_RAW, SparseFormat.TYPE_FILL, SparseFormat.TYPE_DONT_CARE), parsed.chunks.map { it.type })
    }

    @Test fun rejectsMalformedImages() {
        val good = SparseBuilder(4).raw(randomBytes(8)).build()
        expectSparseError(good.copyOf(10))                                   // shorter than header
        expectSparseError(good.copyOf(good.size - 1))                        // truncated raw body
        expectSparseError(good.copyOf().also { it[0] = 0 })                  // bad magic
        expectSparseError(good.copyOf().also { it[4] = 2 })                  // major 2
        expectSparseError(good.copyOf().also { it[12] = 3 })                 // block size 3, not a multiple of 4
        expectSparseError(good.copyOf().also { it[16] = 9 })                 // total_blks disagrees with chunks
        expectSparseError(good.copyOf().also { it[28 + 8] = 99 })            // raw total_sz mismatch
        expectSparseError(good.copyOf().also { it[28] = 0x77 })              // unknown chunk type
        expectSparseError(SparseBuilder(4).fill(1, 2).build().also { it[28 + 8] = 12 }) // fill with no body
    }

    @Test fun absurdChunkCountCannotLoopForever() {
        val good = SparseBuilder(4).raw(randomBytes(8)).build()
        val bad = good.copyOf().also { it[20] = 0xFF.toByte(); it[21] = 0xFF.toByte(); it[22] = 0xFF.toByte(); it[23] = 0xFF.toByte() }
        expectSparseError(bad) // runs off the end of the file almost immediately
    }

    @Test fun smallImagesAreSentUnchanged() {
        val data = randomBytes(1000)
        val plan = FlashPlanner.plan(ByteArraySource(data), 4096)
        assertEquals(1, plan.size)
        assertArrayEquals(data, plan[0].toBytes())
        val sparse = SparseBuilder(4).raw(randomBytes(8)).build()
        assertArrayEquals(sparse, FlashPlanner.plan(ByteArraySource(sparse), 1 shl 20)[0].toBytes())
    }

    @Test fun largeRawImageSplitsAndReassemblesExactly() {
        val size = 1_000_000 + 1234 // not block aligned
        val data = randomBytes(size, seed = 7)
        val max = 64 * 1024L
        val pieces = FlashPlanner.plan(ByteArraySource(data), max)
        assertTrue(pieces.size > 10)

        val target = ByteArray(((size + blk - 1) / blk) * blk)
        for (p in pieces) {
            val bytes = p.toBytes()
            assertEquals(p.size, bytes.size.toLong())
            assertTrue("piece ${bytes.size} > $max", bytes.size <= max)
            assertEquals(((size + blk - 1) / blk).toLong(), decodeSparseInto(bytes, target))
        }
        val expected = data.copyOf(target.size) // zero padded
        assertArrayEquals(expected, target)
    }

    @Test fun sparseInputWithFillAndDontCareKeepsItsMeaning() {
        val b = 4
        val img = SparseBuilder(b)
            .raw(randomBytes(4000 * b, 1))
            .dontCare(500)
            .fill(0x0A0B0C0D, 100_000)
            .raw(randomBytes(3000 * b, 2))
            .dontCare(7)
            .crc32()
            .build()
        val totalBlocks = 4000 + 500 + 100_000 + 3000 + 7
        val garbage = ByteArray(totalBlocks * b) { 0x55 }

        val reference = garbage.copyOf().also { decodeSparseInto(img, it) }
        val viaPieces = garbage.copyOf()
        val max = 5000L
        val pieces = FlashPlanner.plan(ByteArraySource(img), max)
        assertTrue(pieces.size > 3)
        for (p in pieces) {
            val bytes = p.toBytes()
            assertTrue(bytes.size <= max)
            decodeSparseInto(bytes, viaPieces)
        }
        assertArrayEquals(reference, viaPieces)
    }

    @Test fun trailingDontCareIsKept() {
        val img = SparseBuilder(4).raw(randomBytes(4000, 3)).dontCare(5000).build()
        val total = 1000 + 5000
        val pieces = FlashPlanner.plan(ByteArraySource(img), 1500)
        val t = ByteArray(total * 4) { 9 }
        for (p in pieces) assertEquals(total.toLong(), decodeSparseInto(p.toBytes(), t))
    }

    @Test fun smallestAllowedLimitStillWorksAndSmallerFailsCleanly() {
        val data = randomBytes(blk * 3, 5)
        val minimum = Resparser.minimumPieceSize(blk)
        val pieces = FlashPlanner.plan(ByteArraySource(data), minimum)
        val target = ByteArray(data.size)
        for (p in pieces) {
            val bytes = p.toBytes()
            assertTrue(bytes.size <= minimum)
            decodeSparseInto(bytes, target)
        }
        assertArrayEquals(data, target)
        try { FlashPlanner.plan(ByteArraySource(data), minimum - 1); fail("expected failure") }
        catch (_: FastbootException) { assertTrue(true) }
    }

    @Test fun emptyImageIsRejected() {
        try { FlashPlanner.plan(ByteArraySource(ByteArray(0)), 100); fail() } catch (_: FastbootException) { assertTrue(true) }
    }

    @Test fun anImageThatIsAllDontCareStillYieldsOnePiece() {
        val b = SparseBuilder(4)
        repeat(200) { b.dontCare(50) } // 200 chunks: a file bigger than the limit below, with no data at all
        val img = b.build()
        val limit = Resparser.minimumPieceSize(4)
        assertTrue(img.size > limit)
        val pieces = FlashPlanner.plan(ByteArraySource(img), limit)
        assertEquals(1, pieces.size)
        assertEquals(10_000L, decodeSparseInto(pieces[0].toBytes(), ByteArray(40_000)))
    }
}
