#include "bfusb.h"

#include <errno.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

#include "libusb.h"

/* ---- time ---------------------------------------------------------------- */

static uint64_t now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

/* ---- stats --------------------------------------------------------------- */

static atomic_uint_fast64_t s_bytes_in, s_bytes_out, s_calls_in, s_calls_out;
static atomic_uint_fast64_t s_bus_in, s_bus_out, s_gap;
static atomic_uint_fast64_t s_timeouts, s_cancels, s_errors;

/* End of this thread's previous transfer, for host-gap measurement. */
static _Thread_local uint64_t t_last_end_ns;

void bfusb_stats_reset(void)
{
    atomic_store(&s_bytes_in, 0);  atomic_store(&s_bytes_out, 0);
    atomic_store(&s_calls_in, 0);  atomic_store(&s_calls_out, 0);
    atomic_store(&s_bus_in, 0);    atomic_store(&s_bus_out, 0);
    atomic_store(&s_gap, 0);
    atomic_store(&s_timeouts, 0);  atomic_store(&s_cancels, 0);
    atomic_store(&s_errors, 0);
    t_last_end_ns = 0;
}

void bfusb_stats_get(bfusb_stats *o)
{
    if (!o)
        return;
    o->bytes_in = atomic_load(&s_bytes_in);   o->bytes_out = atomic_load(&s_bytes_out);
    o->calls_in = atomic_load(&s_calls_in);   o->calls_out = atomic_load(&s_calls_out);
    o->bus_ns_in = atomic_load(&s_bus_in);    o->bus_ns_out = atomic_load(&s_bus_out);
    o->gap_ns = atomic_load(&s_gap);
    o->timeouts = atomic_load(&s_timeouts);
    o->cancels = atomic_load(&s_cancels);
    o->errors = atomic_load(&s_errors);
}

static void account(unsigned char ep, uint64_t t0, uint64_t t1, int moved, int rc)
{
    const int in = (ep & 0x80) != 0;

    if (t_last_end_ns && t0 > t_last_end_ns && t0 - t_last_end_ns < BFUSB_GAP_CAP_NS)
        atomic_fetch_add(&s_gap, t0 - t_last_end_ns);
    t_last_end_ns = t1;

    if (moved < 0)
        moved = 0;
    if (in) {
        atomic_fetch_add(&s_bytes_in, (uint64_t)moved);
        atomic_fetch_add(&s_calls_in, 1);
        atomic_fetch_add(&s_bus_in, t1 - t0);
    } else {
        atomic_fetch_add(&s_bytes_out, (uint64_t)moved);
        atomic_fetch_add(&s_calls_out, 1);
        atomic_fetch_add(&s_bus_out, t1 - t0);
    }
    if (rc == LIBUSB_ERROR_TIMEOUT)
        atomic_fetch_add(&s_timeouts, 1);
    else if (rc == LIBUSB_ERROR_INTERRUPTED)
        atomic_fetch_add(&s_cancels, 1);
    else if (rc != 0)
        atomic_fetch_add(&s_errors, 1);
}

static double mib(uint64_t bytes) { return (double)bytes / (1024.0 * 1024.0); }

static double mibps(uint64_t bytes, uint64_t ns)
{
    return ns ? mib(bytes) / ((double)ns / 1e9) : 0.0;
}

