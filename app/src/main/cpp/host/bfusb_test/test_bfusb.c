#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "bfusb.h"
#include "libusb.h"
#include "mock_libusb.h"

static int g_fail;
#define CHECK(c, ...) do { if (!(c)) { printf("  FAIL line %d: ", __LINE__); printf(__VA_ARGS__); printf("\n"); g_fail++; } } while (0)

static double now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1e3 + ts.tv_nsec / 1e6;
}

static void reset(void)
{
    memset(&g_mock, 0, sizeof g_mock);
    g_mock.rate_bps = 100e6;
    bfusb_stats_reset();
}

static volatile int g_flag;
static int flag_cb(void *u) { (void)u; return g_flag; }
static const bfusb_cancel CANCEL = { flag_cb, NULL };

static void *raiser(void *arg)
{
    usleep((useconds_t)(long)arg * 1000);
    g_flag = 1;
    return NULL;
}

static unsigned char buf[1 << 20];
static unsigned char *const H = (unsigned char *)0x1; /* opaque handle, never dereferenced */

static void test_success(void)
{
    puts("success: 1 MiB OUT at 100 MB/s");
    reset(); g_flag = 0;
    int n = -1; double t = now_ms();
    int rc = bfusb_bulk(NULL, (libusb_device_handle *)H, 0x01, buf, sizeof buf, &n, 2000, &CANCEL);
    t = now_ms() - t;
    CHECK(rc == 0, "rc=%d", rc);
    CHECK(n == (int)sizeof buf, "n=%d", n);
    CHECK(t > 5 && t < 60, "took %.1f ms (expected ~10)", t);
}

static void test_timeout_partial(void)
{
    puts("timeout: 1 MiB at 10 MB/s (100 ms) with a 30 ms deadline -> TIMEOUT with partial bytes");
    reset(); g_flag = 0; g_mock.rate_bps = 10e6;
    int n = -1; double t = now_ms();
    int rc = bfusb_bulk(NULL, (libusb_device_handle *)H, 0x01, buf, sizeof buf, &n, 30, &CANCEL);
    t = now_ms() - t;
    CHECK(rc == LIBUSB_ERROR_TIMEOUT, "rc=%d", rc);
    CHECK(n > 0 && n < (int)sizeof buf, "partial n=%d", n);
    CHECK(t >= 28 && t < 80, "took %.1f ms", t);
}

static void test_stall_deadline(void)
{
    puts("stall: device never answers, 100 ms deadline -> TIMEOUT on time");
    reset(); g_flag = 0; g_mock.stall = 1;
    int n = -1; double t = now_ms();
    int rc = bfusb_bulk(NULL, (libusb_device_handle *)H, 0x81, buf, 4096, &n, 100, &CANCEL);
    t = now_ms() - t;
    CHECK(rc == LIBUSB_ERROR_TIMEOUT, "rc=%d", rc);
    CHECK(n == 0, "n=%d", n);
    CHECK(t >= 95 && t < 160, "took %.1f ms", t);
}

static void test_cancel_prompt(void)
{
    puts("cancel: stalled transfer with a 10 s deadline, cancel raised at 50 ms -> INTERRUPTED within ~50 ms");
    reset(); g_flag = 0; g_mock.stall = 1;
    pthread_t th; pthread_create(&th, NULL, raiser, (void *)50L);
    int n = -1; double t = now_ms();
    int rc = bfusb_bulk(NULL, (libusb_device_handle *)H, 0x01, buf, sizeof buf, &n, 10000, &CANCEL);
    t = now_ms() - t;
    pthread_join(th, NULL);
    CHECK(rc == LIBUSB_ERROR_INTERRUPTED, "rc=%d", rc);
    CHECK(t >= 45 && t < 120, "took %.1f ms (a plain sync call would take 10000)", t);
}

static void test_precancelled(void)
{
    puts("pre-cancelled: nothing is submitted");
    reset(); g_flag = 1;
    int n = -1;
    int rc = bfusb_bulk(NULL, (libusb_device_handle *)H, 0x01, buf, 512, &n, 1000, &CANCEL);
    CHECK(rc == LIBUSB_ERROR_INTERRUPTED && n == 0, "rc=%d n=%d", rc, n);
    CHECK(g_mock.submits == 0, "submits=%ld", g_mock.submits);
    g_flag = 0;
}

static void test_error_mapping(void)
{
    puts("status mapping: STALL->PIPE, NO_DEVICE->NO_DEVICE, ERROR->IO, partial bytes kept");
    struct { int st; int want; } cases[] = {
        { LIBUSB_TRANSFER_STALL, LIBUSB_ERROR_PIPE },
        { LIBUSB_TRANSFER_NO_DEVICE, LIBUSB_ERROR_NO_DEVICE },
        { LIBUSB_TRANSFER_ERROR, LIBUSB_ERROR_IO },
        { LIBUSB_TRANSFER_OVERFLOW, LIBUSB_ERROR_OVERFLOW },
    };
    for (unsigned i = 0; i < sizeof cases / sizeof *cases; i++) {
        reset(); g_flag = 0; g_mock.fail_status = cases[i].st; g_mock.fail_partial = 1024;
        int n = -1;
        int rc = bfusb_bulk(NULL, (libusb_device_handle *)H, 0x81, buf, 4096, &n, 1000, &CANCEL);
        CHECK(rc == cases[i].want, "status %d -> rc=%d want %d", cases[i].st, rc, cases[i].want);
        CHECK(n == 1024, "partial n=%d", n);
    }
    reset(); g_mock.submit_fail = LIBUSB_ERROR_NO_DEVICE;
    int n = -1;
    int rc = bfusb_bulk(NULL, (libusb_device_handle *)H, 0x01, buf, 64, &n, 1000, &CANCEL);
    CHECK(rc == LIBUSB_ERROR_NO_DEVICE && n == 0, "submit failure rc=%d n=%d", rc, n);
}

