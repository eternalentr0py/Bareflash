package com.bareflash.app;
/** Host twin of the Kotlin LibusbNative object: same JNI symbols. */
public class LibusbNative {
    public native boolean nativeInit();
    public native long nativeWrapFd(int fd);
    public native boolean nativeClaimInterface(long h, int i);
    public native void nativeSetCancelled(boolean f);
    public native void nativeFlushRx();
    public native void nativeSetMaxPacket(int p);
    public native int nativeBulkTransferExact(long h, int ep, byte[] b, int off, int len, int to);
    public native int nativeBulkTransfer(long h, int ep, byte[] b, int off, int len, int to);
    public native String nativeLinkName(int p);
    public native boolean nativeLinkIsSlow(int p);
    public native void nativeStatsReset();
    public native String nativeStatsSummary();
}
