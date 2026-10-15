/*
 * speed_sim: libusb stand-in + MediaTek xflash DA model. See sim_usb.h.
 *
 * Everything here runs on the caller's thread (the real transfer thread in the
 * app is single-threaded as well): completions are computed from timestamps and
 * delivered from libusb_handle_events_timeout_completed(), exactly like libusb
 * delivers them from its event loop. There is no device thread, so a DA reply
 * is only produced once the host has reaped the OUT URB that triggered it, and
 * its timestamps are relative to that OUT URB's completion time rather than to
 * whenever the host happened to get around to reaping it.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "libusb.h"
#include "sim_usb.h"

#define MAGIC 0xFEEEEEEFu

/* ------------------------------------------------------------------ state */

static struct sim_cfg g_cfg;
static struct sim_stats g_st;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_inited;

static double now_s(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + (double)ts.tv_nsec / 1e9;
}

static void sleep_until(double t)
{
    struct timespec ts;
    ts.tv_sec = (time_t)t;
    ts.tv_nsec = (long)((t - (double)ts.tv_sec) * 1e9);
    while (clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &ts, NULL) == EINTR)
        ;
}

void sim_default_cfg(struct sim_cfg *c)
{
    memset(c, 0, sizeof(*c));
    c->in_rate_bps = 38e6;
    c->out_rate_bps = 26e6;
    c->xfer_lat_us = 60;
    c->ev_wake_us = 40;
    c->da_turn_us = 60;
    c->da_prep_us = 100;
    c->da_prog_us_per_mib = 0;
    c->zlp_after_aligned = 1;
    c->status_after_ack = 1;
    c->max_packet = 512;
    c->read_plen = 0x10000;
    c->write_plen = 0x200000;
    c->strict_urb = 1;
}

void sim_set_cfg(const struct sim_cfg *c) { g_cfg = *c; g_inited = 1; }
void sim_get_cfg(struct sim_cfg *c) { *c = g_cfg; }
void sim_get_stats(struct sim_stats *s) { *s = g_st; }

/* --------------------------------------------------------- flash pattern */

#define PAT_LEN ((size_t)(4u << 20) + 4093u)
static unsigned char *g_pat;

static void pat_init(void)
{
    if (g_pat)
        return;
    g_pat = malloc(PAT_LEN);
    uint64_t x = 0x9E3779B97F4A7C15ull;
    for (size_t i = 0; i < PAT_LEN; i++) {
        x ^= x >> 12; x ^= x << 25; x ^= x >> 27;
        g_pat[i] = (unsigned char)((x * 0x2545F4914F6CDD1Dull) >> 56);
    }
}

void sim_pattern_fill(uint64_t off, unsigned char *dst, size_t n)
{
    pat_init();
    size_t p = (size_t)(off % PAT_LEN);
    while (n) {
        size_t k = PAT_LEN - p;
        if (k > n) k = n;
        memcpy(dst, g_pat + p, k);
        dst += k; n -= k; p = 0;
    }
}

/* ------------------------------------------------------- device -> host q */

struct inmsg {
    struct inmsg *next;
    int kind;                   /* 0 bytes, 1 flash pattern, 2 ZLP */
    int len, off;
    double avail;
    uint64_t foff;
    unsigned char b[16];
};

static struct inmsg *g_inq_head, *g_inq_tail;

static void inq_push(struct inmsg *m)
{
    m->next = NULL;
    if (g_inq_tail) g_inq_tail->next = m; else g_inq_head = m;
    g_inq_tail = m;
}

static void enq_bytes(const void *p, int n, double avail)
{
    struct inmsg *m = calloc(1, sizeof(*m));
    m->kind = 0; m->len = n; m->avail = avail;
    memcpy(m->b, p, (size_t)n);
    inq_push(m);
}

static void enq_pattern(uint64_t foff, int n, double avail)
{
    struct inmsg *m = calloc(1, sizeof(*m));
    m->kind = 1; m->len = n; m->foff = foff; m->avail = avail;
    inq_push(m);
}

