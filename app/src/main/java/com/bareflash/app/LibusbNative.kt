package com.bareflash.app

import com.bareflash.app.log.AppLog
import com.bareflash.app.log.LogLevel
import com.bareflash.app.log.LogSource
import android.util.Log

/**
 * Thin JNI bridge to a small native libusb wrapper (see
 * app/src/main/cpp/usb_native.c), used by UsbBridge as the transfer path.
 *
 * Why: every intermittent USB bug chased in this project so far -- stale
 * async UsbRequest completions surfacing after cancel(), double-buffer
 * priming claiming overlapping ranges, short reads on small
 * control-channel packets -- lives in Android's own Java USB Host API.
 * libusb is the same mature, synchronous C-level USB stack desktop
 * mtkclient already relies on via pyusb. On Android, libusb can't open a
 * device node directly (no root), but libusb_wrap_sys_device() lets it
 * take over an already-open, permission-granted UsbDeviceConnection's raw
 * file descriptor instead -- so Android's permission dialog and
 * interface/endpoint discovery in UsbBridge.connect() stay exactly as
 * they were; only the transfer plumbing changes.
 *
 * `available` is false whenever the native library fails to load -- e.g.
 * an ABI without a bundled libusb-1.0.so. UsbBridge.connect() then fails
 * outright (there is no Java UsbRequest fallback).
 *
 * libusb-1.0.so is loaded first so mtkusb's DT_NEEDED resolves from the
 * APK's jniLibs even on Android versions that don't auto-load sibling
 * NEEDED libraries from the same zip directory.
 */
internal object LibusbNative {
    private const val TAG = "LibusbNative"

    val available: Boolean = try {
        try {
            System.loadLibrary("usb-1.0")
        } catch (_: Throwable) {
            // mtkusb's DT_NEEDED still pulls it in when packaged correctly
        }
        System.loadLibrary("mtkusb")
        true
    } catch (e: Throwable) {
        AppLog.log(LogLevel.Fatal, LogSource.Native, TAG, "libusb native bridge not available", e)
        false
    }

    external fun nativeInit(): Boolean

    /**
     * Wraps an already-open fd (UsbDeviceConnection.fileDescriptor) via
     * libusb_wrap_sys_device(). Returns an opaque native handle (0 on
     * failure) -- a token to pass back into the other functions here,
     * not a value to interpret otherwise.
     *
     * nativeInit() MUST have succeeded first; that call sets
     * LIBUSB_OPTION_NO_DEVICE_DISCOVERY so wrap works on unrooted
     * Android (no /dev/bus/usb scan).
     */
    external fun nativeWrapFd(fd: Int): Long

    /**
     * Claims [interfaceNumber], detaching a kernel driver if one is
     * bound, then selects alt-setting 0 so bulk endpoints are enabled.
     */
    external fun nativeClaimInterface(handle: Long, interfaceNumber: Int): Boolean

    external fun nativeReleaseInterface(handle: Long, interfaceNumber: Int)

    /**
     * Closes the libusb-side handle only. Does NOT close the underlying
     * fd -- a libusb_wrap_sys_device()'d handle never owns it, so
     * UsbDeviceConnection.close() is still required afterward.
     */
    external fun nativeClose(handle: Long)

    external fun nativeClearHalt(handle: Long, endpoint: Int): Boolean

    /** Mirrors UsbBridge.requestCancel() into the native exact-loop. */
    external fun nativeSetCancelled(flag: Boolean)

    /** Drop native IN leftover so a protocol reset cannot replay stale bytes. */
    external fun nativeFlushRx()

    /** wMaxPacketSize of the bulk IN endpoint (512 for HS). Native IN URBs
     * are always this size; never a short IN against a full-sized packet. */
    external fun nativeSetMaxPacket(maxPacket: Int)

    /**
     * One libusb_bulk_transfer() call. `endpoint` is the raw USB endpoint
     * address (UsbEndpoint.address -- bit 7 set means IN, matching
     * libusb's own convention exactly, so this one function serves both
     * directions). Returns bytes actually transferred (>=0, including a
     * legitimate short transfer), or a negative libusb error code (e.g.
     * -7 = LIBUSB_ERROR_TIMEOUT, -9 = LIBUSB_ERROR_PIPE / stall) only
     * when nothing was transferred at all.
     *
     * USB happens directly against the Java byte[] (GetByteArrayElements);
     * there is no extra native scratch copy. IN always submits one
     * max-packet URB and parks surplus natively.
     */
    external fun nativeBulkTransfer(
        handle: Long, endpoint: Int, buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int
    ): Int

