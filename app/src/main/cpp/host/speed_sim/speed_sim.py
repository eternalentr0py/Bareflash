#!/usr/bin/env python3
"""
speed_sim: runs the REAL mtkclient xflash readflash()/writeflash() (+ the real
androidusblib.py, usb_native.c and bfusb.c) against a simulated MediaTek DA on
a simulated High-Speed bus, and reports MB/s plus where the time went.

  speed_sim.py --lib build/libspeedsim.so --py <app/src/main/python> [options]
"""
import argparse
import ctypes
import os
import sys
import tempfile
import threading
import time
import types
import zlib


class Cfg(ctypes.Structure):
    _fields_ = [("in_rate_bps", ctypes.c_double), ("out_rate_bps", ctypes.c_double),
                ("xfer_lat_us", ctypes.c_double), ("ev_wake_us", ctypes.c_double),
                ("da_turn_us", ctypes.c_double), ("da_prep_us", ctypes.c_double),
                ("da_prog_us_per_mib", ctypes.c_double),
                ("zlp_after_aligned", ctypes.c_int), ("status_after_ack", ctypes.c_int),
                ("max_packet", ctypes.c_int), ("read_plen", ctypes.c_uint32),
                ("write_plen", ctypes.c_uint32), ("strict_urb", ctypes.c_int),
                ("stall", ctypes.c_int)]


