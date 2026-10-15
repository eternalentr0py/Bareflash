/*
 * Thin JNI bridge to libusb, used by com.bareflash.app.LibusbNative /
 * UsbBridge.kt as the USB transfer path.
 *
 * Why this exists: every intermittent USB bug chased in this project so
 * far (stale async UsbRequest completions after cancel(), overlapping
 * double-buffer priming ranges, short reads on small control-channel
 * packets) lives in Android's own Java USB Host API. libusb is the same
 * mature, synchronous C-level USB stack desktop mtkclient already relies
 * on via pyusb. This file does the minimum needed to hand libusb an
 * already-open, permission-granted Android USB connection instead of
 * reimplementing bulk transfer reliability by hand:
 *
 *   1. Kotlin still does everything Android requires: UsbManager
 *      permission request, UsbManager.openDevice(), interface/endpoint
 *      discovery from the UsbDevice descriptor tree, and (on many OEM
 *      kernels) UsbDeviceConnection.claimInterface() so the fd is
 *      actually authorized for USBFS ioctls.
 *   2. That UsbDeviceConnection's raw file descriptor is handed to
 *      libusb_wrap_sys_device() here -- a libusb entry point added
 *      specifically for sandboxed platforms like Android, which lets
 *      libusb operate on a fd it did not itself open() (no root or udev
 *      rule required).
 *   3. From there, transfers are plain libusb_bulk_transfer() calls --
 *      no async submit/reap queue, no hand-rolled double buffering to
 *      get subtly wrong. Large payloads are accumulated in a native
 *      exact-loop so a single JNI crossing covers a whole DA packet.
 *
 * Android-specific init (MUST happen before libusb_init):
 *   libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY)
 * Without that option, libusb tries to enumerate /dev/bus/usb, which
 * unrooted Android apps cannot read (EACCES). init then fails with
 * LIBUSB_ERROR_OTHER / ACCESS, or wrap_sys_device succeeds but later
 * ioctls fail because the context was built in "scan" mode. This is the
 * documented unrooted-Android sequence (libusb/android/README).
 *
 * URB sizing:
 *   OUT URBs are capped at 16 KiB (Android USBFS / libusb
 *   MAX_BULK_BUFFER_LENGTH). IN URBs must be a multiple of
 *   wMaxPacketSize (512 for HS). A bulk IN URB that is NOT a
 *   multiple of wMaxPacketSize is a USB overflow: Linux USBFS
 *   discards the entire packet (the DAX "drops 512 bytes" bug).
 *   Requesting only 512 bytes is correct but ~32× more ioctls
 *   than a 16 KiB URB; large payloads therefore IN in 16 KiB
 *   multiples of 512, bounced only when the caller wants fewer
 *   than one packet (12-byte xflash headers). Surplus is parked
 *   in g_rx_left. A short packet still ends the frame so we
 *   never swallow the next xflash header.
 *
 * This links against a prebuilt libusb-1.0.so (see
 * app/src/main/jniLibs/<abi>/libusb-1.0.so; libusb is LGPL-licensed and
 * freely redistributable) without the accompanying libusb.h, so the
 * subset of libusb's public C API actually used here is declared by hand
 * below. These signatures match libusb's documented, long-stable public
 * ABI (unchanged across the 1.0.x series).
 */

#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <sched.h>
#include <stdio.h>
#include <unistd.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <android/log.h>

#include "bfusb/bfusb.h"

#define LOG_TAG "usb_native"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

/* libusb error / option constants (public ABI). */
#define LIBUSB_SUCCESS                        0
#define LIBUSB_ERROR_IO                      -1
#define LIBUSB_ERROR_INVALID_PARAM           -2
#define LIBUSB_ERROR_ACCESS                  -3
#define LIBUSB_ERROR_NO_DEVICE               -4
#define LIBUSB_ERROR_NOT_FOUND               -5
#define LIBUSB_ERROR_BUSY                    -6
#define LIBUSB_ERROR_TIMEOUT                 -7
#define LIBUSB_ERROR_OVERFLOW                -8
#define LIBUSB_ERROR_PIPE                    -9
#define LIBUSB_ERROR_INTERRUPTED            -10
#define LIBUSB_ERROR_NO_MEM                 -11
#define LIBUSB_ERROR_NOT_SUPPORTED          -12
#define LIBUSB_ERROR_OTHER                  -99
#define LIBUSB_OPTION_NO_DEVICE_DISCOVERY     2

/* Android USBFS URB ceiling. libusb splits internally around this too
 * (MAX_BULK_BUFFER_LENGTH=16384 on linux_usbfs). Used for OUT; IN uses
 * a single max-packet so we never swallow the next xflash header. */
#define MAX_OUT_URB (16 * 1024)
/* Ceiling for ONE libusb_bulk_transfer() call on the aligned, direct path.
 * libusb splits anything above 16 KiB into 16 KiB usbfs URBs and submits
 * them all at once, so the controller keeps streaming instead of idling
 * between per-16-KiB submit/reap round trips (the old throughput cap, and
 * the reason speed collapsed whenever the worker thread lost its CPU slot).
 * IN requests on this path are ALWAYS the exact remaining protocol length
 * rounded down to a max-packet multiple, so they can never overrun a frame
 * and swallow the next xflash header. */
#define MAX_BIG_URB (1024 * 1024)
#define BIG_URB_MIN (32 * 1024)
#define HS_MAX_PACKET 512
#define IN_BOUNCE_MAX 1024
/* Per-URB timeout slice so a 5s budget is several retries (matching
 * desktop mtkclient's usbread retry loop) rather than one long wait
 * that then gives up. */
#define URB_TIMEOUT_SLICE_MS 1000

typedef struct libusb_context libusb_context;
typedef struct libusb_device_handle libusb_device_handle;

extern int libusb_init(libusb_context **ctx);
extern void libusb_exit(libusb_context *ctx);
extern int libusb_set_option(libusb_context *ctx, int option, ...);
extern int libusb_wrap_sys_device(libusb_context *ctx, intptr_t sys_dev, libusb_device_handle **dev_handle);
extern int libusb_claim_interface(libusb_device_handle *dev_handle, int interface_number);
extern int libusb_release_interface(libusb_device_handle *dev_handle, int interface_number);
extern int libusb_set_interface_alt_setting(libusb_device_handle *dev_handle, int interface_number, int alternate_setting);
extern int libusb_set_auto_detach_kernel_driver(libusb_device_handle *dev_handle, int enable);
extern int libusb_kernel_driver_active(libusb_device_handle *dev_handle, int interface_number);
extern int libusb_detach_kernel_driver(libusb_device_handle *dev_handle, int interface_number);
extern void libusb_close(libusb_device_handle *dev_handle);
extern int libusb_clear_halt(libusb_device_handle *dev_handle, unsigned char endpoint);
extern int libusb_control_transfer(libusb_device_handle *dev_handle,
                                    uint8_t request_type, uint8_t bRequest,
                                    uint16_t wValue, uint16_t wIndex,
                                    unsigned char *data, uint16_t wLength,
                                    unsigned int timeout);
