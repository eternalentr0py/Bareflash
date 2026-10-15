"""
speed_sim: stands in for `com.bareflash.app.UsbBridge` / `UiBridge` so the real
mtkclient Python (androidusblib.py, xflash_lib.py) runs unchanged on a desktop.

The USB work is done by the REAL usb_native.c + bfusb.c (libspeedsim.so, via a
fake JNIEnv). The only hand-ported code is the thin Kotlin loop in
UsbBridge.kt (bulkReadExact / bulkWriteExact / bulkWrite), kept line-for-line
so the two stay comparable; Kotlin cannot be compiled on a desktop here.
"""
import ctypes
import json
import os
import sys
import time
import types

LIBUSB_TIMEOUT = -7
LIBUSB_PIPE = -9
BIG_XFER = 1024 * 1024          # UsbBridge.BIG_XFER

_lib = None


def load(path):
    global _lib
    _lib = ctypes.CDLL(path)
    L = _lib
    P = ctypes.c_void_p
    L.sim_jni_init.restype = ctypes.c_int
    L.sim_jni_wrap.restype = ctypes.c_long
    L.sim_jni_wrap.argtypes = [ctypes.c_int]
    L.sim_jni_claim.argtypes = [ctypes.c_long, ctypes.c_int]
    L.sim_jni_set_cancelled.argtypes = [ctypes.c_int]
    L.sim_jni_set_max_packet.argtypes = [ctypes.c_int]
    L.sim_jni_bulk_exact.argtypes = [ctypes.c_long, ctypes.c_int, P, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int]
    L.sim_jni_bulk.argtypes = L.sim_jni_bulk_exact.argtypes
    L.sim_jni_byte_sum16.argtypes = [P, ctypes.c_int, ctypes.c_int, ctypes.c_int]
    L.sim_jni_stats_summary.restype = ctypes.c_char_p
    L.sim_jni_error_name.restype = ctypes.c_char_p
    L.sim_jni_error_name.argtypes = [ctypes.c_int]
    L.sim_jni_clear_halt.argtypes = [ctypes.c_long, ctypes.c_int]
    L.sim_jni_read_frames.argtypes = [ctypes.c_long, ctypes.c_int, ctypes.c_int, P, ctypes.c_int,
                                      ctypes.c_int, ctypes.c_int, P, P]
    return L


