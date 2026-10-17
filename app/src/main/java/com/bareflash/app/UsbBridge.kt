package com.bareflash.app

import com.bareflash.app.log.AppLog
import com.bareflash.app.log.LogLevel
import com.bareflash.app.log.LogSource
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * USB Host surface used by androidusblib.py via Chaquopy.
 * Permission dialogs must be requested from an Activity first.
 *
 * All transfers go through libusb (see LibusbNative.kt / usb_native.c).
 * UsbManager is used only for device discovery, permission and
 * claimInterface(): several OEM kernels will not authorize USBFS_SUBMITURB
 * on the wrapped fd without a Java-side claim. If libusb is unavailable on
 * this ABI or wrapping/claiming fails, connect() fails instead of falling
 * back to another transfer path.
 */
object UsbBridge {

    /** Link description of the last MediaTek connection ("High-Speed (480 Mbit/s)"), for the pre-flight check. */
    @Volatile var lastLinkName: String? = null
    @Volatile var lastLinkSlow: Boolean = false
    private const val TAG = "UsbBridge"

    // Logcat is not readable from inside this app without root, so on a
    // real device the person running a flash has no way to see any of
    // this -- only whoever can plug the phone into a computer with adb
    // does. These wrappers mirror the actual USB transfer-layer
    // diagnostics (short reads, endpoint stalls, transfer errors --
    // exactly the kind of evidence needed to diagnose a failed command)
    // into the app's own terminal via UiBridge, in addition to Logcat, so
    // "Share log" / "Export log" / "Copy error details" (Console) and
    // Settings > Logging (session copy, crash/log reports) actually have
    // this data to work with. Deliberately NOT used for connect()'s own
    // status lines: those are already surfaced (with repeat-dedup) by
    // androidusblib.py printing UsbBridge.getLastStatus() itself, so
    // mirroring them here too would double them up, and during a
    // reconnect-poll loop that happens every ~250ms, badly.
    private fun dlog(msg: String, toTerminal: Boolean = true) {
        AppLog.log(LogLevel.Warn, LogSource.Usb, TAG, msg)
        if (toTerminal) {
            runCatching { UiBridge.appendLog("[usb-xfer] $msg\n") }
        }
    }

    // libusb error codes we branch on. PIPE is the only one that means
    // "endpoint is stalled, clear_halt and retry". TIMEOUT (-7) used to
    // be lumped in with "n <= 0 → clear_halt", which resets the data
    // toggle on a live endpoint and is what was kicking the DA off the
    // bus ("Waiting for device..." mid-readflash, then 0-byte acks).
    private const val LIBUSB_TIMEOUT = -7
    private const val LIBUSB_PIPE = -9

    // Chunk size for the libusb transfer loop in bulkReadExact()/
    // bulkWriteExact(). IN URBs are further split to one max-packet
    // inside usb_native.c; this cap only bounds the JNI buffer we hand
    // it. 16 KiB is the Android USBFS URB ceiling -- the previous 4 MiB
    // value was the DAX throughput experiment that made final-ack reads
    // swallow the next xflash header.
    private const val XFER_CHUNK = 16 * 1024

    // Aligned, exact-length transfers of at least BIG_MIN bytes go to
    // libusb in one call of up to BIG_XFER bytes. libusb splits those into
    // 16 KiB usbfs URBs and keeps them all in flight, so the bus never
    // idles between submissions and throughput does not depend on how
    // quickly this thread gets rescheduled after every 16 KiB. Reads only
    // ever use this for the exact remaining protocol length (see
    // bulkReadExact), so a frame boundary can never be overrun.
    private const val BIG_XFER = 1024 * 1024
    private const val BIG_MIN = 32 * 1024

    private var appContext: Context? = null
    private var manager: UsbManager? = null

    private var device: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var javaClaimed: Boolean = false

    private val emptyBytes: ByteArray = ByteArray(0)

    // Opaque libusb_device_handle* token from LibusbNative.nativeWrapFd(),
    // passed back into every other LibusbNative call for this
    // connection's lifetime. 0L only between connect() calls (never
    // "connected but not using libusb" -- there is no such state anymore).
    private var libusbHandle: Long = 0L

