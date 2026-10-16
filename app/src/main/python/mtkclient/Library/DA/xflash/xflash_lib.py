#!/usr/bin/python3
# -*- coding: utf-8 -*-
# (c) B.Kerler 2018-2025 GPLv3 License
import logging
import time
import os
import sys
from binascii import hexlify
from struct import pack, unpack
from queue import Queue
from threading import Thread
from Cryptodome.Util.number import long_to_bytes

from mtkclient.Library.Auth.sla import generate_da_sla_signature
from mtkclient.Library.DA.storage import (UfsInfo, NorInfo, NandInfo,
                                          EmmcInfo, RamInfo, DaStorage, EmmcPartitionType)
from mtkclient.Library.DA.xflash.xflash_flash_param import NandExtension
from mtkclient.Library.DA.xflash.xflash_param import Cmd, ChecksumAlgorithm, FtSystemOSE, DataType
from mtkclient.Library.gui_utils import LogBase, logsetup, progress
from mtkclient.Library.error import ErrorHandler
from mtkclient.Library.DA.daconfig import DAconfig
from mtkclient.Library.partition import Partition
from mtkclient.config.payloads import PathConfig
from mtkclient.Library.DA.xflash.extension.xflash import XFlashExt, XCmd
from mtkclient.Library.thread_handling import writedata


