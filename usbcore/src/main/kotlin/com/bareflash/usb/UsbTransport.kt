package com.bareflash.usb

import java.io.IOException

/**
 * One bulk IN/OUT endpoint pair on an already-claimed USB interface.
 *
 * This is the only thing the ADB and fastboot protocol code knows about
 * hardware. The Android app supplies an implementation backed by
 * UsbDeviceConnection; tests supply a fake device. Keeping the protocols
 * behind this interface is what lets them run and be tested on a plain JVM.
 */
public interface UsbTransport : AutoCloseable {

    /**
     * Writes exactly [length] bytes from [data] starting at [offset], splitting
     * into several USB transfers if needed. Throws [UsbTimeoutException] if a
     * transfer does not complete within [timeoutMs], or another
     * [UsbTransportException] on any other failure. Never returns after a
     * partial write.
     */
    @Throws(IOException::class)
    public fun write(data: ByteArray, offset: Int, length: Int, timeoutMs: Int)

    /**
     * Reads one transfer of up to [buffer].size bytes and returns the number of
     * bytes read. A return of 0 means a zero-length packet. Throws
     * [UsbTimeoutException] if nothing arrives within [timeoutMs].
     */
    @Throws(IOException::class)
    public fun read(buffer: ByteArray, timeoutMs: Int): Int

    override fun close()
}

/** Any failure talking to the device. */
public open class UsbTransportException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/** A transfer did not finish in time. The link is still usable. */
public class UsbTimeoutException(message: String) : UsbTransportException(message)

/** The device went away (unplugged, rebooted, or the connection was closed). */
public class UsbDisconnectedException(message: String, cause: Throwable? = null) :
    UsbTransportException(message, cause)
