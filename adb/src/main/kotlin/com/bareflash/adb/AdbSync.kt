package com.bareflash.adb

import com.bareflash.usb.CancelToken
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Metadata of a file on the device. [mtimeSeconds] is Unix time. */
public class RemoteFileStat(
    public val mode: Int,
    public val size: Long,
    public val mtimeSeconds: Long,
) {
    public val isDirectory: Boolean get() = (mode and S_IFMT) == S_IFDIR
    public val isRegularFile: Boolean get() = (mode and S_IFMT) == S_IFREG
    public val isSymlink: Boolean get() = (mode and S_IFMT) == S_IFLNK
    /** The permission bits, e.g. 0x1A4 (0644). */
    public val permissions: Int get() = mode and 0xFFF

    public companion object {
        public const val S_IFMT: Int = 0xF000
        public const val S_IFDIR: Int = 0x4000
        public const val S_IFREG: Int = 0x8000
        public const val S_IFLNK: Int = 0xA000
    }
}

/** One directory entry. `.` and `..` are not included. */
public class RemoteFile(public val name: String, public val stat: RemoteFileStat)

/**
 * The file-transfer protocol (`sync:` service): list, stat, push, pull.
 * Requests are an ASCII id plus a little-endian length (or other value).
 * One request at a time; a failed or cancelled transfer closes the session.
 */
