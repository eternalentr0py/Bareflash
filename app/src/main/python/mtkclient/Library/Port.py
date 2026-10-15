#!/usr/bin/python3
# -*- coding: utf-8 -*-
# (c) B.Kerler 2018-2025 GPLv3 License
# Android host: UsbClass is swapped to androidusblib when MTK_USE_ANDROID_USB=1.
# Handshake keeps the USB claim open on Android (preloader window is short)
# and falls back to usbwrite/usbread when pyusb endpoint objects are absent.
import os
import sys
import logging
import time
from binascii import hexlify
from struct import pack
from mtkclient.Library.gui_utils import LogBase, logsetup

if os.environ.get("MTK_USE_ANDROID_USB") == "1":
    from mtkclient.Library.Connection.androidusblib import UsbClass
else:
    from mtkclient.Library.Connection.usblib import UsbClass
from mtkclient.Library.Connection.seriallib import SerialClass


class Port(metaclass=LogBase):
    class DeviceClass:
        vid = 0
        pid = 0

        def __init__(self, vid, pid):
            self.vid = vid
            self.pid = pid

    def __init__(self, mtk, portconfig, serialportname: str = None, loglevel=logging.INFO):
        self.__logger, self.info, self.debug, self.warning, self.error = logsetup(
            self, self.__logger, loglevel, mtk.config.gui
        )
        self.config = mtk.config
        self.mtk = mtk
        self.serialportname = None
        if serialportname is not None and serialportname != "":
            self.cdc = SerialClass(portconfig=portconfig, loglevel=loglevel, devclass=10)
            self.cdc.setportname(serialportname)
        else:
            self.cdc = UsbClass(portconfig=portconfig, loglevel=loglevel, devclass=10)
        self.usbread = self.cdc.usbread
        self.usbwrite = self.cdc.usbwrite
        self.close = self.cdc.close
        self.rdword = self.cdc.rdword
        self.rword = self.cdc.rword
        self.rbyte = self.cdc.rbyte
        self.detectusbdevices = self.cdc.detectdevices
        self.usbreadwrite = self.cdc.usbreadwrite

        if loglevel == logging.DEBUG:
            logfilename = os.path.join("logs", "log.txt")
            os.makedirs(os.path.dirname(logfilename) or ".", exist_ok=True)
            fh = logging.FileHandler(logfilename, encoding='utf-8')
            self.__logger.addHandler(fh)
            self.__logger.setLevel(logging.DEBUG)
        else:
            self.__logger.setLevel(logging.INFO)

    def run_serial_handshake(self):
        try:
            if hasattr(self.cdc, 'ep_out') or hasattr(self.cdc, 'EP_OUT'):
                ep_out = self.cdc.EP_OUT.write
            else:
                ep_out = self.cdc.write
        except Exception:
            ep_out = self.cdc.write
        try:
            if hasattr(self.cdc, 'ep_in') or hasattr(self.cdc, 'EP_IN'):
                ep_in = self.cdc.EP_IN.read
            else:
                ep_in = self.cdc.read
        except Exception:
            ep_in = self.cdc.read

        i = 0
        startcmd = b"\xa0\x0a\x50\x05"
        length = len(startcmd)
        try:
            while i < length:
                if ep_out(int.to_bytes(startcmd[i], 1, 'little')):
                    v = ep_in(1, timeout=20)
                    if len(v) == 1 and v[0] == ~(startcmd[i]) & 0xFF:
                        i += 1
                    else:
                        i = 0
            self.info("Device detected :)")
            return True
        except Exception as serr:
            self.debug(str(serr))
            time.sleep(0.005)
        return False

    def serial_handshake(self, maxtries=None, loop=0):
        counter = 0
        if not self.cdc.connected:
            self.cdc.connected = self.cdc.connect()
        while 1:
            try:
                if not self.cdc.connected:
                    self.cdc.connected = self.cdc.connect()
                if maxtries is not None and counter == maxtries:
                    break
                counter += 1
                if self.cdc.connected and self.run_serial_handshake():
                    self.info("Handshake successful.")
                    return True
                else:
                    if loop == 5:
                        sys.stdout.write('\n')
                        self.info("Hint:\n\nPower off the phone before connecting.\n" +
                                  "For brom mode, press and hold vol up, vol dwn, or all hw buttons and " +
                                  "connect usb.\n" +
                                  "For preloader mode, don't press any hw button and connect usb.\n"
                                  "If it is already connected and on, hold power for 10 seconds to reset.\n")
                        sys.stdout.write('\n')
                    if loop >= 10:
                        sys.stdout.write('.')
                    if loop >= 20:
                        sys.stdout.write('\n')
                        loop = 0
                    loop += 1
                    time.sleep(0.1)
                    sys.stdout.flush()

            except Exception as serr:
                print(f"Handshake: {str(serr)}")
                if "access denied" in str(serr):
                    self.warning(str(serr))
                self.debug(str(serr))
        return False

    def _endpoint_io(self):
        ep_out_obj = getattr(self.cdc, "EP_OUT", None)
        ep_in_obj = getattr(self.cdc, "EP_IN", None)
        use_ep = (
            ep_out_obj is not None
            and ep_in_obj is not None
            and hasattr(ep_out_obj, "write")
            and hasattr(ep_in_obj, "read")
        )
        if use_ep:
            return True, ep_out_obj.write, ep_in_obj.read, getattr(ep_in_obj, "wMaxPacketSize", 512) or 512
        return False, None, None, 512

    def run_handshake(self, retries=5):
        use_ep, ep_out, ep_in, maxinsize = self._endpoint_io()

        try:
            if hasattr(self.cdc, "set_line_coding"):
                self.cdc.set_line_coding(921600, 0, 8, 1)
            elif hasattr(self.cdc, "setLineCoding"):
                self.cdc.setLineCoding(921600, 0, 8, 1)
        except Exception:
            pass
        try:
            self.cdc.setcontrollinestate(rts=True)
        except TypeError:
            try:
                self.cdc.setcontrollinestate(RTS=True)
            except Exception:
                pass
        except Exception:
            pass

        startcmd = b"\xa0\x0a\x50\x05"
        expected_echo = bytes((~b) & 0xFF for b in startcmd)
        brom_pids = [0x3, 0xF200, 0xD1E9, 0xD1E2, 0xD1EC, 0xD1DD]
        pid = getattr(self.cdc, "pid", None)

        def write_byte(b, timeout=500):
            blob = bytes([b]) if isinstance(b, int) else b
            if use_ep:
                written = ep_out(blob, timeout=timeout)
                return written == 1 or written is True or (isinstance(written, int) and written > 0)
            return bool(self.cdc.usbwrite(blob))

        def read_byte(timeout=500):
            if use_ep:
                return ep_in(1, timeout=timeout) or b""
            return self.cdc.usbread(1, timeout=timeout) or b""

        if pid not in brom_pids:
            try:
                write_byte(0xA0, timeout=200)
            except Exception:
                pass

        for attempt in range(retries):
            received = b""
            try:
                ok = True
                for byte in startcmd:
                    if not write_byte(byte, timeout=500):
                        raise ValueError("Write failed")
                    echo = read_byte(timeout=500)
                    if len(echo) < 1 or echo[0] != ((~byte) & 0xFF):
                        raise ValueError(f"Echo mismatch: got {echo!r}, expected {(~byte) & 0xFF:02x}")
                    received += echo[:1]
                if received == expected_echo:
                    self.info("Device detected :)")
                    return True
            except Exception as e:
                self.debug(f"Handshake attempt {attempt + 1} failed: {e}")
                time.sleep(0.01)

            try:
                if use_ep:
                    ep_in(maxinsize, timeout=50)
                elif hasattr(self.cdc, "flush"):
                    self.cdc.flush()
            except Exception:
                pass

        self.info("Handshake failed after retries")
        return False

    def handshake(self, maxtries=None, loop=0):
        counter = 0
        android = os.environ.get("MTK_USE_ANDROID_USB") == "1"

        # 2.1.4.1 upstream loops `while not connected`, which exits after a
        # successful USB open even if the A0/0A/50/05 ping fails. Preloader
        # on Android is only on the bus for a short window, so we keep
        # retrying while holding the claim.
        while True:
            try:
                if maxtries is not None and counter == maxtries:
                    break
                counter += 1
                if not self.cdc.connected:
                    self.cdc.connected = self.cdc.connect()
                    if not self.cdc.connected and counter == 1:
                        self.warning(
                            "USB open failed — is the device attached "
                            "and permission granted via Detect Device?"
                        )
                if self.cdc.connected and self.run_handshake():
                    return True
                else:
                    if not android:
                        try:
                            self.cdc.close()
                        except Exception:
                            pass
                    else:
                        try:
                            if hasattr(self.cdc, "flush"):
                                self.cdc.flush()
                        except Exception:
                            pass
                        try:
                            iface_count = 0
                            if hasattr(self.cdc, "get_interface_count"):
                                iface_count = self.cdc.get_interface_count()
                            elif hasattr(self.cdc, "getInterfaceCount"):
                                iface_count = self.cdc.getInterfaceCount()
                            if not iface_count:
                                self.cdc.connected = False
                        except Exception:
                            self.cdc.connected = False
                    if loop == 5:
                        sys.stdout.write('\n')
                        self.info(
                            "Hint:\n\nPower off the phone before connecting.\n"
                            "For brom mode, press and hold vol up, vol dwn, or all hw buttons and "
                            "connect usb.\n"
                            "For preloader mode, don't press any hw button and connect usb.\n"
                            "If it is already connected and on, hold power for 10 seconds to reset.\n"
                        )
                        sys.stdout.write('\n')
                    if loop >= 10:
                        sys.stdout.write('.')
                    if loop >= 20:
                        sys.stdout.write('\n')
                        loop = 0
                    loop += 1
                    time.sleep(0.05 if android else 0.3)
                    sys.stdout.flush()

            except Exception as serr:
                if "access denied" in str(serr):
                    self.warning(str(serr))
                self.debug(str(serr))
                if not android:
                    try:
                        self.cdc.close()
                    except Exception:
                        pass
        return False

    def mtk_cmd(self, value, bytestoread=0, nocmd=False):
        resp = b""
        dlen = len(value)
        wr = self.usbwrite(value)
        time.sleep(0.05)
        if wr:
            if nocmd:
                cmdrsp = self.usbread(bytestoread)
                return cmdrsp
            else:
                cmdrsp = self.usbread(dlen)
                if cmdrsp[0] is not value[0]:
                    self.error(f"Cmd error :{hexlify(cmdrsp).decode('utf-8')}")
                    return -1
                if bytestoread > 0:
                    resp = self.usbread(bytestoread)
                return resp
        else:
            self.warning(f"Couldn't send :{hexlify(value).decode('utf-8')}")
            return resp

    def echo(self, data):
        if isinstance(data, int):
            data = pack(">I", data)
        if isinstance(data, bytes):
            data = [data]
        for val in data:
            self.usbwrite(val)
            tmp = self.usbread(len(val), maxtimeout=0)
            if val != tmp:
                return False
        return True