    /**
     * Accumulate `length` bytes in one JNI crossing, looping native
     * libusb_bulk_transfer() until the count, deadline, cancel, or a hard
     * error. IN URBs are a multiple of max-packet (up to 16 KiB), bounced
     * only when the caller wants less than one packet.
     * One stall is retried after clear_halt; TIMEOUT is retried until the
     * deadline without clearing halt. Same return convention as
     * [nativeBulkTransfer]: >=0 bytes moved, or a negative libusb code if
     * nothing moved.
     */
    external fun nativeBulkTransferExact(
        handle: Long, endpoint: Int, buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int
    ): Int

    /**
     * Fused xflash READ_DATA loop: header, payload, two-OUT ACK and the bare
     * OK status frame for as many whole `frameLen` frames as fit in `dst`,
     * in one native call. See nativeReadFrames in usb_native.c for the
     * meaning of `info` and `hdrOut`. Returns the number of whole frames.
     */
    external fun nativeReadFrames(
        handle: Long, epIn: Int, epOut: Int, dst: ByteArray, frameLen: Int,
        hdrTimeoutMs: Int, hdrOut: ByteArray, info: IntArray
    ): Int

    external fun nativeErrorName(code: Int): String

    /**
     * One short IN URB. Returns 0 on ZLP/timeout (the usual case after a
     * max-packet-aligned payload). If real bytes arrive they are parked in
     * the native leftover and the count is returned so the next read sees
     * them. Does nothing when leftover is already holding data.
     */
    external fun nativeDrainZlp(handle: Long, endpoint: Int, timeoutMs: Int): Int

    /** Pins the CALLING thread to the fastest cores. Returns cores in mask, 0 if unchanged. */
    external fun nativePinToFastCores(): Int

    /**
     * One libusb_control_transfer() on the wrapped handle. `data` is the
     * OUT payload or the IN destination (`offset`/`length` into it). Returns
     * bytes moved (>=0) or a negative libusb error.
     */
    external fun nativeControlTransfer(
        handle: Long, requestType: Int, request: Int, value: Int, index: Int,
        data: ByteArray?, offset: Int, length: Int, timeoutMs: Int
    ): Int

    /**
     * Run `headers.size / 6` control transfers in one JNI crossing.
     * headers layout per op: bmRequestType, bRequest, wValue, wIndex, wLength, timeoutMs.
     * payloads is the concatenation of OUT data / IN slots; IN bytes are written
     * back into it. results is filled with per-op byte counts (or negative
     * libusb codes). Returns 0 or the first negative error; later ops still
     * run unless cancelled.
     */
    external fun nativeControlTransferBatch(
        handle: Long, headers: IntArray, payloads: ByteArray?, results: IntArray
    ): Int

    /** "High-Speed (480 Mbit/s)" etc., from a bulk endpoint's wMaxPacketSize. */
    external fun nativeLinkName(maxPacket: Int): String

    /** True for a Full-Speed (USB 1.1) link: certainly slow, usually a bad adapter/cable/hub. */
    external fun nativeLinkIsSlow(maxPacket: Int): Boolean

    /** Zeroes the bulk-transfer counters (call when a connection starts). */
    external fun nativeStatsReset()

    /** Pins the calling thread to the CPUs set in [mask] (bit i = cpu i). */
    external fun nativeSetThreadAffinity(mask: Long)

    /** true = keep the CPU busy while a transfer is outstanding (lower, steadier latency). */
    external fun nativeSetBusyWait(on: Boolean)

    /**
     * One-line throughput summary since the last reset (bytes, MiB/s while on
     * the bus, share of time the bus was busy vs. waiting on the host), or ""
     * if nothing moved. Tells you whether slowness is the link or the host.
     */
    external fun nativeStatsSummary(): String

    /** 16-bit byte sum, native-unrolled. */
    external fun nativeByteSum16(data: ByteArray, offset: Int, length: Int): Int
}
