# :fastboot

Native (pure Kotlin, JVM-only) host implementation of the fastboot protocol. No
binaries, no JNI, no Android dependencies, no network. It talks to hardware only
through `com.bareflash.usb.UsbTransport` (module `:usbcore`).

## What is here

| File | Purpose |
|---|---|
| `FastbootClient` | Commands: `getvar`, `getvar:all`, `download`, `flash`, `erase`, `boot`, `reboot*`, `continue`, `set_active`, `oem`, `flashing`, logical-partition commands |
| `FastbootResponse` | Strict parser for INFO / OKAY / FAIL / DATA packets |
| `Sparse.kt` | Android sparse format parser, and the splitter that fits images into `max-download-size` |
| `ByteSource`, `Payload` | Streaming image input; large images are never held in memory |
| `FastbootVars` | `getvar:all` and size parsing |

## Behaviour worth knowing

- An image no larger than `max-download-size` is sent unchanged. A larger image
  (raw or sparse) is re-emitted as several sparse pieces that each describe the
  whole image and skip the parts owned by other pieces with don't-care chunks.
- Commands are validated before anything is sent: at most 64 printable ASCII
  bytes, and partition names are limited to `[A-Za-z0-9._-]{1,48}`.
- `reboot` and `continue` treat a dropped link or a short silence as success,
  because devices commonly disconnect without answering. A `FAIL` is still an error.
- Cancelling during a download leaves the device waiting for data. Close the
  connection or reboot the device afterwards.

## Verification status

| Check | Status |
|---|---|
| 45 JUnit tests against a fake device (`./gradlew :usbcore:test :fastboot:test`) | passing when last run here, using `kotlinc` and JUnit directly |
| Splitter output accepted by Android's real `simg2img`, byte-identical reassembly (`tools/crosscheck-libsparse.sh`) | passing |
| Gradle / Android Studio build of these modules | **not run** (no Android SDK in the authoring environment) |
| Real bootloader (any device) | **not tested** |

The protocol was written from the AOSP fastboot documentation. Treat it as
unproven on hardware until the device matrix in `docs/RELEASE_CHECKLIST.md` lists it.

## Not yet implemented

Fetch (`fetch:`), `upload`, zip bundles (`flashall`), A/B slot auto-flash logic,
vendor-specific OEM helpers, and the Android `UsbTransport` itself (next subtask).