class Stats(ctypes.Structure):
    _fields_ = [(n, ctypes.c_uint64) for n in
                ("urbs_in urbs_out bytes_in bytes_out proto_errors overflows frames_read "
                 "write_bytes checksum_errors submits zlps").split()]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lib", required=True)
    ap.add_argument("--py", required=True, help="path to app/src/main/python")
    ap.add_argument("--mode", choices=["read", "write", "both"], default="both")
    ap.add_argument("--mib", type=int, default=128)
    ap.add_argument("--in-mbps", type=float, default=38.0)
    ap.add_argument("--out-mbps", type=float, default=26.0)
    ap.add_argument("--lat-us", type=float, default=60.0)
    ap.add_argument("--wake-us", type=float, default=40.0)
    ap.add_argument("--turn-us", type=float, default=60.0)
    ap.add_argument("--prep-us", type=float, default=100.0)
    ap.add_argument("--read-frame", type=lambda x: int(x, 0), default=0x10000)
    ap.add_argument("--write-chunk", type=lambda x: int(x, 0), default=0x200000)
    ap.add_argument("--no-zlp", action="store_true")
    ap.add_argument("--no-status-after-ack", action="store_true")
    ap.add_argument("--call-us", type=float, default=0.0, help="host cost of one Python->JNI bridge call")
    ap.add_argument("--repeat", type=int, default=1)
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()

    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import sim_bridge
    bridge = sim_bridge.install(a.lib)
    L = sim_bridge._lib
    bridge.call_us = a.call_us
    L.sim_set_cfg.argtypes = [ctypes.POINTER(Cfg)]
    L.sim_get_stats.argtypes = [ctypes.POINTER(Stats)]
    L.sim_reset.argtypes = [ctypes.c_size_t]
    L.sim_pattern_fill.argtypes = [ctypes.c_uint64, ctypes.c_void_p, ctypes.c_size_t]
    L.sim_write_buffer.restype = ctypes.c_void_p
    L.sim_write_buffer.argtypes = [ctypes.POINTER(ctypes.c_size_t)]
    L.sim_verify_writes.restype = ctypes.c_int

    cfg = Cfg()
    L.sim_default_cfg.argtypes = [ctypes.POINTER(Cfg)]
    L.sim_default_cfg(ctypes.byref(cfg))
    cfg.in_rate_bps = a.in_mbps * 1e6
    cfg.out_rate_bps = a.out_mbps * 1e6
    cfg.xfer_lat_us = a.lat_us
    cfg.ev_wake_us = a.wake_us
    cfg.da_turn_us = a.turn_us
    cfg.da_prep_us = a.prep_us
    cfg.read_plen = a.read_frame
    cfg.write_plen = a.write_chunk
    cfg.zlp_after_aligned = 0 if a.no_zlp else 1
    cfg.status_after_ack = 0 if a.no_status_after_ack else 1
    L.sim_set_cfg(ctypes.byref(cfg))

    # --- load the real code -------------------------------------------------
    stubs = os.path.join(tempfile.gettempdir(), "simstubs")
    os.makedirs(stubs, exist_ok=True)
    with open(os.path.join(stubs, "fuse.py"), "w") as f:
        f.write("class Operations: pass\nclass LoggingMixIn: pass\n"
                "class FuseOSError(OSError): pass\nclass FUSE:\n    def __init__(self,*a,**k): pass\n")
    sys.path[:0] = [stubs, a.py]
    import logging
    logging.disable(logging.CRITICAL)
    from mtkclient.Library.Connection.androidusblib import UsbClass
    from mtkclient.Library.DA.xflash.xflash_lib import DAXFlash
    from mtkclient.Library.DA.xflash.xflash_param import Cmd, DataType
    from mtkclient.Library.error import ErrorHandler

    usb = UsbClass(portconfig=[[0x0E8D, 0x2000, -1]], devclass=10)
    assert usb.connect(), "simulated connect failed"

    log = []
    da = object.__new__(DAXFlash)
    da.cmd = Cmd(); da.data_type = DataType(); da.eh = ErrorHandler()
    da.usbwrite = usb.usbwrite; da.usbread = usb.usbread
    for lvl in ("info", "debug", "warning", "error"):
        setattr(da, lvl, (lambda l: lambda m, *x, **k: log.append((l, str(m))))(lvl))

    class Storage:
        def get_storage(self, parttype, length): return (1, 8, length)
    da.daconfig = types.SimpleNamespace(storage=Storage())
    da.mtk = types.SimpleNamespace(
        port=types.SimpleNamespace(cdc=usb),
        config=types.SimpleNamespace(guiprogress=None, gui=None,
                                     chipconfig=types.SimpleNamespace(dacode=0x6765)))
    da.is_sim = True

    results = []
    nbytes = a.mib * 1024 * 1024
    tmp = tempfile.mkdtemp(prefix="speedsim", dir="/dev/shm" if os.path.isdir("/dev/shm") else None)

    def stats():
        s = Stats(); L.sim_get_stats(ctypes.byref(s)); return s

    for rep in range(a.repeat):
        if a.mode in ("read", "both"):
            L.sim_reset(0); bridge.flushRx(); L.sim_jni_stats_reset()
            da._plen_cache = None
            plen = da.get_packet_length()
            assert plen is not None, "GET_PACKET_LENGTH failed: %r" % log[-3:]
            out = os.path.join(tmp, "dump.bin")
            c0 = time.thread_time(); t0 = time.perf_counter()
            ok = da.readflash(addr=0, length=nbytes, filename=out, parttype=None, display=False)
            t1 = time.perf_counter(); c1 = time.thread_time()
            st = stats()
            # verify content against the flash pattern
            good = True
            with open(out, "rb") as f:
                off = 0
                exp = ctypes.create_string_buffer(1 << 20)
                while True:
                    blk = f.read(1 << 20)
                    if not blk: break
                    L.sim_pattern_fill(off, exp, len(blk))
                    if blk != exp.raw[:len(blk)]: good = False; break
                    off += len(blk)
                good = good and off == nbytes
            frames = max(st.frames_read, 1)
            results.append(dict(op="read", ok=bool(ok) and good, mbps=nbytes / (t1 - t0) / 1e6,
                                secs=t1 - t0, frames=int(st.frames_read), urbs=int(st.urbs_in + st.urbs_out),
                                cpu_ms_per_frame=(c1 - c0) * 1000 / frames, proto_errors=int(st.proto_errors),
                                native=bridge.stats_summary()))
            os.unlink(out)
        if a.mode in ("write", "both"):
            L.sim_reset(nbytes + (8 << 20)); bridge.flushRx(); L.sim_jni_stats_reset()
            da._plen_cache = None
            plen = da.get_packet_length()
            assert plen is not None, "GET_PACKET_LENGTH failed: %r" % log[-3:]
            src = os.path.join(tmp, "src.bin")
            pat = ctypes.create_string_buffer(1 << 20)
            with open(src, "wb") as f:
                for off in range(0, nbytes, 1 << 20):
                    n = min(1 << 20, nbytes - off)
                    L.sim_pattern_fill(off + 7919, pat, n); f.write(pat.raw[:n])
            c0 = time.thread_time(); t0 = time.perf_counter()
            ok = da.writeflash(addr=0, length=nbytes, filename=src, offset=0, parttype=None, display=False)
            t1 = time.perf_counter(); c1 = time.thread_time()
            st = stats()
            n = ctypes.c_size_t(0)
            p = L.sim_write_buffer(ctypes.byref(n))
            got = ctypes.string_at(p, n.value) if p else b""
            bad_sum = L.sim_verify_writes()
            with open(src, "rb") as f:
                want = f.read()
            good = got == want and bad_sum == 0
            chunks = max(nbytes // a.write_chunk, 1)
            results.append(dict(op="write", ok=bool(ok) and good, mbps=nbytes / (t1 - t0) / 1e6,
                                secs=t1 - t0, frames=chunks, urbs=int(st.urbs_in + st.urbs_out),
                                cpu_ms_per_frame=(c1 - c0) * 1000 / chunks, proto_errors=int(st.proto_errors),
                                native=bridge.stats_summary()))
            os.unlink(src)

    if a.json:
        import json; print(json.dumps(results)); return
    for r in results:
        print("%-5s %7.1f MB/s  %6.2fs  ok=%s  urbs=%d  host-cpu/%s=%.3f ms  proto_errors=%d" % (
            r["op"], r["mbps"], r["secs"], r["ok"], r["urbs"],
            "frame" if r["op"] == "read" else "chunk", r["cpu_ms_per_frame"], r["proto_errors"]))
        print("      " + r["native"])
    errs = [m for l, m in log if l == "error"]
    if errs: print("DA/host errors:", errs[:5])


if __name__ == "__main__":
    main()