static void enq_zlp(double avail)
{
    struct inmsg *m = calloc(1, sizeof(*m));
    m->kind = 2; m->len = 0; m->avail = avail;
    inq_push(m);
    g_st.zlps++;
}

static void le32(unsigned char *d, uint32_t v)
{
    d[0] = (unsigned char)v; d[1] = (unsigned char)(v >> 8);
    d[2] = (unsigned char)(v >> 16); d[3] = (unsigned char)(v >> 24);
}

static uint32_t rd32(const unsigned char *d)
{
    return (uint32_t)d[0] | ((uint32_t)d[1] << 8) | ((uint32_t)d[2] << 16) | ((uint32_t)d[3] << 24);
}

/* A frame is a 12 byte header and its payload, as two separate USB transfers
 * (the DA does two usb_write() calls), so the host sees two short packets. */
static void enq_frame(const void *payload, int n, double t)
{
    unsigned char h[12];
    le32(h, MAGIC); le32(h + 4, 1); le32(h + 8, (uint32_t)n);
    enq_bytes(h, 12, t);
    if (n > 0)
        enq_bytes(payload, n, t);
}

static void enq_status(uint32_t code, double t)
{
    unsigned char p[4];
    le32(p, code);
    enq_frame(p, 4, t);
}

/* ------------------------------------------------------------- the DA */

enum {
    DA_IDLE, DA_DEVCTRL_SUB, DA_READ_PARAM, DA_WRITE_PARAM, DA_READING, DA_WRITING
};

#define CMD_WRITE_DATA 0x010004u
#define CMD_READ_DATA 0x010005u
#define CMD_DEVICE_CTRL 0x010009u
#define CMD_GET_PACKET_LENGTH 0x040007u

static int g_da_state;
/* OUT stream parser */
static int g_rx_hdr_got;
static unsigned char g_rx_hdr[12];
static int g_rx_in_payload;
static uint32_t g_rx_len, g_rx_got;
static unsigned char g_rx_small[128];
/* read state */
static uint64_t g_rd_off, g_rd_left;
/* write state */
static uint64_t g_wr_total, g_wr_left;
static int g_wr_sub;                 /* 0: [0] 1: [checksum] 2: data */
static uint32_t g_wr_cksum;
static unsigned char *g_wr_buf;
static size_t g_wr_cap, g_wr_pos;
struct chunk { size_t off, len; uint32_t cksum; };
static struct chunk *g_chunks;
static size_t g_nchunks, g_chunk_cap;
static size_t g_chunk_start;

const unsigned char *sim_write_buffer(size_t *len) { if (len) *len = g_wr_pos; return g_wr_buf; }

/* Verifies every WRITE chunk's sum16 against what actually arrived. */
int sim_verify_writes(void)
{
    int bad = 0;
    for (size_t i = 0; i < g_nchunks; i++) {
        uint32_t s = 0;
        const unsigned char *p = g_wr_buf + g_chunks[i].off;
        for (size_t k = 0; k < g_chunks[i].len; k++) s += p[k];
        if ((s & 0xFFFF) != (g_chunks[i].cksum & 0xFFFF)) { bad++; g_st.checksum_errors++; }
    }
    return bad;
}

static void da_send_read_frame(double t)
{
    uint32_t n = (uint32_t)(g_rd_left < g_cfg.read_plen ? g_rd_left : g_cfg.read_plen);
    double th = t + g_cfg.da_turn_us / 1e6;
    unsigned char h[12];
    le32(h, MAGIC); le32(h + 4, 1); le32(h + 8, n);
    enq_bytes(h, 12, th);
    double tp = th + g_cfg.da_prep_us / 1e6;
    enq_pattern(g_rd_off, (int)n, tp);
    if (g_cfg.zlp_after_aligned && n % (uint32_t)g_cfg.max_packet == 0)
        enq_zlp(tp);
    g_rd_off += n;
    g_rd_left -= n;
    g_st.frames_read++;
}

