# USB throughput

How MediaTek dumps and flashes move data, why they are as fast as they are,
and how to diagnose them. The switches live in **Settings → Experimental**;
all are on by default and apply from the next command.

## The numbers that matter

A MediaTek XFlash DA moves reads in **64 KiB frames** and waits for a 16-byte
ACK after each one; writes use 2 MiB frames. The DA reports both sizes
(`[xflash] get_packet_length: write=0x200000 read=0x10000`) and cannot be
asked for bigger ones. Reads are therefore bound by per-frame latency, writes
by the bus:

| | typical (Galaxy S24 FE host, Galaxy A10s target, USB 2.0) |
|---|---|
| reads, classic loop | 5.5 MiB/s |
| reads, fused loop + busy-wait | 9–10 MiB/s |
| reads, plus clock hold | slightly more |
| writes | ~33 MiB/s |

## What each switch does

**Fused MediaTek read** (`nativeReadFrames` in `usb_native.c`). The classic
loop costs about eight Python/JNI round trips per frame. The fused loop runs
header → payload → ACK → status-skip in one native call for up to eight frames.
The Python loop is still the fallback: it reads the first frame of every read
and takes over whenever the native loop hands something back (bad magic, a
frame of another size, a status that is not plain OK). Reads of other chips
and all writes use the Python path.

One rule keeps it correct: `bulk_exact()` returns after a short USB packet, so
a read can legitimately return fewer bytes than asked (a device may send a
header in pieces). Every read in the fused loop goes through `bulk_fill()`,
which keeps going until it has the full amount.

**Busy-wait for USB** (`bfusb.c`, `bfusb_set_spin_us`). While a transfer has
been outstanding for less than 30 ms the wait loop polls with a zero timeout
instead of sleeping. A sleeping thread lets its core idle and the governor drop
the clock; each wake-up then pays idle exit plus ramp-up, about a millisecond
per transfer, and a frame is about eight transfers. This is what made speed
decay over a session and jump back after rotating the screen.

**Hold all CPU clusters** (`ClockHold.kt`). Rotating the screen still gained
~6% after busy-waiting, because a USB completion is an interrupt serviced on
whichever core the kernel chose, often in the little cluster, whose clock stayed
low. One light low-priority spinner per cluster keeps every cluster clocked up
for the length of a command. It releases itself at moderate thermal status.

## Larger read frames (experimental, untested on hardware)

Settings → Experimental → *Larger read frames (report Windows)*, off by default.

Reads are slow mostly because the DA sends 64 KiB frames and the phone spends
about 4 ms per frame preparing the next one, against about 2 ms of actual USB
transfer. Reading the MT6765 XFlash DA2 (the `0x6765` entry of
`MTK_DA_V5.bin`, ARM Thumb-2, loaded at `0x40000000`) shows the 64 KiB is not a
fixed limit:

| Where | What it does |
|---|---|
| `0x40005ed4` | `GET_PACKET_LENGTH` handler: returns the two words at `0x4005a600+0x10` (write) and `+0x14` (read) |
| `0x4000b3fc` | storage init: write = `0x200000`; read = `0x10000` only if the flag at `0x4005a600+0xc` is 1, else `0x200000` |

`setup_env` sends `system_os` (the DA logs it as `system_os=%d(0:windows,
1:linux)`) and the client has always sent Linux (1). The switch sends Windows
(0) instead. **What is not confirmed:** that `system_os` is what sets that flag
(`ufs_provision` is the other candidate), and that the DA's buffers really
handle 2 MiB read frames.

How to read the result: run a dump and search the log for `get_packet_length`.

- `read=0x200000`: the DA took the hint. Compare the `read ... MiB/s` line with
  a run with the switch off. The fused loop now buffers at most 1 MiB per call,
  so a 2 MiB frame is one frame per call.
- `read=0x10000`: nothing changed, so the flag comes from somewhere else. Turn
  the switch off.
- The read fails or hangs: turn the switch off and reconnect. Only the
  RAM-resident DA is affected. Keep it off for writes until a read has worked.

Applies only to XFlash (V5) DAs. XML (V6) loaders are unaffected.

## DA log over USB (diagnostic, untested on hardware)

Settings → Experimental → *DA log over USB (diagnostic)*, off by default.

The MT6765 XFlash DA already overlaps storage reads with USB sends: a reader
thread fills one of two buffers (each one read frame big) while a
`write_usb_proc` thread sends the other. So when reads are slow, the question is
which stage is the slow one. The DA measures this itself and prints a speed dump
after each transfer (`[Storage Read]`, `[USB Write]`, `[Memcpy]`, `[Overview]`,
each with a MBps figure) at INFO level, but `setup_env` sends DA logs to the
UART (`log_channel=1`), which cannot be read from the phone.

With the switch on, `setup_env` asks for `log_channel=2` (USB). The DA then
sends each log line as a packet in the normal stream: `0xFEEEEEEF`, type 2
(`DT_MESSAGE`), length, then a u32 and the text. The host logs them as
`[DA] ...` in Settings → Logging (speed lines are also echoed to the terminal as
`[xflash] DA: ...`) and skips them wherever it reads a 12-byte header. Before
each command the host also picks up any queued log packet, so the DA is not
left waiting on its USB IN while the host sends.

