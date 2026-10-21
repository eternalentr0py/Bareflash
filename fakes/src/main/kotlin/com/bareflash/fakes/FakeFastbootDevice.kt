package com.bareflash.fakes

import com.bareflash.usb.UsbTimeoutException
import com.bareflash.usb.UsbTransport
import java.io.ByteArrayOutputStream
import java.util.Locale

/** A scriptable in-memory fastboot device. It speaks the wire protocol, nothing more. */
class FakeFastbootDevice(
    var vars: MutableMap<String, String> = mutableMapOf("max-download-size" to "0x10000", "product" to "fake"),
) : UsbTransport {
    val commands = ArrayList<String>()
    val flashed = ArrayList<Pair<String, ByteArray>>()
    val failures = HashMap<String, String>()          // command prefix -> FAIL text
    val infoLines = HashMap<String, List<String>>()   // command prefix -> INFO lines before OKAY
    val throwOnWrite = HashMap<String, Throwable>()   // command prefix -> exception from write()
    var silent = false                                // never answer
    var lastDownloadAnnounced: Long? = null
    var announceWrongLength = false

    private val out = ArrayDeque<ByteArray>()
    private var expecting = 0L
    private val download = ByteArrayOutputStream()

    fun queue(text: String) { out.addLast(text.toByteArray(Charsets.US_ASCII)) }
    fun queueRaw(bytes: ByteArray) { out.addLast(bytes) }

    override fun write(data: ByteArray, offset: Int, length: Int, timeoutMs: Int) {
        if (expecting > 0) {
            check(length <= expecting) { "Host sent more data than announced" }
            download.write(data, offset, length)
            expecting -= length
            if (expecting == 0L) queue("OKAY")
            return
        }
        val cmd = String(data, offset, length, Charsets.US_ASCII)
        commands += cmd
        throwOnWrite.entries.firstOrNull { cmd.startsWith(it.key) }?.let { throw it.value }
        if (silent) return
        failures.entries.firstOrNull { cmd.startsWith(it.key) }?.let { queue("FAIL" + it.value); return }
        infoLines.entries.firstOrNull { cmd.startsWith(it.key) }?.value?.forEach { queue("INFO$it") }
        when {
            cmd == "getvar:all" -> { vars.forEach { (k, v) -> queue("INFO$k: $v") }; queue("OKAY") }
            cmd.startsWith("getvar:") -> vars[cmd.removePrefix("getvar:")]?.let { queue("OKAY$it") } ?: queue("FAILunknown variable")
            cmd.startsWith("download:") -> {
                val n = cmd.removePrefix("download:").toLong(16)
                lastDownloadAnnounced = n
                download.reset()
                expecting = n
                queue(String.format(Locale.ROOT, "DATA%08x", if (announceWrongLength) n + 1 else n))
            }
            cmd.startsWith("flash:") -> { flashed += cmd.removePrefix("flash:") to download.toByteArray(); download.reset(); queue("OKAY") }
            else -> queue("OKAY")
        }
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val next = out.removeFirstOrNull()
        if (next == null) {
            Thread.sleep(1)
            throw UsbTimeoutException("fake timeout")
        }
        System.arraycopy(next, 0, buffer, 0, next.size)
        return next.size
    }

    override fun close() {}
}

