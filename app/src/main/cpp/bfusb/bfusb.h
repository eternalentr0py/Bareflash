/*
 * bfusb: the USB helper shared by every Bareflash engine (MTK, QDL, Brokkr).
 *
 * It sits directly on libusb and adds three things the engines each needed
 * and none had:
 *
 *   1. bfusb_bulk(): a bulk transfer that can be CANCELLED while in flight.
 *      libusb_bulk_transfer() blocks until completion or its timeout, so a
 *      stalled device made "Stop" wait out the whole transfer budget (minutes
 *      for a large MTK write). With a cancel callback supplied, the transfer
 *      is submitted asynchronously and the caller thread pumps libusb events
 *      in short slices, cancelling the URBs as soon as the callback fires or
 *      the deadline passes. Without a callback it is a plain sync transfer.
 *      Either way the return value and *transferred follow
 *      libusb_bulk_transfer() semantics exactly (partial counts included), so
 *      call sites keep their existing retry / short-read logic.
 *
 *   2. Telemetry: bytes, time on the bus and host-side gaps between
 *      transfers. This answers "is the bus slow or is something else slow?"
 *      with numbers instead of guesses.
 *
 *   3. bfusb_link_name(): negotiated link speed from wMaxPacketSize. A
 *      Full-Speed link (bad OTG adapter, cable or hub) is the commonest
 *      reason for ~1 MB/s flashing and is invisible otherwise.
 *
 * Pure C, no JNI, no Android headers: it is unit-tested on the host against a
 * mock libusb (host/bfusb_test).
 */
#ifndef BFUSB_H
#define BFUSB_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

struct libusb_context;
struct libusb_device_handle;

/* Return non-zero to abort the transfer in flight. Called from the thread
 * running bfusb_bulk(), roughly every BFUSB_POLL_MS. Must be cheap. */
typedef int (*bfusb_cancel_fn)(void *user);

typedef struct bfusb_cancel {
    bfusb_cancel_fn fn;
    void *user;
} bfusb_cancel;

/* How often an in-flight cancellable transfer re-checks cancel + deadline.
 * 2 ms keeps cancel responsive without the old 20 ms host-gap floor. */
#define BFUSB_POLL_MS 2

/* When busy-wait is off, still poll with a zero timeout for this many
 * microseconds after submit so a transfer that completes in under ~1 ms
 * does not pay a full BFUSB_POLL_MS sleep. Far cheaper than a 30–100 ms
 * continuous spin (Experimental "Busy-wait for USB"). */
#define BFUSB_MICRO_SPIN_US 1500u

/* Double-buffer pipeline: keep this many URBs in flight for large bulk
 * transfers so the bus does not idle between sequential submit/reap
 * pairs. Chunk size matches Android USBFS URB ceiling. */
#define BFUSB_PIPE_DEPTH 2
#define BFUSB_PIPE_CHUNK (16 * 1024)
#define BFUSB_PIPE_MIN   (2 * BFUSB_PIPE_CHUNK)

/* Busy-wait window. While a transfer has been outstanding for less than this
 * long, the wait loop polls with a zero timeout instead of sleeping in poll().
 * A sleeping thread lets the core idle and the governor drop the clock, and
 * every wake-up then pays idle-exit plus ramp-up latency (about a millisecond
 * per transfer on big.LITTLE phones, ~8 transfers per 64 KiB MediaTek frame).
 * 0 disables continuous spinning (micro-spin still applies). */
void bfusb_set_spin_us(unsigned int us);
/*
 * Bulk transfer with libusb_bulk_transfer() semantics:
 *   returns 0 on success, otherwise a negative LIBUSB_ERROR_* code
 *   (LIBUSB_ERROR_TIMEOUT when timeout_ms elapsed, LIBUSB_ERROR_INTERRUPTED
 *   when cancel fired). *transferred (may be NULL) always receives the bytes
 *   that did move, including on timeout / cancel / error.
 *   timeout_ms == 0 means no timeout.
 *   ctx must be the libusb context the handle belongs to (NULL = default
 *   context). cancel may be NULL.
 */
int bfusb_bulk(struct libusb_context *ctx, struct libusb_device_handle *h,
               unsigned char endpoint, unsigned char *data, int length,
               int *transferred, unsigned int timeout_ms,
               const bfusb_cancel *cancel);

/* ---- telemetry ---------------------------------------------------------- */

typedef struct bfusb_stats {
    uint64_t bytes_in, bytes_out;
    uint64_t calls_in, calls_out;
    uint64_t bus_ns_in, bus_ns_out;  /* time spent inside bfusb_bulk()        */
    uint64_t gap_ns;                 /* host time between consecutive calls on
                                        the same thread, gaps < BFUSB_GAP_CAP */
    uint64_t timeouts, cancels, errors;
} bfusb_stats;

/* Gaps longer than this are idle time (waiting on the user / device boot),
 * not host latency, and are ignored. */
#define BFUSB_GAP_CAP_NS (500ull * 1000 * 1000)

void bfusb_stats_reset(void);
void bfusb_stats_get(bfusb_stats *out);

/* One human-readable line, e.g.
 *   "USB: out 408.0 MiB @ 31.2 MiB/s, in 12.4 MiB @ 19.0 MiB/s; bus busy 82%,
 *    host gaps 18%; 2 timeouts"
 * Returns the length written (like snprintf). Writes "" if nothing moved. */
int bfusb_stats_format(char *buf, size_t size);

/* ---- link speed --------------------------------------------------------- */

/* "Full-Speed (12 Mbit/s)", "High-Speed (480 Mbit/s)", "SuperSpeed (5 Gbit/s+)"
 * or "unknown". max_packet is a bulk endpoint's wMaxPacketSize. */
const char *bfusb_link_name(int max_packet);

/* 1 when the link is Full-Speed or slower, i.e. certainly slow. */
int bfusb_link_is_slow(int max_packet);

#ifdef __cplusplus
}
#endif

#endif /* BFUSB_H */
