# Brokkr (Samsung / Odin) mode and the shared USB layer

## What was added

### 1. Samsung mode (Brokkr engine, Odin protocol)

A third mode next to MediaTek and Qualcomm (shown as "Samsung" in the app; Brokkr is credited in Settings > About). It uses Brokkr's real C++ engine
(vendored, GPL-3.0) and its own screens, not the command console, because Odin
flashing is a form-and-progress workflow rather than a command stream.

| Piece | Where |
|---|---|
| Brokkr C++ engine (Android platform layer only, v2.4.10) | `app/src/main/cpp/brokkr/` |
| CMake target `libbrokkr.so` | `app/src/main/cpp/brokkr_engine.cmake` |
| JNI bridge, USB and TCP transports, callback interface | `.../samsung/BrokkrBridge.kt` |
| Wireless listener (TCP 13579, for watches) | `.../samsung/WirelessFlashManager.kt` |
| State, package list, devices, flash run, notification/keep-alive | `.../samsung/SamsungViewModel.kt` |
| Models and error explanations | `.../samsung/SamsungModels.kt` |
| Screens: Flash, Progress, Devices, Log | `.../ui/samsung/BrokkrScreens.kt` |
| Mode wiring | `EngineMode.BROKKR`, `MainShell.kt`, `MainActivity.kt` |

Features carried over from the original app: multi-device parallel USB
flashing, wireless (watch) flashing, `.tar` / `.md5` packages with the same
validation messages, manual PIT, auto-reboot, live log, foreground service +
progress notification so a flash survives the screen turning off, and the
plain-language explanations for corrupt files / bootloader refusal / connection
failure / disconnection. Added on top: pick a firmware *folder* (BL/AP/CP/CSC),
per-partition plan with live state, speed/ETA, link-speed check.

**JNI contract.** `libbrokkr.so` finds the Kotlin classes by *name*. The method
and field names in `BrokkrBridge.kt` (`nativeFd`, `sendChunk`, `onPlanItem`, ...)
must not be renamed. Only the JNI function names were changed from upstream
(`com.oops.eros` -> `com.bareflash.app.samsung`); everything else in `brokkr/`
is upstream code, so updating Brokkr means copying a new `src/` over it and
re-applying that one rename.

**C++23 / NDK.** Brokkr needs C++23. NDK 27's libc++ still hides
`std::jthread` behind the experimental switch, so the target defines
`_LIBCPP_ENABLE_EXPERIMENTAL`. That is a compile definition only. Do not use
`-fexperimental-library`: it adds `libc++experimental.a` to the link and the NDK
does not ship it.

### 2. `bfusb`, the shared USB layer (`app/src/main/cpp/bfusb/`)

Linked into MTK (`usb_native.c`), QDL (`qdl/src/usb.c`) and Brokkr
(`libusb_transport.cpp`). It sits on libusb and adds:

* **`bfusb_bulk()`**: same result codes and partial byte counts as
  `libusb_bulk_transfer()`, but the transfer is submitted asynchronously and the
  calling thread pumps events in 20 ms slices, so a **Stop aborts the transfer in
  flight**. Before, Stop on a stalled MTK write waited out the whole transfer
  budget (measured against the original code with a mock device: 3001 ms for a
  3000 ms budget; now 101 ms). Brokkr's transport gets the same, including
  `close()` while a transfer is running.
* **Telemetry**: bytes, MiB/s while on the bus, and *bus busy vs host gaps*.
  Printed at the end of each MTK / QDL / Brokkr run, e.g.
  `USB: out 408.0 MiB @ 31.2 MiB/s; bus busy 82%, host gaps 18%`.
  This is the tool for finding out whether slowness is the USB link or the host.
* **Link speed** from `wMaxPacketSize`: 64 = Full-Speed, 512 = High-Speed,
  1024 = SuperSpeed. A Full-Speed link (bad OTG adapter, cable or hub) gives about
  1 MB/s and is otherwise invisible; the apps now say so.

### Interface claim: `LIBUSB_ERROR_BUSY`