int bfusb_stats_format(char *buf, size_t size)
{
    bfusb_stats s;
    int n = 0;

    if (!buf || !size)
        return 0;
    buf[0] = '\0';
    bfusb_stats_get(&s);
    if (!s.bytes_in && !s.bytes_out)
        return 0;

    n += snprintf(buf + n, size - (size_t)n, "USB:");
    if (s.bytes_out && (size_t)n < size)
        n += snprintf(buf + n, size - (size_t)n, " out %.1f MiB @ %.1f MiB/s",
                      mib(s.bytes_out), mibps(s.bytes_out, s.bus_ns_out));
    if (s.bytes_in && (size_t)n < size)
        n += snprintf(buf + n, size - (size_t)n, "%s in %.1f MiB @ %.1f MiB/s",
                      s.bytes_out ? "," : "", mib(s.bytes_in), mibps(s.bytes_in, s.bus_ns_in));

    const uint64_t bus = s.bus_ns_in + s.bus_ns_out;
    const uint64_t active = bus + s.gap_ns;
    if (active && (size_t)n < size) {
        int busy = (int)((bus * 100ull + active / 2) / active);
        n += snprintf(buf + n, size - (size_t)n, "; bus busy %d%%, host gaps %d%%", busy, 100 - busy);
    }
    if ((s.timeouts || s.errors || s.cancels) && (size_t)n < size)
        n += snprintf(buf + n, size - (size_t)n, "; %llu timeouts, %llu errors, %llu cancels",
                      (unsigned long long)s.timeouts, (unsigned long long)s.errors,
                      (unsigned long long)s.cancels);
    return n >= (int)size ? (int)size - 1 : n;
}

/* ---- link speed ---------------------------------------------------------- */

const char *bfusb_link_name(int max_packet)
{
    if (max_packet > 0 && max_packet <= 64)
        return "Full-Speed (12 Mbit/s)";
    if (max_packet == 512)
        return "High-Speed (480 Mbit/s)";
    if (max_packet >= 1024)
        return "SuperSpeed (5 Gbit/s+)";
    return "unknown";
}

int bfusb_link_is_slow(int max_packet)
{
    return max_packet > 0 && max_packet <= 64;
}

/* ---- the transfer -------------------------------------------------------- */

static volatile unsigned int g_spin_us = 0;

void bfusb_set_spin_us(unsigned int us)
{
    g_spin_us = us;
}

static void LIBUSB_CALL on_done(struct libusb_transfer *t)
{
    __atomic_store_n((int *)t->user_data, 1, __ATOMIC_RELEASE);
}

static int status_to_rc(enum libusb_transfer_status st)
{
    switch (st) {
    case LIBUSB_TRANSFER_COMPLETED: return LIBUSB_SUCCESS;
    case LIBUSB_TRANSFER_TIMED_OUT: return LIBUSB_ERROR_TIMEOUT;
    case LIBUSB_TRANSFER_STALL:     return LIBUSB_ERROR_PIPE;
    case LIBUSB_TRANSFER_NO_DEVICE: return LIBUSB_ERROR_NO_DEVICE;
    case LIBUSB_TRANSFER_OVERFLOW:  return LIBUSB_ERROR_OVERFLOW;
    case LIBUSB_TRANSFER_CANCELLED: return LIBUSB_ERROR_INTERRUPTED; /* refined by caller */
    default:                        return LIBUSB_ERROR_IO;
    }
}

/* Choose poll timeout: full spin window, brief micro-spin, or normal poll. */
static void wait_tv(struct timeval *tv, uint64_t start_ns, int aborting)
{
    tv->tv_sec = 0;
    if (aborting) {
        tv->tv_usec = BFUSB_POLL_MS * 1000;
        return;
    }
    const uint64_t age_us = (now_ns() - start_ns) / 1000ull;
    const unsigned int spin_us = g_spin_us;
    if (spin_us && age_us < (uint64_t)spin_us) {
        tv->tv_usec = 0;                         /* Experimental continuous spin */
        return;
    }
    if (age_us < (uint64_t)BFUSB_MICRO_SPIN_US) {
        tv->tv_usec = 0;                         /* free micro-spin: ~1.5 ms */
        return;
    }
    tv->tv_usec = BFUSB_POLL_MS * 1000;
}