static void da_frame(uint32_t type, uint32_t len, const unsigned char *pl, double t)
{
    (void)type;
    double tr = t + g_cfg.da_turn_us / 1e6;

    if (g_da_state == DA_READING) {
        if (len == 4) {                         /* ACK of the previous data frame */
            if (g_cfg.status_after_ack || g_rd_left == 0)
                enq_status(0, tr);
            if (g_rd_left > 0)
                da_send_read_frame(t);
            else
                g_da_state = DA_IDLE;
            return;
        }
        g_st.proto_errors++;
        return;
    }

    if (g_da_state == DA_WRITING) {
        if (g_wr_left > 0) {
            if (g_wr_sub == 0) { g_wr_sub = 1; return; }
            if (g_wr_sub == 1) { g_wr_cksum = len >= 4 ? rd32(pl) : 0; g_wr_sub = 2; return; }
        }
        /* data frame complete: handled by the payload path below */
    }

    if (len == 4 && g_da_state != DA_WRITING) {
        uint32_t cmd = rd32(pl);
        switch (g_da_state) {
        case DA_DEVCTRL_SUB:
            enq_status(0, tr);
            if (cmd == CMD_GET_PACKET_LENGTH) {
                unsigned char d[8];
                le32(d, g_cfg.write_plen); le32(d + 4, g_cfg.read_plen);
                enq_frame(d, 8, tr);
                enq_status(0, tr);
            }
            g_da_state = DA_IDLE;
            return;
        default:
            break;
        }
        enq_status(0, tr);
        if (cmd == CMD_DEVICE_CTRL) g_da_state = DA_DEVCTRL_SUB;
        else if (cmd == CMD_READ_DATA) g_da_state = DA_READ_PARAM;
        else if (cmd == CMD_WRITE_DATA) g_da_state = DA_WRITE_PARAM;
        return;
    }

    if (g_da_state == DA_READ_PARAM) {
        uint64_t addr = 0, size = 0;
        if (len >= 24) {
            addr = (uint64_t)rd32(pl + 8) | ((uint64_t)rd32(pl + 12) << 32);
            size = (uint64_t)rd32(pl + 16) | ((uint64_t)rd32(pl + 20) << 32);
        }
        enq_status(0, tr);
        g_rd_off = addr; g_rd_left = size;
        g_da_state = DA_READING;
        da_send_read_frame(t);
        return;
    }

    if (g_da_state == DA_WRITE_PARAM) {
        uint64_t size = 0;
        if (len >= 24)
            size = (uint64_t)rd32(pl + 16) | ((uint64_t)rd32(pl + 20) << 32);
        enq_status(0, tr);
        g_wr_total = g_wr_left = size;
        g_wr_sub = 0;
        g_da_state = DA_WRITING;
        return;
    }

    if (g_da_state == DA_WRITING) {              /* a data frame */
        if (g_nchunks == g_chunk_cap) {
            g_chunk_cap = g_chunk_cap ? g_chunk_cap * 2 : 64;
            g_chunks = realloc(g_chunks, g_chunk_cap * sizeof(*g_chunks));
        }
        g_chunks[g_nchunks].off = g_chunk_start;
        g_chunks[g_nchunks].len = len;
        g_chunks[g_nchunks].cksum = g_wr_cksum;
        g_nchunks++;
        g_wr_left = g_wr_left >= len ? g_wr_left - len : 0;
        g_wr_sub = 0;
        double prog = g_cfg.da_prog_us_per_mib * ((double)len / 1048576.0) / 1e6;
        enq_status(0, tr + prog);
        if (g_wr_left == 0) {
            enq_status(0, tr + prog);             /* the final status() in writeflash */
            g_da_state = DA_IDLE;
        }
        return;
    }

    enq_status(0, tr);
}

/* One completed OUT URB reaches the DA. The DA reads a 12 byte header, then
 * exactly `len` payload bytes; whatever a URB carries beyond that is lost, which
 * is how a real DA reacts to a 16 byte "header+payload" OUT. */