`BrokkrUsbTransport.open` (Kotlin) claims the interface with `force=true` through
`UsbDeviceConnection`, then hands the file descriptor to native code. With auto-detach
enabled, libusb claims through usbfs's "disconnect and claim, unless usbfs already owns it"
call, which answers `EBUSY` because the Kotlin side already owns it. Earlier builds treated
that as fatal (`libusb_claim_interface(1) failed: LIBUSB_ERROR_BUSY`), so every Samsung flash
failed before sending a byte. `LibusbUsbTransport::open` now retries once without auto-detach
and, if the interface is still reported busy, continues because it is already held by this
process (the MTK engine's `usb_native.c` does the same). Other claim errors are still reported.
`test_brokkr_transport.cpp` covers all three cases.

### What this does *not* do (be realistic)

Brokkr's own USB transport is a plain synchronous libusb loop, the same technique
your MTK path already uses (up to 1 MiB per call, libusb splits it into queued
16 KiB URBs). Brokkr's flash speed comes from its protocol layer (1 MiB packets,
prefetching, parallel decompression), not from cleverer USB. So `bfusb` fixes
responsiveness and gives you measurements; it does not by itself make a healthy
link faster. If a run reports `host gaps` of 40% or more, the time is going
between transfers (Python/JNI/ack round trips, small protocol frames), which is a
protocol/engine-level fix, not a libusb one. If it reports a Full-Speed link,
fix the hardware path first.

## Tests (no phone, no NDK)

`app/src/main/cpp/host/run_usb_tests.sh` runs all of these (needs clang-18 +
libc++-18, a JDK, python3):

| Test | What it proves |
|---|---|
| `bfusb_test/run.sh` | `bfusb` vs a mock libusb under ASan/UBSan: success, timeout with partial bytes, stall, prompt cancel, pre-cancel, status mapping, ZLP, sync path, two threads, stats |
| `bfusb_test/run_mtk.sh` | the **real** `usb_native.c` JNI code from a JVM: Stop at 100 ms on a stalled 1 MiB write returns in ~100 ms |
| `bfusb_test/run_brokkr_transport.sh` | Brokkr's **real** `LibusbUsbTransport`: cancel and `close_now()` abort a stalled transfer in ~100 ms |
| `brokkr_smoke/run.sh` | `libbrokkr.so`'s real JNI glue: every class/method/field lookup resolves, callbacks arrive with the right types, ODIN handshake leaves through the transport, failures surface, cancel is prompt and safe |

QDL's existing suite (`host/run_tests.sh`) also passes with the modified `usb.c`.
The root `CMakeLists.txt` was configured and fully built on the host (203 steps)
with stand-ins for the Android-only libraries.

## Verification status

* **Android build.** GitHub Actions compiles the app: the Tests workflow builds the Kotlin
  and Compose code and runs the JVM unit tests, and Build & Release builds the APK with the
  NDK. The host tests above are run locally and are not part of CI.
* **UI behaviour.** The Samsung screens compile, but nothing exercises them automatically.
* **Real hardware.** Not verified: the USB permission flow, a real Odin flash, wireless
  flashing with a watch, and actual throughput numbers. The device test matrix is in
  [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md).

## Known limitations

* The Dynamic Island shows Samsung flashes through `SamsungIsland` (Island.kt), which maps the run
  onto the same job model the console engines use. The finished-run card is shown once and does not
  clear the result on the Progress tab.
* The Settings tab in Brokkr mode is the shared one (appearance etc.); it has no
  Samsung-specific settings beyond the options on the Flash tab.
* Strings are English only (the original app ships several translations).
* Folder import prefers `HOME_CSC` (keeps user data) over `CSC` (wipes); add the
  CSC file manually for a clean flash.
* `BrokkrNativeBridge.cancel()` silences native callbacks by design, so
  `SamsungViewModel` reports the stop itself once native returns.

## Licence

Brokkr is GPL-3.0-or-later (c) 2026 Gabriel2392 and is credited in `NOTICE.md`
with its bundled LZ4 / MD5 notices (`app/src/main/cpp/brokkr/THIRD_PARTY_NOTICES.txt`).
This project is GPL-3.0, which is compatible.
