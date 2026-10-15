# MTK speed simulator

Runs the real `usb_native.c`, `bfusb.c`, `androidusblib.py` and `xflash_lib.py`
against a fake High-Speed bus with a small xflash DA behind it (no phone needed).

    ./run_speed_sim.sh --mib 32                 # baseline: read ~21, write ~23 MB/s
    ./run_speed_sim.sh --mib 32 --call-us 2000  # 2 ms host latency per Python->JNI call

`--call-us` is the knob that matters: it models the cost of one Python -> Kotlin
-> JNI hop on a slow, low-clock core. Before the fused read loop (`nativeReadFrames`)
a 64 KiB read frame needed ~5 such hops, so 2 ms each gave ~4.3 MB/s (the speed
reported on device). With the fused loop the same setting reads at ~19 MB/s.
Other knobs: `--read-frame`, `--write-chunk`, `--in-mbps`, `--out-mbps`,
`--no-zlp`, `--no-status-after-ack`. Not modelled: CPU governor, Android
scheduling, ART/Chaquopy overhead (use `--call-us` for those).

On device, look for these log lines to see where time goes:
`[xflash] read ... (hdr/status, payload, ack, writer-blocked)`,
`[xflash] write ... (source-wait, checksum, USB+DA)` and the bfusb
`USB: ... bus busy / host gaps` line.
