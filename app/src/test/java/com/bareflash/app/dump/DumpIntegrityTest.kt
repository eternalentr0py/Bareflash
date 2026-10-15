package com.bareflash.app.dump

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * DumpIntegrity decides whether a backup can be trusted: the whole-file hash, the per-region
 * hashes that locate a mismatch, and the blank-image warning. Only the pure helpers are tested;
 * anything that touches the dump folder or Python is not.
 */
class DumpIntegrityTest {

    private val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    /** A stream of [length] copies of [fill] that never holds the whole image in memory. */
    private fun constantStream(length: Long, fill: Int): InputStream = object : InputStream() {
        private var left = length
        override fun read(): Int = if (left-- > 0) fill else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0L) return -1
            val n = minOf(len.toLong(), left).toInt()
            java.util.Arrays.fill(b, off, off + n, fill.toByte())
            left -= n
            return n
        }
    }

    private fun manifest(chunks: List<String>, chunkSize: Long = DumpIntegrity.CHUNK_BYTES) =
        DumpIntegrity.Manifest(sha256 = "x", bytes = 0L, dateMs = 0L, method = "", command = "", chunkSize = chunkSize, chunks = chunks)

    private fun scan(chunks: List<String>, chunkSize: Long = DumpIntegrity.CHUNK_BYTES) =
        DumpIntegrity.Scan("x", 0L, chunkSize, chunks, emptyList(), emptyList(), 0L, 0L, 0L)

    // ---- hashing -----------------------------------------------------------

    @Test
    fun sha256MatchesKnownVectors() {
        assertEquals(abc, DumpIntegrity.sha256(ByteArrayInputStream("abc".toByteArray())))
        assertEquals(empty, DumpIntegrity.sha256(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun sha256HonoursTheLimit() {
        val input = ByteArrayInputStream("abcdef".toByteArray())
        assertEquals(abc, DumpIntegrity.sha256(input, limit = 3L))
    }

    @Test
    fun scanWholeFileHashEqualsPlainHash() {
        val data = ByteArray(300_000) { (it * 31).toByte() }
        val s = DumpIntegrity.scan(ByteArrayInputStream(data))
        assertEquals(DumpIntegrity.sha256(ByteArrayInputStream(data)), s.sha256)
        assertEquals(data.size.toLong(), s.bytes)
    }

    @Test
    fun smallImageIsOneRegionWhoseHashIsTheFileHash() {
        val s = DumpIntegrity.scan(ByteArrayInputStream("abc".toByteArray()))
        assertEquals(listOf(abc), s.chunks)
        assertEquals(abc, s.sha256)
    }

    @Test
    fun emptyInputHasNoRegions() {
        val s = DumpIntegrity.scan(ByteArrayInputStream(ByteArray(0)))
        assertEquals(0L, s.bytes)
        assertTrue(s.chunks.isEmpty())
        assertEquals(empty, s.sha256)
    }

    @Test
    fun imageLargerThanOneRegionIsSplitAtTheBoundary() {
        val total = DumpIntegrity.CHUNK_BYTES + (1L shl 20)
        val s = DumpIntegrity.scan(constantStream(total, 0x5A))
        assertEquals(total, s.bytes)
        assertEquals(2, s.chunks.size)
        val firstRegion = DumpIntegrity.sha256(constantStream(DumpIntegrity.CHUNK_BYTES, 0x5A))
        val secondRegion = DumpIntegrity.sha256(constantStream(1L shl 20, 0x5A))
        assertEquals(firstRegion, s.chunks[0])
        assertEquals(secondRegion, s.chunks[1])
    }

    @Test
    fun scanStopsAtTheLimit() {
        val s = DumpIntegrity.scan(ByteArrayInputStream(ByteArray(10_000) { 1 }), limit = 4096L)
        assertEquals(4096L, s.bytes)
    }

    // ---- blank blocks -----------------------------------------------------

    @Test
    fun countsAllZeroAndAllFfBlocks() {
        val data = ByteArray(4 * DumpIntegrity.BLOCK_BYTES)
        java.util.Arrays.fill(data, DumpIntegrity.BLOCK_BYTES, 2 * DumpIntegrity.BLOCK_BYTES, 0xFF.toByte())
        data[3 * DumpIntegrity.BLOCK_BYTES + 7] = 1
        val s = DumpIntegrity.scan(ByteArrayInputStream(data))
        assertEquals(4L, s.blocks)
        assertEquals(2L, s.zeroBlocks) // blocks 0 and 2; block 3 has a stray byte
        assertEquals(1L, s.ffBlocks)
    }

    @Test
    fun partialLastBlockStillCounts() {
        val data = ByteArray(DumpIntegrity.BLOCK_BYTES + 100)
        val s = DumpIntegrity.scan(ByteArrayInputStream(data))
        assertEquals(2L, s.blocks)
        assertEquals(2L, s.zeroBlocks)
    }

    @Test
    fun blankNoteFlagsAllZeros() {
        val note = DumpIntegrity.blankNote("boot.img", 1L shl 20, 256L, 256L, 0L)
        assertNotNull(note)
        assertTrue(note!!.contains("all zeros"))
    }

    @Test
    fun blankNoteFlagsErasedFlash() {
        val note = DumpIntegrity.blankNote("boot.img", 1L shl 20, 256L, 0L, 256L)
        assertTrue(note!!.contains("0xFF"))
    }

    @Test
    fun blankNoteFlagsMixedBlank() {
        val note = DumpIntegrity.blankNote("boot.img", 1L shl 20, 256L, 128L, 128L)
        assertTrue(note!!.contains("blank"))
    }

    @Test
    fun blankNoteIgnoresNormalImages() {
        assertNull(DumpIntegrity.blankNote("boot.img", 1L shl 20, 256L, 10L, 0L))
    }

    @Test
    fun blankNoteThresholdIsNinetyNinePointNinePercent() {
        // 1000 blocks: 999 blank is flagged, 998 is not.
        assertNotNull(DumpIntegrity.blankNote("boot.img", 4_096_000L, 1000L, 999L, 0L))
        assertNull(DumpIntegrity.blankNote("boot.img", 4_096_000L, 1000L, 998L, 0L))
    }

    @Test
    fun blankNoteSkipsPartitionsThatAreOftenEmpty() {
        for (name in listOf("misc.img", "para.bin", "expdb.img", "frp.img", "userdata2.img")) {
            assertNull(name, DumpIntegrity.blankNote(name, 1L shl 20, 256L, 256L, 0L))
        }
    }

    @Test
    fun blankNoteSkipsTinyFilesAndEmptyScans() {
        assertNull(DumpIntegrity.blankNote("boot.img", 1024L, 1L, 1L, 0L))
        assertNull(DumpIntegrity.blankNote("boot.img", 1L shl 20, 0L, 0L, 0L))
    }

    @Test
    fun blankNoteUsesTheLeafNameOfAFolderPath() {
        assertNull(DumpIntegrity.blankNote("session/misc.img", 1L shl 20, 256L, 256L, 0L))
        assertTrue(DumpIntegrity.blankNote("session/boot.img", 1L shl 20, 256L, 256L, 0L)!!.startsWith("boot.img"))
    }

    // ---- locating a mismatch ----------------------------------------------

    @Test
    fun badChunksIsEmptyWhenEverythingMatches() {
        assertEquals(emptyList<Int>(), DumpIntegrity.badChunks(manifest(listOf("a", "b")), scan(listOf("a", "b"))))
    }

    @Test
    fun badChunksIgnoresHexCase() {
        assertEquals(emptyList<Int>(), DumpIntegrity.badChunks(manifest(listOf("ab12")), scan(listOf("AB12"))))
    }

    @Test
    fun badChunksListsOnlyTheRegionsThatDiffer() {
        val bad = DumpIntegrity.badChunks(manifest(listOf("a", "b", "c")), scan(listOf("a", "X", "c")))
        assertEquals(listOf(1), bad)
    }

    @Test
    fun badChunksCountsMissingAndExtraRegions() {
        assertEquals(listOf(2), DumpIntegrity.badChunks(manifest(listOf("a", "b", "c")), scan(listOf("a", "b"))))
        assertEquals(listOf(1), DumpIntegrity.badChunks(manifest(listOf("a")), scan(listOf("a", "b"))))
    }

    @Test
    fun badChunksIsNullWhenTheManifestCannotLocateAnything() {
        assertNull(DumpIntegrity.badChunks(manifest(emptyList()), scan(listOf("a"))))
        assertNull(DumpIntegrity.badChunks(manifest(listOf("a"), chunkSize = 1L shl 20), scan(listOf("a"))))
    }

    @Test
    fun describeRegionsMergesNeighboursAndClipsToTheImageSize() {
        val mib64 = 64L * 1024 * 1024
        assertEquals("128\u2013192 MiB", DumpIntegrity.describeRegions(listOf(2), mib64, 10 * mib64))
        assertEquals("64\u2013192 MiB", DumpIntegrity.describeRegions(listOf(1, 2), mib64, 10 * mib64))
        // Last region is shorter than a full chunk.
        assertEquals("64\u2013100 MiB", DumpIntegrity.describeRegions(listOf(1), mib64, 100L * 1024 * 1024))
    }

    @Test
    fun describeRegionsCapsTheList() {
        val mib64 = 64L * 1024 * 1024
        val text = DumpIntegrity.describeRegions(listOf(0, 2, 4, 6, 8, 10, 12), mib64, 20 * mib64, max = 3)
        assertTrue(text, text.endsWith(" and 4 more"))
    }

    @Test
    fun describeRegionsIsEmptyForNothingToDescribe() {
        assertEquals("", DumpIntegrity.describeRegions(emptyList(), 1L shl 26, 1L shl 30))
        assertEquals("", DumpIntegrity.describeRegions(listOf(1), 0L, 1L shl 30))
    }

    // ---- sidecars and sparse images ---------------------------------------

    @Test
    fun sidecarFilesAreRecognised() {
        assertTrue(DumpIntegrity.isSidecar("boot.img.sha256"))
        assertTrue(DumpIntegrity.isSidecar("boot.img.manifest.json"))
        assertFalse(DumpIntegrity.isSidecar("boot.img"))
    }

    private fun sparseHeader(blockSize: Long, blocks: Long): ByteArray {
        val h = ByteArray(28)
        h[0] = 0x3A; h[1] = 0xFF.toByte(); h[2] = 0x26; h[3] = 0xED.toByte()
        for (i in 0..3) h[12 + i] = (blockSize shr (8 * i)).toByte()
        for (i in 0..3) h[16 + i] = (blocks shr (8 * i)).toByte()
        return h
    }

    @Test
    fun sparseHeaderGivesTheExpandedSize() {
        assertEquals(4096L * 1000L, DumpIntegrity.sparseExpandedBytes(sparseHeader(4096, 1000), 28))
    }

    @Test
    fun sparseSizeHandlesValuesAbove31Bits() {
        assertEquals(4096L * 0xFFFFFFFFL, DumpIntegrity.sparseExpandedBytes(sparseHeader(4096, 0xFFFFFFFFL), 28))
    }

    @Test
    fun nonSparseOrShortHeadersGiveNull() {
        assertNull(DumpIntegrity.sparseExpandedBytes(ByteArray(28), 28))
        assertNull(DumpIntegrity.sparseExpandedBytes(sparseHeader(4096, 1000), 27))
        assertNull(DumpIntegrity.sparseExpandedBytes(sparseHeader(0, 1000), 28))
        assertNull(DumpIntegrity.sparseExpandedBytes(sparseHeader(4096, 0), 28))
    }
}
