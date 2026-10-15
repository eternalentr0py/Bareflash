package com.bareflash.app.samsung

import com.bareflash.app.log.AppLog
import com.bareflash.app.log.LogLevel
import com.bareflash.app.log.LogSource
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.system.StructTimeval
import java.io.File
import java.io.IOException
import java.net.Socket

/*
 * Kotlin half of the Brokkr (Odin) engine.
 *
 * libbrokkr.so looks these classes up by *name* through JNI, so the method and
 * field names below are a contract with brokkr/src-jni/brokkr_android_jni.cpp
 * and brokkr/src/platform/android/java_tcp_transport.cpp. Do not rename them.
 */

/** Everything the native flasher reports. Called from native worker threads. */
interface NativeFlashCallbacks {
    fun onLog(level: Int, message: String)
    fun onDevices(ids: Array<String>)
    fun onModel(model: String)
    fun onStage(stage: String)
    fun onPlanItem(
        index: Int, kind: Int, partId: Int, devType: Int,
        partName: String, pitFileName: String, sourceBase: String, size: Long
    )
    fun onPlanReady(count: Int, totalBytes: Long)
    fun onItemActive(index: Int)
    fun onItemDone(index: Int)
    fun onProgress(overallDone: Long, overallTotal: Long, itemDone: Long, itemTotal: Long)
    fun onDeviceError(index: Int, message: String)
    fun onError(message: String)
    fun onFinished(success: Boolean, message: String)
}

/**
 * One Samsung device in download mode, opened through Android's UsbManager.
 *
 * Native reads [nativeFd], [interfaceNumber], [bulkInAddress] and
 * [bulkOutAddress] and wraps the descriptor with libusb. The interface is
 * claimed here with force=true (which detaches cdc_acm on devices that bind
 * it). The native claim on the same descriptor then reports LIBUSB_ERROR_BUSY,
 * because usbfs already owns the interface; LibusbUsbTransport::open treats
 * that as "already ours" instead of failing the flash.
 */
class BrokkrUsbTransport private constructor(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val claimedInterface: UsbInterface,
    @JvmField val nativeFd: Int,
    @JvmField val interfaceNumber: Int,
    @JvmField val bulkInAddress: Int,
    @JvmField val bulkOutAddress: Int,
    /** wMaxPacketSize of the bulk IN endpoint; reveals the negotiated link speed. */
    val maxPacketIn: Int
) {
    @Volatile private var closed = false

    fun getId(): String = idFor(device)

    /** e.g. "High-Speed (480 Mbit/s)". Same thresholds as native bfusb_link_name(). */
    val linkName: String get() = linkName(maxPacketIn)

    /** Full-Speed (USB 1.1): certainly slow, usually a bad OTG adapter, cable or hub. */
    val linkIsSlow: Boolean get() = maxPacketIn in 1..64

    fun isConnected(): Boolean = !closed

    fun close() {
        if (closed) return
        closed = true
        runCatching { connection.releaseInterface(claimedInterface) }
        runCatching { connection.close() }
    }

    companion object {
        const val SAMSUNG_VID = 0x04E8

        /** Download-mode (Odin) product ids, from Brokkr's samsung_usb.hpp. */
        val ODIN_PIDS = setOf(0x6601, 0x685D, 0x68C3)

        fun isOdin(d: UsbDevice) = d.vendorId == SAMSUNG_VID && d.productId in ODIN_PIDS

        fun linkName(maxPacket: Int): String = when {
            maxPacket in 1..64 -> "Full-Speed (12 Mbit/s)"
            maxPacket == 512 -> "High-Speed (480 Mbit/s)"
            maxPacket >= 1024 -> "SuperSpeed (5 Gbit/s+)"
            else -> "unknown"
        }

        /** Short, stable label such as "001/014" used in the UI and per-device errors. */
        fun idFor(d: UsbDevice): String = d.deviceName.removePrefix("/dev/bus/usb/")

        /** Prefers the CDC data interface (class 0x0A); falls back to any interface with a bulk pair. */
        private fun pickInterface(d: UsbDevice): Triple<UsbInterface, Int, Int>? {
            var fallback: Triple<UsbInterface, Int, Int>? = null
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                var bulkIn = -1
                var bulkOut = -1
                for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = ep.address else bulkOut = ep.address
                }
                if (bulkIn < 0 || bulkOut < 0) continue
                val hit = Triple(itf, bulkIn, bulkOut)
                if (itf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) return hit
                if (fallback == null) fallback = hit
            }
            return fallback
        }

        @Throws(IOException::class)
        fun open(usb: UsbManager, device: UsbDevice): BrokkrUsbTransport {
            val (itf, inAddr, outAddr) = pickInterface(device)
                ?: throw IOException("${idFor(device)} has no bulk IN/OUT interface")
            val conn = usb.openDevice(device)
                ?: throw IOException("Could not open ${idFor(device)} (USB access not granted?)")
            if (!conn.claimInterface(itf, true)) {
                conn.close()
                throw IOException("Could not claim interface ${itf.id} on ${idFor(device)}")
            }
            val inPacket = (0 until itf.endpointCount).map { itf.getEndpoint(it) }
                .firstOrNull { it.address == inAddr }?.maxPacketSize ?: 0
            return BrokkrUsbTransport(device, conn, itf, conn.fileDescriptor, itf.id, inAddr, outAddr, inPacket)
        }
    }
}

