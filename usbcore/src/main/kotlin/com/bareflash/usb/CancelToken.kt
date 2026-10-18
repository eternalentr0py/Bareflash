package com.bareflash.usb

/**
 * Polled by long-running protocol loops. Return true to ask the operation to
 * stop as soon as it safely can. Implementations must be cheap and
 * thread-safe, because they are called often and from the worker thread.
 */
public fun interface CancelToken {
    public fun isCancelled(): Boolean

    public companion object {
        /** A token that is never cancelled. */
        public val NEVER: CancelToken = CancelToken { false }
    }
}
