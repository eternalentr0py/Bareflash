package com.bareflash.app.engine;

/** Host stand-in with the same JNI surface as the Kotlin object QdlNative. */
public final class QdlNative {
    public interface Sink { void onLine(String line); }
    public static native int nativeRun(int fd, String[] argv, Sink sink);
    public static native void nativeCancel();
    public static native boolean nativeEmbedded();
}