extern int libusb_bulk_transfer(libusb_device_handle *dev_handle, unsigned char endpoint,
                                 unsigned char *data, int length, int *transferred,
                                 unsigned int timeout);
extern const char *libusb_error_name(int error_code);

/* One libusb_context for the process. UsbBridge is itself a Kotlin
 * singleton object with one connection at a time, so one context here
 * matches that lifetime exactly. */
static libusb_context *g_ctx = NULL;

/* Cooperative cancel, mirrored from UsbBridge.requestCancel(). Checked
 * between libusb_bulk_transfer() calls so a long exact-loop can abort
 * without waiting for the remaining timeout. */
static volatile int g_cancelled = 0;

/* All bulk traffic goes through bfusb_bulk() so a Stop request aborts the URBs
 * in flight (within BFUSB_POLL_MS) instead of waiting out the transfer's
 * timeout, and so throughput / host-gap telemetry covers every transfer. The
 * return value and *transferred follow libusb_bulk_transfer() exactly. */
static int cancel_probe(void *user)
{
    (void) user;
    return g_cancelled;
}

static const bfusb_cancel g_cancel_hook = { cancel_probe, NULL };

static int usb_bulk(libusb_device_handle *dev, unsigned char endpoint, unsigned char *data,
                    int length, int *transferred, unsigned int timeout_ms)
{
    return bfusb_bulk(g_ctx, dev, endpoint, data, length, transferred, timeout_ms, &g_cancel_hook);
}

/* Surplus from an IN URB that returned more than the caller asked for.
 * Always submitting a full max-packet IN (see URB sizing comment) means
 * a 16-byte xflash frame read as 12 bytes parks 4 here, and an unaligned
 * data read (e.g. 16380 after a 4-byte leftover) parks the tail of the
 * last 512-byte packet instead of overflowing USBFS and dropping it. */
static unsigned char g_rx_left[IN_BOUNCE_MAX];
static int g_rx_left_len = 0;
static int g_in_max_packet = HS_MAX_PACKET;

static int now_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return 0;
    }
    return (int) (ts.tv_sec * 1000L + ts.tv_nsec / 1000000L);
}

static long long now_us(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return 0;
    }
    return (long long) ts.tv_sec * 1000000LL + ts.tv_nsec / 1000;
}

static int in_pkt_size(void) {
    int pkt = g_in_max_packet;
    if (pkt < 8) {
        pkt = HS_MAX_PACKET;
    }
    if (pkt > IN_BOUNCE_MAX) {
        pkt = IN_BOUNCE_MAX;
    }
    return pkt;
}

static int drain_rx_left(unsigned char *dst, int want) {
    if (g_rx_left_len <= 0 || want <= 0 || dst == NULL) {
        return 0;
    }
    int n = g_rx_left_len < want ? g_rx_left_len : want;
    memcpy(dst, g_rx_left, (size_t) n);
    g_rx_left_len -= n;
    if (g_rx_left_len > 0) {
        memmove(g_rx_left, g_rx_left + n, (size_t) g_rx_left_len);
    }
    return n;
}

static void park_rx_left(const unsigned char *src, int n) {
    if (src == NULL || n <= 0) {
        return;
    }
    int space = (int) sizeof(g_rx_left) - g_rx_left_len;
    if (n > space) {
        LOGW("rx leftover overflow: parking %d of %d bytes (had %d)", space, n, g_rx_left_len);
        n = space;
        if (n <= 0) {
            return;
        }
    }
    memcpy(g_rx_left + g_rx_left_len, src, (size_t) n);
    g_rx_left_len += n;
}

static void flush_rx_left(void) {
    g_rx_left_len = 0;
}

JNIEXPORT void JNICALL
Java_com_bareflash_app_LibusbNative_nativeSetThreadAffinity(JNIEnv *env, jobject thiz, jlong mask) {
    (void) env;
    (void) thiz;
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int i = 0; i < 64 && i < CPU_SETSIZE; i++) {
        if (((unsigned long long) mask >> i) & 1ull) {
            CPU_SET(i, &set);
        }
    }
    if (sched_setaffinity(0, sizeof(set), &set) != 0) {
        LOGW("sched_setaffinity(mask=0x%llx) failed: errno=%d", (unsigned long long) mask, errno);
    }
}

JNIEXPORT void JNICALL
Java_com_bareflash_app_LibusbNative_nativeSetBusyWait(JNIEnv *env, jobject thiz, jboolean on) {
    (void) env;
    (void) thiz;
    /* 100 ms covers typical DA inter-frame think time. The old 30 ms window
     * left the thread blocking (and the core free to idle) for most of each
     * frame gap — rotation still looked like a speed fix because UI work
     * re-raised clocks during those sleeps. */
    bfusb_set_spin_us(on ? 100000u : 0u);
    LOGI("USB wait mode: %s", on ? "busy-wait (100 ms window)" : "blocking");
}

