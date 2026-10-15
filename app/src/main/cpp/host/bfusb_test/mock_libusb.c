/*
 * Minimal libusb stand-in for host tests: just the transfer API bfusb uses,
 * backed by a simulated device with a configurable data rate, stall, and
 * failure mode. Completion is delivered only from libusb_handle_events_*
 * (as in real libusb), by whichever thread calls it.
 */
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "libusb.h"
#include "mock_libusb.h"

struct mock_cfg g_mock = { .rate_bps = 100e6 };

struct pending {
    struct libusb_transfer *t;
    double start;           /* seconds */
    double due;             /* seconds; < 0 = never (stall) */
    int cancelled;
    struct pending *next;
};

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static struct pending *g_head;

static double now_s(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

struct libusb_transfer *libusb_alloc_transfer(int iso)
{
    (void)iso;
    return calloc(1, sizeof(struct libusb_transfer));
}

void libusb_free_transfer(struct libusb_transfer *t) { free(t); }

int libusb_submit_transfer(struct libusb_transfer *t)
{
    if (g_mock.submit_fail)
        return g_mock.submit_fail;
    struct pending *p = calloc(1, sizeof(*p));
    p->t = t;
    p->start = now_s();
    p->due = g_mock.stall ? -1.0 : p->start + (double)t->length / g_mock.rate_bps;
    pthread_mutex_lock(&g_lock);
    p->next = g_head;
    g_head = p;
    pthread_mutex_unlock(&g_lock);
    __atomic_fetch_add(&g_mock.submits, 1, __ATOMIC_RELAXED);
    return 0;
}

int libusb_cancel_transfer(struct libusb_transfer *t)
{
    int found = LIBUSB_ERROR_NOT_FOUND;
    pthread_mutex_lock(&g_lock);
    for (struct pending *p = g_head; p; p = p->next)
        if (p->t == t) { p->cancelled = 1; found = 0; }
    pthread_mutex_unlock(&g_lock);
    return found;
}

static int partial(const struct pending *p, double now)
{
    if (p->due < 0)            /* stalled: the device never moved a byte */
        return 0;
    double bytes = (now - p->start) * g_mock.rate_bps;
    if (bytes < 0) bytes = 0;
    if (bytes > p->t->length) bytes = p->t->length;
    return (int)bytes;
}

/* Completes everything that is due or cancelled. Returns how many. */
static int reap(void)
{
    struct pending *done = NULL;
    int n = 0;
    double now = now_s();

    pthread_mutex_lock(&g_lock);
    struct pending **pp = &g_head;
    while (*pp) {
        struct pending *p = *pp;
        int fin = 0;
        if (p->cancelled) {
            p->t->status = LIBUSB_TRANSFER_CANCELLED;
            p->t->actual_length = partial(p, now);
            fin = 1;
        } else if (p->due >= 0 && now >= p->due) {
            if (g_mock.fail_status) {
                p->t->status = (enum libusb_transfer_status)g_mock.fail_status;
                p->t->actual_length = g_mock.fail_partial;
            } else {
                p->t->status = LIBUSB_TRANSFER_COMPLETED;
                p->t->actual_length = p->t->length;
            }
            fin = 1;
        }
        if (fin) { *pp = p->next; p->next = done; done = p; n++; }
        else pp = &p->next;
    }
    pthread_mutex_unlock(&g_lock);

    while (done) {
        struct pending *p = done;
        done = p->next;
        p->t->callback(p->t);
        free(p);
    }
    return n;
}

int libusb_handle_events_timeout_completed(libusb_context *ctx, struct timeval *tv, int *completed)
{
    (void)ctx;
    double end = now_s() + tv->tv_sec + tv->tv_usec / 1e6;
    for (;;) {
        reap();
        if (completed && __atomic_load_n(completed, __ATOMIC_ACQUIRE))
            return 0;
        if (now_s() >= end)
            return 0;
        usleep(200);
    }
}

int libusb_bulk_transfer(libusb_device_handle *h, unsigned char ep, unsigned char *data,
                         int length, int *transferred, unsigned int timeout)
{
    struct libusb_transfer *t = libusb_alloc_transfer(0);
    int done = 0;
    libusb_fill_bulk_transfer(t, h, ep, data, length, (libusb_transfer_cb_fn)NULL, &done, timeout);
    t->callback = (libusb_transfer_cb_fn)NULL;
    /* sync semantics: reuse the async sim with an inline completion flag */
    extern void mock_sync_cb(struct libusb_transfer *);
    t->callback = mock_sync_cb;
    int rc = libusb_submit_transfer(t);
    if (rc) { libusb_free_transfer(t); return rc; }
    double end = timeout ? now_s() + timeout / 1000.0 : 0;
    int cancelled = 0;
    while (!__atomic_load_n(&done, __ATOMIC_ACQUIRE)) {
        struct timeval tv = { 0, 1000 };
        libusb_handle_events_timeout_completed(NULL, &tv, &done);
        if (!cancelled && end && now_s() >= end) { libusb_cancel_transfer(t); cancelled = 1; }
    }
    if (transferred) *transferred = t->actual_length;
    enum libusb_transfer_status st = t->status;
    libusb_free_transfer(t);
    if (st == LIBUSB_TRANSFER_COMPLETED) return 0;
    if (st == LIBUSB_TRANSFER_CANCELLED) return LIBUSB_ERROR_TIMEOUT;
    if (st == LIBUSB_TRANSFER_STALL) return LIBUSB_ERROR_PIPE;
    if (st == LIBUSB_TRANSFER_NO_DEVICE) return LIBUSB_ERROR_NO_DEVICE;
    return LIBUSB_ERROR_IO;
}

void mock_sync_cb(struct libusb_transfer *t) { __atomic_store_n((int *)t->user_data, 1, __ATOMIC_RELEASE); }