static void test_zlp(void)
{
    puts("zero-length packet");
    reset(); g_flag = 0;
    int n = -1;
    int rc = bfusb_bulk(NULL, (libusb_device_handle *)H, 0x01, NULL, 0, &n, 100, &CANCEL);
    CHECK(rc == 0 && n == 0, "rc=%d n=%d", rc, n);
}

static void test_sync_path(void)
{
    puts("no cancel callback -> plain sync path, same semantics");
    reset(); g_mock.stall = 1;
    int n = -1; double t = now_ms();
    int rc = bfusb_bulk(NULL, (libusb_device_handle *)H, 0x01, buf, 512, &n, 60, NULL);
    t = now_ms() - t;
    CHECK(rc == LIBUSB_ERROR_TIMEOUT, "rc=%d", rc);
    CHECK(t >= 55 && t < 120, "took %.1f ms", t);
    reset();
    rc = bfusb_bulk(NULL, (libusb_device_handle *)H, 0x01, buf, 8192, &n, 1000, NULL);
    CHECK(rc == 0 && n == 8192, "rc=%d n=%d", rc, n);
}

static void *worker(void *arg)
{
    int *res = arg;
    for (int i = 0; i < 20; i++) {
        int n = 0;
        int rc = bfusb_bulk(NULL, (libusb_device_handle *)H, 0x01, buf, 65536, &n, 2000, &CANCEL);
        if (rc != 0 || n != 65536) { *res = 1; return NULL; }
    }
    return NULL;
}

static void test_concurrent(void)
{
    puts("two threads transferring at once on one context");
    reset(); g_flag = 0;
    int r[2] = { 0, 0 };
    pthread_t a, b;
    pthread_create(&a, NULL, worker, &r[0]);
    pthread_create(&b, NULL, worker, &r[1]);
    pthread_join(a, NULL); pthread_join(b, NULL);
    CHECK(r[0] == 0 && r[1] == 0, "worker results %d %d", r[0], r[1]);
    bfusb_stats s; bfusb_stats_get(&s);
    CHECK(s.bytes_out == 2ull * 20 * 65536, "bytes_out=%llu", (unsigned long long)s.bytes_out);
    CHECK(s.calls_out == 40, "calls_out=%llu", (unsigned long long)s.calls_out);
}

static void test_stats(void)
{
    puts("stats + formatting");
    reset(); g_flag = 0;
    char line[256];
    CHECK(bfusb_stats_format(line, sizeof line) == 0 && line[0] == 0, "empty before any transfer");
    int n;
    for (int i = 0; i < 10; i++) {
        bfusb_bulk(NULL, (libusb_device_handle *)H, 0x01, buf, 1 << 20, &n, 2000, &CANCEL);
        usleep(5000); /* host gap between calls */
    }
    bfusb_bulk(NULL, (libusb_device_handle *)H, 0x81, buf, 4096, &n, 2000, &CANCEL);
    g_mock.stall = 1;
    bfusb_bulk(NULL, (libusb_device_handle *)H, 0x01, buf, 512, &n, 20, &CANCEL);
    bfusb_stats s; bfusb_stats_get(&s);
    CHECK(s.bytes_out == 10ull << 20, "bytes_out=%llu", (unsigned long long)s.bytes_out);
    CHECK(s.bytes_in == 4096, "bytes_in=%llu", (unsigned long long)s.bytes_in);
    CHECK(s.timeouts == 1, "timeouts=%llu", (unsigned long long)s.timeouts);
    CHECK(s.gap_ns > 30ull * 1000 * 1000, "gap_ns=%llu (10 x 5 ms expected)", (unsigned long long)s.gap_ns);
    bfusb_stats_format(line, sizeof line);
    printf("  line: %s\n", line);
    CHECK(strstr(line, "out 10.0 MiB") && strstr(line, "host gaps") && strstr(line, "1 timeouts"), "format");
    char tiny[8];
    int len = bfusb_stats_format(tiny, sizeof tiny);
    CHECK(len < (int)sizeof tiny && strlen(tiny) < sizeof tiny, "truncation len=%d", len);
}

static void test_link(void)
{
    puts("link speed names");
    CHECK(!strncmp(bfusb_link_name(64), "Full-Speed", 10) && bfusb_link_is_slow(64), "64");
    CHECK(!strncmp(bfusb_link_name(512), "High-Speed", 10) && !bfusb_link_is_slow(512), "512");
    CHECK(!strncmp(bfusb_link_name(1024), "SuperSpeed", 10) && !bfusb_link_is_slow(1024), "1024");
    CHECK(!strcmp(bfusb_link_name(0), "unknown") && !bfusb_link_is_slow(0), "0");
}

int main(void)
{
    test_success();
    test_timeout_partial();
    test_stall_deadline();
    test_cancel_prompt();
    test_precancelled();
    test_error_mapping();
    test_zlp();
    test_sync_path();
    test_concurrent();
    test_stats();
    test_link();
    puts(g_fail ? "FAILED" : "ALL PASSED");
    return g_fail ? 1 : 0;
}
