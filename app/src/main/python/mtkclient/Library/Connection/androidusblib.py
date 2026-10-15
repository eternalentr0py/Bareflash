#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# Android USB Host backend for mtkclient 2.1.4.x.
#
# Drop-in replacement for usblib.UsbClass that talks to the device via
# Android UsbManager/UsbDeviceConnection (UsbBridge.kt) instead of pyusb.

import json
import logging
import time

from com.bareflash.app import UsbBridge as _Bridge
from mtkclient.Library.Connection.devicehandler import DeviceClass

# Status strings from UsbBridge (device-not-found, no-permission-yet, etc.)
# repeat on every retry of a polling loop -- printed only when the text
# actually changes, so a stuck-waiting state shows one line, not a
# duplicate per retry.
_last_status_printed: str = ""


class _EndpointProxy:
    """Stand-in for a pyusb endpoint so Port.run_handshake can call
    EP_OUT.write / EP_IN.read / EP_IN.wMaxPacketSize."""

    def __init__(self, direction, owner):
        self._direction = direction
        self._owner = owner
        self.wMaxPacketSize = 512

    def write(self, data, timeout=None):
        if isinstance(data, str):
            data = data.encode("utf-8")
        if isinstance(data, int):
            data = bytes([data & 0xFF])
        data = bytes(data)
        t = int(timeout) if timeout is not None else int(self._owner.timeout)
        n = _Bridge.bulkWrite(data, t)
        return max(0, int(n)) if n is not None else 0

    def read(self, size, timeout=None):
        t = int(timeout) if timeout is not None else int(self._owner.timeout)
        return self._owner.usbread(int(size), timeout=t)


class _DeviceProxy:
    """Stand-in for a pyusb usb.core.Device.

    Kamakiri2 (the default ptype, see mtk_config.py) and KamakiriPl call
    `self.mtk.port.cdc.device.ctrl_transfer(...)` directly on the raw USB
    device object instead of going through Port/UsbClass. On desktop that
    object is a real pyusb Device; on Android there is no such object, so
    `.device` was left at the DeviceClass default of None and any exploit
    that touched it crashed with AttributeError. This proxy gives `.device`
    a `ctrl_transfer` with the same signature/return semantics as pyusb
    (bytes for IN transfers, byte count for OUT transfers), backed by
    UsbClass.ctrl_transfer, which already talks to UsbBridge.
    """

    def __init__(self, owner):
        self._owner = owner

    def ctrl_transfer(self, bmRequestType=None, bRequest=None, wValue=0, wIndex=0,
                      data_or_wLength=None, timeout=None,
                      bm_request_type=None, b_request=None):
        rt = bm_request_type if bm_request_type is not None else bmRequestType
        req = b_request if b_request is not None else bRequest
        return self._owner.ctrl_transfer(rt, req, wValue, wIndex, data_or_wLength)

    def reset(self):
        # No-op: Android doesn't expose a raw device reset through
        # UsbDeviceConnection the way pyusb's Device.reset() does, and
        # nothing on the Android code path currently relies on it (the
        # one call site in kamakiri2.py is commented out upstream too).
        pass