JNIEXPORT void JNICALL
Java_com_bareflash_app_LibusbNative_nativeSetCancelled(JNIEnv *env, jobject thiz, jboolean flag) {
    (void) env;
    (void) thiz;
    g_cancelled = flag ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_com_bareflash_app_LibusbNative_nativeFlushRx(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    flush_rx_left();
}

JNIEXPORT void JNICALL
Java_com_bareflash_app_LibusbNative_nativeSetMaxPacket(JNIEnv *env, jobject thiz, jint maxPacket) {
    (void) env;
    (void) thiz;
    if (maxPacket >= 8 && maxPacket <= IN_BOUNCE_MAX) {
        g_in_max_packet = maxPacket;
    } else {
        g_in_max_packet = HS_MAX_PACKET;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_bareflash_app_LibusbNative_nativeInit(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    if (g_ctx != NULL) {
        return JNI_TRUE;
    }

    /* MUST be set on the default context before libusb_init() on unrooted
     * Android. Skipping device discovery is what lets wrap_sys_device()
     * operate on a UsbManager-granted fd without rw access to
     * /dev/bus/usb. Failure here is non-fatal on builds that default to
     * weak authority, but we log it because a silent miss is the usual
     * reason wrap/claim then fails with ACCESS. */
    int opt = libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    if (opt != LIBUSB_SUCCESS) {
        LOGW("libusb_set_option(NO_DEVICE_DISCOVERY) failed: %d (%s) -- wrap_sys_device may fail without USBFS scan rights",
             opt, libusb_error_name(opt));
    } else {
        LOGI("libusb: NO_DEVICE_DISCOVERY set (unrooted Android wrap path)");
    }

    int rc = libusb_init(&g_ctx);
    if (rc != 0) {
        LOGW("libusb_init failed: %d (%s)", rc, libusb_error_name(rc));
        g_ctx = NULL;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_com_bareflash_app_LibusbNative_nativeWrapFd(JNIEnv *env, jobject thiz, jint fd) {
    (void) env;
    (void) thiz;
    if (g_ctx == NULL) {
        LOGW("nativeWrapFd called before nativeInit succeeded");
        return 0;
    }
    if (fd < 0) {
        LOGW("nativeWrapFd got invalid fd=%d", fd);
        return 0;
    }
    libusb_device_handle *handle = NULL;
    int rc = libusb_wrap_sys_device(g_ctx, (intptr_t) fd, &handle);
    if (rc != 0 || handle == NULL) {
        LOGW("libusb_wrap_sys_device failed for fd=%d: %d (%s)", fd, rc, libusb_error_name(rc));
        return 0;
    }
    LOGI("libusb_wrap_sys_device ok fd=%d handle=%p", fd, (void *) handle);
    return (jlong) (intptr_t) handle;
}

JNIEXPORT jboolean JNICALL
Java_com_bareflash_app_LibusbNative_nativeClaimInterface(JNIEnv *env, jobject thiz,
                                                             jlong handle, jint ifaceNum) {
    (void) env;
    (void) thiz;
    if (handle == 0) {
        return JNI_FALSE;
    }
    libusb_device_handle *dev = (libusb_device_handle *) (intptr_t) handle;

    /* On Android the kernel driver is usually already detached by
     * UsbManager.openDevice(), but some hosts still have usbhid/usbfs
     * attached. Auto-detach + an explicit check covers both; NOT_SUPPORTED
     * is the common unrooted-Android answer and is not a failure. */
    (void) libusb_set_auto_detach_kernel_driver(dev, 1);
    int active = libusb_kernel_driver_active(dev, ifaceNum);
    if (active == 1) {
        int det = libusb_detach_kernel_driver(dev, ifaceNum);
        if (det != 0) {
            LOGW("libusb_detach_kernel_driver(%d) failed: %d (%s) -- claiming anyway",
                 ifaceNum, det, libusb_error_name(det));
        } else {
            LOGI("detached kernel driver on interface %d", ifaceNum);
        }
    } else if (active < 0 && active != LIBUSB_ERROR_NOT_SUPPORTED) {
        LOGW("libusb_kernel_driver_active(%d): %d (%s)", ifaceNum, active, libusb_error_name(active));
    }

    int rc = libusb_claim_interface(dev, ifaceNum);
    if (rc == LIBUSB_ERROR_BUSY) {
        /* UsbDeviceConnection.claimInterface() already owns this
         * interface -- that Java-side claim is the permission handshake
         * several OEM kernels require before USBFS_SUBMITURB is allowed
         * on the wrapped fd. BUSY here is success, not a failure. */
        LOGI("libusb_claim_interface(%d) BUSY (already claimed in Java); continuing", ifaceNum);
        rc = LIBUSB_SUCCESS;
    } else if (rc == LIBUSB_ERROR_ACCESS) {
        /* One retry after a forced detach -- the first claim races the
         * host's automatic driver rebind on some kernels. */
        (void) libusb_detach_kernel_driver(dev, ifaceNum);
        rc = libusb_claim_interface(dev, ifaceNum);
        if (rc == LIBUSB_ERROR_BUSY) {
            rc = LIBUSB_SUCCESS;
        }
    }
    if (rc != 0) {
        LOGW("libusb_claim_interface(%d) failed: %d (%s)", ifaceNum, rc, libusb_error_name(rc));
        return JNI_FALSE;
    }

    /* Alt-setting 0 is what actually enables the bulk endpoints on some
     * composite gadgets. Already-on-0 returns SUCCESS or NOT_FOUND; either
     * is fine. A hard failure is logged but not treated as fatal -- the
     * subsequent bulk transfer will tell us if the endpoints are live. */
    int alt = libusb_set_interface_alt_setting(dev, ifaceNum, 0);
    if (alt != 0 && alt != LIBUSB_ERROR_NOT_FOUND && alt != LIBUSB_ERROR_NOT_SUPPORTED) {
        LOGW("libusb_set_interface_alt_setting(%d, 0): %d (%s)", ifaceNum, alt, libusb_error_name(alt));
    }

    LOGI("claimed interface %d", ifaceNum);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_bareflash_app_LibusbNative_nativeReleaseInterface(JNIEnv *env, jobject thiz,
                                                               jlong handle, jint ifaceNum) {
    (void) env;
    (void) thiz;
    if (handle == 0) {
        return;
    }
    libusb_release_interface((libusb_device_handle *) (intptr_t) handle, ifaceNum);
}

JNIEXPORT void JNICALL
Java_com_bareflash_app_LibusbNative_nativeClose(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    flush_rx_left();
    if (handle == 0) {
        return;
    }
    /* A libusb_wrap_sys_device()'d handle never owns the underlying fd,
     * so this does NOT close it -- UsbDeviceConnection.close() on the
     * Kotlin side is still required afterward. */
    libusb_close((libusb_device_handle *) (intptr_t) handle);
}

JNIEXPORT jboolean JNICALL
Java_com_bareflash_app_LibusbNative_nativeClearHalt(JNIEnv *env, jobject thiz,
                                                        jlong handle, jint endpoint) {
    (void) env;
    (void) thiz;
    if (handle == 0) {
        return JNI_FALSE;
    }
    int rc = libusb_clear_halt((libusb_device_handle *) (intptr_t) handle, (unsigned char) endpoint);
    if (rc != 0) {
        LOGW("libusb_clear_halt(ep=0x%02x) failed: %d (%s)", endpoint, rc, libusb_error_name(rc));
    }
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}

/*
 * Pin (or copy) the Java byte[] for the duration of one or more
 * libusb_bulk_transfer() calls, USB'ing directly into/out of it.
 *
 * GetByteArrayElements is used instead of a native scratch buffer plus
 * Get/SetByteArrayRegion: ART typically pins the array (isCopy=false),
 * which avoids an extra 1-4MiB memcpy on every DA
 * packet. GetPrimitiveArrayCritical is deliberately avoided -- it can
 * stall the GC for the whole transfer.
 *
 * Returns bytes actually transferred (>=0) on success, including a
 * legitimate short transfer (LIBUSB_ERROR_TIMEOUT with transferred>0 is
 * reported as a plain byte count here, not an error, matching the old
 * UsbRequest path's short-return semantics so Kotlin-side retry loops
 * don't need to change). Returns a negative libusb error code only when
 * NOTHING was transferred.
 */
JNIEXPORT jint JNICALL
Java_com_bareflash_app_LibusbNative_nativeBulkTransfer(JNIEnv *env, jobject thiz, jlong handle,
                                                           jint endpoint, jbyteArray buffer,
                                                           jint offset, jint length, jint timeoutMs) {
    (void) thiz;
    if (handle == 0 || buffer == NULL || length <= 0) {
        return -1;
    }
    if (g_cancelled) {
        return LIBUSB_ERROR_INTERRUPTED;
    }

    jsize arr_len = (*env)->GetArrayLength(env, buffer);
    if (offset < 0 || (jlong) offset + (jlong) length > (jlong) arr_len) {
        return LIBUSB_ERROR_INVALID_PARAM;
    }

    jboolean is_copy = JNI_FALSE;
    jbyte *elems = (*env)->GetByteArrayElements(env, buffer, &is_copy);
    if (elems == NULL) {
        return LIBUSB_ERROR_NO_MEM;
    }

    int is_out = (endpoint & 0x80) == 0;
    int transferred = 0;
    int rc;

    if (!is_out) {
        /* Serve leftover first so a 12-byte follow-up never issues a
         * short IN URB against a still-buffered 512-byte packet. */
        transferred = drain_rx_left((unsigned char *) (elems + offset), length);
        if (transferred >= length) {
            (*env)->ReleaseByteArrayElements(env, buffer, elems, 0);
            return transferred;
        }
        unsigned char bounce[IN_BOUNCE_MAX];
        int pkt = in_pkt_size();
        int one = 0;
        rc = usb_bulk((libusb_device_handle *) (intptr_t) handle,
                                   (unsigned char) endpoint,
                                   bounce, pkt, &one,
                                   (unsigned int) timeoutMs);
        if (one > 0) {
            int space = length - transferred;
            int copy = one < space ? one : space;
            memcpy(elems + offset + transferred, bounce, (size_t) copy);
            park_rx_left(bounce + copy, one - copy);
            transferred += copy;
        }
        (*env)->ReleaseByteArrayElements(env, buffer, elems, 0);
        if (transferred > 0) {
            return transferred;
        }
        if (rc != 0) {
            return rc;
        }
        return 0;
    }

    rc = usb_bulk((libusb_device_handle *) (intptr_t) handle,
                               (unsigned char) endpoint,
                               (unsigned char *) (elems + offset),
                               length, &transferred,
                               (unsigned int) timeoutMs);

    (*env)->ReleaseByteArrayElements(env, buffer, elems, JNI_ABORT);

    if (rc != 0 && transferred <= 0) {
        return rc;
    }
    return transferred;
}

/*
 * Core of nativeBulkTransferExact() on a plain buffer, so the fused frame loop
 * below can use exactly the same transfer logic (URB sizing, ZLP skipping,
 * leftover parking, halt handling, deadline) without a JNI crossing per
 * transfer. Returns bytes moved (>0), or a negative libusb code / 0 if none.
 */
static int bulk_exact(libusb_device_handle *dev, unsigned char ep, unsigned char *base,
                      int length, int timeoutMs)
{
    int is_out = (ep & 0x80) == 0;
    int got = 0;
    int halt_retried = 0;
    int last_err = 0;
    int deadline = now_ms() + (timeoutMs > 0 ? timeoutMs : 1);
    unsigned char bounce[IN_BOUNCE_MAX];
    int pkt = in_pkt_size();

    if (!is_out) {
        got = drain_rx_left(base, length);
    }

    while (got < length && !g_cancelled) {
        int remain_ms = deadline - now_ms();
        if (remain_ms <= 0) {
            break;
        }
        int slice = remain_ms;
        int transferred = 0;
        int rc;
        if (is_out) {
            int want = length - got;
            if (want > MAX_BIG_URB) {
                want = MAX_BIG_URB;
            }
            /* Big multi-URB transfers get the whole remaining budget: a
             * mid-transfer timeout cancels in-flight URBs, which is only
             * worth doing for genuinely small, latency-sensitive ones. */
            if (want < BIG_URB_MIN && slice > URB_TIMEOUT_SLICE_MS) {
                slice = URB_TIMEOUT_SLICE_MS;
            }
            rc = usb_bulk(dev, ep,
                                       (base + got),
                                       want, &transferred,
                                       (unsigned int) slice);
            if (transferred > 0) {
                got += transferred;
                halt_retried = 0;
                if (got >= length) {
                    break;
                }
                continue;
            }
        } else {
            /* URB length must be a multiple of wMaxPacketSize. A 12- or
             * 508-byte IN against a 512-byte bulk packet is overflow:
             * USBFS drops the whole packet. When the caller wants at
             * least one packet, submit up to 16 KiB directly into dest.
             * When they want a short header, IN one packet via bounce. */
            int space = length - got;
            int urb;
            int use_bounce;
            if (space < pkt) {
                urb = pkt;
                use_bounce = 1;
            } else {
                urb = space;
                if (urb > MAX_BIG_URB) {
                    urb = MAX_BIG_URB;
                }
                urb -= urb % pkt;
                if (urb < pkt) {
                    urb = pkt;
                    use_bounce = 1;
                } else {
                    use_bounce = 0;
                }
            }
            unsigned char *dst = use_bounce
                ? bounce
                : (base + got);
            if (urb < BIG_URB_MIN && slice > URB_TIMEOUT_SLICE_MS) {
                slice = URB_TIMEOUT_SLICE_MS;
            }
            rc = usb_bulk(dev, ep, dst, urb, &transferred,
                                       (unsigned int) slice);
            if (rc == LIBUSB_SUCCESS && transferred == 0) {
                /* ZLP after a payload that was a multiple of wMaxPacketSize. */
                continue;
            }
            if (transferred > 0) {
                if (use_bounce) {
                    int copy = transferred < space ? transferred : space;
                    memcpy(base + got, bounce, (size_t) copy);
                    park_rx_left(bounce + copy, transferred - copy);
                    got += copy;
                } else {
                    if (transferred > space) {
                        park_rx_left((unsigned char *) (base + got + space),
                                     transferred - space);
                        got += space;
                    } else {
                        got += transferred;
                    }
                }
                halt_retried = 0;
                if (got >= length) {
                    break;
                }
                /* Short packet = end of this USB frame. */
                if (transferred < urb) {
                    break;
                }
                continue;
            }
        }
        last_err = rc;
        if (rc == LIBUSB_ERROR_PIPE && !halt_retried) {
            halt_retried = 1;
            (void) libusb_clear_halt(dev, ep);
            continue;
        }
        if (rc == LIBUSB_ERROR_TIMEOUT || rc == LIBUSB_ERROR_INTERRUPTED) {
            /* Retry timeouts until the overall deadline; do NOT clear_halt
             * -- the ep is not stalled, the DA just hasn't spoken. */
            continue;
        }
        /* Hard failure with nothing this iteration. */
        break;
    }

    if (got > 0) {
        return got;
    }
    if (g_cancelled) {
        return LIBUSB_ERROR_INTERRUPTED;
    }
    return last_err != 0 ? last_err : 0;
}

/*
 * Accumulate `length` bytes (or until the deadline / cancel / hard error)
 * in one JNI crossing.
 *
 * IN  : URB size is a multiple of max-packet, up to MAX_OUT_URB, written
 *       directly into the caller buffer. A request shorter than one
 *       packet still IN's a full max-packet via bounce and parks surplus
 *       (12-byte header on a 16-byte USB frame). A short packet ends
 *       this USB frame without pulling the next xflash header.
 *       A ZLP (SUCCESS + 0 bytes) is a transfer terminator, not an error:
 *       keep reading until the deadline for the real next packet.
 * OUT : up to MAX_OUT_URB (Android USBFS ceiling) per URB.
 *
 * Stall (LIBUSB_ERROR_PIPE) is retried once after clear_halt.
 * TIMEOUT with nothing transferred is retried until the deadline -- it
 * is NOT treated as a halt (CLEAR_FEATURE on a live endpoint resets the
 * data toggle and is what was dropping the DA off the bus).
 *
 * Returns total bytes moved (>=0). A short result means the deadline was
 * hit, the transfer was cancelled, or a hard error occurred after some
 * bytes already moved; a negative libusb code means nothing moved.
 */
JNIEXPORT jint JNICALL
Java_com_bareflash_app_LibusbNative_nativeBulkTransferExact(JNIEnv *env, jobject thiz, jlong handle,
                                                                jint endpoint, jbyteArray buffer,
                                                                jint offset, jint length, jint timeoutMs) {
    (void) thiz;
    if (handle == 0 || buffer == NULL || length <= 0) {
        return -1;
    }
    if (g_cancelled) {
        return LIBUSB_ERROR_INTERRUPTED;
    }

    jsize arr_len = (*env)->GetArrayLength(env, buffer);
    if (offset < 0 || (jlong) offset + (jlong) length > (jlong) arr_len) {
        return LIBUSB_ERROR_INVALID_PARAM;
    }

    jboolean is_copy = JNI_FALSE;
    jbyte *elems = (*env)->GetByteArrayElements(env, buffer, &is_copy);
    if (elems == NULL) {
        return LIBUSB_ERROR_NO_MEM;
    }
    int is_out = (endpoint & 0x80) == 0;
    int r = bulk_exact((libusb_device_handle *) (intptr_t) handle, (unsigned char) endpoint,
                       (unsigned char *) (elems + offset), length, timeoutMs);
    (*env)->ReleaseByteArrayElements(env, buffer, elems, is_out ? JNI_ABORT : 0);
    return r;
}

/*
 * Fused xflash READ_DATA frame loop.
 *
 * The stop-and-wait protocol costs, per 64 KiB frame, a 12-byte header IN, the
 * payload IN, a 12-byte ACK OUT, a 4-byte ACK OUT and (on most DAs) a bare OK
 * status frame IN. Driven from Python that was ~5 Python->Kotlin->JNI round
 * trips per frame; on a phone that sits on a little core at a low clock each of
 * those is a scheduling hop, and the DA is idle for all of them. Here the whole
 * sequence runs in native code, using the same bulk_exact() as everything else.
 *
 * Only the plain case is handled: data frames whose length is exactly
 * frameLen, each ACKed with the two-OUT form. Anything else ends the batch and
 * is handed back to the Python state machine untouched:
 *   info[0] = why we stopped: 0 dst full, 1 header handed back (not a plain
 *             data frame / second status frame), 2 last frame stored but its
 *             ACK did not go out (caller must send it), 3 nothing usable
 *             (timeout, error, cancel)
 *   info[1] = bare status frames skipped just before the stop (0 or 1)
 *   info[2] = bytes of status payload handed back in hdrOut[12..15] (0 or 4)
 *   info[3] = complete data frames stored in dst
 * hdrOut[0..11] is the handed-back frame header (reason 1 only).
 */
/*
 * bulk_exact() returns after a short USB packet ("end of this USB frame"), so it
 * may hand back fewer bytes than asked: the device is free to deliver a header
 * in pieces. The Kotlin path loops on it; the fused loop must too. bulk_fill()
 * repeats bulk_exact() until `length` bytes arrived, the deadline passed, or
 * the transfer was cancelled.
 *
 * Returns the bytes read (== length on success); if nothing arrived, the last
 * libusb error or 0.
 */
static int bulk_fill(libusb_device_handle *dev, unsigned char ep, unsigned char *base,
                     int length, int timeoutMs)
{
    int deadline = now_ms() + (timeoutMs > 0 ? timeoutMs : 1);
    int got = 0;
    int last = 0;
    while (got < length && !g_cancelled) {
        int remain = deadline - now_ms();
        if (remain <= 0) {
            break;
        }
        int r = bulk_exact(dev, ep, base + got, length - got, remain);
        if (r > 0) {
            got += r;
            continue;
        }
        last = r;
        break;
    }
    if (got > 0) {
        return got;
    }
    return g_cancelled ? LIBUSB_ERROR_INTERRUPTED : last;
}

static uint32_t le32(const unsigned char *p)
{
    return (uint32_t) p[0] | ((uint32_t) p[1] << 8) | ((uint32_t) p[2] << 16) | ((uint32_t) p[3] << 24);
}

/* Why nativeReadFrames() returned (info[0]). */
enum {
    READ_BATCH_DONE = 0,      /* all requested frames received and ACKed */
    READ_HANDBACK = 1,        /* a header we do not handle: Python continues with it */
    READ_ACK_FAILED = 2,      /* last frame stored, its ACK did not go out */
    READ_STOPPED = 3          /* timeout, error or cancel */
};

#define READ_INFO_LEN 7       /* reason, skips, pay_back, frames, us_hdr, us_pay, us_ack */

/*
 * The MediaTek xflash READ_DATA frame loop, run natively.
 *
 * Per 64 KiB frame the DA sends a 12-byte header (magic, type, length), the
 * payload, then waits for a 16-byte ACK; after the ACK it sends a bare OK
 * status frame (header with length 4 plus a 4-byte zero payload) before the
 * next data frame. Doing that from Python costs about eight JNI/Python round
 * trips per frame; here one call moves up to `dst`-capacity frames.
 *
 * Anything unexpected (bad magic, a frame of another size, a status that is
 * not a plain OK) stops the batch and is handed back through hdrOut/infoOut,
 * so the Python loop handles it exactly as it always did.
 *
 * infoOut: [reason, status frames skipped, status payload bytes handed back
 * (0 or 4), frames stored, then cumulative microseconds spent waiting for
 * status+header, receiving payloads and sending ACKs]. Returns frames stored.
 */
JNIEXPORT jint JNICALL
Java_com_bareflash_app_LibusbNative_nativeReadFrames(JNIEnv *env, jobject thiz, jlong handle,
                                                         jint epIn, jint epOut, jbyteArray dst,
                                                         jint frameLen, jint hdrTimeoutMs,
                                                         jbyteArray hdrOut, jintArray infoOut) {
    (void) thiz;
    int frames = 0;
    int reason = READ_STOPPED;
    int skips = 0;
    int pay_back = 0;
    long long us_hdr = 0, us_pay = 0, us_ack = 0;
    unsigned char hdr[16];
    memset(hdr, 0, sizeof(hdr));

    jsize cap = dst ? (*env)->GetArrayLength(env, dst) : 0;
    if (handle != 0 && dst != NULL && frameLen > 0 && cap >= frameLen && !g_cancelled) {
        libusb_device_handle *dev = (libusb_device_handle *) (intptr_t) handle;
        const unsigned char in_ep = (unsigned char) epIn;
        const unsigned char out_ep = (unsigned char) epOut;
        jbyte *base = (*env)->GetByteArrayElements(env, dst, NULL);
        if (base != NULL) {
            const int max_frames = cap / frameLen;
            const int pay_budget = 8000 > (frameLen / 64 + 4000) ? 8000 : (frameLen / 64 + 4000);
            static const unsigned char ack[16] = {
                0xEF, 0xEE, 0xEE, 0xFE, 1, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0
            };
            unsigned char ack_buf[16];
            while (frames < max_frames && !g_cancelled) {
                long long t0 = now_us();
                int r = bulk_fill(dev, in_ep, hdr, 12, hdrTimeoutMs);
                us_hdr += now_us() - t0;
                if (r != 12) {
                    reason = READ_STOPPED;
                    break;
                }
                if (le32(hdr) != 0xFEEEEEEFu) {
                    reason = READ_HANDBACK;
                    break;
                }
                const int32_t slen = (int32_t) le32(hdr + 8);
                if (slen == 4) {
                    /* Bare status frame: skip one that says OK, hand back anything
                     * else (including a second one in a row). */
                    t0 = now_us();
                    r = bulk_fill(dev, in_ep, hdr + 12, 4, pay_budget);
                    us_hdr += now_us() - t0;
                    if (r != 4) {
                        reason = READ_STOPPED;
                        break;
                    }
                    if (skips == 0 && le32(hdr + 12) == 0) {
                        skips = 1;
                        continue;
                    }
                    pay_back = 4;
                    reason = READ_HANDBACK;
                    break;
                }
                if (slen != frameLen) {
                    reason = READ_HANDBACK;
                    break;
                }
                t0 = now_us();
                r = bulk_fill(dev, in_ep, (unsigned char *) base + (size_t) frames * frameLen,
                              frameLen, pay_budget);
                us_pay += now_us() - t0;
                if (r != frameLen) {
                    /* A partial payload cannot be resumed from here. */
                    reason = READ_STOPPED;
                    break;
                }
                frames++;
                skips = 0;
                memcpy(ack_buf, ack, 16);
                t0 = now_us();
                if (bulk_fill(dev, out_ep, ack_buf, 12, 5000) != 12 ||
                    bulk_fill(dev, out_ep, ack_buf + 12, 4, 5000) != 4) {
                    reason = READ_ACK_FAILED;
                    break;
                }
                us_ack += now_us() - t0;
                reason = READ_BATCH_DONE;
            }
            (*env)->ReleaseByteArrayElements(env, dst, base, 0);
            if (g_cancelled) {
                reason = READ_STOPPED;
            }
        }
    }

    if (hdrOut != NULL && (*env)->GetArrayLength(env, hdrOut) >= 16) {
        jbyte *h = (*env)->GetByteArrayElements(env, hdrOut, NULL);
        if (h != NULL) {
            memcpy(h, hdr, 16);
            (*env)->ReleaseByteArrayElements(env, hdrOut, h, 0);
        }
    }
    if (infoOut != NULL && (*env)->GetArrayLength(env, infoOut) >= READ_INFO_LEN) {
        jint *i = (*env)->GetIntArrayElements(env, infoOut, NULL);
        if (i != NULL) {
            i[0] = reason;
            i[1] = skips;
            i[2] = pay_back;
            i[3] = frames;
            i[4] = (jint) us_hdr;
            i[5] = (jint) us_pay;
            i[6] = (jint) us_ack;
            (*env)->ReleaseIntArrayElements(env, infoOut, i, 0);
        }
    }
    return frames;
}

JNIEXPORT jstring JNICALL
Java_com_bareflash_app_LibusbNative_nativeErrorName(JNIEnv *env, jobject thiz, jint code) {
    (void) thiz;
    const char *name = libusb_error_name(code);
    return (*env)->NewStringUTF(env, name != NULL ? name : "UNKNOWN");
}

/*
 * After a payload whose length is a multiple of wMaxPacketSize the gadget
 * may queue a ZLP on bulk IN. Submitting a 4-byte OUT while that ZLP is
 * still pending is the Android USBFS failure mode that kills dumps
 * at 64 KiB. One short IN consumes the ZLP (or times out immediately if
 * there isn't one). Real bytes -- a next-frame header that arrived early
 * -- are parked in g_rx_left instead of being discarded.
 *
 * Returns 0 for ZLP/timeout/already-buffered leftover; >0 if unexpected
 * payload was parked; negative libusb error only when the IN itself failed
 * hard with nothing transferred.
 */
JNIEXPORT jint JNICALL
Java_com_bareflash_app_LibusbNative_nativeDrainZlp(JNIEnv *env, jobject thiz,
                                                       jlong handle, jint endpoint,
                                                       jint timeoutMs) {
    (void) env;
    (void) thiz;
    if (handle == 0) {
        return 0;
    }
    if (g_rx_left_len > 0) {
        return 0;
    }
    if (g_cancelled) {
        return LIBUSB_ERROR_INTERRUPTED;
    }
    unsigned char bounce[IN_BOUNCE_MAX];
    int pkt = in_pkt_size();
    int transferred = 0;
    int slice = timeoutMs > 0 ? timeoutMs : 1;
    int rc = usb_bulk((libusb_device_handle *) (intptr_t) handle,
                                   (unsigned char) endpoint,
                                   bounce, pkt, &transferred,
                                   (unsigned int) slice);
    if (transferred > 0) {
        park_rx_left(bounce, transferred);
        return transferred;
    }
    if (rc == LIBUSB_SUCCESS || rc == LIBUSB_ERROR_TIMEOUT) {
        return 0;
    }
    return rc;
}

/*
 * Pin the CALLING thread to the fastest CPU cores.
 *
 * The transfer thread does a little CPU work per 64 KiB xflash frame
 * (JNI, Python, ack) and then blocks on USB. Android's frequency governor
 * sees a low-utilisation thread, parks it on a little core at a low clock,
 * and every frame gets slower -- which lowers utilisation further. Any
 * burst of unrelated load on that cluster (a rotation, an overlay app)
 * lifts the clock for a moment and speed "jumps back". Running the thread
 * on the prime/big cores removes the dependency on that accident.
 *
 * "Fastest" = highest cpuinfo_max_freq (falls back to cpu_capacity).
 * Returns the number of cores in the resulting mask, or 0 if nothing was
 * changed (unreadable sysfs, affinity denied) -- never an error the app
 * needs to act on.
 */
static long read_cpu_metric(int cpu) {
    char path[96];
    long v = -1;
    FILE *f;
    snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
    f = fopen(path, "r");
    if (f == NULL) {
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpu_capacity", cpu);
        f = fopen(path, "r");
    }
    if (f != NULL) {
        if (fscanf(f, "%ld", &v) != 1) {
            v = -1;
        }
        fclose(f);
    }
    return v;
}

JNIEXPORT jint JNICALL
Java_com_bareflash_app_LibusbNative_nativePinToFastCores(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    long n = sysconf(_SC_NPROCESSORS_CONF);
    if (n <= 1 || n > CPU_SETSIZE) {
        return 0;
    }
    long metric[CPU_SETSIZE];
    long best = -1;
    long worst = -1;
    for (int i = 0; i < n; i++) {
        metric[i] = read_cpu_metric(i);
        if (metric[i] > best) {
            best = metric[i];
        }
        if (metric[i] > 0 && (worst < 0 || metric[i] < worst)) {
            worst = metric[i];
        }
    }
    if (best <= 0 || worst <= 0) {
        return 0;
    }
    /* Keep everything except the slowest cluster. The old rule (only cores
     * within 10% of the top) collapsed to ONE prime core on 1+3+4 SoCs, and
     * every thread this one spawns (Python file writer, source prefetch,
     * libusb helpers) inherits the mask -- so the USB loop, the GIL holder
     * and the writer all fought over a single core. Refuse to pin unless at
     * least 3 cores remain for that reason. */
    cpu_set_t set;
    CPU_ZERO(&set);
    int count = 0;
    for (int i = 0; i < n; i++) {
        if (metric[i] > worst) {
            CPU_SET(i, &set);
            count++;
        }
    }
    if (count < 3 || count == n) {
        return 0; /* homogeneous SoC, or too few fast cores to share */
    }
    if (sched_setaffinity(0, sizeof(set), &set) != 0) {
        LOGW("sched_setaffinity failed; leaving affinity alone");
        return 0;
    }
    LOGI("transfer thread pinned to %d fast core(s) (max metric %ld)", count, best);
    return count;
}

/*
 * One libusb_control_transfer() on the same wrapped handle used for bulk.
 * Routing control through libusb (instead of Java UsbDeviceConnection)
 * keeps a single USBFS ioctl path and drops a JNI + Java USB round-trip
 * per SETUP packet.
 *
 * `data` may be NULL when wLength is 0. For IN, `data` is the destination
 * buffer of at least `length` bytes. Returns bytes transferred (>=0) or a
 * negative libusb error.
 */
JNIEXPORT jint JNICALL
Java_com_bareflash_app_LibusbNative_nativeControlTransfer(JNIEnv *env, jobject thiz,
        jlong handle, jint requestType, jint request, jint value, jint index,
        jbyteArray data, jint offset, jint length, jint timeoutMs) {
    (void) thiz;
    if (handle == 0) {
        return LIBUSB_ERROR_NO_DEVICE;
    }
    if (g_cancelled) {
        return LIBUSB_ERROR_INTERRUPTED;
    }
    if (length < 0) {
        return LIBUSB_ERROR_INVALID_PARAM;
    }

    libusb_device_handle *dev = (libusb_device_handle *) (intptr_t) handle;
    unsigned char *buf = NULL;
    jbyte *elems = NULL;
    jboolean is_copy = JNI_FALSE;
    int is_in = (requestType & 0x80) != 0;

    if (length > 0) {
        if (data == NULL) {
            return LIBUSB_ERROR_INVALID_PARAM;
        }
        jsize arr_len = (*env)->GetArrayLength(env, data);
        if (offset < 0 || (jlong) offset + (jlong) length > (jlong) arr_len) {
            return LIBUSB_ERROR_INVALID_PARAM;
        }
        elems = (*env)->GetByteArrayElements(env, data, &is_copy);
        if (elems == NULL) {
            return LIBUSB_ERROR_NO_MEM;
        }
        buf = (unsigned char *) (elems + offset);
    }

    int rc = libusb_control_transfer(dev,
                                      (uint8_t) requestType,
                                      (uint8_t) request,
                                      (uint16_t) value,
                                      (uint16_t) index,
                                      buf,
                                      (uint16_t) length,
                                      (unsigned int) (timeoutMs > 0 ? timeoutMs : 1));

    if (elems != NULL) {
        (*env)->ReleaseByteArrayElements(env, data, elems, is_in ? 0 : JNI_ABORT);
    }

    if (rc < 0) {
        return rc;
    }
    return rc;
}

/*
 * Execute `count` control transfers in one JNI crossing.
 *
 * headers: int[count * 6] = {bmRequestType, bRequest, wValue, wIndex, wLength, timeoutMs}…
 * payloads: concatenated OUT data and IN receive slots, in op order.
 * results:  int[count] filled with bytes transferred or a negative libusb code.
 *
 * Returns 0 if every op transferred (or IN/OUT with wLength 0 succeeded),
 * otherwise the first negative libusb error. Later ops still run unless
 * cancelled or the handle is gone — Kamakiri sequences expect the GET
 * descriptor that follows a SET_LINE_CODING even if the SET stalls.
 */
JNIEXPORT jint JNICALL
Java_com_bareflash_app_LibusbNative_nativeControlTransferBatch(JNIEnv *env, jobject thiz,
        jlong handle, jintArray headers, jbyteArray payloads, jintArray results) {
    (void) thiz;
    if (handle == 0 || headers == NULL || results == NULL) {
        return LIBUSB_ERROR_INVALID_PARAM;
    }
    if (g_cancelled) {
        return LIBUSB_ERROR_INTERRUPTED;
    }

    jsize header_len = (*env)->GetArrayLength(env, headers);
    jsize result_len = (*env)->GetArrayLength(env, results);
    if (header_len < 6 || (header_len % 6) != 0) {
        return LIBUSB_ERROR_INVALID_PARAM;
    }
    int count = (int) (header_len / 6);
    if (result_len < count) {
        return LIBUSB_ERROR_INVALID_PARAM;
    }

    jint *hdr = (*env)->GetIntArrayElements(env, headers, NULL);
    jint *res = (*env)->GetIntArrayElements(env, results, NULL);
    if (hdr == NULL || res == NULL) {
        if (hdr) (*env)->ReleaseIntArrayElements(env, headers, hdr, JNI_ABORT);
        if (res) (*env)->ReleaseIntArrayElements(env, results, res, JNI_ABORT);
        return LIBUSB_ERROR_NO_MEM;
    }

    int payload_needed = 0;
    for (int i = 0; i < count; i++) {
        int n = hdr[i * 6 + 4];
        if (n > 0) {
            payload_needed += n;
        }
    }

    jbyte *pay = NULL;
    jboolean pay_copy = JNI_FALSE;
    if (payload_needed > 0) {
        if (payloads == NULL || (*env)->GetArrayLength(env, payloads) < payload_needed) {
            (*env)->ReleaseIntArrayElements(env, headers, hdr, JNI_ABORT);
            (*env)->ReleaseIntArrayElements(env, results, res, JNI_ABORT);
            return LIBUSB_ERROR_INVALID_PARAM;
        }
        pay = (*env)->GetByteArrayElements(env, payloads, &pay_copy);
        if (pay == NULL) {
            (*env)->ReleaseIntArrayElements(env, headers, hdr, JNI_ABORT);
            (*env)->ReleaseIntArrayElements(env, results, res, JNI_ABORT);
            return LIBUSB_ERROR_NO_MEM;
        }
    }

    libusb_device_handle *dev = (libusb_device_handle *) (intptr_t) handle;
    int cursor = 0;
    int first_err = 0;
    int wrote_in = 0;

    for (int i = 0; i < count; i++) {
        if (g_cancelled) {
            res[i] = LIBUSB_ERROR_INTERRUPTED;
            if (first_err == 0) first_err = LIBUSB_ERROR_INTERRUPTED;
            continue;
        }
        int rt = hdr[i * 6 + 0];
        int req = hdr[i * 6 + 1];
        int val = hdr[i * 6 + 2];
        int idx = hdr[i * 6 + 3];
        int len = hdr[i * 6 + 4];
        int tmo = hdr[i * 6 + 5];
        if (len < 0) len = 0;
        unsigned char *buf = NULL;
        if (len > 0 && pay != NULL) {
            buf = (unsigned char *) (pay + cursor);
        }
        int rc = libusb_control_transfer(dev,
                                          (uint8_t) rt,
                                          (uint8_t) req,
                                          (uint16_t) val,
                                          (uint16_t) idx,
                                          buf,
                                          (uint16_t) len,
                                          (unsigned int) (tmo > 0 ? tmo : 1));
        res[i] = rc;
        if (rc < 0 && first_err == 0) {
            first_err = rc;
        }
        if ((rt & 0x80) && rc > 0) {
            wrote_in = 1;
        }
        cursor += len;
    }

    if (pay != NULL) {
        (*env)->ReleaseByteArrayElements(env, payloads, pay, wrote_in ? 0 : JNI_ABORT);
    }
    (*env)->ReleaseIntArrayElements(env, results, res, 0);
    (*env)->ReleaseIntArrayElements(env, headers, hdr, JNI_ABORT);
    return first_err;
}

/*
 * 16-bit byte sum used as the xflash write checksum. Cheaper in C than a
 * CPython loop over a multi-MiB payload (and cheaper than the Kotlin
 * equivalent when the data is already pinned here).
 */
JNIEXPORT jint JNICALL
Java_com_bareflash_app_LibusbNative_nativeByteSum16(JNIEnv *env, jobject thiz,
                                                        jbyteArray data, jint offset, jint length) {
    (void) thiz;
    if (data == NULL || length <= 0) {
        return 0;
    }
    jsize arr_len = (*env)->GetArrayLength(env, data);
    if (offset < 0 || (jlong) offset + (jlong) length > (jlong) arr_len) {
        return 0;
    }
    jboolean is_copy = JNI_FALSE;
    jbyte *elems = (*env)->GetByteArrayElements(env, data, &is_copy);
    if (elems == NULL) {
        return 0;
    }
    const unsigned char *p = (const unsigned char *) (elems + offset);
    unsigned int sum = 0;
    int i = 0;
    /* 8-byte unroll — the compiler can emit SIMD on arm64. */
    for (; i + 8 <= length; i += 8) {
        sum += p[i] + p[i + 1] + p[i + 2] + p[i + 3]
             + p[i + 4] + p[i + 5] + p[i + 6] + p[i + 7];
    }
    for (; i < length; i++) {
        sum += p[i];
    }
    (*env)->ReleaseByteArrayElements(env, data, elems, JNI_ABORT);
    return (jint) (sum & 0xFFFF);
}

/* ---- bfusb telemetry ------------------------------------------------------ */

JNIEXPORT jstring JNICALL
Java_com_bareflash_app_LibusbNative_nativeLinkName(JNIEnv *env, jobject thiz, jint maxPacket) {
    (void) thiz;
    return (*env)->NewStringUTF(env, bfusb_link_name((int) maxPacket));
}

JNIEXPORT jboolean JNICALL
Java_com_bareflash_app_LibusbNative_nativeLinkIsSlow(JNIEnv *env, jobject thiz, jint maxPacket) {
    (void) env;
    (void) thiz;
    return bfusb_link_is_slow((int) maxPacket) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_bareflash_app_LibusbNative_nativeStatsReset(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    bfusb_stats_reset();
}

/* One line, empty when nothing was transferred since the last reset. */
JNIEXPORT jstring JNICALL
Java_com_bareflash_app_LibusbNative_nativeStatsSummary(JNIEnv *env, jobject thiz) {
    (void) thiz;
    char line[320];
    bfusb_stats_format(line, sizeof(line));
    return (*env)->NewStringUTF(env, line);
}