public class AdbSyncSession internal constructor(
    private val stream: AdbStream,
    private val statV2: Boolean,
    private val listV2: Boolean,
) : AutoCloseable {

    /** Metadata for [path], or null if it does not exist. */
    public fun stat(path: String): RemoteFileStat? {
        val request = pathBytes(path)
        return guarded { statChecked(request) }
    }

    private fun statChecked(request: ByteArray): RemoteFileStat? {
        sendRequest(if (statV2) "STA2" else "STAT", request)
        if (statV2) {
            expectId("STA2")
            val b = readBlock(STAT_V2_SIZE)
            val error = b.getInt()
            skip(b, 8 + 8) // dev, ino
            val mode = b.getInt()
            skip(b, 4 * 3) // nlink, uid, gid
            val size = b.getLong()
            skip(b, 8) // atime
            val mtime = b.getLong() // ctime, the last 8 bytes, is left unread
            return if (error == ENOENT || (error == 0 && mode == 0 && size == 0L && mtime == 0L)) null
            else if (error != 0) throw AdbSyncException("stat failed with errno $error")
            else RemoteFileStat(mode, size, mtime)
        } else {
            expectId("STAT")
            val b = readBlock(12)
            val mode = b.getInt()
            val size = b.getInt().toLong() and 0xFFFFFFFFL
            val mtime = b.getInt().toLong() and 0xFFFFFFFFL
            return if (mode == 0 && size == 0L && mtime == 0L) null else RemoteFileStat(mode, size, mtime)
        }
    }

    /** The entries of directory [path]. Throws [AdbSyncException] if it cannot be listed. */
    public fun list(path: String): List<RemoteFile> {
        val request = pathBytes(path)
        return guarded { listChecked(request) }
    }

    private fun listChecked(request: ByteArray): List<RemoteFile> {
        sendRequest(if (listV2) "LIS2" else "LIST", request)
        val out = ArrayList<RemoteFile>()
        while (true) {
            val id = readId()
            when (id) {
                "DONE" -> { readBlock(if (listV2) DENT_V2_FIXED else 16); return out }
                "FAIL" -> throw AdbSyncException(readFailMessage())
                "DENT" -> {
                    val b = readBlock(16)
                    val mode = b.getInt()
                    val size = b.getInt().toLong() and 0xFFFFFFFFL
                    val mtime = b.getInt().toLong() and 0xFFFFFFFFL
                    val nameLen = b.getInt()
                    addEntry(out, readName(nameLen), RemoteFileStat(mode, size, mtime))
                }
                "DNT2" -> {
                    val b = readBlock(DENT_V2_FIXED)
                    val error = b.getInt()
                    skip(b, 8 + 8)
                    val mode = b.getInt()
                    skip(b, 4 * 3)
                    val size = b.getLong()
                    skip(b, 8)
                    val mtime = b.getLong()
                    skip(b, 8) // ctime
                    val nameLen = b.getInt()
                    val name = readName(nameLen)
                    if (error == 0) addEntry(out, name, RemoteFileStat(mode, size, mtime))
                }
                else -> throw AdbProtocolException("Unexpected '$id' in directory listing")
            }
        }
    }

    /**
     * Sends [input] to [remotePath]. [mode] is the full Unix mode (regular file
     * 0100644 by default) and [mtimeSeconds] the modification time to set.
     * [onProgress] receives the running byte count.
     */
    public fun push(
        input: InputStream,
        remotePath: String,
        mode: Int = DEFAULT_FILE_MODE,
        mtimeSeconds: Long = System.currentTimeMillis() / 1000,
        cancel: CancelToken = CancelToken.NEVER,
        onProgress: ((Long) -> Unit)? = null,
    ): Long {
        pathBytes(remotePath)
        val request = pathBytes(remotePath + "," + mode.toString(10))
        return guarded { pushChecked(input, request, mtimeSeconds, cancel, onProgress) }
    }

    private fun pushChecked(
        input: InputStream,
        request: ByteArray,
        mtimeSeconds: Long,
        cancel: CancelToken,
        onProgress: ((Long) -> Unit)?,
    ): Long {
        sendRequest("SEND", request)
        val buf = ByteArray(8 + SYNC_DATA_MAX)
        var total = 0L
        while (true) {
            if (cancel.isCancelled()) throw AdbCancelledException()
            val n = input.read(buf, 8, SYNC_DATA_MAX)
            if (n < 0) break
            if (n == 0) continue
            putHeader(buf, "DATA", n)
            stream.write(buf, 0, 8 + n)
            total += n
            onProgress?.invoke(total)
        }
        val done = ByteArray(8)
        putHeader(done, "DONE", mtimeSeconds.coerceIn(0, 0xFFFFFFFFL).toInt())
        stream.write(done)
        when (val id = readId()) {
            "OKAY" -> { readBlock(4); return total }
            "FAIL" -> throw AdbSyncException(readFailMessage())
            else -> throw AdbProtocolException("Unexpected '$id' after push")
        }
    }

    /** Copies [remotePath] into [output]; returns the number of bytes copied. */
    public fun pull(
        remotePath: String,
        output: OutputStream,
        cancel: CancelToken = CancelToken.NEVER,
        onProgress: ((Long) -> Unit)? = null,
    ): Long {
        val request = pathBytes(remotePath)
        return guarded { pullChecked(request, output, cancel, onProgress) }
    }

    private fun pullChecked(request: ByteArray, output: OutputStream, cancel: CancelToken, onProgress: ((Long) -> Unit)?): Long {
        sendRequest("RECV", request)
        var total = 0L
        val buf = ByteArray(SYNC_DATA_MAX)
        while (true) {
            if (cancel.isCancelled()) throw AdbCancelledException()
            when (val id = readId()) {
                "DATA" -> {
                    val len = readInt()
                    if (len < 0 || len > SYNC_DATA_MAX) throw AdbProtocolException("Sync data block of $len bytes")
                    readInto(buf, len)
                    output.write(buf, 0, len)
                    total += len
                    onProgress?.invoke(total)
                }
                "DONE" -> { readInt(); return total }
                "FAIL" -> throw AdbSyncException(readFailMessage())
                else -> throw AdbProtocolException("Unexpected '$id' during pull")
            }
        }
    }

    override fun close() {
        try {
            if (!stream.isClosed) stream.write(requestHeader("QUIT", 0), timeoutMs = 2_000)
        } catch (_: AdbException) {
            // Closing anyway.
        }
        stream.close()
    }

    // ---- Wire helpers -------------------------------------------------------

    private inline fun <T> guarded(block: () -> T): T {
        try {
            return block()
        } catch (e: AdbSyncException) {
            throw e // the device answered FAIL: the session is still in step
        } catch (e: Throwable) {
            stream.close() // anything else leaves the stream out of step
            throw e
        }
    }

    private fun pathBytes(path: String): ByteArray {
        require(path.isNotEmpty() && '\u0000' !in path) { "Invalid path" }
        val b = path.toByteArray(Charsets.UTF_8)
        require(b.size <= MAX_PATH_BYTES) { "Path too long" }
        return b
    }

    private fun requestHeader(id: String, value: Int): ByteArray = ByteArray(8).also { putHeader(it, id, value) }

    private fun putHeader(dst: ByteArray, id: String, value: Int) {
        for (i in 0 until 4) dst[i] = id[i].code.toByte()
        ByteBuffer.wrap(dst, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    }

    private fun sendRequest(id: String, payload: ByteArray) {
        val msg = ByteArray(8 + payload.size)
        putHeader(msg, id, payload.size)
        System.arraycopy(payload, 0, msg, 8, payload.size)
        stream.write(msg)
    }

    private fun readInto(dst: ByteArray, length: Int) {
        if (stream.readFully(dst, 0, length, IO_TIMEOUT_MS) < length) throw AdbProtocolException("Sync stream ended early")
    }

    private fun readBlock(length: Int): ByteBuffer {
        val b = ByteArray(length)
        readInto(b, length)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun readId(): String {
        val b = ByteArray(4)
        readInto(b, 4)
        return String(b, Charsets.US_ASCII)
    }

    private fun readInt(): Int = readBlock(4).getInt()

    private fun expectId(expected: String) {
        val id = readId()
        if (id == "FAIL") throw AdbSyncException(readFailMessage())
        if (id != expected) throw AdbProtocolException("Expected '$expected', got '$id'")
    }

    private fun readFailMessage(): String {
        val len = readInt()
        if (len < 0 || len > MAX_FAIL_MESSAGE) throw AdbProtocolException("Failure message length $len out of range")
        val b = ByteArray(len)
        readInto(b, len)
        return String(b, Charsets.UTF_8)
    }

    private fun readName(length: Int): String {
        if (length < 0 || length > MAX_PATH_BYTES) throw AdbProtocolException("Directory entry name length $length out of range")
        val b = ByteArray(length)
        readInto(b, length)
        return String(b, Charsets.UTF_8)
    }

    private fun skip(b: ByteBuffer, n: Int) { b.position(b.position() + n) }

    /** Entry names come from the device, so refuse anything that could escape a directory. */
    private fun addEntry(out: MutableList<RemoteFile>, name: String, stat: RemoteFileStat) {
        if (name == "." || name == "..") return
        if (name.isEmpty() || '/' in name || '\u0000' in name) throw AdbProtocolException("Unsafe directory entry name")
        out += RemoteFile(name, stat)
    }

    internal companion object {
        const val SYNC_DATA_MAX = 64 * 1024
        const val DEFAULT_FILE_MODE = 0x81A4 // 0100644
        const val MAX_PATH_BYTES = 1024
        const val MAX_FAIL_MESSAGE = 4096
        const val IO_TIMEOUT_MS = 30_000L
        const val ENOENT = 2
        /** After the id: error u32, dev u64, ino u64, mode u32, nlink u32, uid u32, gid u32, size u64, atime i64, mtime i64, ctime i64. */
        const val STAT_V2_SIZE = 4 + 8 + 8 + 4 + 4 + 4 + 4 + 8 + 8 + 8 + 8
        /** The fixed part of a DNT2 entry or DONE block after the id: stat fields plus ctime and the name length. */
        const val DENT_V2_FIXED = 4 + 8 + 8 + 4 + 4 + 4 + 4 + 8 + 8 + 8 + 8 + 4
    }
}

/** Opens the file-transfer service. Needs a device with the `sync:` service, which every adbd has. */
public fun AdbConnection.openSync(): AdbSyncSession =
    AdbSyncSession(open("sync:"), supports("stat_v2"), supports("ls_v2"))
