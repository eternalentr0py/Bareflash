/* The rest of the libusb surface usb_native.c declares by hand. Test-only. */
#include <stdarg.h>
#include <stdint.h>
#include <jni.h>
#include "libusb.h"
#include "mock_libusb.h"

int libusb_init_context(libusb_context **ctx, const struct libusb_init_option o[], int n)
{ (void)o; (void)n; if (ctx) *ctx = (libusb_context *)0x10; return 0; }
void libusb_interrupt_event_handler(libusb_context *ctx) { (void)ctx; }
const char *libusb_strerror(int c) { (void)c; return "mock error"; }
int libusb_init(libusb_context **ctx) { if (ctx) *ctx = (libusb_context *)0x10; return 0; }
void libusb_exit(libusb_context *ctx) { (void)ctx; }
int libusb_set_option(libusb_context *ctx, enum libusb_option o, ...) { (void)ctx; (void)o; return 0; }
int libusb_wrap_sys_device(libusb_context *ctx, intptr_t fd, libusb_device_handle **h)
{ (void)ctx; (void)fd; *h = (libusb_device_handle *)0x20; return 0; }
/* Claim behaviour, mirroring usbfs when the Java side already claimed the interface:
 * 0 = ok, 1 = BUSY only while auto-detach is on (DISCONNECT_CLAIM_EXCEPT_DRIVER),
 * 2 = always BUSY, 3 = ACCESS. */
static int g_claim_mode;
static int g_auto_detach;
int libusb_claim_interface(libusb_device_handle *h, int i)
{
    (void)h; (void)i;
    if (g_claim_mode == 1) return g_auto_detach ? LIBUSB_ERROR_BUSY : 0;
    if (g_claim_mode == 2) return LIBUSB_ERROR_BUSY;
    if (g_claim_mode == 3) return LIBUSB_ERROR_ACCESS;
    return 0;
}
int libusb_release_interface(libusb_device_handle *h, int i) { (void)h; (void)i; return 0; }
int libusb_set_interface_alt_setting(libusb_device_handle *h, int i, int a) { (void)h; (void)i; (void)a; return 0; }
int libusb_set_auto_detach_kernel_driver(libusb_device_handle *h, int e) { (void)h; g_auto_detach = e; return 0; }
int libusb_kernel_driver_active(libusb_device_handle *h, int i) { (void)h; (void)i; return 0; }
int libusb_detach_kernel_driver(libusb_device_handle *h, int i) { (void)h; (void)i; return 0; }
void libusb_close(libusb_device_handle *h) { (void)h; }
int libusb_clear_halt(libusb_device_handle *h, unsigned char e) { (void)h; (void)e; return 0; }
int libusb_control_transfer(libusb_device_handle *h, uint8_t rt, uint8_t r, uint16_t v, uint16_t i,
                            unsigned char *d, uint16_t l, unsigned int t)
{ (void)h; (void)rt; (void)r; (void)v; (void)i; (void)d; (void)t; return l; }
const char *libusb_error_name(int c)
{
    switch (c) {
    case 0: return "LIBUSB_SUCCESS";
    case -3: return "LIBUSB_ERROR_ACCESS";
    case -6: return "LIBUSB_ERROR_BUSY";
    case -7: return "LIBUSB_ERROR_TIMEOUT";
    case -10: return "LIBUSB_ERROR_INTERRUPTED";
    default: return "LIBUSB_ERROR_OTHER";
    }
}
int __android_log_print(int p, const char *t, const char *f, ...) { (void)p; (void)t; (void)f; return 0; }

/* Test control from Java. */
JNIEXPORT void JNICALL Java_MockCtl_set(JNIEnv *e, jclass c, jdouble rate, jint stall)
{ (void)e; (void)c; g_mock.rate_bps = rate; g_mock.stall = stall; }

/* C++ tests reset the simulated device through this. */
void mock_reset_for_cpp(double rate, int stall)
{
    g_mock.rate_bps = rate;
    g_mock.stall = stall;
    g_mock.fail_status = 0;
    g_mock.submit_fail = 0;
    g_claim_mode = 0;
    g_auto_detach = 0;
}

void mock_set_claim_mode(int mode) { g_claim_mode = mode; g_auto_detach = 0; }
