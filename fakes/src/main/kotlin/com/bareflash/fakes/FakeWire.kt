package com.bareflash.fakes

import com.bareflash.adb.AdbMessage
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The 24-byte ADB header, implemented independently of the production codec. */
object FakeWire {
    const val HEADER_SIZE = 24

    class Header(val command: Int, val arg0: Int, val arg1: Int, val length: Int, val checksum: Int)

    fun checksum(data: ByteArray): Int = data.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) }

    fun encodeHeader(m: AdbMessage, withChecksum: Boolean): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(m.command).putInt(m.arg0).putInt(m.arg1).putInt(m.data.size)
            .putInt(if (withChecksum) checksum(m.data) else 0).putInt(m.command.inv()).array()

    fun decodeHeader(b: ByteArray): Header {
        require(b.size == HEADER_SIZE) { "header must be 24 bytes" }
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val command = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val length = bb.int
        val checksum = bb.int
        val magic = bb.int
        require(magic == command.inv()) { "bad magic" }
        require(length >= 0) { "negative length" }
        return Header(command, arg0, arg1, length, checksum)
    }
}

/** One key shared by tests, because generating 2048-bit RSA keys is slow. */
object TestKeys {
    val pair: com.bareflash.adb.AdbKeyPair by lazy { com.bareflash.adb.AdbKeyPair.generate() }
}