static int bulk_async(struct libusb_context *ctx, struct libusb_device_handle *h,
                      unsigned char ep, unsigned char *data, int length,
                      int *moved, unsigned int timeout_ms, const bfusb_cancel *cancel)
{
    struct libusb_transfer *t = libusb_alloc_transfer(0);
    if (!t)
        return LIBUSB_ERROR_NO_MEM;

    int done = 0;
    /* timeout 0 = none: the deadline below is enforced here so that expiry can
     * report the partial byte count and use the same cancel path. */
    libusb_fill_bulk_transfer(t, h, ep, data, length, on_done, &done, 0);

    int rc = libusb_submit_transfer(t);
    if (rc != 0) {
        libusb_free_transfer(t);
        *moved = 0;
        return rc;
    }

    const uint64_t start = now_ns();
    const uint64_t deadline = timeout_ms ? start + (uint64_t)timeout_ms * 1000000ull : 0;
    int aborting = 0;
    int cause = 0; /* LIBUSB_ERROR_TIMEOUT or LIBUSB_ERROR_INTERRUPTED once aborting */

    while (!__atomic_load_n(&done, __ATOMIC_ACQUIRE)) {
        struct timeval tv;
        wait_tv(&tv, start, aborting);
        int er = libusb_handle_events_timeout_completed(ctx, &tv, &done);

        if (aborting || __atomic_load_n(&done, __ATOMIC_ACQUIRE))
            continue;

        if (er != 0 && er != LIBUSB_ERROR_TIMEOUT && er != LIBUSB_ERROR_INTERRUPTED) {
            cause = er;                          /* event loop is broken */
            aborting = 1;
        } else if (cancel && cancel->fn && cancel->fn(cancel->user)) {
            cause = LIBUSB_ERROR_INTERRUPTED;
            aborting = 1;
        } else if (deadline && now_ns() >= deadline) {
            cause = LIBUSB_ERROR_TIMEOUT;
            aborting = 1;
        }
        if (aborting)
            (void)libusb_cancel_transfer(t);     /* NOT_FOUND = already finished: fine */
    }

    *moved = t->actual_length;
    enum libusb_transfer_status st = t->status;
    libusb_free_transfer(t);

    if (st == LIBUSB_TRANSFER_CANCELLED)
        return cause ? cause : LIBUSB_ERROR_INTERRUPTED;
    /* Completed (or failed on its own) just before we aborted: report what the
     * hardware said, not our cancel. */
    return status_to_rc(st);
}

/*
 * OUT-only double buffer: keep BFUSB_PIPE_DEPTH URBs in flight so the
 * controller streams instead of idling between sequential submit/reap.
 * IN stays on bulk_async — short packets mark USB frame ends and must not
 * race a second in-flight URB into the next protocol message.
 */
