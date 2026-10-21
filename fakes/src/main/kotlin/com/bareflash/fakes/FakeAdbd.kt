package com.bareflash.fakes

import com.bareflash.adb.AdbCommand
import com.bareflash.adb.AdbMessage
import com.bareflash.adb.AdbProtocol

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

/** The device's in-memory filesystem for sync tests. Paths are absolute, no trailing slash. */
/** Shell v2 packet ids (the wire values, kept here so the fakes need no internal API). */
private const val SHELL_STDIN = 0
private const val SHELL_STDOUT = 1
private const val SHELL_STDERR = 2
private const val SHELL_EXIT = 3
private const val SHELL_CLOSE_STDIN = 4
private const val SHELL_WINDOW_SIZE = 5

class FakeFs {
    class Entry(var data: ByteArray, var mode: Int, var mtime: Long)

    val files = LinkedHashMap<String, Entry>()
    val dirs = linkedSetOf("/", "/sdcard")

    /** Extra raw names a directory listing should return, for hostile-device tests. */
    val injectedNames = HashMap<String, List<String>>()

    fun put(path: String, data: ByteArray, mode: Int = 0x81A4, mtime: Long = 1_700_000_000) {
        files[path] = Entry(data, mode, mtime)
        var p = path.substringBeforeLast('/')
        while (p.isNotEmpty()) { dirs += p; p = p.substringBeforeLast('/', "") }
    }

    fun children(dir: String): List<String> {
        val prefix = if (dir == "/") "/" else "$dir/"
        val names = sortedSetOf<String>()
        for (p in files.keys + dirs) if (p != dir && p.startsWith(prefix) && '/' !in p.removePrefix(prefix)) names += p.removePrefix(prefix)
        return names.toList() + (injectedNames[dir] ?: emptyList())
    }
}

class ShellOutcome(val stdout: ByteArray = ByteArray(0), val stderr: ByteArray = ByteArray(0), val exit: Int = 0)

/**
 * A message-level model of adbd, written from the protocol description. It
 * is driven by [onMessage] and returns the messages the device would send.
 * Wrapped as a USB transport for client tests and as a TCP server so the
 * real `adb` binary can be pointed at it.
 */