That pickup can miss a log line the DA queues a little later (seen on the
A10s right after `init_dram`): the DA then blocks sending it, stops reading
OUT, and the host's next command header times out after 4 s with `sent=0`.
So command headers and other writes of up to 64 bytes use a 300 ms timeout
while the log is on; on a timeout the host reads the pending log lines and
retries (up to 5 times, then one normal-timeout attempt). A 64-byte write is a
single USB packet, so a timed-out attempt sent nothing and a retry cannot
duplicate data. Larger writes are untouched.

This covers every small write in the XFlash host code: `xsend`, `send_param`,
the ACK writes, and the `boot_to` / `send_data` headers. The stage-2 upload is
where it mattered on the A10s: the DA logs a line right after it reads the
BOOT_TO parameters, ahead of reading the 12-byte `send_data` header, so that
header write was the one that timed out.

How to read the result: run a read of a few hundred MiB and compare the
`[Storage Read]` and `[USB Write]` speeds. Whichever is near the host-measured
MiB/s is the limit; if both are much higher, the limit is on the phone's
receiving side.

Applies only to XFlash (V5) DAs and takes effect when the DA is next loaded.
Leave it off for writes. If a read fails or hangs, turn it off and reconnect.

### Results on the A10s (MT6765, `GD6BMB` eMMC), 1.0.1-beta.4

Two reads of the `vendor` partition (448 MiB), both with larger read frames
(2 MiB) and DA log over USB on. The DA's own speed dump:

| Run | `[Storage Read]` | `[USB Write]` | `[Overview]` | Host: header+status wait / payload |
|---|---|---|---|---|
| 1 | 23.06 s, 19 MBps | 18.80 s, 23 MBps | 23.15 s, 19 MBps | 21.9 ms / 80.7 ms per frame |
| 2 | 22.84 s, 19 MBps | 18.59 s, 24 MBps | 22.93 s, 19 MBps | 21.8 ms / 79.8 ms per frame |

Reading the storage took almost the whole transfer, and the USB send finished
about 4.3 s earlier and spent that time waiting: the DA's USB thread idles until
the reader thread has filled the next buffer, which is the ~21.8 ms per frame the
host sees as header+status wait.

**That is an effect of the DA log, not the normal case.** Reads with the DA log
off, on the same phone, show a header+status wait of only 3.4 ms per frame
(`userdata`, 2.2 GiB region: 22.9 MiB/s; `boot`, 32 MiB: 22.9 MiB/s, payload
80.6 ms per frame = 24.8 MiB/s while receiving). Every read with the log on
shows ~21.8 ms instead, with the same ~80 ms payload time. So with the log on
the DA starts each frame about 18 ms later (cause not identified; the log
wraps the DA's USB writes in a lock), and the DA's speed-dump figures taken
with the log on understate the storage stage. With the log off, storage keeps
up and the limit is the DA's USB transmit, about 24.8 MiB/s, while the same
link carried about 49 MiB/s in the other direction during writes.

### eMMC mode (from the debug-level log)

The DA brings the eMMC up in High-Speed DDR mode (`SET_CLK(52000kHz):
SCLK(50000kHz) MODE(2) DDR(1)`, 8-bit bus, `Max.Speed: 52000 kHz`). That is a
theoretical ~95 MiB/s, well above the ~24 MiB/s seen, so the eMMC bus clock is
not what limits reads. The DA has no HS200/HS400 strings, so it cannot go
faster than this mode anyway.

The *Verbose DA log (debug)* switch asks the DA for level 1, which adds its
eMMC clock and bus-mode lines (`[SDn] SET_CLK(...kHz): SCLK(...) MODE(...)
DDR(...)`) to the log. These are printed only at level 1 or lower. It produces
much more output, so use it on a small read: the DA's DRAM calibration alone
prints about 700 lines in a row, and the host now skips any number of them.

## USB send path diagnostic (DMA vs PIO)

The MT6765 XFlash DA2's bulk-IN send routine checks a byte at `0x40053978` in
its own RAM: if 1, it hands up to `0xfc00` (64512) bytes to the controller as
one DMA descriptor; if not 1, it falls back to sending 512 bytes at a time in
software, with a setup pass before each chunk. The flag starts at 0 at DA init
and is set to 1 only by a specific USB endpoint-configuration routine; which
path actually runs at read time was not confirmed from static analysis alone.

Every `r` command now reads this flag once, right after `get_packet_length`,
using the extension's own `readmem` (the same command path that already
fetches the eMMC info) and logs it: `[xflash] usb_tag flag @0x40053978 = 0x..
(DMA path | PIO fallback)`. `0x0` would point at this as a likely cause of the
read-speed ceiling (the PIO loop's per-chunk overhead, 4096 chunks for a 2 MiB
frame against about 33 DMA descriptors); `0x1` would rule it out, and the
limit would be elsewhere in the DA's USB transmit path.

## Diagnosing a slow or failing read

Settings → Logging, search `xflash`:

- `[xflash] read 32 MiB in … (frame=…)` – overall speed.
- `fused read timing over N frames: status+header wait …, payload …, ACK …` –
  per-frame split. Status+header wait is mostly the phone preparing the next
  frame; payload is the USB data transfer; ACK should be well under 0.5 ms.
- `readflash start: … path=fused|classic` – which loop ran.
- `fused call #n delivered nothing: info=[reason, skips, payBack, frames, …]` –
  the native loop gave up before storing a frame; `hdr=` is the last header.
- `bad frame header at …: header=… source=…` – the stream is out of step; the
  raw bytes say where.

If a particular phone or target fails with the fused read on, turn it off in
Settings → Experimental and attach the log export (Settings → Logging → Export).
