package com.bareflash.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The six message types, as little-endian ints of their four ASCII letters. */
public object AdbCommand {
    public const val SYNC: Int = 0x434e5953 // "SYNC"
    public const val CNXN: Int = 0x4e584e43 // "CNXN"
    public const val AUTH: Int = 0x48545541 // "AUTH"
    public const val OPEN: Int = 0x4e45504f // "OPEN"
    public const val OKAY: Int = 0x59414b4f // "OKAY"
    public const val CLSE: Int = 0x45534c43 // "CLSE"
    public const val WRTE: Int = 0x45545257 // "WRTE"
    public const val STLS: Int = 0x534c5453 // "STLS"

    /** The four letters, or hex for anything unknown. For logs. */
    public fun name(command: Int): String {
        val b = ByteArray(4) { i -> (command ushr (8 * i)).toByte() }
        return if (b.all { it in 0x41..0x5A }) String(b, Charsets.US_ASCII) else "0x%08x".format(command)
    }
}

/** Protocol constants. */
public object AdbProtocol {
    /** Original protocol: every message carries a checksum. */
    public const val VERSION: Int = 0x01000000

    /** Since Android 7: the checksum is neither sent nor checked. */
    public const val VERSION_SKIP_CHECKSUM: Int = 0x01000001

    public const val AUTH_TOKEN: Int = 1
    public const val AUTH_SIGNATURE: Int = 2
    public const val AUTH_RSAPUBLICKEY: Int = 3

    /** Size of the token the device asks the host to sign. */
    public const val AUTH_TOKEN_SIZE: Int = 20

    /** Payload size every adbd accepts before anything is negotiated. */
    public const val LEGACY_MAX_PAYLOAD: Int = 4096

    /** Largest payload this host will ever accept in one message. */
    public const val HOST_MAX_PAYLOAD: Int = 256 * 1024

    /** Host-side features this implementation actually uses. */
    public val HOST_FEATURES: List<String> = listOf("shell_v2", "stat_v2", "ls_v2")
}

/** One ADB message. [arg0] and [arg1] mean different things per [command]. */
public class AdbMessage(
    public val command: Int,
    public val arg0: Int,
    public val arg1: Int,
    public val data: ByteArray = ByteArray(0),
) {
    override fun toString(): String =
        "${AdbCommand.name(command)}(arg0=$arg0, arg1=$arg1, ${data.size} bytes)"
}

/** Header encoding and validation. All integers are little-endian. */
internal object AdbWire {
    const val HEADER_SIZE = 24

    /** The "checksum" is the plain unsigned sum of the payload bytes, truncated to 32 bits. */
    fun checksum(data: ByteArray): Int {
        var sum = 0
        for (b in data) sum += b.toInt() and 0xFF
        return sum
    }

    fun encodeHeader(message: AdbMessage, withChecksum: Boolean): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(message.command)
            .putInt(message.arg0)
            .putInt(message.arg1)
            .putInt(message.data.size)
            .putInt(if (withChecksum) checksum(message.data) else 0)
            .putInt(message.command.inv())
            .array()

    class Header(val command: Int, val arg0: Int, val arg1: Int, val length: Int, val checksum: Int)

    /** Parses a 24-byte header, rejecting a wrong magic or a payload length above [maxLength]. */
    fun decodeHeader(bytes: ByteArray, maxLength: Int): Header {
        if (bytes.size != HEADER_SIZE) throw AdbProtocolException("Header must be $HEADER_SIZE bytes, got ${bytes.size}")
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val command = b.int
        val arg0 = b.int
        val arg1 = b.int
        val length = b.int
        val checksum = b.int
        val magic = b.int
        if (magic != command.inv()) throw AdbProtocolException("Bad message magic")
        if (length < 0 || length > maxLength) throw AdbProtocolException("Payload length $length out of range (max $maxLength)")
        return Header(command, arg0, arg1, length, checksum)
    }
}
