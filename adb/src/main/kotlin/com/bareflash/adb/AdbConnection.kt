package com.bareflash.adb

import com.bareflash.usb.CancelToken
import com.bareflash.usb.UsbTransport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Tunables for [AdbConnection.connect]. The defaults suit a phone on a USB cable. */
public class AdbConnectionConfig(
    /** Shown on the device's authorization prompt, normally `user@host`. */
    public val keyName: String = "bareflash@android",
    /** How long the CNXN/AUTH exchange may take when no user action is needed. */
    public val connectTimeoutMs: Long = 10_000,
    /** How long to wait for the user to tap "Allow" after the key has been offered. */
    public val approvalTimeoutMs: Long = 60_000,
    public val writeTimeoutMs: Int = 10_000,
    public val openTimeoutMs: Long = 10_000,
)

/** Callbacks for the authorization step. All methods have empty defaults. */
public interface AdbAuthListener {
    /**
     * The device did not recognize this host's key, which has now been offered.
     * The user must accept the prompt on the device; compare [fingerprint]
     * with what it shows.
     */
    public fun onWaitingForUserApproval(fingerprint: String) {}
}

/** The device's own description of itself, from its CNXN banner. */
public class AdbDeviceInfo(
    /** `device`, `recovery`, `sideload`, `bootloader`, ... */
    public val state: String,
    public val properties: Map<String, String>,
    public val features: Set<String>,
) {
    public val model: String? get() = properties["ro.product.model"]
    public val product: String? get() = properties["ro.product.name"]
    public val device: String? get() = properties["ro.product.device"]

    internal companion object {
        /** Parses `device::ro.product.name=x;ro.product.model=y;features=a,b`. */
        fun parse(banner: String): AdbDeviceInfo {
            val sep = banner.indexOf("::")
            val state = if (sep >= 0) banner.substring(0, sep) else banner
            val rest = if (sep >= 0) banner.substring(sep + 2) else ""
            val props = LinkedHashMap<String, String>()
            for (part in rest.split(';')) {
                val eq = part.indexOf('=')
                if (eq > 0) props[part.substring(0, eq)] = part.substring(eq + 1)
            }
            val features = props["features"]?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
            return AdbDeviceInfo(state, props, features)
        }
    }
}

/**
 * An authenticated ADB session with one device, over any [UsbTransport].
 *
 * Create it with [connect]. A background thread reads every incoming message
 * and routes it to the matching [AdbStream]; any thread may open streams.
 * Closing the connection closes the transport.
 */