    // Surplus IN bytes are parked only in native (g_rx_left). The old
    // Kotlin-side leftover buffer was never populated after connect and
    // only added branching on the hot path; it has been removed.

    @Volatile
    private var lastStatus: String = "not initialized"

    /** Most recent hard transfer failure ("IN error=-4 (LIBUSB_ERROR_NO_DEVICE) ..."), for diagnostics. */
    @Volatile
    private var lastXferError: String = ""

    @JvmStatic
    fun getLastXferError(): String = lastXferError

    /** Device name retained at connect for O(1)-ish presence checks. */
    private var connectedDeviceName: String? = null

    /** True while the wrapped device is still enumerated by UsbManager. */
    @JvmStatic
    fun isDevicePresent(): Boolean {
        val name = connectedDeviceName ?: return false
        val d = device ?: return false
        val listed = manager?.deviceList?.get(name)
        return listed != null && listed.deviceId == d.deviceId
    }

    /** 16-bit byte sum, the xflash write checksum. Native-unrolled when libusb is loaded. */
    @JvmStatic
    fun byteSum16(data: ByteArray): Int {
        if (data.isEmpty()) return 0
        if (LibusbNative.available) {
            return LibusbNative.nativeByteSum16(data, 0, data.size)
        }
        var sum = 0
        var i = 0
        val n = data.size
        while (i + 8 <= n) {
            sum += (data[i].toInt() and 0xFF) + (data[i + 1].toInt() and 0xFF) +
                (data[i + 2].toInt() and 0xFF) + (data[i + 3].toInt() and 0xFF) +
                (data[i + 4].toInt() and 0xFF) + (data[i + 5].toInt() and 0xFF) +
                (data[i + 6].toInt() and 0xFF) + (data[i + 7].toInt() and 0xFF)
            i += 8
        }
        while (i < n) {
            sum += data[i].toInt() and 0xFF
            i++
        }
        return sum and 0xFFFF
    }

    /** Set while the user is cancelling a command; makes transfers fail fast. */
    @Volatile
    private var cancelled: Boolean = false