/**
 * Wireless (TCP) transport, used for watches that dial in to the phone.
 * Matches the semantics of the desktop TcpConnection: send writes everything
 * or fails, receive returns at least one byte or fails, negative = dead link.
 *
 * Plain blocking [Socket] with kernel send/receive timeouts. A dup of the
 * descriptor is kept for non-destructive liveness polling.
 */
class BrokkrTcpTransport private constructor(
    private val socket: Socket,
    private val probe: ParcelFileDescriptor,
    val peerHost: String,
    val peerPort: Int
) {
    @Volatile private var closed = false
    @Volatile private var timeoutMs = DEFAULT_TIMEOUT_MS
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    fun getId(): String = "$peerHost:$peerPort"

    fun getPeerLabel(): String = getId()

    fun isConnected(): Boolean {
        if (closed) return false
        return try {
            val p = StructPollfd().apply { fd = probe.fileDescriptor; events = 0 }
            Os.poll(arrayOf(p), 0)
            val r = p.revents.toInt()
            when {
                r.has(OsConstants.POLLERR or OsConstants.POLLNVAL) -> false
                // Peer closed but unread bytes may remain; stay "connected" until drained.
                r.has(OsConstants.POLLHUP) && !r.has(OsConstants.POLLIN) -> false
                else -> true
            }
        } catch (_: Throwable) { false }
    }

    fun setPacketSizeHint(bytes: Int) {
        if (bytes <= 0) return
        val size = bytes.coerceAtMost(4 * 1024 * 1024)
        runCatching { socket.sendBufferSize = size }
        runCatching { socket.receiveBufferSize = size }
    }

    fun setTimeoutMs(ms: Int) { applyTimeout(if (ms <= 0) 1 else ms) }

    fun sendChunk(data: ByteArray, length: Int, timeout: Int, retries: Int): Int {
        if (closed || length < 0 || length > data.size) return -1
        if (timeout > 0 && timeout != timeoutMs) applyTimeout(timeout)
        return try {
            output.write(data, 0, length)
            length
        } catch (_: Throwable) { fail() }
    }

    fun receiveChunk(out: ByteArray, length: Int, timeout: Int, retries: Int): Int {
        if (closed || length <= 0 || length > out.size) return -1
        if (timeout > 0 && timeout != timeoutMs) applyTimeout(timeout)
        return try {
            val n = input.read(out, 0, length)
            if (n > 0) n else fail()   // 0 cannot happen for length > 0; -1 = peer closed
        } catch (_: Throwable) { fail() } // includes SocketTimeoutException
    }

    /** Zero-length packets only exist on USB; over TCP this is a no-op. */
    fun receiveZlp(retries: Int): Int = if (closed) -1 else 0

    fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
        runCatching { probe.close() }
    }

    private fun fail(): Int { close(); return -1 }

    private fun applyTimeout(ms: Int) {
        timeoutMs = ms
        runCatching { socket.soTimeout = ms }
        // Java has no send timeout; set SO_SNDTIMEO on the shared socket directly.
        runCatching {
            Os.setsockoptTimeval(
                probe.fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO,
                StructTimeval.fromMillis(ms.toLong())
            )
        }
    }

    private fun Int.has(mask: Int) = (this and mask) != 0

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 10_000

        @Throws(IOException::class)
        fun wrap(socket: Socket): BrokkrTcpTransport {
            runCatching { socket.tcpNoDelay = true }
            runCatching { socket.keepAlive = true }
            val host = socket.inetAddress?.hostAddress ?: "unknown"
            val t = BrokkrTcpTransport(socket, ParcelFileDescriptor.fromSocket(socket), host, socket.port)
            t.applyTimeout(DEFAULT_TIMEOUT_MS)
            return t
        }
    }
}