class _UsbBridge:
    """Mirrors UsbBridge.kt for the calls androidusblib.py and xflash_lib.py make."""

    def __init__(self):
        self.handle = 0
        self.cancelled = False
        self.rx_leftover = b""
        self.last_status = "not initialized"
        self.last_xfer_error = ""
        self.ep_in = 0x81
        self.ep_out = 0x01
        self.max_in = 512
        self.max_out = 512
        # Models what one Python->Kotlin->JNI call costs on a phone (Chaquopy
        # marshalling + a throttled core). Busy-waits, so it is real CPU time.
        self.call_us = 0.0

    def _call(self):
        if self.call_us:
            end = time.perf_counter() + self.call_us / 1e6
            while time.perf_counter() < end:
                pass

    # -- connection -------------------------------------------------------
    def connect(self, portconfig_json, dev_class):
        self.cancelled = False
        _lib.sim_jni_set_cancelled(0)
        if not _lib.sim_jni_init():
            self.last_status = "libusb_init() failed"
            return False
        h = _lib.sim_jni_wrap(5)
        if not h or not _lib.sim_jni_claim(h, 1):
            self.last_status = "wrap/claim failed"
            return False
        self.handle = h
        self.rx_leftover = b""
        _lib.sim_jni_set_max_packet(self.max_in)
        _lib.sim_jni_flush_rx()
        _lib.sim_jni_stats_reset()
        self.last_status = "connected vid=0xe8d pid=0x2000 transport=libusb (simulated)"
        return True

    def close(self, reset):
        self.handle = 0

    def isConnected(self): return self.handle != 0
    def getLastStatus(self): return self.last_status
    def getVid(self): return 0x0E8D
    def getPid(self): return 0x2000
    def getMaxPacketIn(self): return self.max_in
    def getMaxPacketOut(self): return self.max_out
    def detectDevices(self): return json.dumps([{"vid": 0x0E8D, "pid": 0x2000}])
    def getLastXferError(self): return self.last_xfer_error
    def isDevicePresent(self): return True
    def requestCancel(self, flag): self.cancelled = bool(flag); _lib.sim_jni_set_cancelled(1 if flag else 0)

    def flushRx(self):
        self.rx_leftover = b""
        _lib.sim_jni_flush_rx()

    def byteSum16(self, data):
        buf = (ctypes.c_char * len(data)).from_buffer_copy(data) if not isinstance(data, bytearray) \
            else (ctypes.c_char * len(data)).from_buffer(data)
        return _lib.sim_jni_byte_sum16(ctypes.addressof(buf), len(data), 0, len(data))

    # -- transfers (ported from UsbBridge.kt) ------------------------------
    def bulkWrite(self, data, timeout):
        self._call()
        if self.cancelled or not self.handle:
            return -1
        buf = (ctypes.c_char * len(data)).from_buffer_copy(data)
        n = _lib.sim_jni_bulk(self.handle, self.ep_out, ctypes.addressof(buf), len(data), 0, len(data), timeout)
        if n == LIBUSB_PIPE and not self.cancelled and _lib.sim_jni_clear_halt(self.handle, self.ep_out):
            n = _lib.sim_jni_bulk(self.handle, self.ep_out, ctypes.addressof(buf), len(data), 0, len(data), timeout)
        return n

    def bulkReadExact(self, total, timeout_ms):
        self._call()
        if not self.handle or total <= 0 or self.cancelled:
            return b""
        out = bytearray(total)
        cbuf = (ctypes.c_char * total).from_buffer(out)
        addr = ctypes.addressof(cbuf)
        got = 0
        if self.rx_leftover:
            take = min(total, len(self.rx_leftover))
            out[:take] = self.rx_leftover[:take]
            self.rx_leftover = self.rx_leftover[take:]
            got = take
            if got >= total:
                return bytes(out)
        deadline = time.monotonic() + max(timeout_ms, 1) / 1000.0
        halt_tried = False
        while got < total and not self.cancelled:
            remain_ms = int((deadline - time.monotonic()) * 1000)
            if remain_ms <= 0:
                break
            still = total - got
            n = _lib.sim_jni_bulk_exact(self.handle, self.ep_in, addr, total, got, still, max(remain_ms, 1))
            if n > 0:
                got += min(n, still)
                halt_tried = False
                if got >= total:
                    break
                continue
            if n < 0 and n != LIBUSB_TIMEOUT:
                self.last_xfer_error = "IN error=%d (%s) got=%d of %d" % (
                    n, _lib.sim_jni_error_name(n).decode(), got, total)
            if self.cancelled:
                break
            if n == LIBUSB_PIPE and not halt_tried:
                halt_tried = True
                if _lib.sim_jni_clear_halt(self.handle, self.ep_in):
                    continue
            break
        if got <= 0:
            return b""
        return bytes(out) if got == total else bytes(out[:got])

    # -- fused READ_DATA loop (UsbBridge.readFrames) -----------------------
    def readFrames(self, frame_len, frames, hdr_tmo):
        self._call()
        self.fr_info = [3, 0, 0, 0]
        self.fr_hdr = b"\0" * 16
        if not self.handle or self.cancelled or frame_len <= 0 or frames <= 0 or self.rx_leftover:
            return b""
        dst = ctypes.create_string_buffer(frame_len * frames)
        hdr = ctypes.create_string_buffer(16)
        info = (ctypes.c_int * 4)()
        _lib.sim_jni_read_frames(self.handle, self.ep_in, self.ep_out, ctypes.addressof(dst),
                                 frame_len * frames, frame_len, hdr_tmo,
                                 ctypes.addressof(hdr), ctypes.addressof(info))
        self.fr_info = list(info)
        self.fr_hdr = hdr.raw
        return dst.raw

    def readFramesInfo(self): return self.fr_info
    def readFramesHdr(self): return self.fr_hdr

    def bulkWriteExact(self, data, timeout_ms):
        self._call()
        if not self.handle:
            return -1
        if not data:
            return 0
        if self.cancelled:
            return -1
        budget_ms = max(timeout_ms, 1) + len(data) // 64
        deadline = time.monotonic() + budget_ms / 1000.0
        src = data if isinstance(data, (bytes, bytearray)) else bytes(data)
        buf = (ctypes.c_char * len(src)).from_buffer_copy(src)
        addr = ctypes.addressof(buf)
        sent = 0
        halt_tried = False
        while sent < len(src) and not self.cancelled:
            remain_ms = int((deadline - time.monotonic()) * 1000)
            if remain_ms <= 0:
                break
            want = min(BIG_XFER, len(src) - sent)
            n = _lib.sim_jni_bulk_exact(self.handle, self.ep_out, addr, len(src), sent, want, max(remain_ms, 1))
            if n <= 0:
                if n < 0 and n != LIBUSB_TIMEOUT:
                    self.last_xfer_error = "OUT error=%d (%s) sent=%d of %d" % (
                        n, _lib.sim_jni_error_name(n).decode(), sent, len(src))
                if self.cancelled:
                    break
                if n == LIBUSB_PIPE and not halt_tried:
                    halt_tried = True
                    if _lib.sim_jni_clear_halt(self.handle, self.ep_out):
                        continue
                break
            sent += n
        return sent

    def stats_summary(self):
        return (_lib.sim_jni_stats_summary() or b"").decode()


class _UiBridge:
    @staticmethod
    def reportProgress(pos, total, label): pass


def install(lib_path):
    """Registers com.bareflash.app.{UsbBridge,UiBridge} and returns the bridge."""
    load(lib_path)
    bridge = _UsbBridge()
    for name in ("com", "com.bareflash", "com.bareflash.app"):
        sys.modules[name] = types.ModuleType(name)
    app = sys.modules["com.bareflash.app"]
    app.UsbBridge = bridge
    app.UiBridge = _UiBridge
    os.environ["MTK_USE_ANDROID_USB"] = "1"
    return bridge
