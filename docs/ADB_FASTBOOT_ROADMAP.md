# ADB and fastboot: build plan

Goal: a fully native ADB and fastboot client inside Bareflash, no bundled
`adb`/`fastboot` binaries, no network, with Console, Commands and GUI tabs and
screen mirroring with touch control over OTG.

## Design rules

- Protocols are pure Kotlin behind `UsbTransport`, so they run and are tested on a plain JVM against fake devices.
- The Android app owns the real USB connection and all UI.
- OTG-only builds need no `INTERNET` permission. Wireless ADB (TLS pairing) would, so it belongs in a separate build flavor.
- Third-party code that cannot be rebuilt from source here is vendored as-is with its license, version and checksum recorded.

## Subtasks

| # | Subtask | State |
|---|---|---|
| 1 | `:usbcore` + `:fastboot` protocol, sparse splitter, fake-device tests, libsparse cross-check | done (JVM-verified) |
| 2 | Android `UsbTransport` over `UsbDeviceConnection`; device-filter by interface class; new `EngineMode` skeleton | done (JVM-verified logic; Android layer compile-checked only, not run on a device) |
| 3 | `:adb` core: CNXN/AUTH (RSA key in app storage), streams, shell v2, sync, exec | done (JVM tests, real-`adb` interop; not tested on hardware) |
| 4 | Console tab (real CLI parser for `adb ...` / `fastboot ...`) and Commands tab | |
| 5 | Device, Files, Apps, Logcat, Fastboot GUI tabs | |
| 6 | Screen mirror: vendored scrcpy-server + Kotlin client, `MediaCodec` decode, multi-touch mapping | |
| 7 | Own Java server replacing scrcpy-server | |
| 8 | Hardening: fuzzing, device matrix, reproducible-build notes, dependency verification | |

## Subtask 2 notes

- New mode `EngineMode.ADB_FASTBOOT`, with an empty vendor filter so the MediaTek/Qualcomm console ignores it. It owns USB through `AdbFbViewModel`, like the Samsung mode.
- `device_filter.xml` gained two interface-based entries (class 255, subclass 66, protocol 1 and 3). Android's `DeviceFilter.matches(UsbDevice)` checks each interface when the device-level class does not match (checked in the Android 12 source), so one entry covers any vendor. **Open question for the first device test:** whether launching the app by plugging in an ADB phone behaves as intended. `MainActivity.handleIntent` routes such a launch to this mode.
- Every attach of a phone with USB debugging on now offers to open Bareflash. That is expected for an ADB tool, and the "always open" choice is the user's.
- UI so far: Devices (list, grant, connect), Fastboot (variables, reboot), Log. No flashing or erasing is reachable from the UI yet. That arrives with the Fastboot GUI step, behind typed confirmation.
- ADB devices are listed, but "Connect" is shown only for fastboot until subtask 3.

