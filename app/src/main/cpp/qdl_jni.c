/*
 * JNI bridge for the QDL engine (libqdl_jni.so).
 *
 * qdl itself runs in a forked child process (see android/qdl_runner.c); this
 * file only converts arguments, forwards the child's output lines to the
 * Kotlin sink on the calling thread, and exposes cancel().
 */
#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>

#include "qdl_runner.h"

#define TAG "qdl_jni"

struct sink_ctx {
    JNIEnv   *env;
    jobject   sink;
    jmethodID on_line;
};

/*
 * NewStringUTF() wants *modified* UTF-8 and CheckJNI aborts the process on
 * anything else. qdl echoes device log text, which is not guaranteed to be
 * clean, so keep ASCII and well-formed 2/3-byte sequences and replace the
 * rest with '?'. out must hold at least 3 * strlen(in) + 1 bytes (never
 * larger than the input in practice, so in_len + 1 is enough).
 */
static void sanitize_utf8(const char *in, char *out)
{
    const unsigned char *s = (const unsigned char *) in;
    char *o = out;
    while (*s) {
        if (*s < 0x80) {
            *o++ = (char) *s++;
        } else if ((s[0] & 0xE0) == 0xC0 && s[0] >= 0xC2 && (s[1] & 0xC0) == 0x80) {
            *o++ = (char) s[0]; *o++ = (char) s[1]; s += 2;
        } else if ((s[0] & 0xF0) == 0xE0 && (s[1] & 0xC0) == 0x80 && (s[2] & 0xC0) == 0x80) {
            *o++ = (char) s[0]; *o++ = (char) s[1]; *o++ = (char) s[2]; s += 3;
        } else {
            *o++ = '?';
            s++;
        }
    }
    *o = '\0';
}

static void on_line(const char *line, void *user)
{
    struct sink_ctx *c = (struct sink_ctx *) user;
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", line);

    size_t n = strlen(line);
    char *clean = (char *) malloc(n + 1);
    if (!clean) return;
    sanitize_utf8(line, clean);

    jstring js = (*c->env)->NewStringUTF(c->env, clean);
    free(clean);
    if (js) {
        (*c->env)->CallVoidMethod(c->env, c->sink, c->on_line, js);
        (*c->env)->DeleteLocalRef(c->env, js);
    }
    if ((*c->env)->ExceptionCheck(c->env)) (*c->env)->ExceptionClear(c->env);
}

JNIEXPORT jboolean JNICALL
Java_com_bareflash_app_engine_QdlNative_nativeEmbedded(JNIEnv *env, jclass cls)
{
    (void) env; (void) cls;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_bareflash_app_engine_QdlNative_nativeCancel(JNIEnv *env, jclass cls)
{
    (void) env; (void) cls;
    qdl_runner_cancel();
}

JNIEXPORT jint JNICALL
Java_com_bareflash_app_engine_QdlNative_nativeRun(JNIEnv *env, jclass cls, jint fd,
                                                   jobjectArray jargv, jobject sink)
{
    (void) cls;

    jclass sink_cls = (*env)->FindClass(env, "com/bareflash/app/engine/QdlNative$Sink");
    if (!sink_cls) return -110;
    struct sink_ctx ctx = {
        .env = env,
        .sink = sink,
        .on_line = (*env)->GetMethodID(env, sink_cls, "onLine", "(Ljava/lang/String;)V"),
    };
    if (!ctx.on_line) return -110;

    int n = (*env)->GetArrayLength(env, jargv);
    char **argv = (char **) calloc((size_t) n + 2, sizeof(char *));
    if (!argv) return -111;
    argv[0] = strdup("qdl");
    for (int i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, jargv, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i + 1] = strdup(c ? c : "");
        if (c) (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
    }

    int rc = qdl_runner_run((int) fd, n + 1, argv, on_line, &ctx);

    for (int i = 0; i <= n; i++) free(argv[i]);
    free(argv);
    return rc;
}
