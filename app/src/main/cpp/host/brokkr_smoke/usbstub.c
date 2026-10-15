#include <stdint.h>
#include <stddef.h>
/* Link-test stub: only definitions, never executed. */
typedef struct libusb_context libusb_context; typedef struct libusb_device_handle libusb_device_handle;
int libusb_init_context(libusb_context **c, const void *o, int n){(void)c;(void)o;(void)n;return -1;}
void libusb_exit(libusb_context *c){(void)c;}
int libusb_wrap_sys_device(libusb_context *c, intptr_t fd, libusb_device_handle **h){(void)c;(void)fd;(void)h;return -1;}
int libusb_set_auto_detach_kernel_driver(libusb_device_handle *h,int e){(void)h;(void)e;return 0;}
int libusb_claim_interface(libusb_device_handle *h,int i){(void)h;(void)i;return 0;}
int libusb_release_interface(libusb_device_handle *h,int i){(void)h;(void)i;return 0;}
void libusb_close(libusb_device_handle *h){(void)h;}
int libusb_bulk_transfer(libusb_device_handle *h,unsigned char e,unsigned char *d,int l,int *t,unsigned int to){(void)h;(void)e;(void)d;(void)l;(void)t;(void)to;return -1;}
const char *libusb_error_name(int c){(void)c;return "x";}
const char *libusb_strerror(int c){(void)c;return "x";}
void libusb_interrupt_event_handler(libusb_context *c){(void)c;}
int __android_log_write(int p,const char*t,const char*m){(void)p;(void)t;(void)m;return 0;}

/* async transfer API used by bfusb */
struct libusb_transfer;
struct timeval;
struct libusb_transfer *libusb_alloc_transfer(int n){(void)n;return 0;}
void libusb_free_transfer(struct libusb_transfer *t){(void)t;}
int libusb_submit_transfer(struct libusb_transfer *t){(void)t;return -1;}
int libusb_cancel_transfer(struct libusb_transfer *t){(void)t;return -1;}
int libusb_handle_events_timeout_completed(libusb_context *c, struct timeval *tv, int *done){(void)c;(void)tv;(void)done;return -1;}
