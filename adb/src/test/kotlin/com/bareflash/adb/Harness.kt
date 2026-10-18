package com.bareflash.adb

/** A connected [AdbConnection] over a fake device, closed after the block. */
class Harness(
    val core: FakeAdbdCore = FakeAdbdCore(),
    transferSize: Int = 16 * 1024,
    zeroLengthPackets: Boolean = false,
    packetSize: Int = 512,
    config: AdbConnectionConfig = AdbConnectionConfig(),
) : AutoCloseable {
    val transport = FakeAdbUsbTransport(core, transferSize, zeroLengthPackets, packetSize)
    val conn: AdbConnection = AdbConnection.connect(transport, TestKeys.pair, config)
    override fun close() = conn.close()
}

inline fun withDevice(
    core: FakeAdbdCore = FakeAdbdCore(),
    transferSize: Int = 16 * 1024,
    zeroLengthPackets: Boolean = false,
    block: (Harness) -> Unit,
) {
    Harness(core, transferSize, zeroLengthPackets).use { block(it) }
}

inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
    try { block() } catch (t: Throwable) {
        if (t is T) return t
        throw AssertionError("expected ${T::class.simpleName} but got $t", t)
    }
    throw AssertionError("expected ${T::class.simpleName}")
}

fun pattern(n: Int, seed: Int = 1): ByteArray = ByteArray(n) { ((it * 31 + seed * 7) xor (it ushr 8)).toByte() }
