package com.bareflash.adb

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

/**
 * One logical connection to a device service (a shell, a sync session, ...),
 * multiplexed over the single USB link.
 *
 * Flow control follows the protocol's basic rule: at most one data message is
 * in flight per direction. Writes wait for the device's OKAY before sending
 * the next chunk. For reads, the OKAY that lets the device send more is
 * sent only once the previous chunk has been consumed, so a slow reader
 * slows the device down instead of filling memory.
 *
 * A stream is meant for one reading thread and one writing thread at a time.
 */
public class AdbStream internal constructor(
    private val connection: AdbConnection,
    internal val localId: Int,
    public val destination: String,
) : AutoCloseable {

    private val lock = ReentrantLock()
    private val changed: Condition = lock.newCondition()

    private var remoteId = 0
    private var opened = false
    private var remoteClosed = false
    private var localClosed = false
    private var writeCredit = false
    private var failure: AdbException? = null
    private val inbox = ArrayDeque<ByteArray>()
    private var inboxOffset = 0

    // ---- Called by the connection's reader thread ---------------------------

    internal fun onOkay(remote: Int) {
        lock.lock()
        try {
            if (!opened) {
                opened = true
                remoteId = remote
            }
            writeCredit = true
            changed.signalAll()
        } finally {
            lock.unlock()
        }
    }

    internal fun onData(data: ByteArray) {
        lock.lock()
        try {
            if (localClosed) return
            if (data.isNotEmpty()) inbox.addLast(data)
            changed.signalAll()
        } finally {
            lock.unlock()
        }
    }

    /** Returns the remote id to answer with CLSE, or 0 if no reply is due. */
    internal fun onRemoteClose(): Int {
        lock.lock()
        try {
            if (!opened) failure = AdbStreamRejectedException(destination)
            remoteClosed = true
            val reply = if (opened && !localClosed) remoteId else 0
            localClosed = true
            changed.signalAll()
            return reply
        } finally {
            lock.unlock()
        }
    }

    internal fun fail(cause: AdbException) {
        lock.lock()
        try {
            if (failure == null) failure = cause
            changed.signalAll()
        } finally {
            lock.unlock()
        }
    }

    // ---- Called by the thread that opens the stream -------------------------

    internal fun awaitOpen(timeoutMs: Long) {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        lock.lock()
        try {
            while (!opened) {
                failure?.let { throw it }
                if (remoteClosed) throw AdbStreamRejectedException(destination)
                if (remaining <= 0) throw AdbTimeoutException("Timed out opening '$destination'")
                remaining = changed.awaitNanos(remaining)
            }
        } finally {
            lock.unlock()
        }
    }

    // ---- Public I/O ---------------------------------------------------------

    /** Sends all of [data]; returns when the device has acknowledged the last chunk's predecessor. */
    @JvmOverloads
    public fun write(data: ByteArray, offset: Int = 0, length: Int = data.size, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        require(offset >= 0 && length >= 0 && offset <= data.size - length) { "Range out of bounds" }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var done = 0
        while (done < length) {
            val n = minOf(length - done, connection.maxPayload)
            val target = acquireWriteCredit(deadline)
            connection.sendWrite(localId, target, data.copyOfRange(offset + done, offset + done + n))
            done += n
        }
    }

    /**
     * Reads up to [length] bytes. Returns the count, or -1 when the stream has
     * ended and everything has been read. Throws [AdbTimeoutException] if no
     * data arrives within [timeoutMs].
     */
    public fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) { "Range out of bounds" }
        if (length == 0) return 0
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var ackTarget = 0
        val copied: Int
        lock.lock()
        try {
            while (inbox.isEmpty()) {
                failure?.let { throw it }
                if (remoteClosed || localClosed) return -1
                if (remaining <= 0) throw AdbTimeoutException("Timed out reading from '$destination'")
                remaining = changed.awaitNanos(remaining)
            }
            val chunk = inbox.first()
            copied = minOf(length, chunk.size - inboxOffset)
            System.arraycopy(chunk, inboxOffset, buffer, offset, copied)
            inboxOffset += copied
            if (inboxOffset == chunk.size) {
                inbox.removeFirst()
                inboxOffset = 0
                // Let the device send the next chunk, unless it has already hung up.
                if (!remoteClosed && !localClosed) ackTarget = remoteId
            }
        } finally {
            lock.unlock()
        }
        if (ackTarget != 0) connection.sendOkay(localId, ackTarget)
        return copied
    }

    /** Reads exactly [length] bytes, or fewer only if the stream ends first. Returns the number read. */
    public fun readFully(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var done = 0
        while (done < length) {
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (left <= 0) throw AdbTimeoutException("Timed out reading from '$destination'")
            val n = read(buffer, offset + done, length - done, left)
            if (n < 0) break
            done += n
        }
        return done
    }

    /** Reads until the stream ends. Throws if more than [maxBytes] arrive. */
    public fun readAll(maxBytes: Int, timeoutMs: Long): ByteArray {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (left <= 0) throw AdbTimeoutException("Timed out reading from '$destination'")
            val n = read(buf, 0, buf.size, left)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > maxBytes) throw AdbException("Output from '$destination' exceeds $maxBytes bytes")
            out.write(buf, 0, n)
        }
    }

    /** True once either side has closed the stream. */
    public val isClosed: Boolean
        get() {
            lock.lock()
            try { return localClosed || remoteClosed } finally { lock.unlock() }
        }

    override fun close() {
        var target = 0
        lock.lock()
        try {
            if (localClosed) {
                changed.signalAll()
                return
            }
            localClosed = true
            if (opened && !remoteClosed) target = remoteId
            inbox.clear()
            inboxOffset = 0
            changed.signalAll()
        } finally {
            lock.unlock()
        }
        connection.forget(localId)
        if (target != 0) connection.sendCloseQuietly(localId, target)
    }

    private fun acquireWriteCredit(deadlineNanos: Long): Int {
        lock.lock()
        try {
            while (!writeCredit) {
                failure?.let { throw it }
                if (localClosed) throw AdbClosedException("Stream '$destination' is closed")
                if (remoteClosed) throw AdbClosedException("Device closed '$destination'")
                val remaining = deadlineNanos - System.nanoTime()
                if (remaining <= 0) throw AdbTimeoutException("Timed out writing to '$destination'")
                changed.awaitNanos(remaining)
            }
            failure?.let { throw it }
            if (localClosed) throw AdbClosedException("Stream '$destination' is closed")
            writeCredit = false
            return remoteId
        } finally {
            lock.unlock()
        }
    }

    public companion object {
        public const val DEFAULT_TIMEOUT_MS: Long = 15_000
    }
}
