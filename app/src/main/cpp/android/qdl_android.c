/*
 * Globals and helpers shared between the vendored qdl sources and the
 * Bareflash runner. Compiled WITHOUT the compat header (it defines what the
 * header declares).
 */
#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

/* File descriptor of the Qualcomm device from UsbManager, or -1. Read by usb.c. */
volatile int qdl_android_usb_fd = -1;

const char *qdl_progname_ = "qdl";

void qdl_android_exit(int status)
{
    fflush(NULL);
    _exit(status);
}

void qdl_warnx(const char *fmt, ...)
{
    va_list ap;
    fprintf(stderr, "%s: ", qdl_progname_);
    if (fmt) {
        va_start(ap, fmt);
        vfprintf(stderr, fmt, ap);
        va_end(ap);
    }
    fputc('\n', stderr);
}

void qdl_warn(const char *fmt, ...)
{
    int saved = errno;
    va_list ap;
    fprintf(stderr, "%s: ", qdl_progname_);
    if (fmt) {
        va_start(ap, fmt);
        vfprintf(stderr, fmt, ap);
        va_end(ap);
        fputs(": ", stderr);
    }
    fprintf(stderr, "%s\n", strerror(saved));
}