static void da_rx(const unsigned char *d, int n, double t)
{
    int pos = 0;
    while (pos < n) {
        if (!g_rx_in_payload) {
            int need = 12 - g_rx_hdr_got;
            int take = n - pos < need ? n - pos : need;
            memcpy(g_rx_hdr + g_rx_hdr_got, d + pos, (size_t)take);
            g_rx_hdr_got += take; pos += take;
            if (g_rx_hdr_got < 12)
                continue;
            if (g_cfg.strict_urb && pos < n) {   /* extra bytes after a 12 byte header read */
                g_st.proto_errors++;
                return;
            }
            g_rx_hdr_got = 0;
            if (rd32(g_rx_hdr) != MAGIC) { g_st.proto_errors++; return; }
            g_rx_len = rd32(g_rx_hdr + 8);
            g_rx_got = 0;
            g_rx_in_payload = 1;
            if (g_rx_len == 0) { g_rx_in_payload = 0; da_frame(rd32(g_rx_hdr + 4), 0, g_rx_small, t); }
            if (g_da_state == DA_WRITING && g_wr_left > 0 && g_wr_sub == 2 && g_rx_len > 0)
                g_chunk_start = g_wr_pos;
            continue;
        }
        uint32_t need = g_rx_len - g_rx_got;
        uint32_t take = (uint32_t)(n - pos) < need ? (uint32_t)(n - pos) : need;
        int is_data = g_da_state == DA_WRITING && g_wr_left > 0 && g_wr_sub == 2;
        if (is_data) {
            if (g_wr_pos + take <= g_wr_cap)
                memcpy(g_wr_buf + g_wr_pos, d + pos, take);
            g_wr_pos += take;
            g_st.write_bytes += take;
        } else if (g_rx_got + take <= sizeof(g_rx_small)) {
            memcpy(g_rx_small + g_rx_got, d + pos, take);
        }
        g_rx_got += take; pos += (int)take;
        if (g_rx_got < g_rx_len)
            continue;
        g_rx_in_payload = 0;
        da_frame(rd32(g_rx_hdr + 4), g_rx_len, g_rx_small, t);
        if (g_cfg.strict_urb && pos < n) {       /* URB carried more than the DA asked for */
            g_st.proto_errors++;
            return;
        }
    }
}

/* ------------------------------------------------------------- transfers */

struct pend {
    struct libusb_transfer *t;
    double submit_t, due, deadline;
    int dir_in, resolved, cancelled, rsv_len, rsv_status;
    struct pend *next;
};

static struct pend *g_head;
static double g_bus_free;

void sim_reset(size_t write_capacity)
{
    pthread_mutex_lock(&g_lock);
    if (!g_inited) { sim_default_cfg(&g_cfg); g_inited = 1; }
    pat_init();
    while (g_head) { struct pend *p = g_head; g_head = p->next; free(p); }
    while (g_inq_head) { struct inmsg *m = g_inq_head; g_inq_head = m->next; free(m); }
    g_inq_tail = NULL;
    memset(&g_st, 0, sizeof(g_st));
    g_bus_free = 0;
    g_da_state = DA_IDLE;
    g_rx_hdr_got = 0; g_rx_in_payload = 0; g_rx_len = g_rx_got = 0;
    g_rd_off = g_rd_left = 0;
    g_wr_total = g_wr_left = 0; g_wr_sub = 0; g_wr_pos = 0; g_nchunks = 0; g_chunk_start = 0;
    free(g_wr_buf);
    g_wr_buf = write_capacity ? malloc(write_capacity) : NULL;
    g_wr_cap = write_capacity;
    pthread_mutex_unlock(&g_lock);
}

struct libusb_transfer *libusb_alloc_transfer(int iso)
{
    (void)iso;
    return calloc(1, sizeof(struct libusb_transfer));
}

void libusb_free_transfer(struct libusb_transfer *t) { free(t); }

int libusb_submit_transfer(struct libusb_transfer *t)
{
    struct pend *p = calloc(1, sizeof(*p));
    p->t = t;
    p->submit_t = now_s();
    p->dir_in = (t->endpoint & 0x80) != 0;
    p->due = -1;
    p->deadline = t->timeout ? p->submit_t + t->timeout / 1000.0 : 0;
    pthread_mutex_lock(&g_lock);
    if (g_cfg.stall) {
        p->resolved = 0;
    } else if (!p->dir_in) {
        double t0 = p->submit_t + g_cfg.xfer_lat_us / 1e6;
        if (g_bus_free > t0) t0 = g_bus_free;
        p->due = t0 + (double)t->length / g_cfg.out_rate_bps;
        g_bus_free = p->due;
        p->resolved = 1;
    }
    struct pend **pp = &g_head;
    while (*pp) pp = &(*pp)->next;
    *pp = p;
    g_st.submits++;
    pthread_mutex_unlock(&g_lock);
    return 0;
}

