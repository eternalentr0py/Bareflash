package com.bareflash.fastboot

import java.io.IOException

/** Base type for everything the fastboot layer reports on its own. */
public open class FastbootException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/** The device answered FAIL. [message] is the device's own text. */
public class FastbootFailException(message: String) : FastbootException(message)

/** The device sent something the protocol does not allow. */
public class FastbootProtocolException(message: String) : FastbootException(message)

/** The caller's [com.bareflash.usb.CancelToken] fired. Device state may be mid-transfer. */
public class FastbootCancelledException : FastbootException("Cancelled")

/** No terminal response arrived in time. */
public class FastbootTimeoutException(message: String) : FastbootException(message)