class DAXFlash(metaclass=LogBase):
    """ Handles XFlash protocol """

    def __init__(self, mtk, daconfig, loglevel=logging.INFO):
        # self.extensions_address = 0x68000000
        self.extensions_address = 0x4FFF0000
        self.daversion = None
        (self.__logger, self.info, self.debug, self.warning,
         self.error) = logsetup(self, self.__logger, loglevel, mtk.config.gui)
        self.cmd = Cmd()
        self.checksum_algorithm = ChecksumAlgorithm()
        self.ft_system_ose = FtSystemOSE()
        self.data_type = DataType()
        self.mtk = mtk
        self.loglevel = loglevel
        self.daext = False
        self.sram = None
        self.dram = None
        self.emmc = None
        self.nand = None
        self.nor = None
        self.ufs = None
        self.chipid = None
        self.randomid = None
        self.__logger = self.__logger
        self.eh = ErrorHandler()
        self.config = self.mtk.config
        self.usbwrite = self.mtk.port.usbwrite
        self.usbread = self.mtk.port.usbread
        self.echo = self.mtk.port.echo
        self.rbyte = self.mtk.port.rbyte
        self.rdword = self.mtk.port.rdword
        self.daconfig = daconfig
        self.partition = Partition(self.mtk, self.readflash, self.read_pmt, loglevel)
        self.pathconfig = PathConfig()
        self.generatekeys = self.mtk.config.generatekeys
        if self.generatekeys:
            self.mtk.daloader.patch = True
        self.xft = XFlashExt(self.mtk, self, loglevel)

        try:
            # from mtkclient.Library.Exploit.kamakiripl import KamakiriPl
            # self.kamakiri_pl = KamakiriPl(self.mtk, loglevel)
            self.kamakiri_pl = None
        except Exception:
            self.kamakiri_pl = None

        try:
            from mtkclient.Library.Exploit.carbonara import Carbonara
            self.carbonara = Carbonara(self.mtk, loglevel)
        except Exception:
            self.carbonara = None

    @staticmethod
    def usleep(usec):
        time.sleep(usec / 100000)

    def _android_usb(self) -> bool:
        return os.environ.get("MTK_USE_ANDROID_USB") == "1"

    def _prep_ack_out(self):
        """Intentionally empty. A ZLP after a max-packet-aligned payload is
        already skipped by the next header read (a 0-byte IN success is a
        terminator, not data). The old pre-ACK "drain" issued a short IN URB
        with an 8 ms timeout, which made libusb cancel an in-flight URB on the
        bulk IN endpoint once per frame -- a much more likely source of
        endpoint trouble than the ZLP it was meant to handle."""
        return

    FAST_READ_BATCH = 16
    # Upper bound on the bytes one fused call may buffer. 16 x 64 KiB frames is
    # 1 MiB; without this cap 16 x 2 MiB frames (the DA's larger read frame)
    # would allocate and copy 32 MiB per call. A 2 MiB frame is already one
    # call per 2 MiB, so the batch simply shrinks to a single frame.
    FAST_READ_BATCH_BYTES = 1 << 20

    def _applog_event(self, level, message):
        """Mirror a diagnostic line into the app's log (Settings -> Logging)."""
        try:
            from com.bareflash.app.log import AppLog
            AppLog.fromPython(level, "xflash", message, None)
        except Exception:
            pass

    def _fast_read_ok(self) -> bool:
        """Fused native READ_DATA loop is usable: Android transport, the
        two-OUT ACK form (everything except dacode 0x6781), and a bridge that
        has it."""
        if not self._android_usb():
            return False
        if self.mtk.config.chipconfig.dacode in [0x6781]:
            return False
        try:
            from com.bareflash.app import UsbBridge as _UsbBridge
            # Off unless the app switches it on: on-device logs showed the
            # first natively-read frame header arriving shifted by one byte.
            if not bool(_UsbBridge.isFusedReadEnabled()):
                return False
            return hasattr(_UsbBridge, "readFrames")
        except Exception:
            return False

    # ---- DA log over USB (Settings -> Experimental, diagnostic, off by default)
    # With log_channel=2 the DA sends its log lines as DT_MESSAGE packets
    # (magic, type 2, len, then a u32 and the text) in the same stream as the
    # protocol replies. They can appear ahead of any reply, so every header
    # read goes through _rd_hdr(), which logs and skips them. All of this is
    # inert unless _da_log_on is set in setup_env.
    _da_log_on = False
    _da_log_pump_next = False
    _hdr_stash = None
    DA_LOG_MAX_MSG = 4096

    def _da_log_usb_requested(self) -> bool:
        if not self._android_usb():
            return False
        try:
            from com.bareflash.app import UsbBridge as _UsbBridge
            return bool(_UsbBridge.isDaLogUsbEnabled())
        except Exception:
            return False

    def _da_msg_body(self, hdr) -> bool:
        """True if `hdr` (12 bytes already read) was a DA log packet; its
        payload is then consumed and logged. Otherwise nothing is touched."""
        if not self._da_log_on or len(hdr) != 12:
            return False
        magic, dtype, plen = unpack("<III", hdr)
        if (magic != 0xFEEEEEEF or dtype != self.data_type.DT_MESSAGE
                or plen > self.DA_LOG_MAX_MSG):
            return False
        body = self.usbread(plen, maxtimeout=2000) if plen else b""
        if len(body) < plen:
            self.warning(f"[xflash] DA log packet truncated: {len(body)} of {plen} bytes")
        raw = bytes(body[4:] if len(body) >= 4 else body)
        text = raw.decode("ascii", "replace").replace("\x00", "").strip()
        if text:
            self._applog_event("I", f"[DA] {text}")
            if ("MBps" in text or "SPEED DUMP" in text or "DUMP END" in text):
                print(f"[xflash] DA: {text}")
        return True

    def _rd_hdr(self, maxtimeout=None):
        """Read one 12-byte protocol header, skipping DA log packets."""
        hdr = b""
        for _ in range(512):
            if self._hdr_stash is not None:
                hdr, self._hdr_stash = self._hdr_stash, None
            elif maxtimeout is None:
                hdr = self.usbread(12)
            else:
                hdr = self.usbread(12, maxtimeout=maxtimeout)
            if self._da_msg_body(hdr):
                continue
            return hdr
        return hdr

    def _da_log_pump(self, wait_ms=20):
        """Before the host writes a command: take any log packet the DA has
        queued, so the DA is never left blocked on its USB IN while we send."""
        if not self._da_log_on:
            return
        for _ in range(32):
            hdr = self.usbread(12, maxtimeout=wait_ms)
            if len(hdr) != 12:
                return
            if not self._da_msg_body(hdr):
                # A real reply that arrived early: keep it for the next reader.
                self._hdr_stash = bytes(hdr)
                return

    def _da_report_windows(self) -> bool:
        """Settings -> Experimental -> "Larger read frames". True makes setup_env
        report system_os=Windows. Always False off Android or if the bridge
        lacks the switch, so the protocol stays exactly as before."""
        if not self._android_usb():
            return False
        try:
            from com.bareflash.app import UsbBridge as _UsbBridge
            return bool(_UsbBridge.isDaWindowsEnvEnabled())
        except Exception:
            return False

    def _fast_read_batch(self, frame: int, nframes: int):
        from com.bareflash.app import UsbBridge as _UsbBridge
        buf = _UsbBridge.readFrames(int(frame), int(nframes), 8000)
        info = [int(x) for x in _UsbBridge.readFramesInfo()]
        hdr = bytes(_UsbBridge.readFramesHdr())
        return (bytes(buf) if buf is not None else b""), info, hdr

    def _usb_diag(self) -> str:
        """One-line USB transport state for failure messages (Android only)."""
        if not self._android_usb():
            return ""
        try:
            from com.bareflash.app import UsbBridge as _UsbBridge
            err = str(_UsbBridge.getLastXferError() or "none")
            present = bool(_UsbBridge.isDevicePresent())
            return f"usb: last_error={err}, device_present={present}"
        except Exception:
            return ""

    def _sum16(self, data) -> int:
        """xflash write checksum. CPython sums 2 MiB in ~40 ms per chunk,
        which is a third of the whole transfer time at 15 MB/s."""
        if self._android_usb():
            try:
                from com.bareflash.app import UsbBridge as _UsbBridge
                return int(_UsbBridge.byteSum16(bytes(data))) & 0xFFFF
            except Exception:
                pass
        return sum(data) & 0xFFFF

    def ack(self, rstatus=True):
        try:
            # 0x6781 DA reads header+payload from ONE USB transaction.
            # Every other chip (MT6765/0x6765 Helio P35, MT6771, 0x6750,
            # 0x6762, 0x6785, 0x6761, ...) reads TWO: a 12-byte header
            # then a 4-byte payload. Combining them into one 16-byte OUT
            # (the old Android "64 KiB" workaround) makes those DAs take
            # the header and lose the payload in the same URB -- they
            # then sit forever waiting for a 4-byte packet that will not
            # arrive, and the host dump dies at exactly one
            # read_packet_length (0x10000) with "Incomplete flash read".
            # Keep the 16-byte shape only for 0x6781. On Android, make
            # the two tiny OUTs reliable by draining a leftover IN ZLP
            # and retrying a failed write -- do not merge the packets.
            one_shot = self.mtk.config.chipconfig.dacode in [0x6781]
            if one_shot:
                stmp = pack("<IIII", self.cmd.MAGIC, self.data_type.DT_PROTOCOL_FLOW, 4, 0)
                if not self._usbwrite_retry(stmp):
                    return -1
            else:
                stmp = pack("<III", self.cmd.MAGIC, self.data_type.DT_PROTOCOL_FLOW, 4)
                data = pack("<I", 0)
                self._prep_ack_out()
                if not self._usbwrite_retry(stmp):
                    return -1
                if not self._usbwrite_retry(data):
                    return -1
            if rstatus:
                status = self.status()
                return status
            return True
        except Exception:
            return -1

    def _usbwrite_retry(self, payload: bytes) -> bool:
        if self.usbwrite(payload):
            return True
        if not self._android_usb():
            return False
        # One bounded retry: USBFS sometimes reports 0 on a tiny OUT
        # submitted immediately after a large IN even though the
        # endpoint is not stalled.
        try:
            time.sleep(0.002)
        except Exception:
            pass
        return bool(self.usbwrite(payload))

    def xsend(self, data, datatype=DataType.DT_PROTOCOL_FLOW, is64bit: bool = False):
        if isinstance(data, int):
            if is64bit:
                data = pack("<Q", data)
                length = 8
            else:
                data = pack("<I", data)
                length = 4
        else:
            length = len(data)
        if self._da_log_on:
            self._da_log_pump()
            self._da_log_pump_next = True
        tmp = pack("<III", self.cmd.MAGIC, datatype, length)
        if self.usbwrite(tmp):
            return self.usbwrite(data)
        return False

    def xread(self):
        try:
            hdr = self._rd_hdr()
            magic, _, length = unpack("<III", hdr)
        except Exception as err:
            self.error(f"xread error: {str(err)}")
            return -1
        if magic != 0xFEEEEEEF:
            self.error("xread error: Wrong magic")
            return -1
        resp = self.usbread(length)
        return resp

    def rdword(self, count=1):
        data = []
        for _ in range(count):
            data.append(unpack("<I", self.xread())[0])
        if count == 1:
            return data[0]
        return data

    def status(self):
        hdr = self._rd_hdr()
        if len(hdr) != 12:
            # ZLP after a max-packet-aligned payload, or a late frame:
            # one bounded retry before failing soft.
            hdr = self._rd_hdr(2000)
        if len(hdr) != 12:
            # A short/empty header here used to hit unpack("<III", hdr)
            # directly, which raises "unpack requires a buffer of 12
            # bytes" and aborts whatever command was waiting on this
            # status (get_packet_length, writeflash's final status, ...).
            # Because the exception fires before the (possibly still
            # in-flight) remainder of this frame is drained, every status()
            # call after it reads misaligned data from the stream, which is
            # what shows up downstream as "Unknown: 0x200000" style errors.
            # Fail soft instead so callers see a normal error status and
            # the caller-level retry logic gets a chance to resync.
            # Fail soft: a short/empty header here used to hit unpack()
            # and abort the command. Callers treat -1 as "no status".
            # Use warning (not error) so Android's logger.error tracker
            # does not mark a completed flash as a failed command.
            self.warning(f"Status header error: Too few data {hex(len(hdr))}")
            return -1
        magic, _, length = unpack("<III", hdr)
        if magic != 0xFEEEEEEF:
            self.warning("Status error: Wrong magic")
            return -1
        tmp = self.usbread(length)
        if len(tmp) < length:
            self.warning(f"Status length error: Too few data {hex(len(hdr))}")
            return -1
        if length == 2:
            status = unpack("<H", tmp)[0]
            if status == 0x0:
                return 0
        elif length == 4:
            status = unpack("<I", tmp)[0]
            if status == 0xFEEEEEEF:
                return 0
        else:
            status = unpack("<" + str(length // 4) + "I", tmp)[0]
        return status

    def read_pmt(self) -> tuple:
        return self.partition.get_pmt()

    def send_param(self, params):
        if isinstance(params, bytes):
            params = [params]
        if self._da_log_pump_next:
            # Once per command (not per write frame): its "Enter cmd" log line
            # is queued right now.
            self._da_log_pump_next = False
            self._da_log_pump()
        for param in params:
            pkt = pack("<III", self.cmd.MAGIC, self.data_type.DT_PROTOCOL_FLOW, len(param))
            if self.usbwrite(pkt):
                length = len(param)
                pos = 0
                # 1 MiB per USB call on Android: libusb splits it into 16 KiB
                # usbfs URBs and keeps them all in flight. This is only
                # packetization of an already-announced protocol payload, not
                # a cap on DA write_packet_length; every chunk is a multiple
                # of 512 so the DA sees one continuous stream.
                usb_chunk = 0x100000 if self._android_usb() else 0x4000
                while length > 0:
                    dsize = min(length, usb_chunk)
                    if not self.usbwrite(param[pos:pos + dsize]):
                        break
                    pos += dsize
                    length -= dsize
                if length > 0:
                    self.error(f"Short USB write in send_param: {pos} of {len(param)} bytes")
                    return False
        status = self.status()
        if status == 0:
            return True
        if status != 0xc0040050:
            self.error(f"Error on sending parameter: {self.eh.status(status)}")
            if status == 0xc0020053:
                # Anti roll back DA error
                sys.exit(1)
        return False

    def send_devctrl(self, cmd, param=None, status=None, read_data=True):
        if status is None:
            status = [0]
        if self.xsend(self.cmd.DEVICE_CTRL):
            status[0] = self.status()
            if status[0] == 0x0:
                if self.xsend(cmd):
                    status[0] = self.status()
                    if status[0] == 0x0:
                        if param is None:
                            if not read_data:
                                return b""
                            return self.xread()
                        return self.send_param(param)
        # 0xC0010004 = not supported. CC_OPTIONAL_DOWNLOAD_ACT is
        # post-write housekeeping the DA may ignore; never fail the
        # flash that already committed.
        if status[0] != 0xC0010004:
            if cmd == self.cmd.CC_OPTIONAL_DOWNLOAD_ACT:
                self.warning(
                    f"Optional download act ignored: {self.eh.status(status[0])}"
                )
            else:
                self.error(f"Error on sending dev ctrl {hex(cmd)}: " + self.eh.status(status[0]))
        return b""

    def set_reset_key(self, reset_key=0x68):
        # default:0x0,one:0x50,two:0x68
        param = pack("<I", reset_key)
        return self.send_devctrl(self.cmd.SET_RESET_KEY, param)

    def set_meta(self, porttype="off"):
        class MtkBootModeFlag:
            boot_mode = b"\x00"  # 0:normal, 1:meta
            com_type = b"\x00"  # 0:unknown, 1:uart, 2:usb
            com_id = b"\x00"  # 0:single interface device (meta,adb)

            # 1:composite device (meta, adb disable)
            # 2:no meta, adb enable
            # 3:no meta, adb disable

            def __init__(self, mode="off"):
                if mode == "off":
                    self.boot_mode = b"\x00"
                    self.com_type = b"\x00"
                    self.com_id = b"\x00"
                elif mode == "uart":
                    self.boot_mode = b"\x01"
                    self.com_type = b"\x01"
                    self.com_id = b"\x00"
                elif mode == "usb":
                    self.boot_mode = b"\x01"
                    self.com_type = b"\x02"
                    self.com_id = b"\x00"

            def get(self):
                return self.boot_mode + self.com_type + self.com_id

        metamode = MtkBootModeFlag(porttype).get()
        return self.send_devctrl(self.cmd.SET_META_BOOT_MODE, metamode)

    def set_checksum_level(self, checksum_level=0x0):
        param = pack("<I", checksum_level)
        # none[0x0]. USB[0x1]. storage[0x2], both[0x3]
        return self.send_devctrl(self.cmd.SET_CHECKSUM_LEVEL, param)

    def set_battery_opt(self, option=0x2):
        param = pack("<I", option)
        # battery[0x0]. USB power[0x1]. auto[0x2]
        return self.send_devctrl(self.cmd.SET_BATTERY_OPT, param)

    def send_emi(self, emi):
        if self.xsend(self.cmd.INIT_EXT_RAM):
            status = self.status()
            if status == 0:
                try:
                    time.sleep(0.01)
                    if self.xsend(pack("<I", len(emi))):
                        try:
                            if self.send_param([emi]):
                                self.info("DRAM setup passed.")
                                return True
                        except Exception as err:
                            self.info(f"DRAM setup failed: {str(err)}. Use mtk.py with --preloader preloader.bin !")
                            return False
                except Exception as err:
                    self.error(f"Error on sending emi: {str(err)}")
                    return False
            else:
                self.error(f"Error on sending emi: {self.eh.status(status)}")
        return False

    def send_data(self, data):
        pkt2 = pack("<III", self.cmd.MAGIC, self.data_type.DT_PROTOCOL_FLOW, len(data))
        if self.usbwrite(pkt2):
            bytestowrite = len(data)
            maxoutsize = self.mtk.port.cdc.EP_OUT.wMaxPacketSize
            pos = 0
            while bytestowrite > 0:
                if self.usbwrite(data[pos:pos + maxoutsize]):
                    pos += maxoutsize
                    bytestowrite -= maxoutsize
            status = self.status()  # 0xC0070004
            if status == 0x0:
                return True
            self.error(f"Error on sending data: {self.eh.status(status)}")
        return False

    def _status_after_jump(self, addr, reconnect_budget_s=3.0, poll_interval_s=0.2):
        """
        On this device, the stage1->stage2 (and DA-extensions) jump
        intermittently causes a REAL USB detach/reattach -- confirmed by a
        manual log capture showing the terminal's "Waiting for device..."
        collapse-line (real ACTION_USB_DEVICE_ATTACHED/DETACHED broadcasts,
        per TerminalNoiseFilter.kt) landing exactly at this jump, not just
        during the earlier, expected BROM/Kamakiri re-enumerations. When
        that happens, Android tears down and recreates the
        UsbDeviceConnection as a new object -- unlike desktop pyusb/libusb,
        which can often ride through the same blip on the same handle --
        so the very next status() read here comes back as an immediate,
        genuine "Too few data 0x0": not a slow device, a connection to a
        device that's no longer there. It's intermittent (a prior run on
        this same device jumped straight to "Boot to succeeded" with no
        gap at all), consistent with a real, timing-sensitive bus event
        rather than a fixed protocol bug -- so retry through it rather
        than failing the whole boot on what is often a few hundred ms of
        bus silence.

        cdc.connect() (androidusblib.UsbClass.connect(), the same call
        that opened the connection the first time) is safe to call again
        here: it only succeeds once the device is actually back and
        already has USB permission -- both already relied on elsewhere in
        this exact exploit chain (see UsbBridge.waitAndRawControlTransfer,
        used after the Kamakiri reset) -- and usbread()/usbwrite() always
        go through UsbBridge's current native connection state, not a
        cached reference, so a reopened connection is picked up
        immediately with no other changes needed.
        """
        try:
            status = self.status()
        except Exception:
            status = -1
        if status != -1:
            return status

        cdc = getattr(getattr(self.mtk, "port", None), "cdc", None)
        deadline = time.time() + reconnect_budget_s
        reconnected = False
        while time.time() < deadline:
            if cdc is not None and hasattr(cdc, "connect"):
                try:
                    if cdc.connect():
                        reconnected = True
                except Exception:
                    pass
            time.sleep(poll_interval_s)
            try:
                status = self.status()
            except Exception:
                status = -1
            if status != -1:
                break
        if reconnected:
            shown = hex(status) if status >= 0 else status
            print(f"[xflash] boot_to: device reappeared mid-jump (addr={hex(addr)}), "
                  f"status after reconnect+retry: {shown}")
        return status

    def boot_to(self, addr, da, display=True, timeout=0.5):  # =0x40000000
        if self.xsend(self.cmd.BOOT_TO):
            if self.status() == 0:
                param = pack("<QQ", addr, len(da))
                pkt1 = pack("<III", self.cmd.MAGIC, self.data_type.DT_PROTOCOL_FLOW, len(param))
                if self.usbwrite(pkt1):
                    if self.usbwrite(param):
                        if self.send_data(da):
                            # if addr == 0x68000000:
                            if addr == self.extensions_address:
                                if display:
                                    self.info("Extensions were accepted. Jumping to extensions...")
                            else:
                                if display:
                                    self.info("Upload data was accepted. Jumping to stage 2...")
                            if timeout:
                                time.sleep(timeout)
                            status = self._status_after_jump(addr)

                            if status == 0x434E5953 or status == 0x0:
                                if display:
                                    self.info("Boot to succeeded.")
                                return True
                            self.error(f"Error on boot to: {self.eh.status(status)}, addr: {hex(addr)}")
                        else:
                            self.error(f"Error on boot to send_data, addr: {hex(addr)}")
                    else:
                        self.error(f"Error on boot usbwrite, addr: {hex(addr)}")
                else:
                    self.error(f"Error on boot usbwrite, addr: {hex(addr)}")
            else:
                self.error(f"Error on boot to, addr: {hex(addr)}")
        return False

    def get_connection_agent(self):
        # brom
        res = self.send_devctrl(self.cmd.GET_CONNECTION_AGENT)
        if res != b"":
            status = self.status()
            if status == 0x0:
                return res
            self.error(f"Error on getting connection agent: {self.eh.status(status)}")
        return None

    """
    def get_dram_type(self):
        res = self.send_devctrl(self.Cmd.GET_DRAM_TYPE)
        status = self.status()
        if status == 0x0:
            return res
    """

    def formatflash(self, addr, length, storage=None,
                    parttype=None, display=False):
        part_info = self.daconfig.storage.get_storage(parttype, length)
        if not part_info:
            return False
        storage, parttype, length = part_info
        pg = progress(total=length, prefix="Erasing:", guiprogress=self.mtk.config.guiprogress)
        if display:
            self.info(f"Formatting addr {hex(addr)} with length {hex(length)}, please standby....")
        if self.xsend(self.cmd.FORMAT):
            status = self.status()
            if status == 0:
                # storage: emmc:1,slc,nand,nor,ufs
                # section: boot,user of emmc:8, LU1, LU2

                ne = NandExtension()
                param = pack("<IIQQ", storage, parttype, addr, length)
                param += pack("<IIIIIIII", ne.cellusage, ne.addr_type, ne.bin_type, ne.region,
                              ne.format_level, ne.sys_slc_percent, ne.usr_slc_percent, ne.phy_max_size)
                if self.send_param(param):
                    status = self.status()
                    while status == 0x40040004:  # STATUS_CONTINUE
                        # it receive some data maybe sleep in ms time,
                        time.sleep(self.status() / 1000.0)
                        status = self.ack()
                    if status == 0x40040005:  # STATUS_COMPLETE
                        if display:
                            pg.update(length)
                            self.info(f"Successsfully formatted addr {hex(addr)} with length {length}.")
                        return True

            if status != 0x0:
                self.error(f"Error on format: {self.eh.status(status)}")
        return False

    def get_da_version(self, display=True):
        data = self.send_devctrl(self.cmd.GET_DA_VERSION)
        if data != b"":
            status = self.status()
            if status == 0:
                if display:
                    self.info(f"DA-VERSION:       {data.decode('utf-8')}")
                return data
            if display:
                self.error(f"Error on getting chip id: {self.eh.status(status)}")
            return None
        return None

    def get_chip_id(self, display=True):
        class Chipid:
            hw_code = 0
            hw_sub_code = 0
            hw_version = 0
            sw_version = 0
            chip_evolution = 0

        chipid = Chipid
        data = self.send_devctrl(self.cmd.GET_CHIP_ID)
        if data != b"":
            (chipid.hw_code, chipid.hw_sub_code, chipid.hw_version,
             chipid.sw_version, chipid.chip_evolution) = (unpack("<HHHHH", data[:(5 * 2)]))
            status = self.status()
            if status == 0:
                if display:
                    self.info(f"HW-CODE:          {hex(chipid.hw_code)}")
                    self.info(f"HWSUB-CODE:       {hex(chipid.hw_sub_code)}")
                    self.info(f"HW-VERSION:       {hex(chipid.hw_version)}")
                    self.info(f"SW-VERSION:       {hex(chipid.sw_version)}")
                    self.info(f"CHIP-EVOLUTION:   {hex(chipid.chip_evolution)}")
                return chipid
            self.error(f"Error on getting chip id: {self.eh.status(status)}")
        return None

    def get_ram_info(self):
        resp = self.send_devctrl(self.cmd.GET_RAM_INFO)
        if resp != b"":
            status = self.status()
            if status == 0x0:
                sram = RamInfo()
                dram = RamInfo()
                if len(resp) == 24:
                    (sram.type, sram.base_address, sram.size, dram.type,
                     dram.base_address, dram.size) = unpack("<IIIIII", resp)
                elif len(resp) == 48:
                    (sram.type, sram.base_address, sram.size, dram.type,
                     dram.base_address, dram.size) = unpack("<QQQQQQ", resp)
                self.daconfig.storage.sram = sram
                self.daconfig.storage.dram = dram
                return sram, dram
            self.error(f"Error on getting ram info: {self.eh.status(status)}")
        return None, None

    def get_emmc_info(self, display=True):
        resp = self.send_devctrl(self.cmd.GET_EMMC_INFO)
        if resp == b'':
            return None
        status = self.status()
        if status == 0:
            emmc = EmmcInfo()
            pos = 0
            emmc.type, emmc.block_size = unpack("<II", resp[pos:pos + 8])
            pos += 8
            (emmc.boot1_size, emmc.boot2_size, emmc.rpmb_size, emmc.gp1_size,
             emmc.gp2_size, emmc.gp3_size, emmc.gp4_size, emmc.user_size) = (
                unpack("<QQQQQQQQ", resp[pos:pos + (8 * 8)]))
            pos += 8 * 8
            emmc.cid = resp[pos:pos + (4 * 4)]
            pos += (4 * 4)
            emmc.fwver = unpack("<Q", resp[pos:pos + 8])[0]
            pos += 8
            emmc.unknown = resp[pos:]
            if emmc.type != 0 and display:
                self.info(f"EMMC FWVer:      {hex(emmc.fwver)}")
                try:
                    self.info(f"EMMC ID:         {emmc.cid[3:9].decode('utf-8')}")
                except Exception:
                    pass
                self.info(f"EMMC CID:        {hexlify(emmc.cid).decode('utf-8')}")
                if self.config.hwparam is not None:
                    self.config.set_cid(emmc.cid)
                self.info(f"EMMC Boot1 Size: {hex(emmc.boot1_size)}")
                self.info(f"EMMC Boot2 Size: {hex(emmc.boot2_size)}")
                self.info(f"EMMC GP1 Size:   {hex(emmc.gp1_size)}")
                self.info(f"EMMC GP2 Size:   {hex(emmc.gp2_size)}")
                self.info(f"EMMC GP3 Size:   {hex(emmc.gp3_size)}")
                self.info(f"EMMC GP4 Size:   {hex(emmc.gp4_size)}")
                self.info(f"EMMC RPMB Size:  {hex(emmc.rpmb_size)}")
                self.info(f"EMMC USER Size:  {hex(emmc.user_size)}")
            return emmc
        self.error(f"Error on getting emmc info: {self.eh.status(status)}")
        return None

    def get_nand_info(self, display=True):
        resp = self.send_devctrl(self.cmd.GET_NAND_INFO)
        if resp == b'':
            return None
        status = self.status()
        if status == 0:
            nand = NandInfo()
            pos = 0
            nand.type, nand.page_size, nand.block_size, nand.spare_size = (
                unpack("<IIII", resp[pos:pos + 16]))
            pos += 16
            nand.total_size, nand.available_size = unpack("<QQ", resp[pos:pos + (2 * 8)])
            pos += 2 * 8
            nand.nand_bmt_exist = resp[pos:pos + 1]
            pos += 1
            nand.nand_id = bytearray(resp[pos:pos + 12])
            if nand.type != 0:
                self.mtk.config.pagesize = nand.page_size
                self.mtk.daloader.daconfig.pagesize = nand.page_size
                if display:
                    self.info(f"NAND Pagesize:   {hex(nand.page_size)}")
                    self.info(f"NAND Blocksize:  {hex(nand.block_size)}")
                    self.info(f"NAND Sparesize:  {hex(nand.spare_size)}")
                    self.info(f"NAND Total size: {hex(nand.total_size)}")
                    self.info(f"NAND Avail:      {hex(nand.available_size)}")
                    self.info(f"NAND ID:         {nand.nand_id.hex()}")
            return nand
        self.error(f"Error on getting nand info: {self.eh.status(status)}")
        return None

    def get_rpmb_status(self):
        resp = self.send_devctrl(self.cmd.GET_RPMB_STATUS)
        if resp == b'':
            return None
        status = self.status()
        if status == 0:
            return resp
        return None

    def get_nor_info(self, display=True):
        resp = self.send_devctrl(self.cmd.GET_NOR_INFO)
        if resp == b'':
            return None
        status = self.status()
        if status == 0:
            nor = NorInfo()
            nor.type, nor.page_size, nor.available_size = unpack("<IIQ", resp[:16])
            if nor.type != 0:
                if display:
                    self.info(f"NOR Pagesize: {hex(nor.page_size)}")
                    self.info(f"NOR Size:     {hex(nor.available_size)}")
            return nor
        self.error(f"Error on getting nor info: {self.eh.status(status)}")
        return None

    def get_ufs_info(self, display=True):
        resp = self.send_devctrl(self.cmd.GET_UFS_INFO)
        if resp == b'':
            return None
        status = self.status()
        if status == 0:
            ufs = UfsInfo()
            ufs.type, ufs.block_size, ufs.lu2_size, ufs.lu1_size, ufs.lu0_size = (
                unpack("<IIQQQ", resp[:(2 * 4) + (3 * 8)]))
            pos = (2 * 4) + (3 * 8)
            buf = resp[pos:]
            ufs.cid = buf[:16]
            ufs.fwver = buf[22:22 + 4]
            ufs.serial = buf[30:30 + 0xC]
            if ufs.type != 0:
                if display:
                    self.info(f"UFS Blocksize: {hex(ufs.block_size)}")
                    try:
                        self.info(f"UFS ID:       {ufs.cid[2:].decode('utf-8')}")
                    except Exception:
                        pass
                    self.info(f"UFS MID:      {hex(ufs.cid[0])}")
                    self.info(f"UFS CID:      {hexlify(ufs.cid).decode('utf-8')}")
                    self.info(f"UFS FWVer:    {hexlify(ufs.fwver).decode('utf-8')}")
                    self.info(f"UFS Serial:   {hexlify(ufs.serial).decode('utf-8')}")
                    self.info(f"UFS LU0 Size: {hex(ufs.lu0_size)}")
                    self.info(f"UFS LU1 Size: {hex(ufs.lu1_size)}")
                    self.info(f"UFS LU2 Size: {hex(ufs.lu2_size)}")
                if self.config.hwparam is not None:
                    self.config.set_cid(buf[:0x11 + 2] + buf[0x16:0x16 + 4 + 1] + buf[0x1E:0x1E + 0xC])
                self.mtk.config.pagesize = ufs.block_size
                self.mtk.daloader.daconfig.pagesize = ufs.block_size
            return ufs
        self.error(f"Error on getting ufs info: {self.eh.status(status)}")
        return None

    def get_expire_date(self):
        res = self.send_devctrl(self.cmd.GET_EXPIRE_DATE)
        if res != b"":
            status = self.status()
            if status == 0x0:
                return res
            self.error(f"Error on getting expire date: {self.eh.status(status)}")
        return None

    def get_random_id(self):
        res = self.send_devctrl(self.cmd.GET_RANDOM_ID)
        if res != b"":
            status = self.status()
            if status == 0:
                return res
            self.error(f"Error on getting random id: {self.eh.status(status)}")
        return None

    def get_hrid(self):
        res = self.send_devctrl(self.cmd.GET_HRID)
        if res != b"":
            status = self.status()
            if status == 0:
                return res
            self.error(f"Error on getting hrid info: {self.eh.status(status)}")
        return None

    def get_dev_fw_info(self):
        res = self.send_devctrl(self.cmd.GET_DEV_FW_INFO)
        if res != b"":
            status = self.status()
            if status == 0:
                return res
            self.error(f"Error on getting dev fw info: {self.eh.status(status)}")
        return None

    def get_da_stor_life_check(self):
        res = self.send_devctrl(self.cmd.DA_STOR_LIFE_CYCLE_CHECK)
        if res != b"":
            return unpack("<I", res)[0]
        return 0

    def get_partition_table_category(self):
        res = self.send_devctrl(self.cmd.GET_PARTITION_TBL_CATA)
        if res != b"":
            value = unpack("<I", res)[0]
            if value == 0x64:
                return "GPT"
            if value == 0x65:
                return "PMT"
        return 0

    def get_packet_length(self):
        cached = getattr(self, "_plen_cache", None)
        if cached is not None:
            return cached
        resp = self.send_devctrl(self.cmd.GET_PACKET_LENGTH)
        if resp != b"" and resp != -1 and isinstance(resp, (bytes, bytearray)) and len(resp) >= 8:
            status = self.status()
            class Packetlen:
                write_packet_length = 0
                read_packet_length = 0

            plen = Packetlen()
            plen.write_packet_length, plen.read_packet_length = unpack("<II", resp[:8])
            # Extra status after the payload is missing on some DAs
            # (already consumed by send_devctrl/xread). Do not discard
            # a valid length pair -- writeflash would then crash on None.
            if status not in (0, -1):
                self.warning(f"get_packet_length extra status: {self.eh.status(status)}")
            print(f"[xflash] get_packet_length: write={hex(plen.write_packet_length)} "
                  f"read={hex(plen.read_packet_length)}")
            self._plen_cache = plen
            return plen
        return None

    def get_sla_status(self):
        resp = self.send_devctrl(self.cmd.SLA_ENABLED_STATUS)
        if resp != b"":
            status = self.status()
            if status == 0:
                return int.from_bytes(resp, 'little')
            self.error(f"Error on getting sla enabled status: {self.eh.status(status)}")
        return None

    def get_usb_speed(self):
        resp = self.send_devctrl(self.cmd.GET_USB_SPEED)
        if resp != b"":
            status = self.status()
            if status == 0:
                # full-speed, high-speed, hyper-speed
                return resp
            self.error(f"Error on getting usb speed: {self.eh.status(status)}")
        return None

    def set_usb_speed(self):
        resp = self.xsend(self.cmd.SWITCH_USB_SPEED)
        if resp != b"":
            status = self.status()
            if status == 0:
                if self.xsend(pack("<I", 0x0E8D2001)):
                    status = self.status()
                    if status == 0:
                        return True
            else:
                self.error(f"Error on getting usb speed: {self.eh.status(status)}")
        return False

    def cmd_write_data(self, addr, size, storage=DaStorage.MTK_DA_STORAGE_EMMC,
                       parttype=EmmcPartitionType.MTK_DA_EMMC_PART_USER):
        if self.xsend(self.cmd.WRITE_DATA):
            status = self.status()
            if status == 0:
                # storage: emmc:1,slc,nand,nor,ufs
                # section: boot,user of emmc:8, LU1, LU2
                ne = NandExtension()
                param = pack("<IIQQ", storage, parttype, addr, size)
                param += pack("<IIIIIIII", ne.cellusage, ne.addr_type, ne.bin_type, ne.region,
                              ne.format_level, ne.sys_slc_percent, ne.usr_slc_percent, ne.phy_max_size)
                if self.send_param(param):
                    return True
            else:
                self.error(f"Error on writing data: {self.eh.status(status)}")
        return False

    def cmd_read_data(self, addr, size, storage=DaStorage.MTK_DA_STORAGE_EMMC,
                      parttype=EmmcPartitionType.MTK_DA_EMMC_PART_USER):
        status = -1
        if self.xsend(self.cmd.READ_DATA):
            status = self.status()
            if status == 0:
                ne = NandExtension()
                param = pack("<IIQQ", storage, parttype, addr, size)
                param += pack("<IIIIIIII", ne.cellusage, ne.addr_type, ne.bin_type, ne.region,
                              ne.format_level, ne.sys_slc_percent, ne.usr_slc_percent, ne.phy_max_size)
                if not self.send_param(param):
                    self.error("Error on reading data: send_param failed")
                    return False
                # send_param already waited for status. After that the DA
                # either sends one more 4-byte status frame, or goes straight
                # to the first DATA frame. The old code called status() here,
                # which for a data frame swallows the whole frame (64 KiB),
                # never acks it, and returns the frame's first dword as the
                # "status" -- e.g. 0xffffffff from erased flash -- turning a
                # healthy read into "Error on reading data: Unknown:
                # 0xffffffff". It also blocked ~6 s when nothing came.
                # Peek the 12-byte header instead: a 4-byte frame is the
                # extra status (consume it); anything larger is the first
                # data frame's header, kept for readflash.
                self._pending_read_hdr = None
                self._pending_read_payload = None
                hdr = self._rd_hdr(400)
                if len(hdr) == 12:
                    magic, _, plen_ = unpack("<III", hdr)
                    if magic != 0xFEEEEEEF:
                        self.warning(f"[xflash] read start: unexpected magic {hex(magic)}")
                    elif plen_ > 4:
                        self._pending_read_hdr = bytes(hdr)
                    else:
                        extra = self.usbread(plen_, maxtimeout=2000) if plen_ > 0 else b""
                        if len(extra) >= 4:
                            val = unpack("<I", extra[:4])[0]
                            if val not in (0x0, 0xFEEEEEEF):
                                self.error(f"Error on reading data: {self.eh.status(val)}")
                                return False
                return True
            if status != 0x0 and status != -1:
                self.error(f"Error on reading data: {self.eh.status(status)}")
        return False

    # One READ_DATA command never asks for more than this. A single 25 GB
    # request (userdata) is one long-lived DA state machine; if the DA ends
    # it early for any reason, everything after that point used to be lost
    # and the whole dump failed. Short segments bound the damage, and an
    # early "done" from the DA is resumed from the exact byte offset.
    READ_SEGMENT = 0x40000000  # 1 GiB
    # Upper bound on dump data buffered between the USB loop and the file
    # writer thread. Absorbs storage stalls so the DA is never left waiting.
    READ_QUEUE_BYTES = 32 * 1024 * 1024
    # Consecutive resumes that each moved less than READ_MIN_PROGRESS bytes
    # before the DA ended the read again. Progress >= that resets the count,
    # so a long dump can resume any number of times, but a DA that ends every
    # read immediately still fails fast instead of looping forever.
    READ_MAX_RESUMES = 8
    READ_MIN_PROGRESS = 0x100000

    def readflash(self, addr, length, filename, parttype=None, display=True) -> (bytes, bool):
        partinfo = self.daconfig.storage.get_storage(parttype, length)
        rq = None
        buffer = None
        if not partinfo:
            return False if filename else b""
        # Get optimal packet sizes (before the writer queue, which is sized
        # from the frame length).
        plen = self.get_packet_length()
        read_packet_length = plen.read_packet_length if plen else 0x100000  # fallback 1MB
        if read_packet_length <= 0:
            read_packet_length = 0x100000
        werrors = []
        if filename:
            # Byte-bounded, not item-bounded: with 64 KiB frames the old
            # 32-item queue held only 2 MiB, i.e. ~60 ms of data at 32 MB/s,
            # so any storage hiccup stalled the USB loop and the DA.
            rq = Queue(maxsize=max(8, self.READ_QUEUE_BYTES // max(read_packet_length, 0x1000)))
            worker = Thread(target=writedata, args=(filename, rq, werrors), daemon=True)
            worker.start()
        else:
            buffer = bytearray()
        storage, parttype, length = partinfo
        pg = progress(total=length, prefix="Read:", guiprogress=self.mtk.config.guiprogress)
        max_usb_packet = self.mtk.port.cdc.EP_IN.wMaxPacketSize
        bytesread = 0
        read_ok = False
        ack_resends = 0
        resumes = 0
        # Timing, so the next run shows where the time actually goes:
        # header/status frames, payload reads, ACK writes, and time blocked
        # on a full writer queue (storage slower than USB).
        _pc = time.perf_counter
        t_begin = _pc()
        t_hdr = t_data = t_ack = t_blocked = 0.0
        fu_hdr = fu_pay = fu_ack = fu_frames = 0
        fast_read = self._fast_read_ok()
        fused_calls = 0
        self._applog_event(
            "I", f"readflash start: addr={hex(addr)} length={hex(length)} "
                 f"path={'fused' if fast_read else 'classic'} frame={hex(read_packet_length)}"
        )
        try:
            while bytesread < length:
                seg_start = bytesread
                seg_len = min(length - bytesread, self.READ_SEGMENT)
                if not self.cmd_read_data(addr=addr + seg_start, size=seg_len,
                                          storage=storage, parttype=parttype):
                    break
                seg_left = seg_len
                hard_fail = False
                # True once the DA's terminating 4-byte status frame has been
                # consumed by the frame loop (so the final-ack read below must
                # not wait for a second one).
                terminated = False
                # Consecutive bare status frames seen with no data between them.
                status_skips = 0
                while seg_left > 0:
                    t_a = _pc()
                    # The first frame of every segment always goes through the
                    # proven path: that is where the DA's start-of-read status
                    # frame, the 400 ms peek in cmd_read_data and any pending
                    # header are sorted out. The fused loop only takes over once
                    # the stream is known to be aligned.
                    if (fast_read and bytesread > seg_start
                            and getattr(self, "_pending_read_hdr", None) is None
                            and not getattr(self.mtk.port.cdc, "_rxbuf", b"")
                            and seg_left >= read_packet_length):
                        # One native call moves up to FAST_READ_BATCH whole
                        # frames (header, payload, ACK, OK-status skip). Any
                        # frame it does not understand is handed back and the
                        # loop below handles it exactly as before.
                        fused_calls += 1
                        nfr = min(
                            self.FAST_READ_BATCH,
                            max(1, self.FAST_READ_BATCH_BYTES // read_packet_length),
                            seg_left // read_packet_length,
                        )
                        fbuf, finfo, fhdr = self._fast_read_batch(read_packet_length, nfr)
                        t_f = _pc()
                        if finfo[0] != 0 and not finfo[3]:
                            self._applog_event(
                                "W", f"fused call #{fused_calls} delivered nothing: "
                                     f"info={finfo} hdr={fhdr.hex()}"
                            )
                        fu_hdr += finfo[4]
                        fu_pay += finfo[5]
                        fu_ack += finfo[6]
                        fu_frames += finfo[3]
                        t_data += t_f - t_a
                        nstored = finfo[3] if len(fbuf) >= finfo[3] * read_packet_length else 0
                        for fi in range(nstored):
                            chunk = fbuf[fi * read_packet_length:(fi + 1) * read_packet_length]
                            if filename:
                                rq.put(chunk)
                            else:
                                buffer.extend(chunk)
                            seg_left -= read_packet_length
                            bytesread += read_packet_length
                            if display:
                                pg.update(read_packet_length)
                        t_blocked += _pc() - t_f
                        if nstored:
                            ack_resends = 0
                            status_skips = 0
                        reason = finfo[0]
                        if reason == 2:
                            if self.ack(rstatus=False) == -1:
                                self.error(
                                    f"ACK write failed after data frame at {hex(bytesread)}"
                                    f" ({self._usb_diag()})"
                                )
                                hard_fail = True
                                break
                        elif reason == 1:
                            if fhdr[:4] != b"\xef\xee\xee\xfe":
                                self.warning(
                                    f"[xflash] fused read lost sync at {hex(bytesread)}: "
                                    f"hdr={fhdr.hex()} info={finfo}; fused read disabled for this dump"
                                )
                                fast_read = False
                            self._pending_read_hdr = fhdr[:12]
                            if finfo[2] == 4:
                                self._pending_read_payload = fhdr[12:16]
                            status_skips = finfo[1]
                        elif reason == 3:
                            # Timeout/error: let the proven path below retry,
                            # resend the ACK and report, and stay on it.
                            fast_read = False
                        if reason != 3 or nstored:
                            continue
                        t_a = _pc()
                    # A silent bus for this long after an ACK means the ACK
                    # never landed (or the DA is gone). Below, that gets at
                    # most two bounded re-sends -- and only while the device
                    # is still enumerated -- instead of abandoning a
                    # multi-GB dump on the spot.
                    hdr_wait = 8000
                    status = getattr(self, "_pending_read_hdr", None)
                    self._pending_read_hdr = None
                    hdr_src = "pending" if status is not None else "usbread"
                    if status is None:
                        status = self._rd_hdr(hdr_wait)
                    elif self._da_msg_body(status):
                        # Native handed back a DA log packet: it is consumed
                        # now, so go round again and read the next header.
                        continue
                    if len(status) != 12:
                        # ZLP after a payload that was a multiple of
                        # wMaxPacketSize. Native skips ZLPs; one retry
                        # catches a late next-frame header.
                        status = self._rd_hdr(hdr_wait)
                    if len(status) != 12:
                        self.warning(
                            f"[xflash] no frame header at {hex(bytesread)} of {hex(length)}"
                            f" ({self._usb_diag()})"
                        )
                        if (bytesread > 0 and ack_resends < 2 and self._android_usb()
                                and "device_present=False" not in self._usb_diag()):
                            ack_resends += 1
                            self.warning(f"[xflash] re-sending ACK ({ack_resends}/2)")
                            if self.ack(rstatus=False) != -1:
                                continue
                        self.error(
                            f"Timeout or short read on header at {hex(bytesread)}"
                            + (
                                " (DA did not answer the previous ACK)"
                                if bytesread > 0
                                else ""
                            )
                        )
                        hard_fail = True
                        break
                    magic, _, slength = unpack("<III", status)
                    if magic != 0xFEEEEEEF:
                        self.error(f"Invalid magic in data packet: {hex(magic)}")
                        self._applog_event(
                            "E",
                            f"bad frame header at {hex(bytesread)} of {hex(length)}: "
                            f"header={bytes(status).hex()} source={hdr_src} "
                            f"frames_done={bytesread // max(read_packet_length, 1)} "
                            f"fused_calls={fused_calls} status_skips={status_skips} "
                            f"usb={self._usb_diag()}"
                        )
                        hard_fail = True
                        break
                    remaining = slength
                    t_b = _pc()
                    t_hdr += t_b - t_a
                    pieces = []
                    pend_pay = getattr(self, "_pending_read_payload", None)
                    self._pending_read_payload = None
                    if pend_pay is not None and slength == 4:
                        pieces.append(pend_pay)
                        remaining = 0
                    while remaining > 0:
                        # Protocol payload size, not URB size. Native reads
                        # exactly this many bytes (pipelined, straight into
                        # the destination), so it can never run into the next
                        # frame's header.
                        to_read = min(remaining, read_packet_length)
                        # Budget scales with payload so a big frame on a slow
                        # OTG cable cannot time out at the default 4 s.
                        read_budget = max(8000, (to_read // 64) + 4000)
                        data = self.usbread(
                            to_read,
                            maxtimeout=read_budget,
                            w_max_packet_size=max_usb_packet,
                        )
                        if not data:
                            self.error(
                                f"USB read failed mid-packet at {hex(bytesread)}"
                                f" ({self._usb_diag()})"
                            )
                            raise IOError("USB read failure")
                        pieces.append(data)
                        remaining -= len(data)
                    chunk = pieces[0] if len(pieces) == 1 else b"".join(pieces)
                    t_c = _pc()
                    t_data += t_c - t_b
                    if slength > 4:
                        # ACK first: the DA starts on the next frame as soon
                        # as it has the ACK, so queueing the chunk (which can
                        # block on a slow writer) must not delay it.
                        ack_ok = self.ack(rstatus=False)
                        t_d = _pc()
                        t_ack += t_d - t_c
                        if filename:
                            rq.put(chunk)
                        else:
                            buffer.extend(chunk)
                        t_blocked += _pc() - t_d
                        if ack_ok == -1:
                            self.error(
                                f"ACK write failed after data frame at {hex(bytesread + len(chunk))}"
                                f" ({self._usb_diag()})"
                            )
                            hard_fail = True
                            break
                        ld = len(chunk)
                        seg_left -= ld
                        bytesread += ld
                        ack_resends = 0
                        status_skips = 0
                        if display:
                            pg.update(ld)
                    elif slength == 4:
                        if len(chunk) < 4:
                            self.error(
                                f"Read completed with truncated status "
                                f"({len(chunk)} of 4 bytes)"
                            )
                            hard_fail = True
                            break
                        flag = unpack("<I", chunk[:4])[0]
                        if flag != 0:
                            self.error(f"Read completed with error status {hex(flag)}"
                                       f" at {hex(bytesread)}")
                            hard_fail = True
                            break
                        # A bare "OK" status frame (magic, type 1, len 4,
                        # payload 0) between data frames is the DA's reply to
                        # our ACK, NOT the end of the read: on this DA it
                        # arrives after every ACK, ahead of the next data
                        # frame. Treating it as "done" is what ended every
                        # dump at exactly one frame (0x10000). Skip it and
                        # read the next header. Two in a row with no data
                        # between means the DA really is finished.
                        status_skips += 1
                        if status_skips < 2 and seg_left > 0:
                            continue
                        terminated = True
                        if seg_left > 0:
                            self.warning(
                                f"[xflash] DA ended the read early at {hex(bytesread)} of "
                                f"{hex(length)} (status frame, flag=0, raw hdr={bytes(status).hex()}"
                                f" payload={bytes(chunk).hex()}, segment_asked={hex(seg_len)},"
                                f" frame={hex(read_packet_length)})"
                            )
                        break
                    else:
                        self.error(f"Invalid frame length {slength} at {hex(bytesread)}")
                        hard_fail = True
                        break

                # Final acknowledge -- drain even on incomplete so the next
                # command does not eat a stale frame. Skipped when the DA's
                # terminating status frame was already consumed above.
                if not terminated:
                    fstatus = self._rd_hdr(5000)
                    if len(fstatus) != 12:
                        self.warning(f"Timeout or short read on final ack: {hex(len(fstatus))} bytes; resyncing")
                        fstatus = self._rd_hdr(15000)
                        if len(fstatus) != 12:
                            self.usbread(12, maxtimeout=250)
                            fstatus = b""
                    if len(fstatus) == 12:
                        fmagic, _, fslength = unpack("<III", fstatus)
                        if fmagic == 0xFEEEEEEF:
                            resdata = self.usbread(fslength, w_max_packet_size=max_usb_packet)
                            if fslength == 4:
                                if len(resdata) < 4:
                                    self.warning(
                                        f"Final ack payload truncated: {len(resdata)} of 4 bytes"
                                    )
                                elif unpack("<I", resdata[:4])[0] != 0:
                                    self.error("Read completed with error status")
                                    hard_fail = True
                if hard_fail:
                    break
                if bytesread < length and bytesread == seg_start:
                    self.error(
                        f"DA returned no data for the read at {hex(addr + seg_start)}"
                        f" ({self._usb_diag()})"
                    )
                    break
                if bytesread < length and terminated:
                    if bytesread - seg_start >= self.READ_MIN_PROGRESS:
                        resumes = 0
                    resumes += 1
                    if resumes > self.READ_MAX_RESUMES:
                        self.error(f"DA ended the read early too many times at {hex(bytesread)}")
                        break
                    self.warning(f"[xflash] resuming read at {hex(bytesread)} ({resumes}/{self.READ_MAX_RESUMES})")
            read_ok = bytesread >= length
            t_total = _pc() - t_begin
            if bytesread >= 0x400000 and t_total > 0:
                print(f"[xflash] read {bytesread / 1048576:.0f} MiB in {t_total:.1f}s = "
                      f"{bytesread / 1048576 / t_total:.1f} MiB/s (frame={hex(read_packet_length)}; "
                      f"hdr/status {t_hdr:.1f}s, payload {t_data:.1f}s, ack {t_ack:.1f}s, "
                      f"writer-blocked {t_blocked:.1f}s)")
                if fu_frames:
                    self._applog_event(
                        "I",
                        f"fused read timing over {fu_frames} frames: "
                        f"status+header wait {fu_hdr / fu_frames / 1000:.2f} ms/frame, "
                        f"payload {fu_pay / fu_frames / 1000:.2f} ms/frame "
                        f"({(read_packet_length / 1048576) / max(fu_pay / fu_frames / 1e6, 1e-9):.1f} MiB/s while receiving), "
                        f"ACK {fu_ack / fu_frames / 1000:.2f} ms/frame"
                    )
            if not read_ok:
                self.error(
                    f"Incomplete flash read: {hex(bytesread)} of {hex(length)} bytes"
                )
            if filename:
                rq.put(None)
                worker.join(timeout=30)
                if werrors:
                    self.error(f"Writing the dump file failed: {werrors[0]}")
                    return False
                if display and read_ok:
                    pg.done()
                if worker.is_alive():
                    self.warning("Writer thread didn't finish cleanly")
                    return False
                return True if read_ok else False
            else:
                if display and read_ok:
                    pg.done()
                return buffer if read_ok else (buffer or b"")
        except Exception as e:
            self.error(f"Exception during readflash: {str(e)}")
        finally:
            if filename and 'rq' in locals():
                try:
                    rq.put(None, timeout=1)
                except Exception:
                    pass
                if 'worker' in locals():
                    worker.join(timeout=10)
        # Cleanup on failure -- do not fake 100% progress.
        if filename:
            try:
                rq.put(None)
            except Exception:
                pass
            try:
                worker.join(timeout=10)
            except Exception:
                pass
        return buffer if filename == "" else False

    class ShutDownModes:
        NORMAL = 0
        HOME_SCREEN = 1
        FASTBOOT = 2

    def shutdown(self, async_mode: int = 0, dl_bit: int = 0, bootmode: ShutDownModes = ShutDownModes.NORMAL):
        if self.xsend(self.cmd.SHUTDOWN):
            status = self.status()
            if status == 0:
                hasflags = 0
                # bootmode 0: shutdown 1: home screen, 2: fastboot
                if async_mode or dl_bit or bootmode != self.ShutDownModes.NORMAL:
                    hasflags = 1
                enablewdt = 0  # Disable wdt
                dont_resetrtc = 0  # Reset RTC
                leaveusb = 0  # Disconnect usb
                if self.xsend(pack("<IIIIIIII", hasflags, enablewdt, async_mode, bootmode, dl_bit,
                                   dont_resetrtc, leaveusb, 0)):
                    status = self.status()
                    if status == 0:
                        self.mtk.port.close(reset=True)
                        return True
            else:
                self.error(f"Error on sending shutdown: {self.eh.status(status)}")
        self.mtk.port.close(reset=True)
        return False

    def writeflash(self, addr, length, filename: str = "", offset=0, parttype=None, wdata=None, display=True):
        fh = None
        fill = 0
        if filename != "":
            if os.path.exists(filename):
                fsize = os.stat(filename).st_size
                length = min(fsize, length)
                fh = open(filename, "rb")
                fh.seek(offset)
            else:
                self.error(f"Filename doesn't exists: {filename}, aborting flash write.")
                return False
        if length % 512 != 0:
            fill = 512 - (length % 512)
            length += fill
        partinfo = self.daconfig.storage.get_storage(parttype, length)
        if not partinfo:
            return False
        storage, parttype, plength = partinfo
        length = min(length, plength)
        pg = progress(total=length, prefix="Write:", guiprogress=self.mtk.config.guiprogress)
        # self.send_devctrl(self.Cmd.START_DL_INFO)
        plen = self.get_packet_length()
        write_packet_size = plen.write_packet_length if plen else 0x200000

        bytestowrite = length
        if self.cmd_write_data(addr, length, storage, parttype):
            prefetch_ex = None
            next_read = None
            try:
                pos = 0
                if fh:
                    # Read chunk N+1 from the source file (often an SD card or
                    # SAF fd, with wildly variable latency) while chunk N is on
                    # the wire, so storage stalls never idle the USB bus.
                    from concurrent.futures import ThreadPoolExecutor
                    prefetch_ex = ThreadPoolExecutor(max_workers=1)
                    # Two chunks ahead (one worker, so reads stay in order):
                    # a single slow read no longer leaves the bus idle.
                    pending_reads = []
                    left = bytestowrite
                    for _ in range(2):
                        if left <= 0:
                            break
                        n_rd = min(write_packet_size, left)
                        pending_reads.append(prefetch_ex.submit(fh.read, n_rd))
                        left -= n_rd
                    next_read = left
                _pc = time.perf_counter
                t_w0 = _pc()
                t_src = t_sum = t_send = 0.0
                while bytestowrite > 0:
                    dsize = min(write_packet_size, bytestowrite)
                    if fh:
                        t_a = _pc()
                        data = bytearray(pending_reads.pop(0).result())
                        t_src += _pc() - t_a
                        if next_read > 0:
                            n_rd = min(write_packet_size, next_read)
                            pending_reads.append(prefetch_ex.submit(fh.read, n_rd))
                            next_read -= n_rd
                        if len(data) < dsize:
                            data.extend(b"\x00" * (dsize - len(data)))
                    else:
                        data = wdata[pos:pos + dsize]
                    if len(data) % 512 != 0:
                        fill = 512 - (len(data) % 512)
                        data += fill * b"\x00"
                    if display:
                        pg.update(len(data))
                    t_b = _pc()
                    checksum = self._sum16(data)
                    t_c = _pc()
                    t_sum += t_c - t_b
                    if not self.send_param([pack("<I", 0x0), pack("<I", checksum), data]):
                        self.error("Error on writing pos 0x%08X" % pos)
                        return False
                    t_send += _pc() - t_c
                    bytestowrite -= dsize
                    pos += dsize
                if bytestowrite > 0 or pos < length:
                    self.error(
                        f"Incomplete flash write: {hex(pos)} of {hex(length)} bytes"
                    )
                    if fh:
                        fh.close()
                    return False
                # Last send_param already waited for the per-chunk status.
                # Some DAs send one extra overall status; some do not.
                # Timeout (-1) after every chunk was ACKed is not a failed
                # write -- the eMMC already has the data.
                status = self.status()
                # Only a DA protocol error (0xC0xxxxxx) means the flash
                # itself failed. Timeout (-1) or a leftover-desync value
                # after every chunk already ACKed is not a failed write.
                if isinstance(status, int) and status not in (0x0, -1) and (status & 0xFF000000) == 0xC0000000:
                    self.error(f"Error on writeflash: {self.eh.status(status)}")
                    if fh:
                        fh.close()
                    return False
                try:
                    self.send_devctrl(
                        self.cmd.CC_OPTIONAL_DOWNLOAD_ACT, read_data=False
                    )
                except Exception:
                    pass
                t_tot = _pc() - t_w0
                if t_tot > 0 and length >= 0x400000:
                    print(
                        f"[xflash] write {length / 1048576:.0f} MiB in {t_tot:.1f}s = "
                        f"{length / 1048576 / t_tot:.1f} MiB/s (chunk={hex(write_packet_size)}; "
                        f"source-wait {t_src:.1f}s, checksum {t_sum:.1f}s, USB+DA {t_send:.1f}s)"
                    )
                if display:
                    pg.done()
                if fh:
                    fh.close()
                return True
            except Exception as e:
                self.error(str(e))
                if fh:
                    fh.close()
                return False
            finally:
                if prefetch_ex is not None:
                    prefetch_ex.shutdown(wait=False)
        if fh:
            fh.close()
        return False

    def sync(self):
        """ XFlash Sync command """
        if self.xsend(self.cmd.SYNC_SIGNAL):
            return True
        return False

    def setup_env(self):
        """ XFlash Setup environment command """
        if self.xsend(self.cmd.SETUP_ENVIRONMENT):
            da_log_level = int(self.daconfig.uartloglevel)
            log_channel = 1
            self._da_log_on = False
            self._da_log_pump_next = False
            self._hdr_stash = None
            if self._da_log_usb_requested():
                # Diagnostic: DA log over USB (channel 2). INFO or more verbose
                # is needed for the DA's speed dump to be printed at all.
                log_channel = 2
                da_log_level = min(da_log_level, 2)
                self._da_log_on = True
                self._applog_event("I", "[xflash] setup_env: DA log over USB on "
                                        "(Settings -> Experimental)")
            # The MT6765 XFlash DA appears to pick its READ_DATA frame from this:
            # 64 KiB when told Linux, 2 MiB otherwise (docs/USB_PERFORMANCE.md;
            # not yet confirmed on hardware). The app switch is off by default.
            # XML (V6) loaders have their own setup and are not affected.
            if self._da_report_windows():
                system_os = self.ft_system_ose.OS_WIN
                self._applog_event("I", "[xflash] setup_env: reporting system_os=Windows "
                                        "(Settings -> Experimental)")
            else:
                system_os = self.ft_system_ose.OS_LINUX
            ufs_provision = 0x0
            param = pack("<IIIII", da_log_level, log_channel, system_os, ufs_provision, 0x0)
            if self.send_param(param):
                return True
        return False

    def setup_hw_init(self):
        """ XFlash Setup Hardware Init command """
        if self.xsend(self.cmd.SETUP_HW_INIT_PARAMS):
            param = pack("<I", 0x0)  # No config
            if self.send_param(param):
                return True
        return False

    def patch_da(self, da1, da2):
        """ XFlash patch da1 and da2 """
        da1sig_len = self.daconfig.da_loader.region[1].m_sig_len
        # ------------------------------------------------
        da2sig_len = self.daconfig.da_loader.region[2].m_sig_len
        hashaddr, hashmode, hashlen = self.mtk.daloader.compute_hash_pos(da1, da2, da1sig_len, da2sig_len,
                                                                         self.daconfig.da_loader.v6)
        if hashaddr is not None:
            da1 = self.xft.patch_da1(da1)
            da2 = self.xft.patch_da2(da2)
            da1 = self.mtk.daloader.fix_hash(da1, da2, hashaddr, hashmode, hashlen)
            self.mtk.daloader.patch = True
            self.daconfig.da2 = da2[:hashlen]
        else:
            self.mtk.daloader.patch = False
            self.daconfig.da2 = da2[:-da2sig_len]
        return da1, da2

    def upload_da1(self):
        if self.daconfig.da_loader is None:
            self.error("No valid da loader found... aborting.")
            return False
        loader = self.daconfig.loader
        self.info(f"Uploading xflash stage 1 from {os.path.basename(loader)}")
        if not os.path.exists(loader):
            self.info(f"Couldn't find {loader}, aborting.")
            return False
        with open(loader, 'rb') as bootldr:
            # stage 1
            da1offset = self.daconfig.da_loader.region[1].m_buf
            da1size = self.daconfig.da_loader.region[1].m_len
            da1address = self.daconfig.da_loader.region[1].m_start_addr
            da1sig_len = self.daconfig.da_loader.region[1].m_sig_len
            bootldr.seek(da1offset)
            da1 = bootldr.read(da1size)
            # ------------------------------------------------
            da2offset = self.daconfig.da_loader.region[2].m_buf
            da2sig_len = self.daconfig.da_loader.region[2].m_sig_len
            bootldr.seek(da2offset)
            da2 = bootldr.read(self.daconfig.da_loader.region[2].m_len)
            if self.mtk.daloader.patch or not self.config.target_config["sbc"] and not self.config.stock:
                da1, da2 = self.patch_da(da1, da2)
            else:
                self.mtk.daloader.patch = False
                self.daconfig.da2 = da2[:-da2sig_len]
            if self.mtk.preloader.send_da(da1address, da1size, da1sig_len, da1):
                self.info("Successfully uploaded stage 1, jumping ..")
                if self.mtk.preloader.jump_da(da1address):
                    sync = self.usbread(1)
                    if sync != b"\xC0":
                        self.error("Error on DA sync")
                        return False
                    self.sync()
                    # if self.kamakiri_pl is not None:
                    #    self.kamakiri_pl.bypass2ndDA()
                    self.setup_env()
                    self.setup_hw_init()
                    res = self.xread()
                    if res == pack("<I", self.cmd.SYNC_SIGNAL):
                        self.info("Successfully received DA sync")
                        return True
                    self.error(f"Error jumping to DA: {res}")
                else:
                    self.error("Error on jumping to DA.")
            else:
                self.error("Error on sending DA.")
        return False

    def reinit(self, display=False):
        self.config.sram, self.config.dram = self.get_ram_info()
        self.emmc = self.get_emmc_info(display)
        self.nand = self.get_nand_info(display)
        self.nor = self.get_nor_info(display)
        self.ufs = self.get_ufs_info(display)
        if self.emmc is not None and self.emmc.type != 0:
            self.daconfig.storage.flashtype = "emmc"
            self.daconfig.storage.emmc = self.emmc
        elif self.nand is not None and self.nand.type != 0:
            self.daconfig.storage.flashtype = "nand"
            self.daconfig.storage.nand = self.nand
        elif self.nor is not None and self.nor.type != 0:
            self.daconfig.storage.flashtype = "nor"
            self.daconfig.storage.nor = self.nor
        elif self.ufs is not None and self.ufs.type != 0:
            self.daconfig.storage.flashtype = "ufs"
            self.daconfig.storage.ufs = self.ufs
        self.chipid = self.get_chip_id(display=False)
        self.daversion = self.get_da_version(display=False)
        self.randomid = self.get_random_id()
        self.daconfig.storage.set_flash_size()
        speed = self.get_usb_speed()
        if speed == b"full-speed" and self.daconfig.reconnect:
            self.info("Reconnecting to stage2 with higher speed")
            self.config.set_gui_status(self.config.tr("Reconnecting to stage2 with higher speed"))
            self.set_usb_speed()
            self.mtk.port.close(reset=True)
            time.sleep(2)
            while not self.mtk.port.cdc.connect():
                time.sleep(0.5)
            self.info("Connected to stage2 with higher speed")
            self.mtk.port.cdc.set_fast_mode(True)
            self.config.set_gui_status(self.config.tr("Connected to stage2 with higher speed"))

    def set_remote_sec_policy(self, data):
        return self.send_devctrl(self.cmd.SET_REMOTE_SEC_POLICY, data)

    def handle_sla(self, da2):
        rsakey = None
        from mtkclient.Library.Auth.sla_keys import da_sla_keys
        for key in da_sla_keys:
            if da2.find(long_to_bytes(key.n)) != -1:
                rsakey = key
                break
        if "_lake" in self.mtk.loader or "_tides" in self.mtk.loader or "_moon" in self.mtk.loader:
            print("Trying lake ....")
            # Xiaomi Redmi 14C
            res = self.get_dev_fw_info()
            if res != b"":
                sla_signature = bytes.fromhex(
                    "62737010D445F66526F0F52BACBCF1E8FE6522CC50617E7B20098E6243DE2E5D7BB71D3607BD8DBADA63521B9EA99EDEA069DCAD9F04D622AE62AC594010D62BEE7AF4B325115C6F0457238BE8D4CB89A7CD1EAF56ACB0C0A16EE016A6B9B030C5794C3B761999E9B684B7B3760B914571DE060FFB07182056F889DD047E3689FB14EFDEBC3CEACE9864E074534DFC5DBF23BCBAD571258CF48C61D7DCAF6ECC8BF908F7F6E0B841A8A7E11D2F64CA40CED98013A9FB381215E7B0051ACAB2C3ACDDD3D08F34FA38AEE8FAD02C9B9EB402750124727EA37B532100C329AF123FA702495740FD1FDBD9EC6EF32D8A76C1767F97986E83E9CE3EFB8E29D5803D84")
                if self.set_remote_sec_policy(data=sla_signature):
                    print("SLA Signature was accepted.")
                    return True
        if "motorola" in self.mtk.loader or "lamu" in self.mtk.loader:
            print("Trying lamu ....")
            # Motorola G05
            res = self.get_dev_fw_info()
            if res != b"":
                sla_signature = bytes.fromhex(
                    "65E9F25C9A26488DD7948FFA3511B883963301821AD162376FAAF40FA207E92536F85529AD5A8C54BDAFB650C95B603E5643047EEEC1F83D051E02218B585CEDC2181CBFFDEB8F6E8074CBAB17E39FD3A6F8F5F708F1BBA37816200BBC768F36C80A6A99883FCADE93D577B73C9CBB401C0E62BD5D1654EED9949FF8AB03635B682217EDC59B22517ED6EF45AD2B1CAFC9B9C18C46C138A58EEEFE8301D9CDA659F7B35D43BDE6A64D6095FBD07FA762ADFC02082D3F61CD18A37B6B2D0321C5DE36F8148A1C46F6E651D8E695CAD841A05CB261AF78C9480EA5477211B3D6FD2DC9FC4B367DC21882485092C76C6DF658591C08AA60AF059004B2D11E4E4E0E")
                if self.set_remote_sec_policy(data=sla_signature):
                    print("SLA Signature was accepted.")
                    return True
                data=bytearray(b"\x42"*0x10)
                for rsakey in da_sla_keys:
                    if rsakey.vendor == "Motorola":
                        sla_signature = generate_da_sla_signature(data=data, key=rsakey.key)
                        if self.set_remote_sec_policy(data=sla_signature):
                            print("SLA Signature was accepted.")
                            return True
                        else:
                            data = res[4:4 + 0x10]
                            sla_signature = generate_da_sla_signature(data=data, key=rsakey.key)
                            if self.set_remote_sec_policy(data=sla_signature):
                                print("SLA Signature was accepted.")
                                return True
        if rsakey is None:
            res = self.get_dev_fw_info()
            if res != b"":
                data = res[4:4 + 0x10]
                sla_signature = generate_da_sla_signature(data=data, key=rsakey.key)
                if self.set_remote_sec_policy(data=sla_signature):
                    print("SLA Signature was accepted.")
                    return True
        else:
            print("No valid sla key found, trying dummy auth ....")
            # Xiaomi
            sla_signature = b"\x00" * 0x100
            if self.set_remote_sec_policy(data=sla_signature):
                print("SLA Signature was accepted.")
                return True
        return False

    def upload_da(self):
        if not self.mtk.daloader.patch:
            if (self.kamakiri_pl is not None and not self.mtk.config.chipconfig.damode == 6 and
                    self.mtk.config.target_config["sbc"]):
                self.kamakiri_pl.initbrom()
        if self.upload_da1():
            self.get_expire_date()
            self.set_reset_key(0x68)
            # self.set_battery_opt(0x2)
            self.set_checksum_level(0x0)
            connagent = self.get_connection_agent()
            # dev_fw_info=self.get_dev_fw_info()
            # dramtype = self.get_dram_type()
            stage = None
            if connagent == b"brom":
                stage = 1
                if self.daconfig.emi is None:
                    emmc_info = self.get_emmc_info(False)
                    if emmc_info is not None and emmc_info.user_size != 0:
                        self.info(f"DRAM config needed for: {hexlify(emmc_info.cid[:8]).decode('utf-8')}")
                    else:
                        ufs_info = self.get_ufs_info()
                        if ufs_info is not None and ufs_info.block_size != 0:
                            self.info(f"DRAM config needed for: {hexlify(ufs_info.cid).decode('utf-8')}")
                    self.info("No preloader given. Searching for preloader")
                    found = False
                    for root, _, files in os.walk(os.path.join(self.pathconfig.get_loader_path(), 'Preloader')):
                        for file in files:
                            with open(os.path.join(root, file), "rb") as rf:
                                data = rf.read()
                                if emmc_info is not None:
                                    if emmc_info.cid[:8] in data:
                                        preloader = os.path.join(root, file)
                                        self.daconfig.extract_emi(preloader)
                                        self.info("Sending emi data ...")
                                        if not self.send_emi(self.daconfig.emi):
                                            self.info("Emi data NOT accepted ...")
                                            continue
                                        self.info("Emi data accepted ...")
                                        found = True
                                        self.info("Detected working preloader: " + preloader)
                                        break
                                else:
                                    self.warning("No emmc info, can't parse existing preloaders.")
                                if found:
                                    break
                    if not found:
                        self.warning("No preloader given. Operation may fail due to missing dram setup.")
                else:
                    self.info("Sending emi data ...")
                    if not self.send_emi(self.daconfig.emi):
                        return False
                    self.info("Sending emi data succeeded.")
            elif connagent == b"preloader":
                stage = 1
            if stage == 1:
                self.info("Uploading stage 2...")
                stage = stage + 1
                loaded = False
                if not self.mtk.daloader.patch and not self.mtk.config.stock and connagent == b"preloader":
                    if (self.carbonara is not None and
                            self.mtk.config.target_config["sbc"]):
                        # Do NOT patch da1 on usage of carbonara
                        loaded = self.carbonara.patchda1_and_upload_da2()
                        if not loaded:
                            self.mtk.daloader.patch = False
                if not loaded:
                    loaded = self.boot_to(self.daconfig.da_loader.region[stage].m_start_addr, self.daconfig.da2)
                if loaded:
                    self.info("Successfully uploaded stage 2")
                    sla_enabled = self.get_sla_status()
                    if sla_enabled:
                        self.info("DA SLA is enabled")
                        if not self.handle_sla(self.daconfig.da2):
                            self.error("Can't bypass DA SLA")
                    else:
                        self.info("DA SLA is disabled")
                    self.reinit(True)
                    if self.mtk.daloader.patch:
                        daextdata = self.xft.patch()
                    else:
                        daextdata = None
                    if daextdata is not None:
                        self.daext = False
                        if self.boot_to(addr=self.extensions_address, da=daextdata):
                            ret = self.send_devctrl(XCmd.CUSTOM_ACK)
                            status = self.status()
                            if status == 0x0 and unpack("<I", ret)[0] == 0xA1A2A3A4:
                                self.info(f"DA Extensions successfully added at {hex(self.extensions_address)}")
                                self.daext = True
                                self.xft.custom_set_storage(ufs=self.daconfig.storage.flashtype == "ufs")
                        if not self.daext:
                            self.warning("DA Extensions failed to enable")

                        if self.generatekeys:
                            self.xft.generate_keys()
                    return True
                self.error("Error on booting to da (xflash)")
                return False
            self.error(f"Didn't get brom connection, got instead: {hexlify(connagent).decode('utf-8')}")
        return False


def main():
    from mtkclient.Library.mtk_class import Mtk
    from mtkclient.config.mtk_config import MtkConfig
    config = MtkConfig(logging.INFO)
    config.init_hwcode(0x717)
    config.hwver = 0xca00
    config.swver = 0
    mtk = Mtk(config=config, loglevel=logging.INFO,
              serialportname=None)
    daconfig = DAconfig(mtk=mtk, loader=mtk.config.loader,
                        preloader=mtk.config.preloader, loglevel=logging.INFO)
    daconfig.setup()
    dax = DAXFlash(mtk, daconfig, loglevel=logging.INFO)
    loader = daconfig.loader

    print(f"Uploading xflash stage 1 from {os.path.basename(loader)}")
    if not os.path.exists(loader):
        print(f"Couldn't find {loader}, aborting.")
        return False
    with open(loader, 'rb') as bootldr:
        # stage 1
        da1offset = daconfig.da_loader.region[1].m_buf
        da1size = daconfig.da_loader.region[1].m_len
        bootldr.seek(da1offset)
        da1 = bootldr.read(da1size)
        # ------------------------------------------------
        da2offset = daconfig.da_loader.region[2].m_buf
        bootldr.seek(da2offset)
        da2 = bootldr.read(daconfig.da_loader.region[2].m_len)
        dax.patch_da(da1, da2)
    return True


if __name__ == "__main__":
    main()