int libusb_cancel_transfer(struct libusb_transfer *t)
{
    int found = LIBUSB_ERROR_NOT_FOUND;
    pthread_mutex_lock(&g_lock);
    for (struct pend *p = g_head; p; p = p->next)
        if (p->t == t) { p->cancelled = 1; found = 0; }
    pthread_mutex_unlock(&g_lock);
    return found;
}

/* Try to satisfy an IN URB from the DA's queue. Consumption is committed only
 * when the URB can complete (full, short packet, ZLP or overflow) before its
 * deadline, so a URB waiting for data that does not exist yet leaves the queue
 * untouched. */
static int resolve_in(struct pend *p)
{
    struct libusb_transfer *t = p->t;
    const int pkt = g_cfg.max_packet;
    const int need = t->length;
    double tcur = p->submit_t + g_cfg.xfer_lat_us / 1e6;
    if (g_bus_free > tcur) tcur = g_bus_free;

    /* dry run */
    struct inmsg *m = g_inq_head;
    int moff = m ? m->off : 0;
    int got = 0, terminated = 0, status = LIBUSB_TRANSFER_COMPLETED;
    int full = 0;                  /* whole messages consumed */

    while (!terminated && got < need) {
        if (!m)
            return 0;                                      /* needs data the DA has not produced */
        if (m->kind == 2) {                                /* ZLP ends the transfer */
            if (m->avail > tcur) tcur = m->avail;
            full++; m = m->next; moff = 0; terminated = 1;
            break;
        }
        int rem = m->len - moff;
        int space = need - got;
        int first = rem < pkt ? rem : pkt;
        if (m->avail > tcur) tcur = m->avail;
        if (space < first) {                               /* next packet does not fit: overflow */
            moff += first;
            if (moff >= m->len) { full++; m = m->next; moff = 0; }
            status = LIBUSB_TRANSFER_OVERFLOW;
            terminated = 1;
            break;
        }
        int take = rem < space ? rem : space;
        if (take < rem) take -= take % pkt;
        tcur += (double)take / g_cfg.in_rate_bps;
        got += take; moff += take;
        if (moff >= m->len) {
            int short_pkt = (m->len % pkt) != 0;
            full++; m = m->next; moff = 0;
            if (short_pkt) terminated = 1;
        }
    }
    if (p->deadline && tcur > p->deadline)
        return 0;                                          /* will time out first; keep the data queued */

    /* commit: copy `got` bytes out, drop what was consumed */
    unsigned char *dst = t->buffer;
    int copied = 0;
    for (int i = 0; i < full; i++) {
        struct inmsg *c = g_inq_head;
        int take = c->len - c->off;
        if (take > got - copied) take = got - copied;
        if (take > 0 && c->kind == 0) memcpy(dst + copied, c->b + c->off, (size_t)take);
        else if (take > 0 && c->kind == 1) sim_pattern_fill(c->foff + (uint64_t)c->off, dst + copied, (size_t)take);
        if (take > 0) copied += take;
        g_inq_head = c->next;
        if (!g_inq_head) g_inq_tail = NULL;
        free(c);
    }
    if (g_inq_head && moff > g_inq_head->off) {
        struct inmsg *c = g_inq_head;
        int take = moff - c->off;
        if (take > got - copied) take = got - copied;
        if (take > 0 && c->kind == 0) memcpy(dst + copied, c->b + c->off, (size_t)take);
        else if (take > 0 && c->kind == 1) sim_pattern_fill(c->foff + (uint64_t)c->off, dst + copied, (size_t)take);
        c->off = moff;
    }
    if (status == LIBUSB_TRANSFER_OVERFLOW) { g_st.overflows++; g_st.proto_errors++; }

    p->rsv_len = got;
    p->rsv_status = status;
    p->due = tcur;
    p->resolved = 1;
    g_bus_free = tcur;
    return 1;
}