static int bulk_async_pipeline_out(struct libusb_context *ctx, struct libusb_device_handle *h,
                                   unsigned char ep, unsigned char *data, int length,
                                   int *moved, unsigned int timeout_ms, const bfusb_cancel *cancel)
{
    struct slot {
        struct libusb_transfer *t;
        int done;
        int active;
    } slots[BFUSB_PIPE_DEPTH];
    memset(slots, 0, sizeof(slots));

    int got = 0;
    int next_off = 0;
    int aborting = 0;
    int cause = 0;
    int hard_rc = 0;
    const uint64_t start = now_ns();
    const uint64_t deadline = timeout_ms ? start + (uint64_t)timeout_ms * 1000000ull : 0;

    for (int i = 0; i < BFUSB_PIPE_DEPTH; i++) {
        slots[i].t = libusb_alloc_transfer(0);
        if (!slots[i].t) {
            hard_rc = LIBUSB_ERROR_NO_MEM;
            aborting = 1;
            break;
        }
    }

    for (;;) {
        if (!aborting) {
            for (int i = 0; i < BFUSB_PIPE_DEPTH; i++) {
                if (slots[i].active || next_off >= length)
                    continue;
                int chunk = length - next_off;
                if (chunk > BFUSB_PIPE_CHUNK)
                    chunk = BFUSB_PIPE_CHUNK;
                slots[i].done = 0;
                libusb_fill_bulk_transfer(slots[i].t, h, ep, data + next_off, chunk,
                                          on_done, &slots[i].done, 0);
                int src = libusb_submit_transfer(slots[i].t);
                if (src != 0) {
                    hard_rc = src;
                    aborting = 1;
                    break;
                }
                slots[i].active = 1;
                next_off += chunk;
            }
        }

        int any_active = 0;
        for (int i = 0; i < BFUSB_PIPE_DEPTH; i++) {
            if (slots[i].active)
                any_active = 1;
        }
        if (!any_active)
            break;

        struct timeval tv;
        wait_tv(&tv, start, aborting);
        int dummy = 0;
        int er = libusb_handle_events_timeout_completed(ctx, &tv, &dummy);
        if (er != 0 && er != LIBUSB_ERROR_TIMEOUT && er != LIBUSB_ERROR_INTERRUPTED) {
            hard_rc = er;
            aborting = 1;
        } else if (!aborting && cancel && cancel->fn && cancel->fn(cancel->user)) {
            cause = LIBUSB_ERROR_INTERRUPTED;
            aborting = 1;
        } else if (!aborting && deadline && now_ns() >= deadline) {
            cause = LIBUSB_ERROR_TIMEOUT;
            aborting = 1;
        }
        if (aborting) {
            for (int i = 0; i < BFUSB_PIPE_DEPTH; i++) {
                if (slots[i].active)
                    (void)libusb_cancel_transfer(slots[i].t);
            }
        }

        for (int i = 0; i < BFUSB_PIPE_DEPTH; i++) {
            if (!slots[i].active || !__atomic_load_n(&slots[i].done, __ATOMIC_ACQUIRE))
                continue;
            slots[i].active = 0;
            enum libusb_transfer_status st = slots[i].t->status;
            int n = slots[i].t->actual_length;
            if (n > 0)
                got += n;
            if (st == LIBUSB_TRANSFER_COMPLETED) {
                /* ok */
            } else if (st == LIBUSB_TRANSFER_CANCELLED) {
                if (!cause && !hard_rc)
                    cause = LIBUSB_ERROR_INTERRUPTED;
            } else {
                if (!hard_rc)
                    hard_rc = status_to_rc(st);
                aborting = 1;
                for (int j = 0; j < BFUSB_PIPE_DEPTH; j++) {
                    if (slots[j].active)
                        (void)libusb_cancel_transfer(slots[j].t);
                }
            }
        }
    }

    for (int i = 0; i < BFUSB_PIPE_DEPTH; i++) {
        if (slots[i].t)
            libusb_free_transfer(slots[i].t);
    }

    *moved = got;
    if (hard_rc)
        return hard_rc;
    if (cause)
        return cause;
    return 0;
}

int bfusb_bulk(struct libusb_context *ctx, struct libusb_device_handle *h,
               unsigned char endpoint, unsigned char *data, int length,
               int *transferred, unsigned int timeout_ms, const bfusb_cancel *cancel)
{
    int moved = 0;
    const uint64_t t0 = now_ns();
    int rc;

    if (cancel && cancel->fn) {
        if (cancel->fn(cancel->user)) {          /* already cancelled: don't even submit */
            if (transferred)
                *transferred = 0;
            return LIBUSB_ERROR_INTERRUPTED;
        }
        /* Pipeline large OUT transfers (writes). IN stays single-URB so a
         * short packet cannot race a second buffer into the next frame. */
        if (length >= BFUSB_PIPE_MIN && (endpoint & 0x80) == 0)
            rc = bulk_async_pipeline_out(ctx, h, endpoint, data, length, &moved,
                                         timeout_ms, cancel);
        else
            rc = bulk_async(ctx, h, endpoint, data, length, &moved, timeout_ms, cancel);
    } else {
        rc = libusb_bulk_transfer(h, endpoint, data, length, &moved, timeout_ms);
    }

    account(endpoint, t0, now_ns(), moved, rc);
    if (transferred)
        *transferred = moved;
    return rc;
}
