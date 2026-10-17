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

How to read the result: run a read of a few hundred MiB and compare the
`[Storage Read]` and `[USB Write]` speeds. Whichever is near the host-measured
MiB/s is the limit; if both are much higher, the limit is on the phone's
receiving side.

Applies only to XFlash (V5) DAs and takes effect when the DA is next loaded.
Leave it off for writes. If a read fails or hangs, turn it off and reconnect.

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
