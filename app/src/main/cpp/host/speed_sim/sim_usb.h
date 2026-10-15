/*
 * speed_sim: a fake MediaTek device on a fake USB bus.
 *
 * sim_usb.c implements the slice of libusb that usb_native.c and bfusb.c call
 * (transfers, event handling, claim/wrap stubs) on top of a timing model of a
 * High-Speed bulk pipe, and puts a small xflash DA behind that pipe. The REAL
 * usb_native.c / bfusb.c / mtkclient Python run against it unchanged, so a
 * throughput number from here is the number the real code would produce on a
 * bus with these characteristics.
 *
 * What is modelled:
 *   - a half-duplex bus with separate IN / OUT data rates and a fixed cost per
 *     URB (usbfs submit + completion interrupt),
 *   - libusb's event loop: completions are only delivered from
 *     libusb_handle_events_*, the waiting thread wakes `ev_wake_us` after the
 *     event fires,
 *   - bulk IN packetisation: a short packet or ZLP ends an IN URB, a URB whose
 *     length is not a multiple of wMaxPacketSize overflows (usbfs drops the
 *     packet), and an OUT URB larger than one DA read loses the surplus,
 *   - the xflash framing (12 byte header + payload) for GET_PACKET_LENGTH,
 *     READ_DATA (stop-and-wait ACK per frame) and WRITE_DATA (3 params + data,
 *     one status per chunk).
 *
 * What is NOT modelled: CPU frequency scaling, Android scheduling, ART/Chaquopy
 * call overhead. Those are measured separately (host CPU per frame) and are
 * called out in the report, not folded into these numbers.
 */
#ifndef SIM_USB_H
#define SIM_USB_H

#include <stdint.h>
#include <stddef.h>

struct sim_cfg {
    double in_rate_bps;        /* device -> host payload rate on the bus            */
    double out_rate_bps;       /* host -> device payload rate (flash-write bound)   */
    double xfer_lat_us;        /* fixed per-URB cost: submit ioctl + irq + reap      */
    double ev_wake_us;         /* thread wake-up after the event fd fires            */
    double da_turn_us;         /* DA turnaround: last ACK byte -> first reply byte   */
    double da_prep_us;         /* DA reads the next frame from flash before sending  */
    double da_prog_us_per_mib; /* DA programs flash before it ACKs a write chunk     */
    int    zlp_after_aligned;  /* DA sends a ZLP after a payload that is 512-aligned */
    int    status_after_ack;   /* DA sends a bare OK status frame after every ACK    */
    int    max_packet;         /* wMaxPacketSize of the bulk endpoints               */
    uint32_t read_plen;        /* GET_PACKET_LENGTH: read frame size                 */
    uint32_t write_plen;       /* GET_PACKET_LENGTH: write chunk size                */
    int    strict_urb;         /* DA loses OUT bytes beyond what it asked for        */
    int    stall;              /* 1: nothing ever completes (cancel / timeout tests) */
};

struct sim_stats {
    uint64_t urbs_in, urbs_out;
    uint64_t bytes_in, bytes_out;
    uint64_t proto_errors;     /* misaligned OUT, bad magic, overflow, ...           */
    uint64_t overflows;        /* IN URB not a multiple of wMaxPacketSize            */
    uint64_t frames_read;      /* data frames the DA delivered                       */
    uint64_t write_bytes;      /* payload bytes the DA accepted                      */
    uint64_t checksum_errors;  /* write chunk whose sum16 did not match              */
    uint64_t submits;          /* libusb_submit_transfer calls                       */
    uint64_t zlps;
};

void sim_set_cfg(const struct sim_cfg *c);
void sim_get_cfg(struct sim_cfg *c);
void sim_default_cfg(struct sim_cfg *c);
/* Clears the bus, the DA and the counters. write_capacity = bytes the DA will
 * remember from a WRITE_DATA so the host can verify them afterwards. */
void sim_reset(size_t write_capacity);
void sim_get_stats(struct sim_stats *s);
const unsigned char *sim_write_buffer(size_t *len);

/* Deterministic "flash" content: byte i of the device is pattern[i % period]. */
void sim_pattern_fill(uint64_t off, unsigned char *dst, size_t n);

#endif