    @JvmStatic
    fun requestCancel(flag: Boolean) {
        cancelled = flag
        if (LibusbNative.available) {
            LibusbNative.nativeSetCancelled(flag)
        }
    }

    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
        manager = appContext?.getSystemService(Context.USB_SERVICE) as? UsbManager
        lastStatus = "initialized"
    }

    @JvmStatic
    fun getLastStatus(): String = lastStatus

    @JvmStatic
    fun getVid(): Int = device?.vendorId ?: -1

    @JvmStatic
    fun getPid(): Int = device?.productId ?: -1

    private fun findDevice(vid: Int, pid: Int): UsbDevice? =
        manager?.deviceList?.values?.firstOrNull { it.vendorId == vid && it.productId == pid }

    @JvmStatic
    fun detectDevices(): String {
        val arr = JSONArray()
        manager?.deviceList?.values?.forEach { d ->
            val o = JSONObject()
            o.put("vid", d.vendorId)
            o.put("pid", d.productId)
            o.put("name", d.deviceName)
            o.put("interfaces", d.interfaceCount)
            o.put("hasPermission", manager?.hasPermission(d) == true)
            val ifaces = JSONArray()
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                var bulkIn = 0
                var bulkOut = 0
                for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn++ else bulkOut++
                    }
                }
                val io = JSONObject()
                io.put("index", i)
                io.put("class", itf.interfaceClass)
                io.put("subclass", itf.interfaceSubclass)
                io.put("bulkIn", bulkIn)
                io.put("bulkOut", bulkOut)
                ifaces.put(io)
            }
            o.put("ifaceDetail", ifaces)
            arr.put(o)
        }
        return arr.toString()
    }

    @JvmStatic
    fun hasPermission(vid: Int, pid: Int): Boolean {
        val d = findDevice(vid, pid) ?: return false
        return manager?.hasPermission(d) ?: false
    }

    @JvmStatic
    fun connect(portConfigJson: String, devClass: Int): Boolean {
        close(false)
        cancelled = false
        if (LibusbNative.available) {
            LibusbNative.nativeSetCancelled(false)
        }
        val mgr = manager
        if (mgr == null) {
            lastStatus = "UsbManager is null (init not called?)"
            Log.e(TAG, lastStatus)
            return false
        }
        if (!LibusbNative.available) {
            lastStatus = "libusb native library is not available on this build/ABI; cannot connect"
            Log.e(TAG, lastStatus)
            return false
        }

        val configs = JSONArray(portConfigJson)
        var found: UsbDevice? = null
        var wantIface = -1
        loop@ for (i in 0 until configs.length()) {
            val c = configs.getJSONArray(i)
            val vid = c.getInt(0)
            val pid = c.getInt(1)
            val ifaceNum = if (c.length() > 2) c.getInt(2) else -1
            val d = findDevice(vid, pid)
            if (d != null) {
                found = d
                wantIface = ifaceNum
                break@loop
            }
        }
        if (found == null) {
            found = mgr.deviceList?.values?.firstOrNull { it.vendorId == 0x0E8D }
            wantIface = -1
        }
        val dev = found
        if (dev == null) {
            lastStatus = "No MTK USB device attached (looked for portconfig=$portConfigJson)"
            Log.w(TAG, lastStatus)
            return false
        }
        if (!mgr.hasPermission(dev)) {
            lastStatus = "No USB permission for ${dev.deviceName} (vid=0x${dev.vendorId.toString(16)} pid=0x${dev.productId.toString(16)}). Tap Detect Device first."
            Log.w(TAG, lastStatus)
            return false
        }
        val conn = mgr.openDevice(dev)
        if (conn == null) {
            lastStatus = "openDevice() failed for ${dev.deviceName}"
            Log.w(TAG, lastStatus)
            return false
        }

        try {
            // Only devices with more than one configuration actually need
            // one explicitly selected. Every MTK BROM/Preloader/DA
            // interface declares exactly one, and issuing
            // SET_CONFIGURATION anyway resets every interface's endpoint
            // halt/data-toggle state (USB 2.0 Sec. 9.4.7) even when
            // reselecting the same value -- real disruption, for a
            // minimal bootloader-mode USB gadget that doesn't expect it,
            // with no upside on hardware that only has the one config.
            if (dev.configurationCount > 1) {
                val cfg = dev.getConfiguration(0)
                val okCfg = conn.setConfiguration(cfg)
                Log.i(TAG, "setConfiguration(0) -> $okCfg (interfaces=${cfg.interfaceCount})")
                if (!okCfg) {
                    Log.w(TAG, "setConfiguration returned false; continuing")
                }
            }
        } catch (e: Exception) {
            AppLog.log(LogLevel.Warn, LogSource.Usb, TAG, "setConfiguration failed", e)
        }

        var selectedIface: UsbInterface? = null
        if (wantIface in 0 until dev.interfaceCount) {
            selectedIface = dev.getInterface(wantIface)
        } else {
            for (i in 0 until dev.interfaceCount) {
                val itf = dev.getInterface(i)
                if (devClass == -1 || itf.interfaceClass == devClass) {
                    selectedIface = itf
                    break
                }
            }
            if (selectedIface == null) {
                for (i in 0 until dev.interfaceCount) {
                    val itf = dev.getInterface(i)
                    var hasIn = false
                    var hasOut = false
                    for (e in 0 until itf.endpointCount) {
                        val ep = itf.getEndpoint(e)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            if (ep.direction == UsbConstants.USB_DIR_IN) hasIn = true
                            else hasOut = true
                        }
                    }
                    if (hasIn && hasOut) {
                        Log.i(TAG, "Falling back to interface $i (class=0x${itf.interfaceClass.toString(16)}) with bulk endpoints")
                        selectedIface = itf
                        break
                    }
                }
            }
        }
        if (selectedIface == null) {
            lastStatus = "No bulk interface on ${dev.deviceName} (interfaces=${dev.interfaceCount}, devClass=$devClass)"
            Log.w(TAG, lastStatus)
            conn.close()
            return false
        }

        // Java-side claim first. On several OEM kernels the
        // UsbDeviceConnection fd is not actually authorized for
        // USBDEVFS_SUBMITURB until claimInterface() has succeeded here,
        // even if libusb later claims the same interface. force=true
        // detaches a kernel driver if one is bound.
        javaClaimed = try {
            conn.claimInterface(selectedIface, true)
        } catch (e: Exception) {
            AppLog.log(LogLevel.Error, LogSource.Usb, TAG, "claimInterface threw", e)
            false
        }
        if (!javaClaimed) {
            Log.w(TAG, "Java claimInterface(${selectedIface.id}) returned false; libusb will try")
        }

        if (!LibusbNative.nativeInit()) {
            lastStatus = "libusb_init() failed"
            Log.e(TAG, lastStatus)
            runCatching { conn.releaseInterface(selectedIface) }
            conn.close()
            javaClaimed = false
            return false
        }
        val fd = conn.fileDescriptor
        if (fd < 0) {
            lastStatus = "UsbDeviceConnection.fileDescriptor is invalid ($fd); cannot wrap for libusb"
            Log.e(TAG, lastStatus)
            runCatching { conn.releaseInterface(selectedIface) }
            conn.close()
            javaClaimed = false
            return false
        }
        val handle = LibusbNative.nativeWrapFd(fd)
        if (handle == 0L) {
            lastStatus = "libusb_wrap_sys_device() failed for fd=$fd (need NO_DEVICE_DISCOVERY + USB permission)"
            Log.e(TAG, lastStatus)
            runCatching { conn.releaseInterface(selectedIface) }
            conn.close()
            javaClaimed = false
            return false
        }
        if (!LibusbNative.nativeClaimInterface(handle, selectedIface.id)) {
            lastStatus = "libusb_claim_interface(${selectedIface.id}) failed (interface busy or no USBFS rights)"
            Log.e(TAG, lastStatus)
            LibusbNative.nativeClose(handle)
            runCatching { conn.releaseInterface(selectedIface) }
            conn.close()
            javaClaimed = false
            return false
        }
        libusbHandle = handle
        Log.i(TAG, "libusb: wrapped fd=$fd, claimed interface ${selectedIface.id} (javaClaimed=$javaClaimed)")

        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until selectedIface.endpointCount) {
            val ep = selectedIface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
        }
        if (inEp == null || outEp == null) {
            lastStatus = "No bulk IN/OUT on interface ${selectedIface.id}"
            Log.w(TAG, lastStatus)
            LibusbNative.nativeReleaseInterface(libusbHandle, selectedIface.id)
            LibusbNative.nativeClose(libusbHandle)
            libusbHandle = 0L
            runCatching { conn.releaseInterface(selectedIface) }
            conn.close()
            javaClaimed = false
            return false
        }

        device = dev
        connectedDeviceName = dev.deviceName
        connection = conn
        iface = selectedIface
        epIn = inEp
        epOut = outEp
        if (LibusbNative.available) {
            LibusbNative.nativeSetMaxPacket(inEp.maxPacketSize)
            LibusbNative.nativeFlushRx()
            LibusbNative.nativeStatsReset()
            UsbTuning.applyToNative()
            val link = LibusbNative.nativeLinkName(inEp.maxPacketSize)
            lastLinkName = link
            lastLinkSlow = LibusbNative.nativeLinkIsSlow(inEp.maxPacketSize)
            if (LibusbNative.nativeLinkIsSlow(inEp.maxPacketSize)) {
                dlog("USB link: $link. This is USB 1.1 speed, so transfers will be about 10x slower. " +
                    "Try a different OTG adapter or cable, and avoid hubs.")
            } else {
                dlog("USB link: $link", toTerminal = false)
            }
        }
        // Report the interface the same way MainActivity's "Detect Device"
        // diagnostic does (positional index into getInterface(i)), not
        // selectedIface.id (the descriptor's own bInterfaceNumber field).
        // The two numbers don't have to match -- composite MTK USB
        // descriptors commonly declare interface numbers out of the order
        // Android returns them in -- and logging the descriptor's number
        // here made it look like the wrong interface had been claimed even
        // when the actually-selected one had the correct class and bulk
        // endpoints all along.
        var selectedIdx = -1
        for (i in 0 until dev.interfaceCount) {
            if (dev.getInterface(i) === selectedIface) { selectedIdx = i; break }
        }
        lastStatus = "connected vid=0x${dev.vendorId.toString(16)} pid=0x${dev.productId.toString(16)} " +
            "iface[$selectedIdx] (bInterfaceNumber=${selectedIface.id}) class=0x${selectedIface.interfaceClass.toString(16)} " +
            "epIn=0x${inEp.address.toString(16)}(${inEp.maxPacketSize}) " +
            "epOut=0x${outEp.address.toString(16)}(${outEp.maxPacketSize}) " +
            "transport=libusb"
        Log.i(TAG, lastStatus)
        return true
    }

    @JvmStatic
    fun close(reset: Boolean) {
        if (libusbHandle != 0L && LibusbNative.available) {
            val summary = LibusbNative.nativeStatsSummary()
            if (summary.isNotEmpty()) dlog(summary)
        }
        try {
            if (libusbHandle != 0L) {
                iface?.let { LibusbNative.nativeReleaseInterface(libusbHandle, it.id) }
                // Does not close the fd (see nativeClose's doc comment) --
                // connection?.close() below still has to do that.
                LibusbNative.nativeClose(libusbHandle)
            }
            if (javaClaimed) {
                val c = connection
                val i = iface
                if (c != null && i != null) {
                    runCatching { c.releaseInterface(i) }
                }
            }
            connection?.close()
        } catch (e: Exception) {
            AppLog.log(LogLevel.Warn, LogSource.Usb, TAG, "close() failed", e)
        }
        libusbHandle = 0L
        javaClaimed = false
        connection = null
        iface = null
        epIn = null
        epOut = null
        device = null
        connectedDeviceName = null
        lastStatus = "closed"
    }

    @JvmStatic
    fun isConnected(): Boolean = connection != null && libusbHandle != 0L && epIn != null && epOut != null

    @JvmStatic
    fun getMaxPacketIn(): Int = epIn?.maxPacketSize ?: 0

    @JvmStatic
    fun getMaxPacketOut(): Int = epOut?.maxPacketSize ?: 0

    /** Drop any IN leftover so a protocol reset cannot replay stale bytes. */
    @JvmStatic
    fun flushRx() {
        if (LibusbNative.available) {
            LibusbNative.nativeFlushRx()
        }
    }

    /**
     * Why endpoint-halt recovery matters: a libusb_bulk_transfer() that
     * returns LIBUSB_ERROR_PIPE (-9) almost instantly is usbfs reporting
     * that the endpoint is STALLed -- which the device-side DA firmware
     * does deliberately to signal a protocol error. Once that happens,
     * every later transfer on that endpoint keeps failing the same way
     * until something clears the halt. TIMEOUT (-7) is NOT a stall:
     * CLEAR_FEATURE ENDPOINT_HALT on a live endpoint resets the data
     * toggle and desyncs the DA (often hard enough that Android sees a
     * disconnect). Only PIPE is recovered here.
     */
    @JvmStatic
    fun bulkWrite(data: ByteArray, timeout: Int): Int {
        if (cancelled) return -1
        if (libusbHandle == 0L) return -1
        val ep = epOut ?: return -1
        var n = LibusbNative.nativeBulkTransfer(libusbHandle, ep.address, data, 0, data.size, timeout)
        if (n == LIBUSB_PIPE && !cancelled && LibusbNative.nativeClearHalt(libusbHandle, ep.address)) {
            n = LibusbNative.nativeBulkTransfer(libusbHandle, ep.address, data, 0, data.size, timeout)
        }
        if (n < 0) dlog("bulkWrite failed n=$n (${LibusbNative.nativeErrorName(n)}) len=${data.size} timeout=$timeout")
        return n
    }

    @JvmStatic
    fun bulkRead(length: Int, timeout: Int): ByteArray {
        if (cancelled) return emptyBytes
        if (libusbHandle == 0L) return emptyBytes
        return bulkReadExact(length, timeout)
    }

    /**
     * Reads up to `totalLength` bytes. Native IN URBs are already a
     * multiple of wMaxPacketSize with surplus parked in g_rx_left, so
     * this writes the exact protocol length straight into the destination
     * — no per-call scratch beyond the returned buffer, no second leftover
     * layer on the hot path.
     */
    @JvmStatic
    fun bulkReadExact(totalLength: Int, timeoutMs: Int): ByteArray {
        if (libusbHandle == 0L) return emptyBytes
        val ep = epIn ?: return emptyBytes
        if (totalLength <= 0) return emptyBytes
        if (cancelled) return emptyBytes

        val out = ByteArray(totalLength)
        var got = 0
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(1)
        var haltRecoveryTried = false
        val callStart = System.currentTimeMillis()
        while (got < totalLength && !cancelled) {
            val remainMs = (deadline - System.currentTimeMillis()).toInt()
            if (remainMs <= 0) break
            val stillNeed = totalLength - got
            val n = LibusbNative.nativeBulkTransferExact(
                libusbHandle, ep.address, out, got, stillNeed, remainMs.coerceAtLeast(1)
            )
            if (n > 0) {
                got += minOf(n, stillNeed)
                haltRecoveryTried = false
                if (got >= totalLength) break
                continue
            }
            if (n < 0 && n != LIBUSB_TIMEOUT) {
                lastXferError = "IN error=$n (${LibusbNative.nativeErrorName(n)}) got=$got of $totalLength"
                dlog("bulkReadExact error=$n (${LibusbNative.nativeErrorName(n)}) got=$got of $totalLength")
            }
            if (cancelled) break
            if (n == LIBUSB_PIPE && !haltRecoveryTried) {
                haltRecoveryTried = true
                if (LibusbNative.nativeClearHalt(libusbHandle, ep.address)) continue
            }
            break
        }
        if (got < totalLength) {
            dlog(
                "bulkReadExact short: wanted=$totalLength got=$got elapsedMs=${System.currentTimeMillis() - callStart} budgetMs=$timeoutMs",
                toTerminal = false
            )
        }
        return when {
            got <= 0 -> emptyBytes
            got == totalLength -> out
            else -> out.copyOf(got)
        }
    }

    /** Settings -> General. See [UsbTuning]. */
    @JvmStatic
    fun isFusedReadEnabled(): Boolean = UsbTuning.fusedRead.value

    /** Settings -> Experimental. Read by the MediaTek DA setup (xflash_lib.setup_env). */
    @JvmStatic
    fun isDaWindowsEnvEnabled(): Boolean = UsbTuning.daWindowsEnv.value

    /** Settings -> Experimental. Read by the MediaTek DA setup (xflash_lib.setup_env). */
    @JvmStatic
    fun isDaLogUsbEnabled(): Boolean = UsbTuning.daLogUsb.value

    /** Settings -> Experimental. Read by the MediaTek DA setup (xflash_lib.setup_env). */
    @JvmStatic
    fun isDaLogDebugEnabled(): Boolean = UsbTuning.daLogDebug.value

    private val framesInfo = IntArray(7)
    private val framesHdr = ByteArray(16)

    /**
     * Reads up to `frames` xflash READ_DATA frames of exactly `frameLen`
     * bytes, ACKing each, entirely in native code (one JNI call instead of
     * ~5 per frame). Returns a buffer of `frames * frameLen` bytes; how many
     * of those are valid and why the batch ended is in [framesInfo]/
     * [framesHdr] (see nativeReadFrames). Empty if the fast path cannot be
     * used right now (no handle or cancelled).
     */
    @JvmStatic
    fun readFrames(frameLen: Int, frames: Int, hdrTimeoutMs: Int): ByteArray {
        framesInfo.fill(0)
        framesInfo[0] = 3
        if (libusbHandle == 0L || cancelled || frameLen <= 0 || frames <= 0) return emptyBytes
        val inEp = epIn ?: return emptyBytes
        val outEp = epOut ?: return emptyBytes
        val dst = ByteArray(frameLen * frames)
        LibusbNative.nativeReadFrames(
            libusbHandle, inEp.address, outEp.address, dst, frameLen, hdrTimeoutMs,
            framesHdr, framesInfo
        )
        return dst
    }

    /** [reason, statusSkips, statusPayloadBytes, framesStored, usHeaderWait, usPayload, usAck] of the last [readFrames]. */
    @JvmStatic
    fun readFramesInfo(): IntArray = framesInfo.copyOf()

    /** 12-byte handed-back header + 4 bytes of status payload (see [readFramesInfo]). */
    @JvmStatic
    fun readFramesHdr(): ByteArray = framesHdr.copyOf()

    /** Write-side counterpart to bulkReadExact(): one native exact-loop
     * per XFER_CHUNK until `data` is fully sent, the deadline is hit, or a
     * call returns 0/negative (PIPE gets one halt-clear retry; TIMEOUT
     * does not). */
    @JvmStatic
    fun bulkWriteExact(data: ByteArray, timeoutMs: Int): Int {
        if (libusbHandle == 0L) return -1
        val ep = epOut ?: return -1
        if (data.isEmpty()) return 0
        if (cancelled) return -1
        // The caller's timeout is per small packet; a multi-MiB payload needs
        // a budget that grows with its size (1 s per 64 KiB, i.e. still
        // completes at 64 KiB/s) or it would be cut off mid-transfer.
        val budgetMs = timeoutMs.coerceAtLeast(1).toLong() + (data.size / 64L)
        val deadline = System.currentTimeMillis() + budgetMs

        var sent = 0
        var haltRecoveryTried = false
        val callStart = System.currentTimeMillis()
        while (sent < data.size && !cancelled) {
            val remainMs = (deadline - System.currentTimeMillis()).toInt()
            if (remainMs <= 0) break
            val want = minOf(BIG_XFER, data.size - sent)
            val n = LibusbNative.nativeBulkTransferExact(
                libusbHandle, ep.address, data, sent, want, remainMs.coerceAtLeast(1)
            )
            if (n <= 0) {
                if (n < 0 && n != LIBUSB_TIMEOUT) {
                    lastXferError = "OUT error=$n (${LibusbNative.nativeErrorName(n)}) sent=$sent of ${data.size}"
                    dlog("bulkWriteExact error=$n (${LibusbNative.nativeErrorName(n)}) sent=$sent of ${data.size}")
                }
                if (cancelled) break
                if (n == LIBUSB_PIPE && !haltRecoveryTried) {
                    haltRecoveryTried = true
                    if (LibusbNative.nativeClearHalt(libusbHandle, ep.address)) continue
                }
                break
            }
            sent += n
        }
        if (sent < data.size) {
            dlog(
                "bulkWriteExact short: wanted=${data.size} sent=$sent elapsedMs=${System.currentTimeMillis() - callStart} budgetMs=$budgetMs",
                toTerminal = false
            )
        }
        return sent
    }

    @JvmStatic
    fun controlTransferOut(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray, timeout: Int): Int {
        if (cancelled) return -1
        if (libusbHandle != 0L) {
            val n = LibusbNative.nativeControlTransfer(
                libusbHandle, requestType, request, value, index,
                data, 0, data.size, timeout
            )
            if (n < 0) dlog("ctrl OUT n=$n (${LibusbNative.nativeErrorName(n)}) req=0x${request.toString(16)}")
            return n
        }
        val conn = connection ?: return -1
        return conn.controlTransfer(requestType, request, value, index, data, data.size, timeout)
    }

    @JvmStatic
    fun controlTransferIn(requestType: Int, request: Int, value: Int, index: Int, length: Int, timeout: Int): ByteArray {
        val len = length.coerceAtLeast(0)
        if (len == 0) return emptyBytes
        if (cancelled) return emptyBytes
        if (libusbHandle != 0L) {
            val buf = ByteArray(len)
            val n = LibusbNative.nativeControlTransfer(
                libusbHandle, requestType, request, value, index,
                buf, 0, len, timeout
            )
            return if (n > 0) {
                if (n == len) buf else buf.copyOf(n)
            } else emptyBytes
        }
        val conn = connection ?: return emptyBytes
        val buf = ByteArray(len)
        val n = conn.controlTransfer(requestType, request, value, index, buf, buf.size, timeout)
        return if (n > 0) buf.copyOf(n) else emptyBytes
    }

    /**
     * Run several control transfers in one JNI crossing.
     *
     * `headers` is a flat int array, 6 ints per op:
     *   bmRequestType, bRequest, wValue, wIndex, wLength, timeoutMs
     * `payloads` concatenates OUT data and IN receive slots in the same
     * order. Returns `[IntArray counts, ByteArray payloads]`. IN bytes are
     * written into the payload buffer; Python reads them from the second
     * element. Counts are per-op transferred lengths (negative = libusb error).
     */
    @JvmStatic
    fun controlTransferBatch(headers: IntArray, payloads: ByteArray?): Array<Any> {
        val count = headers.size / 6
        val results = IntArray(count) { -1 }
        val pay = payloads ?: ByteArray(0)
        if (count == 0 || cancelled || libusbHandle == 0L) {
            return arrayOf(results, pay)
        }
        LibusbNative.nativeControlTransferBatch(libusbHandle, headers, pay, results)
        return arrayOf(results, pay)
    }

    /**
     * Android equivalent of the desktop Kamakiri exploit's raw-pyusb
     * reset dance (kamakiri.py Kamakiri.exploit()).
     *
     * After the watchdog-reset payload runs, the device briefly drops off
     * the bus and re-enumerates. Desktop mtkclient re-opens it with pyusb
     * and disables libusb's managed interface claim so a control transfer
     * can go out without re-claiming the (now stale) interface. On Android
     * that workaround isn't needed: control transfers addressed to
     * endpoint 0 target the device itself, not a claimed interface, so a
     * plain openDevice() + controlTransfer() is sufficient -- no
     * claimInterface() call at all, and no libusb involvement either.
     *
     * This does not touch the primary `device`/`connection`/`iface` state;
     * it opens and closes its own short-lived connection so it can't
     * disturb a session the rest of the class is mid-way through.
     */
    @JvmStatic
    fun waitAndRawControlTransfer(
        vid: Int, waitMs: Int,
        requestType: Int, request: Int, value: Int, index: Int, length: Int
    ): Boolean {
        val mgr = manager
        if (mgr == null) {
            lastStatus = "Kamakiri: UsbManager is null (init not called?)"
            Log.e(TAG, lastStatus)
            return false
        }

        val deadline = System.currentTimeMillis() + waitMs.coerceAtLeast(0)
        var dev: UsbDevice? = null
        while (System.currentTimeMillis() < deadline && !cancelled) {
            val candidate = mgr.deviceList?.values?.firstOrNull { it.vendorId == vid }
            if (candidate != null && mgr.hasPermission(candidate)) {
                dev = candidate
                break
            }
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                break
            }
        }
        if (dev == null) {
            lastStatus = "Kamakiri: device (vid=0x${vid.toString(16)}) did not reappear with " +
                "USB permission within ${waitMs}ms after the reset. If it re-enumerated with a " +
                "new device id, tap Detect Device again quickly and retry."
            Log.w(TAG, lastStatus)
            return false
        }

        val conn = mgr.openDevice(dev)
        if (conn == null) {
            lastStatus = "Kamakiri: openDevice() failed on reappeared device"
            Log.w(TAG, lastStatus)
            return false
        }
        return try {
            val len = length.coerceAtLeast(0)
            val buf = if (len > 0) ByteArray(len) else null
            val n = conn.controlTransfer(requestType, request, value, index, buf, len, 2000)
            lastStatus = "Kamakiri: raw control transfer after reset -> $n"
            Log.i(TAG, lastStatus)
            n >= 0
        } catch (e: Exception) {
            lastStatus = "Kamakiri: raw control transfer threw: ${e.message}"
            AppLog.log(LogLevel.Error, LogSource.Usb, TAG, lastStatus, e)
            false
        } finally {
            conn.close()
        }
    }
}