/**
 * Owns one native flash session. [run] blocks until the flash ends and must be
 * called from a worker thread. [cancel] is safe from any thread.
 */
class BrokkrNativeBridge(cacheDir: File) {

    private val cachePath: String = cacheDir.absolutePath
    @Volatile private var handle = 0L
    @Volatile var cancelled = false
        private set

    private external fun nativeCreateSession(callback: NativeFlashCallbacks, cacheDir: String): Long
    private external fun nativeDestroySession(handle: Long)
    private external fun nativeRequestCancel(handle: Long)
    private external fun nativeRunSession(
        handle: Long, transports: Array<BrokkrUsbTransport>, packageFds: IntArray,
        packageLabels: Array<String>, manualPitFd: Int, manualPitLabel: String?, autoReboot: Boolean
    )
    private external fun nativeRunWirelessSession(
        handle: Long, transport: BrokkrTcpTransport, packageFds: IntArray,
        packageLabels: Array<String>, manualPitFd: Int, manualPitLabel: String?, autoReboot: Boolean
    )

    /** Blocks. Returns once native has finished (success, failure or cancel). */
    fun run(
        callbacks: NativeFlashCallbacks,
        usb: List<BrokkrUsbTransport>,
        tcp: BrokkrTcpTransport?,
        packageFds: IntArray,
        packageLabels: Array<String>,
        manualPitFd: Int,
        manualPitLabel: String?,
        autoReboot: Boolean
    ) {
        val h = nativeCreateSession(callbacks, cachePath)
        check(h != 0L) { "Could not create a native Brokkr session" }
        handle = h
        try {
            if (cancelled) return
            if (tcp != null) {
                nativeRunWirelessSession(h, tcp, packageFds, packageLabels, manualPitFd, manualPitLabel, autoReboot)
            } else {
                nativeRunSession(h, usb.toTypedArray(), packageFds, packageLabels, manualPitFd, manualPitLabel, autoReboot)
            }
        } finally {
            handle = 0L
            nativeDestroySession(h)
        }
    }

    /**
     * Asks native to stop. Native silences its callbacks as part of this, so
     * the caller is responsible for reporting the run as cancelled.
     */
    fun cancel() {
        cancelled = true
        val h = handle
        if (h != 0L) nativeRequestCancel(h)
    }

    companion object {
        /** Loads libusb first: libbrokkr.so has it as a DT_NEEDED dependency. */
        val loaded: Boolean by lazy {
            try {
                System.loadLibrary("usb-1.0")
                System.loadLibrary("brokkr")
                true
            } catch (t: Throwable) {
                AppLog.log(LogLevel.Fatal, LogSource.Samsung, "brokkr", "Native flasher library failed to load", t)
                loadError = t.message ?: t.javaClass.simpleName
                false
            }
        }

        @Volatile var loadError: String? = null
    }
}
