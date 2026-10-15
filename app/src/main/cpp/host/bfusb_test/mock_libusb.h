#ifndef MOCK_LIBUSB_H
#define MOCK_LIBUSB_H
struct mock_cfg {
    double rate_bps;     /* simulated bus rate, bytes/s */
    int stall;           /* 1: transfers never complete on their own */
    int fail_status;     /* enum libusb_transfer_status to finish with (0 = success) */
    int fail_partial;    /* bytes moved before that failure */
    int submit_fail;     /* LIBUSB_ERROR_* returned by submit (0 = ok) */
    long submits;
};
extern struct mock_cfg g_mock;
#endif
