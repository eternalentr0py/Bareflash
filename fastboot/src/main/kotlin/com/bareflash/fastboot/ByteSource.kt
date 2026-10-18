package com.bareflash.fastboot

import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile

/**
 * Random-access, read-only bytes: an image file, a content URI's file
 * descriptor, or an in-memory array in tests. Implementations must be safe to
 * call from the worker thread that runs a flash.
 */
public interface ByteSource : AutoCloseable {
    public val size: Long

    /** Reads up to [length] bytes at [position]; returns the count, or -1 at end of data. */
    public fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int

    override fun close() {}
}

/** Reads exactly [length] bytes or throws [EOFException]. */
public fun ByteSource.readFullyAt(position: Long, buffer: ByteArray, offset: Int, length: Int) {
    var done = 0
    while (done < length) {
        val n = readAt(position + done, buffer, offset + done, length - done)
        if (n < 0) throw EOFException("Unexpected end of image at ${position + done}")
        done += n
    }
}

public class ByteArraySource(private val data: ByteArray) : ByteSource {
    override val size: Long get() = data.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= data.size) return -1
        val n = minOf(length.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, n)
        return n
    }
}

public class FileByteSource(file: File) : ByteSource {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        raf.seek(position)
        return raf.read(buffer, offset, length)
    }

    override fun close(): Unit = raf.close()
}