class FakeAdbdCore(
    val fs: FakeFs = FakeFs(),
    var banner: String = "device::ro.product.name=fake;ro.product.model=Fake Phone;ro.product.device=fakedev;features=shell_v2,cmd,stat_v2,ls_v2",
    var requireAuth: Boolean = false,
    var userApprovesPrompt: Boolean = true,
    /** After a public key is offered, answer with another token instead of accepting. */
    var rejectOfferedKey: Boolean = false,
    var maxPayload: Int = 1 shl 20,
    var version: Int = AdbProtocol.VERSION_SKIP_CHECKSUM,
    var shellHandler: (String) -> ShellOutcome = { ShellOutcome() },
    val authorizedKeys: MutableList<PublicKey> = ArrayList(),
) {
    val offeredKeyMessages = ArrayList<ByteArray>()
    val openedServices = ArrayList<String>()
    val failedSends = HashSet<String>()           // paths whose SEND ends in FAIL
    var rebootRequested: String? = null
    var hostVersion = 0
    var hostMaxPayload = AdbProtocol.LEGACY_MAX_PAYLOAD
    var hostBanner = ""
    var authenticated = false
    private var token = ByteArray(0)
    private val random = SecureRandom()
    private var nextDeviceId = 100
    private val streams = HashMap<Int, DevStream>() // by device id

    @Synchronized
    fun onMessage(m: AdbMessage): List<AdbMessage> {
        val out = ArrayList<AdbMessage>()
        when (m.command) {
            AdbCommand.CNXN -> {
                hostVersion = m.arg0; hostMaxPayload = m.arg1; hostBanner = String(m.data).trimEnd('\u0000')
                if (!requireAuth) accept(out) else challenge(out)
            }
            AdbCommand.AUTH -> when (m.arg0) {
                AdbProtocol.AUTH_SIGNATURE -> if (authorizedKeys.any { verify(it, m.data) }) accept(out) else challenge(out)
                AdbProtocol.AUTH_RSAPUBLICKEY -> {
                    offeredKeyMessages += m.data
                    if (rejectOfferedKey) challenge(out)
                    else if (userApprovesPrompt) { authorizedKeys += parseAndroidKey(m.data); accept(out) }
                    // else: the user ignores the prompt, so the device stays silent
                }
            }
            AdbCommand.OPEN -> if (authenticated) open(m, out)
            AdbCommand.WRTE -> streams[m.arg1]?.let { s ->
                out += AdbMessage(AdbCommand.OKAY, s.deviceId, s.hostId)
                s.onHostData(m.data, out)
            }
            AdbCommand.OKAY -> streams[m.arg1]?.let { it.acked = true; it.pump(out) }
            AdbCommand.CLSE -> streams.remove(m.arg1)?.let { out += AdbMessage(AdbCommand.CLSE, it.deviceId, it.hostId) }
        }
        return out
    }

    private fun accept(out: MutableList<AdbMessage>) {
        authenticated = true
        out += AdbMessage(AdbCommand.CNXN, version, maxPayload, (banner + "\u0000").toByteArray())
    }

    private fun challenge(out: MutableList<AdbMessage>) {
        token = ByteArray(AdbProtocol.AUTH_TOKEN_SIZE).also { random.nextBytes(it) }
        out += AdbMessage(AdbCommand.AUTH, AdbProtocol.AUTH_TOKEN, 0, token)
    }

    /** adbd checks the signature as RSA PKCS#1 v1.5 over SHA-1's DigestInfo prefix plus the raw token. */
    private fun verify(key: PublicKey, signature: ByteArray): Boolean = try {
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        c.init(Cipher.DECRYPT_MODE, key)
        val prefix = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
        c.doFinal(signature).contentEquals(prefix + token)
    } catch (_: Exception) { false }

    private fun open(m: AdbMessage, out: MutableList<AdbMessage>) {
        val service = String(m.data).trimEnd('\u0000')
        openedServices += service
        val id = nextDeviceId++
        val limit = minOf(maxPayload, hostMaxPayload)
        val s: DevStream? = when {
            service.startsWith("shell,v2,") -> ShellV2Stream(id, m.arg0, limit, service.substringAfter(':'), service.substringAfter("shell,v2,").substringBefore(':').contains("pty"))
            service.startsWith("shell:") -> LegacyShellStream(id, m.arg0, limit, service.removePrefix("shell:"), merge = true)
            service.startsWith("exec:") -> LegacyShellStream(id, m.arg0, limit, service.removePrefix("exec:"), merge = false)
            service == "sync:" -> SyncStream(id, m.arg0, limit)
            service.startsWith("reboot:") -> { rebootRequested = service.removePrefix("reboot:").ifEmpty { "system" }; LegacyShellStream(id, m.arg0, limit, "", merge = false) }
            else -> null
        }
        if (s == null) { out += AdbMessage(AdbCommand.CLSE, 0, m.arg0); return }
        streams[id] = s
        out += AdbMessage(AdbCommand.OKAY, id, m.arg0)
        s.start(out)
        s.pump(out)
    }

    // ---- Device-side streams ------------------------------------------------

    private abstract inner class DevStream(val deviceId: Int, val hostId: Int, val limit: Int) {
        private val queue = ArrayDeque<ByteArray>()
        var acked = true
        var closeWhenDone = false

        open fun start(out: MutableList<AdbMessage>) {}
        open fun onHostData(data: ByteArray, out: MutableList<AdbMessage>) {}

        fun enqueue(data: ByteArray) {
            var i = 0
            while (i < data.size) { val n = minOf(limit, data.size - i); queue.addLast(data.copyOfRange(i, i + n)); i += n }
        }

        fun pump(out: MutableList<AdbMessage>) {
            if (acked && queue.isNotEmpty()) {
                acked = false
                out += AdbMessage(AdbCommand.WRTE, deviceId, hostId, queue.removeFirst())
            } else if (acked && queue.isEmpty() && closeWhenDone) {
                streams.remove(deviceId)
                out += AdbMessage(AdbCommand.CLSE, deviceId, hostId)
            }
        }
    }

    private inner class LegacyShellStream(id: Int, host: Int, limit: Int, val command: String, val merge: Boolean) : DevStream(id, host, limit) {
        override fun start(out: MutableList<AdbMessage>) {
            val r = shellHandler(command)
            enqueue(if (merge) r.stdout + r.stderr else r.stdout)
            closeWhenDone = true
        }
    }

    private inner class ShellV2Stream(id: Int, host: Int, limit: Int, val command: String, val pty: Boolean) : DevStream(id, host, limit) {
        private var inbuf = ByteArray(0)
        val stdinSeen = ByteArrayOutputStream()
        val resizes = ArrayList<String>()

        private fun packet(kind: Int, data: ByteArray) {
            // Like adbd, cap each packet's payload; a packet may still be split across messages.
            var i = 0
            do {
                val n = minOf(32 * 1024, data.size - i)
                enqueue(ByteBuffer.allocate(5 + n).order(ByteOrder.LITTLE_ENDIAN).put(kind.toByte()).putInt(n).put(data, i, n).array())
                i += n
            } while (i < data.size)
        }

        override fun start(out: MutableList<AdbMessage>) {
            if (command == "cat") return // interactive echo, driven by stdin packets
            val r = shellHandler(command)
            if (r.stdout.isNotEmpty()) packet(SHELL_STDOUT, r.stdout)
            if (r.stderr.isNotEmpty()) packet(SHELL_STDERR, r.stderr)
            packet(SHELL_EXIT, byteArrayOf(r.exit.toByte()))
            closeWhenDone = true
        }

        override fun onHostData(data: ByteArray, out: MutableList<AdbMessage>) {
            inbuf += data
            while (inbuf.size >= 5) {
                val kind = inbuf[0].toInt()
                val len = ByteBuffer.wrap(inbuf, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (inbuf.size < 5 + len) break
                val payload = inbuf.copyOfRange(5, 5 + len)
                inbuf = inbuf.copyOfRange(5 + len, inbuf.size)
                when (kind) {
                    SHELL_STDIN -> { stdinSeen.write(payload); if (command == "cat") packet(SHELL_STDOUT, payload) }
                    SHELL_CLOSE_STDIN -> if (command == "cat") { packet(SHELL_EXIT, byteArrayOf(0)); closeWhenDone = true }
                    SHELL_WINDOW_SIZE -> resizes += String(payload)
                }
            }
            pump(out)
        }
    }

    private inner class SyncStream(id: Int, host: Int, limit: Int) : DevStream(id, host, limit) {
        private var inbuf = ByteArray(0)
        private var sendPath: String? = null
        private var sendMode = 0
        private val sendData = ByteArrayOutputStream()

        private fun reply(id: String, vararg ints: Int) {
            val b = ByteBuffer.allocate(4 + 4 * ints.size).order(ByteOrder.LITTLE_ENDIAN)
            b.put(id.toByteArray(Charsets.US_ASCII)); ints.forEach { b.putInt(it) }
            enqueue(b.array())
        }

        private fun fail(msg: String) {
            val m = msg.toByteArray()
            enqueue("FAIL".toByteArray() + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(m.size).array() + m)
        }

        private fun statV2(id: String, error: Int, mode: Int, size: Long, mtime: Long, extra: ByteArray = ByteArray(0)): ByteArray =
            ByteBuffer.allocate(4 + 68 + extra.size).order(ByteOrder.LITTLE_ENDIAN)
                .put(id.toByteArray(Charsets.US_ASCII)).putInt(error).putLong(1).putLong(2).putInt(mode).putInt(1).putInt(1000).putInt(1000)
                .putLong(size).putLong(mtime).putLong(mtime).putLong(mtime).put(extra).array()

        override fun onHostData(data: ByteArray, out: MutableList<AdbMessage>) {
            inbuf += data
            while (inbuf.size >= 8) {
                val id = String(inbuf, 0, 4, Charsets.US_ASCII)
                val len = ByteBuffer.wrap(inbuf, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val streaming = sendPath != null
                val needs = when {
                    streaming && id == "DATA" -> len
                    streaming && id == "DONE" -> 0
                    id == "QUIT" -> 0
                    else -> len
                }
                if (inbuf.size < 8 + needs) break
                val body = inbuf.copyOfRange(8, 8 + needs)
                inbuf = inbuf.copyOfRange(8 + needs, inbuf.size)
                handle(id, len, body)
            }
            pump(out)
        }

        private fun handle(id: String, len: Int, body: ByteArray) {
            val path = String(body)
            when {
                sendPath != null && id == "DATA" -> sendData.write(body)
                sendPath != null && id == "DONE" -> {
                    val p = sendPath!!
                    if (p in failedSends) fail("couldn't create file: No space left on device")
                    else { fs.put(p, sendData.toByteArray(), sendMode, len.toLong() and 0xFFFFFFFFL); reply("OKAY", 0) }
                    sendPath = null; sendData.reset()
                }
                id == "QUIT" -> closeWhenDone = true
                id == "STAT" -> { val e = fs.files[path]; val d = path in fs.dirs
                    if (e != null) reply("STAT", e.mode, e.data.size, e.mtime.toInt()) else if (d) reply("STAT", 0x41ED, 4096, 1_700_000_000) else reply("STAT", 0, 0, 0) }
                id == "STA2" -> { val e = fs.files[path]; val d = path in fs.dirs
                    enqueue(if (e != null) statV2("STA2", 0, e.mode, e.data.size.toLong(), e.mtime) else if (d) statV2("STA2", 0, 0x41ED, 4096, 1_700_000_000) else statV2("STA2", 2, 0, 0, 0)) }
                id == "LIST" -> {
                    if (path !in fs.dirs) { fail("No such directory"); return }
                    for (n in listOf(".", "..") + fs.children(path)) {
                        val full = if (path == "/") "/$n" else "$path/$n"
                        val e = fs.files[full]
                        val nb = n.toByteArray()
                        val b = ByteBuffer.allocate(20 + nb.size).order(ByteOrder.LITTLE_ENDIAN).put("DENT".toByteArray())
                            .putInt(e?.mode ?: 0x41ED).putInt(e?.data?.size ?: 4096).putInt((e?.mtime ?: 1_700_000_000).toInt()).putInt(nb.size).put(nb)
                        enqueue(b.array())
                    }
                    enqueue("DONE".toByteArray() + ByteArray(16))
                }
                id == "LIS2" -> {
                    if (path !in fs.dirs) { fail("No such directory"); return }
                    for (n in listOf(".", "..") + fs.children(path)) {
                        val full = if (path == "/") "/$n" else "$path/$n"
                        val e = fs.files[full]
                        val nb = n.toByteArray()
                        val lenBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(nb.size).array()
                        enqueue(statV2("DNT2", 0, e?.mode ?: 0x41ED, (e?.data?.size ?: 4096).toLong(), e?.mtime ?: 1_700_000_000, lenBytes + nb))
                    }
                    enqueue("DONE".toByteArray() + ByteArray(72))
                }
                id == "RECV" -> {
                    val e = fs.files[path] ?: run { fail("No such file or directory"); return }
                    var i = 0
                    while (i < e.data.size) {
                        val n = minOf(64 * 1024, e.data.size - i)
                        enqueue("DATA".toByteArray() + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array() + e.data.copyOfRange(i, i + n))
                        i += n
                    }
                    reply("DONE", 0)
                }
                id == "SEND" -> {
                    val comma = path.lastIndexOf(',')
                    sendPath = path.substring(0, comma); sendMode = path.substring(comma + 1).toInt(); sendData.reset()
                }
                else -> fail("unknown sync command $id")
            }
        }
    }

    companion object {
        /** Decodes the 524-byte Android struct (inside base64) back into a JCA public key. Independent of the production encoder. */
        fun parseAndroidKey(message: ByteArray): PublicKey {
            val text = String(message).trimEnd('\u0000')
            val raw = java.util.Base64.getDecoder().decode(text.substringBefore(' '))
            require(raw.size == 524) { "Android key struct must be 524 bytes, got ${raw.size}" }
            val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            require(b.getInt(0) == 64)
            val n = BigInteger(1, raw.copyOfRange(8, 8 + 256).reversedArray())
            val e = BigInteger.valueOf(b.getInt(520).toLong() and 0xFFFFFFFFL)
            return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
        }
    }
}