static void finish(struct pend *p, enum libusb_transfer_status st, int actual)
{
    p->t->status = st;
    p->t->actual_length = actual;
}

/* Delivers everything due at `now`. Returns the number completed. */
static int reap(double now)
{
    struct pend *done = NULL, **dtail = &done;
    int n = 0;

    pthread_mutex_lock(&g_lock);
    for (;;) {
        int progressed = 0;
        struct pend **pp = &g_head;
        while (*pp) {
            struct pend *p = *pp;
            int fin = 0;
            if (p->cancelled) {
                finish(p, LIBUSB_TRANSFER_CANCELLED, 0);
                fin = 1;
            } else if (g_cfg.stall) {
                if (p->deadline && now >= p->deadline) { finish(p, LIBUSB_TRANSFER_TIMED_OUT, 0); fin = 1; }
            } else {
                if (p->dir_in && !p->resolved)
                    resolve_in(p);
                if (p->resolved && p->due <= now && (!p->deadline || p->due <= p->deadline)) {
                    if (p->dir_in) {
                        finish(p, (enum libusb_transfer_status)p->rsv_status, p->rsv_len);
                        g_st.urbs_in++; g_st.bytes_in += (uint64_t)p->rsv_len;
                    } else {
                        finish(p, LIBUSB_TRANSFER_COMPLETED, p->t->length);
                        g_st.urbs_out++; g_st.bytes_out += (uint64_t)p->t->length;
                        da_rx(p->t->buffer, p->t->length, p->due);
                    }
                    fin = 1;
                    progressed = 1;      /* the DA may have produced data for a waiting IN URB */
                } else if (p->deadline && now >= p->deadline) {
                    finish(p, LIBUSB_TRANSFER_TIMED_OUT, 0);
                    fin = 1;
                }
            }
            if (fin) {
                *pp = p->next; p->next = NULL;
                *dtail = p; dtail = &p->next; n++;
            } else {
                pp = &p->next;
            }
        }
        if (!progressed)
            break;
    }
    pthread_mutex_unlock(&g_lock);

    while (done) {
        struct pend *p = done;
        done = p->next;
        p->t->callback(p->t);
        free(p);
    }
    return n;
}

/* Earliest time something will become deliverable, or <0 if nothing will. */
static double next_event(void)
{
    double best = -1;
    pthread_mutex_lock(&g_lock);
    for (struct pend *p = g_head; p; p = p->next) {
        double e = -1;
        if (p->cancelled) e = 0;
        else if (p->resolved) e = p->due;
        if (p->deadline && (e < 0 || p->deadline < e)) e = p->deadline;
        if (e >= 0 && (best < 0 || e < best)) best = e;
    }
    pthread_mutex_unlock(&g_lock);
    return best;
}

int libusb_handle_events_timeout_completed(libusb_context *ctx, struct timeval *tv, int *completed)
{
    (void)ctx;
    const double end = now_s() + (double)tv->tv_sec + (double)tv->tv_usec / 1e6;
    for (;;) {
        double now = now_s();
        int n = reap(now);
        if (completed && __atomic_load_n(completed, __ATOMIC_ACQUIRE))
            return 0;
        if (n > 0)
            return 0;
        if (now >= end)
            return 0;
        double ev = next_event();
        double until = end;
        if (ev >= 0) {
            ev += g_cfg.ev_wake_us / 1e6;          /* the thread wakes a little after the irq */
            if (ev < until) until = ev;
        }
        if (until > now)
            sleep_until(until);
    }
}

static void sync_cb(struct libusb_transfer *t) { __atomic_store_n((int *)t->user_data, 1, __ATOMIC_RELEASE); }

