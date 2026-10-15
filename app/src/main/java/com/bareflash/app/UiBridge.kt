package com.bareflash.app

/**
 * Channel between the embedded Python runtime and the UI layer.
 * All callbacks may arrive on a background thread; collectors are
 * responsible for marshalling onto the main thread.
 */
object UiBridge {
    @JvmStatic
    var listener: ((String) -> Unit)? = null

    /** (bytesDone, bytesTotal, label) for measurable transfers. */
    @JvmStatic
    var progressListener: ((Long, Long, String) -> Unit)? = null

    /** (friendlyMessage, fullDetail) for a failed command. */
    @JvmStatic
    var errorListener: ((String, String) -> Unit)? = null

    @JvmStatic
    fun appendLog(msg: String) {
        listener?.invoke(msg)
    }

    @JvmStatic
    fun reportProgress(done: Long, total: Long, label: String) {
        progressListener?.invoke(done, total, label)
    }

    @JvmStatic
    fun reportError(friendly: String, detail: String) {
        com.bareflash.app.log.AppLog.logDetail(
            com.bareflash.app.log.LogLevel.Error,
            com.bareflash.app.log.AppLog.activeEngine,
            "command-error", friendly, detail
        )
        errorListener?.invoke(friendly, detail)
    }
}
