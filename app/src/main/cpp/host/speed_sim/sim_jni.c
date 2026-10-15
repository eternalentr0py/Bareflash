/*
 * speed_sim: calls the REAL usb_native.c JNI entry points without a JVM.
 *
 * A fake JNIEnv implements the six JNI functions usb_native.c uses (array
 * length / elements, string creation) over plain memory, and thin C wrappers
 * with simple signatures are exported so Python can reach the same entry
 * points ctypes-style. Nothing in usb_native.c is modified or bypassed.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

struct fake_arr { jsize len; void *data; };

static jsize fn_GetArrayLength(JNIEnv *env, jarray a)
{ (void)env; return ((struct fake_arr *)a)->len; }

static jbyte *fn_GetByteArrayElements(JNIEnv *env, jbyteArray a, jboolean *c)
{ (void)env; if (c) *c = JNI_FALSE; return (jbyte *)((struct fake_arr *)a)->data; }

static void fn_ReleaseByteArrayElements(JNIEnv *env, jbyteArray a, jbyte *e, jint m)
{ (void)env; (void)a; (void)e; (void)m; }

static jint *fn_GetIntArrayElements(JNIEnv *env, jintArray a, jboolean *c)
{ (void)env; if (c) *c = JNI_FALSE; return (jint *)((struct fake_arr *)a)->data; }

static void fn_ReleaseIntArrayElements(JNIEnv *env, jintArray a, jint *e, jint m)
{ (void)env; (void)a; (void)e; (void)m; }

static char g_last_string[512];
static jstring fn_NewStringUTF(JNIEnv *env, const char *s)
{
    (void)env;
    strncpy(g_last_string, s ? s : "", sizeof(g_last_string) - 1);
    return (jstring)g_last_string;
}

static struct JNINativeInterface_ g_fn;
static const struct JNINativeInterface_ *g_envp = &g_fn;

static JNIEnv *env(void)
{
    g_fn.GetArrayLength = fn_GetArrayLength;
    g_fn.GetByteArrayElements = fn_GetByteArrayElements;
    g_fn.ReleaseByteArrayElements = fn_ReleaseByteArrayElements;
    g_fn.GetIntArrayElements = fn_GetIntArrayElements;
    g_fn.ReleaseIntArrayElements = fn_ReleaseIntArrayElements;
    g_fn.NewStringUTF = fn_NewStringUTF;
    return (JNIEnv *)&g_envp;
}

/* The real entry points, linked from usb_native.c. */
#define N(name) Java_com_bareflash_app_LibusbNative_##name
extern jboolean N(nativeInit)(JNIEnv *, jobject);
extern jlong N(nativeWrapFd)(JNIEnv *, jobject, jint);
extern jboolean N(nativeClaimInterface)(JNIEnv *, jobject, jlong, jint);
extern void N(nativeSetCancelled)(JNIEnv *, jobject, jboolean);
extern void N(nativeFlushRx)(JNIEnv *, jobject);
extern void N(nativeSetMaxPacket)(JNIEnv *, jobject, jint);
extern jint N(nativeBulkTransfer)(JNIEnv *, jobject, jlong, jint, jbyteArray, jint, jint, jint);
extern jint N(nativeBulkTransferExact)(JNIEnv *, jobject, jlong, jint, jbyteArray, jint, jint, jint);
extern jboolean N(nativeClearHalt)(JNIEnv *, jobject, jlong, jint);
extern jint N(nativeByteSum16)(JNIEnv *, jobject, jbyteArray, jint, jint);
extern void N(nativeStatsReset)(JNIEnv *, jobject);
extern jstring N(nativeStatsSummary)(JNIEnv *, jobject);
extern jstring N(nativeErrorName)(JNIEnv *, jobject, jint);
extern jint N(nativeReadFrames)(JNIEnv *, jobject, jlong, jint, jint, jbyteArray, jint, jint,
                                jbyteArray, jintArray);

#define ARR(buf, len) struct fake_arr a = { (jsize)(len), (buf) }

int sim_jni_init(void) { return N(nativeInit)(env(), NULL); }
long sim_jni_wrap(int fd) { return (long)N(nativeWrapFd)(env(), NULL, fd); }
int sim_jni_claim(long h, int iface) { return N(nativeClaimInterface)(env(), NULL, h, iface); }
void sim_jni_set_cancelled(int f) { N(nativeSetCancelled)(env(), NULL, f ? JNI_TRUE : JNI_FALSE); }
void sim_jni_flush_rx(void) { N(nativeFlushRx)(env(), NULL); }
void sim_jni_set_max_packet(int p) { N(nativeSetMaxPacket)(env(), NULL, p); }
int sim_jni_clear_halt(long h, int ep) { return N(nativeClearHalt)(env(), NULL, h, ep); }

int sim_jni_bulk_exact(long h, int ep, void *buf, int buf_len, int off, int len, int tmo)
{ ARR(buf, buf_len); return N(nativeBulkTransferExact)(env(), NULL, h, ep, (jbyteArray)&a, off, len, tmo); }

int sim_jni_bulk(long h, int ep, void *buf, int buf_len, int off, int len, int tmo)
{ ARR(buf, buf_len); return N(nativeBulkTransfer)(env(), NULL, h, ep, (jbyteArray)&a, off, len, tmo); }

int sim_jni_byte_sum16(void *buf, int buf_len, int off, int len)
{ ARR(buf, buf_len); return N(nativeByteSum16)(env(), NULL, (jbyteArray)&a, off, len); }

void sim_jni_stats_reset(void) { N(nativeStatsReset)(env(), NULL); }

const char *sim_jni_stats_summary(void)
{ jstring s = N(nativeStatsSummary)(env(), NULL); return (const char *)s; }

const char *sim_jni_error_name(int code)
{ jstring s = N(nativeErrorName)(env(), NULL, code); return (const char *)s; }

int sim_jni_read_frames(long h, int ep_in, int ep_out, void *dst, int dst_len, int frame_len,
                        int hdr_tmo, void *hdr16, int *info4)
{
    struct fake_arr d = { (jsize)dst_len, dst };
    struct fake_arr hh = { 16, hdr16 };
    struct fake_arr ii = { 4, info4 };
    return N(nativeReadFrames)(env(), NULL, h, ep_in, ep_out, (jbyteArray)&d, frame_len, hdr_tmo,
                               (jbyteArray)&hh, (jintArray)&ii);
}
