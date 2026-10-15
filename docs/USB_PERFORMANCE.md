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
