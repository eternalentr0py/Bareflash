package com.bareflash.app.samsung;
/** Host-side twin of the Kotlin class: same JNI symbol names, Object params. */
public class BrokkrNativeBridge {
    public native long nativeCreateSession(Object cb, String cacheDir);
    public native void nativeDestroySession(long h);
    public native void nativeRequestCancel(long h);
    public native void nativeRunSession(long h, Object[] transports, int[] fds, String[] labels, int pitFd, String pitLabel, boolean reboot);
    public native void nativeRunWirelessSession(long h, Object transport, int[] fds, String[] labels, int pitFd, String pitLabel, boolean reboot);
}