int libusb_bulk_transfer(libusb_device_handle *h, unsigned char ep, unsigned char *data,
                         int length, int *transferred, unsigned int timeout)
{
    struct libusb_transfer *t = libusb_alloc_transfer(0);
    int completed = 0;
    libusb_fill_bulk_transfer(t, h, ep, data, length, sync_cb, &completed, timeout);
    libusb_submit_transfer(t);
    while (!__atomic_load_n(&completed, __ATOMIC_ACQUIRE)) {
        struct timeval tv = { 60, 0 };
        libusb_handle_events_timeout_completed(NULL, &tv, &completed);
    }
    if (transferred) *transferred = t->actual_length;
    enum libusb_transfer_status st = t->status;
    libusb_free_transfer(t);
    switch (st) {
    case LIBUSB_TRANSFER_COMPLETED: return 0;
    case LIBUSB_TRANSFER_TIMED_OUT:
    case LIBUSB_TRANSFER_CANCELLED: return LIBUSB_ERROR_TIMEOUT;
    case LIBUSB_TRANSFER_STALL: return LIBUSB_ERROR_PIPE;
    case LIBUSB_TRANSFER_NO_DEVICE: return LIBUSB_ERROR_NO_DEVICE;
    case LIBUSB_TRANSFER_OVERFLOW: return LIBUSB_ERROR_OVERFLOW;
    default: return LIBUSB_ERROR_IO;
    }
}

/* ------------------------------------------------- the rest of the surface */

int libusb_init_context(libusb_context **ctx, const struct libusb_init_option o[], int n)
{ (void)o; (void)n; if (ctx) *ctx = (libusb_context *)0x10; return 0; }
void libusb_interrupt_event_handler(libusb_context *ctx) { (void)ctx; }
int libusb_init(libusb_context **ctx) { if (ctx) *ctx = (libusb_context *)0x10; return 0; }
void libusb_exit(libusb_context *ctx) { (void)ctx; }
int libusb_set_option(libusb_context *ctx, enum libusb_option o, ...) { (void)ctx; (void)o; return 0; }
int libusb_wrap_sys_device(libusb_context *ctx, intptr_t fd, libusb_device_handle **h)
{ (void)ctx; (void)fd; *h = (libusb_device_handle *)0x20; return 0; }
int libusb_claim_interface(libusb_device_handle *h, int i) { (void)h; (void)i; return 0; }
int libusb_release_interface(libusb_device_handle *h, int i) { (void)h; (void)i; return 0; }
int libusb_set_interface_alt_setting(libusb_device_handle *h, int i, int a) { (void)h; (void)i; (void)a; return 0; }
int libusb_set_auto_detach_kernel_driver(libusb_device_handle *h, int e) { (void)h; (void)e; return 0; }
int libusb_kernel_driver_active(libusb_device_handle *h, int i) { (void)h; (void)i; return 0; }
int libusb_detach_kernel_driver(libusb_device_handle *h, int i) { (void)h; (void)i; return 0; }
void libusb_close(libusb_device_handle *h) { (void)h; }
int libusb_clear_halt(libusb_device_handle *h, unsigned char e) { (void)h; (void)e; return 0; }
int libusb_control_transfer(libusb_device_handle *h, uint8_t rt, uint8_t r, uint16_t v, uint16_t i,
                            unsigned char *d, uint16_t l, unsigned int t)
{ (void)h; (void)rt; (void)r; (void)v; (void)i; (void)d; (void)t; return l; }
const char *libusb_strerror(int c) { (void)c; return "sim error"; }
const char *libusb_error_name(int c)
{
    switch (c) {
    case 0: return "LIBUSB_SUCCESS";
    case -1: return "LIBUSB_ERROR_IO";
    case -3: return "LIBUSB_ERROR_ACCESS";
    case -6: return "LIBUSB_ERROR_BUSY";
    case -7: return "LIBUSB_ERROR_TIMEOUT";
    case -8: return "LIBUSB_ERROR_OVERFLOW";
    case -9: return "LIBUSB_ERROR_PIPE";
    case -10: return "LIBUSB_ERROR_INTERRUPTED";
    default: return "LIBUSB_ERROR_OTHER";
    }
}
int __android_log_print(int p, const char *t, const char *f, ...) { (void)p; (void)t; (void)f; return 0; }