class UsbClass(DeviceClass):
    def __init__(self, loglevel=logging.INFO, portconfig=None, devclass=-1):
        super().__init__(loglevel, portconfig, devclass)
        self.interface = -1
        self.EP_IN = None
        self.EP_OUT = None
        self.backend = None
        self.timeout = 4000
        self._rxbuf = bytearray()
        self.vid = None
        self.pid = None

    def setLineCoding(self, baudrate=None, parity=0, databits=8, stopbits=1):
        pass

    def set_line_coding(self, baudrate=None, parity=0, databits=8, stopbits=1):
        pass

    def setbreak(self):
        pass

    def setcontrollinestate(self, rts=None, dtr=None, is_ftdi=False, RTS=None, DTR=None, isFTDI=False):
        pass

    def set_fast_mode(self, enabled: bool):
        pass

    def flush(self):
        self._rxbuf = bytearray()
        try:
            _Bridge.flushRx()
        except Exception:
            pass

    def setportname(self, portname: str):
        self.portname = portname

    def detectdevices(self):
        raw = _Bridge.detectDevices()
        try:
            devices = json.loads(raw)
        except Exception:
            devices = []
        return [(d["vid"], d["pid"]) for d in devices]

    def getInterfaceCount(self):
        return 1 if _Bridge.isConnected() else 0

    def get_interface_count(self):
        return self.getInterfaceCount()

    @staticmethod
    def _flatten_portconfig(portconfig):
        """Normalize mtk_class.setup()'s two portconfig shapes into a flat
        [[vid, pid, interface], ...] list -- the only shape UsbBridge.connect()
        understands (it does JSONArray(json).getJSONArray(i).getInt(0/1/2)).

        - Explicit --vid/--pid: mtk_class.py builds [[vid, pid, interface]]
          already, i.e. a flat list -- pass through (padding a missing
          interface with -1).
        - Auto-detect (no --vid/--pid, the common case): mtk_class.py passes
          usb_ids.default_ids, a nested {vid: {pid: interface, ...}, ...}
          dict. json.dumps()-ing that directly produces a JSON *object*,
          which crashes UsbBridge's JSONArray(...) parse -- it must be
          flattened to triples first.
        """
        if not portconfig:
            return []
        if isinstance(portconfig, dict):
            flat = []
            for vid, pids in portconfig.items():
                if isinstance(pids, dict):
                    for pid, iface in pids.items():
                        flat.append([int(vid), int(pid), int(iface)])
                else:
                    # Defensive fallback for a bare iterable of pids with no
                    # interface info.
                    for pid in pids:
                        flat.append([int(vid), int(pid), -1])
            return flat
        flat = []
        for entry in portconfig:
            entry = list(entry)
            if len(entry) == 2:
                entry.append(-1)
            flat.append(entry)
        return flat

    def connect(self, ep_in=-1, ep_out=-1, EP_IN=-1, EP_OUT=-1):
        if self.connected:
            self.close()

        portconfig = self._flatten_portconfig(self.portconfig)
        portconfig_json = json.dumps(portconfig)

        ok = _Bridge.connect(portconfig_json, self.devclass)
        status = ""
        try:
            status = str(_Bridge.getLastStatus())
        except Exception:
            status = ""
        if status:
            global _last_status_printed
            if status != _last_status_printed:
                print(f"[UsbBridge] {status}")
                _last_status_printed = status
        if not ok:
            self.debug("Couldn't detect/open the device, or USB permission "
                       "was not granted yet. Is it connected?")
            self.connected = False
            self.EP_IN = None
            self.EP_OUT = None
            self._rxbuf = bytearray()
            self.vid = None
            self.pid = None
            return False

        try:
            self.vid = int(_Bridge.getVid())
            self.pid = int(_Bridge.getPid())
        except Exception:
            self.vid = None
            self.pid = None

        pkt_in = _Bridge.getMaxPacketIn() or 512
        pkt_out = _Bridge.getMaxPacketOut() or 512
        self.EP_IN = _EndpointProxy("in", self)
        self.EP_IN.wMaxPacketSize = int(pkt_in)
        self.EP_OUT = _EndpointProxy("out", self)
        self.EP_OUT.wMaxPacketSize = int(pkt_out)
        self._rxbuf = bytearray()
        self.device = _DeviceProxy(self)
        self.connected = True
        return True

    def close(self, reset=False):
        try:
            _Bridge.close(bool(reset))
        except Exception as err:
            self.debug(str(err))
        self.connected = False
        self.EP_IN = None
        self.EP_OUT = None
        self._rxbuf = bytearray()
        self.device = None

    def reopen_and_control_transfer(self, bmRequestType, bRequest, wValue, wIndex,
                                     length=0, wait_ms=4000):
        """Android equivalent of Kamakiri's raw-pyusb reset dance.

        Desktop Kamakiri re-opens the device with pyusb after a watchdog
        reset and disables libusb's managed interface claim so it can send
        one more control transfer without re-claiming. Android's
        UsbDeviceConnection needs no such trick: control transfers to
        endpoint 0 target the device, not a claimed interface, so a fresh
        openDevice() is enough. UsbBridge.waitAndRawControlTransfer polls
        for the device to reappear (it briefly vanishes during the reset),
        opens it without claiming any interface, and fires the transfer.
        """
        vid = int(self.vid) if self.vid else 0x0E8D
        length = int(length) if isinstance(length, int) else len(length)
        try:
            ok = bool(_Bridge.waitAndRawControlTransfer(
                vid, int(wait_ms), int(bmRequestType), int(bRequest),
                int(wValue), int(wIndex), length))
        except Exception as err:
            self.debug(str(err))
            ok = False
        if not ok:
            try:
                status = str(_Bridge.getLastStatus())
            except Exception:
                status = ""
            if status:
                global _last_status_printed
                if status != _last_status_printed:
                    print(f"[UsbBridge] {status}")
                    _last_status_printed = status
        return ok

    def get_read_packetsize(self):
        size = _Bridge.getMaxPacketIn()
        return size if size else 512

    def get_write_packetsize(self):
        size = _Bridge.getMaxPacketOut()
        return size if size else 512

    def usbwrite(self, data, pktsize=None):
        if isinstance(data, str):
            data = bytes(data, "utf-8")
        if isinstance(data, int):
            data = bytes([data & 0xFF])
        data = bytes(data)
        # Match desktop usblib.UsbClass.usbwrite(): True only if the whole
        # payload went out. Returning a byte count was truthy on a short
        # write, so send_data()'s `if usbwrite(chunk): pos += chunksize`
        # skipped unsent bytes and desynced xflash.
        # Each call is one USB transfer (or several URBs for large data)
        # so ack()'s two consecutive 12+4 writes stay two packets.
        written = _Bridge.bulkWriteExact(data, self.timeout if self.timeout else 5000)
        if written is None or written < 0:
            written = 0
        self.verify_data(data, pre="TX:")
        return written == len(data)

    def write(self, command, pktsize=None):
        return self.usbwrite(command, pktsize)

    def usbread(self, resplen=None, timeout=0, maxtimeout=None, w_max_packet_size=None):
        """Read exactly `resplen` bytes, buffering surplus bulk packets.

        `timeout`/`maxtimeout` bound the TOTAL wall-clock time spent here.

        Packet-boundary handling (at-least-one-max-packet IN, leftover
        buffer for a 16-byte xflash frame read as 12+4) lives in
        UsbBridge.bulkReadExact -- this just asks for the protocol length
        and stitches a leftover from a previous call if Python-side _rxbuf
        still holds anything.
        """
        if resplen is None:
            resplen = self.maxsize
        resplen = int(resplen)
        if resplen <= 0:
            return b""

        if maxtimeout is not None:
            t = int(maxtimeout)
        else:
            t = int(timeout) if timeout else int(self.timeout)
        # t == 0 means "caller didn't ask for a specific deadline" -- give
        # bulk transfers (e.g. DA payloads) a generous but still bounded
        # budget rather than blocking forever.
        total_budget_ms = t if t > 0 else 5000

        if not self._rxbuf:
            # Common case: nothing buffered on the Python side, and
            # UsbBridge never returns more than it was asked for, so the
            # result can be handed back after a single conversion instead of
            # being copied through a bytearray twice (this runs once per
            # frame on multi-GB dumps).
            result = _Bridge.bulkReadExact(int(resplen), int(total_budget_ms))
            chunk = bytes(result) if result is not None else b""
            if len(chunk) <= resplen:
                self.verify_data(chunk, pre="RX:")
                return chunk
            self._rxbuf += chunk[resplen:]
            chunk = chunk[:resplen]
            self.verify_data(chunk, pre="RX:")
            return chunk

        data = bytearray()
        if self._rxbuf:
            take = min(resplen, len(self._rxbuf))
            data += self._rxbuf[:take]
            del self._rxbuf[:take]

        remaining = resplen - len(data)
        if remaining > 0:
            result = _Bridge.bulkReadExact(int(remaining), int(total_budget_ms))
            chunk = bytes(result) if result is not None else b""
            if len(chunk) <= remaining:
                data += chunk
            else:
                data += chunk[:remaining]
                self._rxbuf += chunk[remaining:]

        data = bytes(data[:resplen])
        self.verify_data(data, pre="RX:")
        return data

    def usbxmlread(self, maxtimeout=100):
        data = b""
        end = b"</xml>"
        empty_reads = 0
        for _ in range(maxtimeout):
            chunk = self.usbread(self.maxsize)
            if not chunk:
                empty_reads += 1
                if empty_reads > 3:
                    break
                continue
            empty_reads = 0
            data += chunk
            if end in data:
                break
        return data

    def usbreadwrite(self, data, resplen):
        self.usbwrite(data)
        return self.usbread(resplen)

    def ctrl_transfer(self, bmRequestType, bRequest, wValue, wIndex, data_or_wLength,
                      bm_request_type=None, b_request=None, w_value=None, w_index=None,
                      data_or_w_length=None):
        rt = bm_request_type if bm_request_type is not None else bmRequestType
        req = b_request if b_request is not None else bRequest
        val = w_value if w_value is not None else wValue
        idx = w_index if w_index is not None else wIndex
        payload = data_or_w_length if data_or_w_length is not None else data_or_wLength
        is_in = bool(rt & 0x80)
        if is_in:
            length = payload if isinstance(payload, int) else len(payload)
            result = _Bridge.controlTransferIn(rt, req, val, idx, int(length), self.timeout)
            return bytes(result) if result is not None else b""
        data = payload if payload is not None else b""
        if isinstance(data, str):
            data = bytes(data, "utf-8")
        data = bytes(data)
        return _Bridge.controlTransferOut(rt, req, val, idx, data, self.timeout)

    def ctrl_transfer_batch(self, ops, timeout=None):
        """Run several USB control transfers in one JNI crossing.

        Each op is (bmRequestType, bRequest, wValue, wIndex, data_or_wLength).
        IN transfers take an int length; OUT transfers take bytes/bytearray.
        Returns a list: bytes for IN, int transferred for OUT.
        """
        tmo = int(timeout) if timeout is not None else int(self.timeout)
        headers = []
        chunks = []
        kinds = []  # True = IN
        for op in ops:
            rt, req, val, idx, payload = op[0], op[1], op[2], op[3], op[4]
            is_in = bool(rt & 0x80)
            if is_in:
                length = payload if isinstance(payload, int) else len(payload)
                chunks.append(bytes(length))
                kinds.append(True)
            else:
                data = payload if payload is not None else b""
                if isinstance(data, str):
                    data = bytes(data, "utf-8")
                data = bytes(data)
                length = len(data)
                chunks.append(data)
                kinds.append(False)
            headers.extend([int(rt), int(req), int(val), int(idx), int(length), tmo])
        payload = b"".join(chunks) if chunks else b""
        try:
            ret = _Bridge.controlTransferBatch(headers, payload)
            results = [int(x) for x in ret[0]]
            returned = bytes(ret[1]) if ret[1] is not None else b""
        except Exception as err:
            self.debug(str(err))
            results = [-1] * len(ops)
            returned = b""
        out = []
        cursor = 0
        for i, is_in in enumerate(kinds):
            n = results[i] if i < len(results) else -1
            blob_len = len(chunks[i])
            if is_in:
                if n and n > 0:
                    out.append(returned[cursor:cursor + n])
                else:
                    out.append(b"")
            else:
                out.append(n if n is not None else -1)
            cursor += blob_len
        return out


# Back-compat alias used by older call sites / docs
usb_class = UsbClass