public class AdbConnection private constructor(
    private val transport: UsbTransport,
    private val channel: MessageChannel,
    private val config: AdbConnectionConfig,
    private val log: (String) -> Unit,
    public val info: AdbDeviceInfo,
    /** Largest payload the device accepts in one data message. */
    public val maxPayload: Int,
) : AutoCloseable {

    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private val nextLocalId = AtomicInteger(1)

    @Volatile
    private var closedReason: AdbException? = null

    private val reader = Thread(::readLoop, "adb-reader").apply { isDaemon = true }

    /** Features that both the device offers and this host implements. */
    public fun supports(feature: String): Boolean =
        feature in info.features && feature in AdbProtocol.HOST_FEATURES

    public val isOpen: Boolean get() = closedReason == null

    /** Opens the device service named [destination], e.g. `shell:ls` or `sync:`. */
    @JvmOverloads
    public fun open(destination: String, timeoutMs: Long = config.openTimeoutMs): AdbStream {
        require(destination.isNotEmpty() && '\u0000' !in destination) { "Invalid service name" }
        val payload = destination.toByteArray(Charsets.UTF_8) + 0
        require(payload.size <= minOf(maxPayload, AdbProtocol.LEGACY_MAX_PAYLOAD)) { "Service name too long" }
        throwIfClosed()
        val id = nextLocalId.getAndUpdate { if (it == Int.MAX_VALUE) 1 else it + 1 }
        val stream = AdbStream(this, id, destination)
        streams[id] = stream
        try {
            sendOrFail(AdbMessage(AdbCommand.OPEN, id, 0, payload))
            stream.awaitOpen(timeoutMs)
        } catch (e: Throwable) {
            streams.remove(id)
            throw e
        }
        return stream
    }

    override fun close() {
        fail(AdbClosedException("Connection closed"))
        reader.interrupt()
    }

    // ---- Used by AdbStream ---------------------------------------------------

    internal fun sendWrite(localId: Int, remoteId: Int, data: ByteArray) =
        sendOrFail(AdbMessage(AdbCommand.WRTE, localId, remoteId, data))

    internal fun sendOkay(localId: Int, remoteId: Int) =
        sendOrFail(AdbMessage(AdbCommand.OKAY, localId, remoteId))

    internal fun sendCloseQuietly(localId: Int, remoteId: Int) {
        try {
            channel.send(AdbMessage(AdbCommand.CLSE, localId, remoteId))
        } catch (e: java.io.IOException) {
            log("CLSE not sent: ${e.message}")
        }
    }

    internal fun forget(localId: Int) {
        streams.remove(localId)
    }

    // ---- Reader thread -------------------------------------------------------

    private fun readLoop() {
        try {
            while (closedReason == null) {
                val m = channel.receive(MessageChannel.NO_DEADLINE, PAYLOAD_TIMEOUT_MS)
                dispatch(m)
            }
        } catch (e: AdbClosedException) {
            fail(e)
        } catch (e: java.io.IOException) {
            fail(if (e is AdbException) e else AdbClosedException("USB link failed: ${e.message}", e))
        } catch (e: RuntimeException) {
            fail(AdbProtocolException("Internal error in reader: $e"))
        }
    }

    private fun dispatch(m: AdbMessage) {
        when (m.command) {
            AdbCommand.OKAY -> streams[m.arg1]?.onOkay(m.arg0)
            AdbCommand.WRTE -> {
                val s = streams[m.arg1]
                if (s != null) s.onData(m.data) else log("WRTE for unknown stream ${m.arg1} ignored")
            }
            AdbCommand.CLSE -> {
                val s = streams.remove(m.arg1) ?: return
                val replyTo = s.onRemoteClose()
                if (replyTo != 0) sendCloseQuietly(s.localId, replyTo)
            }
            else -> log("Ignoring ${AdbCommand.name(m.command)} after connect")
        }
    }

    private fun sendOrFail(m: AdbMessage) {
        throwIfClosed()
        try {
            channel.send(m)
        } catch (e: java.io.IOException) {
            val reason = AdbClosedException("USB link failed: ${e.message}", e)
            fail(reason)
            throw reason
        }
    }

    private fun throwIfClosed() {
        closedReason?.let { throw AdbClosedException("Connection is closed: ${it.message}", it) }
    }

    @Synchronized
    private fun fail(reason: AdbException) {
        if (closedReason != null) return
        closedReason = reason
        channel.stopped = true
        for (s in streams.values) s.fail(reason)
        streams.clear()
        try { transport.close() } catch (_: java.io.IOException) { /* already gone */ }
    }

    public companion object {
        private const val PAYLOAD_TIMEOUT_MS = 30_000L

        /**
         * Performs the CNXN/AUTH handshake and returns a ready connection.
         *
         * The caller keeps ownership of [transport] if this throws; on success
         * the connection owns it. If the device has not seen [keys] before it
         * shows a prompt: [listener] is told, and this call waits up to
         * [AdbConnectionConfig.approvalTimeoutMs] for the user to accept.
         */
        public fun connect(
            transport: UsbTransport,
            keys: AdbKeyPair,
            config: AdbConnectionConfig = AdbConnectionConfig(),
            listener: AdbAuthListener? = null,
            cancel: CancelToken = CancelToken.NEVER,
            log: (String) -> Unit = {},
        ): AdbConnection {
            val channel = MessageChannel(transport, AdbProtocol.HOST_MAX_PAYLOAD, config.writeTimeoutMs)
            val banner = "host::features=" + AdbProtocol.HOST_FEATURES.joinToString(",")
            channel.send(AdbMessage(AdbCommand.CNXN, AdbProtocol.VERSION_SKIP_CHECKSUM, AdbProtocol.HOST_MAX_PAYLOAD, banner.toByteArray() + 0))

            var signed = false
            var keyOffered = false
            var deadline = System.nanoTime() + config.connectTimeoutMs * NANOS_PER_MS
            while (true) {
                if (cancel.isCancelled()) throw AdbCancelledException()
                val m = channel.receive(deadline, PAYLOAD_TIMEOUT_MS)
                when (m.command) {
                    AdbCommand.CNXN -> return finishConnect(transport, channel, config, log, m)
                    AdbCommand.AUTH -> {
                        if (m.arg0 != AdbProtocol.AUTH_TOKEN) {
                            log("Ignoring AUTH type ${m.arg0}")
                            continue
                        }
                        if (m.data.size != AdbProtocol.AUTH_TOKEN_SIZE) throw AdbProtocolException("Auth token must be ${AdbProtocol.AUTH_TOKEN_SIZE} bytes")
                        if (!signed) {
                            channel.send(AdbMessage(AdbCommand.AUTH, AdbProtocol.AUTH_SIGNATURE, 0, keys.signToken(m.data)))
                            signed = true
                        } else if (!keyOffered) {
                            channel.send(AdbMessage(AdbCommand.AUTH, AdbProtocol.AUTH_RSAPUBLICKEY, 0, keys.publicKeyMessage(config.keyName)))
                            keyOffered = true
                            deadline = System.nanoTime() + config.approvalTimeoutMs * NANOS_PER_MS
                            listener?.onWaitingForUserApproval(keys.fingerprint())
                        } else {
                            throw AdbAuthException("The device rejected this host's key")
                        }
                    }
                    AdbCommand.STLS -> throw AdbProtocolException("Device requires TLS, which is only used over the network")
                    else -> log("Ignoring ${AdbCommand.name(m.command)} during handshake")
                }
            }
        }

        private fun finishConnect(
            transport: UsbTransport,
            channel: MessageChannel,
            config: AdbConnectionConfig,
            log: (String) -> Unit,
            cnxn: AdbMessage,
        ): AdbConnection {
            val peerVersion = cnxn.arg0
            val negotiated = minOf(peerVersion, AdbProtocol.VERSION_SKIP_CHECKSUM)
            val needsChecksum = negotiated < AdbProtocol.VERSION_SKIP_CHECKSUM
            channel.sendChecksums = needsChecksum
            channel.verifyChecksums = needsChecksum
            val peerMax = if (cnxn.arg1 >= AdbProtocol.LEGACY_MAX_PAYLOAD) cnxn.arg1 else AdbProtocol.LEGACY_MAX_PAYLOAD
            val maxPayload = minOf(peerMax, AdbProtocol.HOST_MAX_PAYLOAD)
            val text = String(cnxn.data, Charsets.UTF_8).trimEnd('\u0000')
            log("Connected: $text (version 0x${peerVersion.toString(16)}, max payload $maxPayload)")
            val conn = AdbConnection(transport, channel, config, log, AdbDeviceInfo.parse(text), maxPayload)
            conn.reader.start()
            return conn
        }

        private const val NANOS_PER_MS = 1_000_000L
    }
}
