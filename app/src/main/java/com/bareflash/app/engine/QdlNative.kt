package com.bareflash.app.engine

/**
 * JNI bridge to libqdl_jni.so. qdl runs in a forked child process; the
 * native side streams its output lines to [Sink] on the calling thread.
 */
object QdlNative {

    fun interface Sink { fun onLine(line: String) }

    @Volatile var loadError: String? = null

    val loaded: Boolean by lazy {
        try { System.loadLibrary("qdl_jni"); true }
        catch (t: Throwable) {
            com.bareflash.app.log.AppLog.log(
                com.bareflash.app.log.LogLevel.Fatal, com.bareflash.app.log.LogSource.Qualcomm,
                "qdl", "QDL native library failed to load", t
            )
            loadError = t.message ?: t.javaClass.simpleName
            false
        }
    }

    /** True when the library loaded and reports the engine present. */
    val ready: Boolean get() = loaded && nativeEmbedded()

    /** Runs qdl with [argv] over the already-open USB [fd] (-1 when no device is needed). Blocks. */
    @JvmStatic external fun nativeRun(fd: Int, argv: Array<String>, sink: Sink): Int
    @JvmStatic external fun nativeCancel()
    @JvmStatic external fun nativeEmbedded(): Boolean
}
