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
| 2 | Android `UsbTransport` over `UsbDeviceConnection`; device-filter by interface class; new `EngineMode` skeleton | next |
| 3 | `:adb` core: CNXN/AUTH (RSA key in app storage), streams, shell v2, sync, exec | |
| 4 | Console tab (real CLI parser for `adb ...` / `fastboot ...`) and Commands tab | |
| 5 | Device, Files, Apps, Logcat, Fastboot GUI tabs | |
| 6 | Screen mirror: vendored scrcpy-server + Kotlin client, `MediaCodec` decode, multi-touch mapping | |
| 7 | Own Java server replacing scrcpy-server | |
| 8 | Hardening: fuzzing, device matrix, reproducible-build notes, dependency verification | |
