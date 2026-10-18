package com.bareflash.fastboot

import com.bareflash.usb.CancelToken

public fun interface ByteSink {
    public fun write(buffer: ByteArray, offset: Int, length: Int)
}

/** Something that can be sent after a `download:` command: [size] bytes, streamed. */
public interface Payload {
    public val size: Long
    public fun writeTo(sink: ByteSink, cancel: CancelToken)
}

/** A piece of a payload: literal bytes, or a range of a [ByteSource]. */
public sealed interface Segment {
    public val length: Long

    public class Bytes(public val data: ByteArray) : Segment {
        override val length: Long get() = data.size.toLong()
    }

    public class Range(
        public val source: ByteSource,
        public val offset: Long,
        override val length: Long,
    ) : Segment
}

/** A payload made of [Segment]s, streamed in bounded buffers so large images never sit in memory. */
public class SegmentPayload(private val segments: List<Segment>) : Payload {
    override val size: Long = segments.sumOf { it.length }

    override fun writeTo(sink: ByteSink, cancel: CancelToken) {
        val buf = ByteArray(CHUNK)
        for (seg in segments) {
            when (seg) {
                is Segment.Bytes -> {
                    if (cancel.isCancelled()) throw FastbootCancelledException()
                    sink.write(seg.data, 0, seg.data.size)
                }
                is Segment.Range -> {
                    var done = 0L
                    while (done < seg.length) {
                        if (cancel.isCancelled()) throw FastbootCancelledException()
                        val want = minOf(buf.size.toLong(), seg.length - done).toInt()
                        seg.source.readFullyAt(seg.offset + done, buf, 0, want)
                        sink.write(buf, 0, want)
                        done += want
                    }
                }
            }
        }
    }

    private companion object {
        const val CHUNK = 1 shl 20
    }
}

/** A whole [ByteSource] sent as-is. */
public fun ByteSource.asPayload(): Payload = SegmentPayload(listOf(Segment.Range(this, 0, size)))
