package com.bareflash.adb

import java.io.IOException

/** Base type for everything the ADB layer reports on its own. */
public open class AdbException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The device (or the cable) sent something the protocol does not allow. */
public class AdbProtocolException(message: String) : AdbException(message)

/** The device did not accept this host's key, or the user declined the prompt. */
public class AdbAuthException(message: String) : AdbException(message)

/** An operation did not finish in time. */
public class AdbTimeoutException(message: String) : AdbException(message)

/** The connection or stream is closed (by us, by the device, or because the link failed). */
public class AdbClosedException(message: String, cause: Throwable? = null) : AdbException(message, cause)

/** The device refused to open a service, e.g. an unknown service name. */
public class AdbStreamRejectedException(public val destination: String) :
    AdbException("Device refused to open '$destination'")

/** The caller's [com.bareflash.usb.CancelToken] fired. The stream involved is closed. */
public class AdbCancelledException : AdbException("Cancelled")

/** The device answered FAIL to a sync request. [message] is the device's own text. */
public class AdbSyncException(message: String) : AdbException(message)

/** The device does not offer a feature that the operation needs. */
public class AdbUnsupportedException(message: String) : AdbException(message)
