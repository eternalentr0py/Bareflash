# Bareflash: continuation brief

**Purpose:** everything needed to continue this project in a new chat with no other context. Paste this file into the new chat, supply a GitHub token (see "Working rules"), and say "continue".

**Brief revision:** 1, written after subtask 1 (fastboot core). Update this file after every implementation step and send it to the owner.

---

## 1. Owner, goal and standing requirements

Owner: Chris (GitHub `eternalentr0py`). Repo: `https://github.com/eternalentr0py/Bareflash`, default branch `main`. (Other repos on the account: `Bareflash-debug` private, `ErosFlashTool`, `MtkClientAndroid1` private. Only `Bareflash` is the target.)

Goal: one open-source Android app that flashes and controls *other* devices over USB OTG, with **no telemetry, no analytics, no ads, no internet access, no data sent to third parties**. Modes: MediaTek, Qualcomm (QDL), Samsung (Odin), a general-purpose flashing tool, and a new **ADB + fastboot** mode that should be on par with the Play Store app Bugjaeger (which has telemetry/ads and is closed source).

Owner's requirements for ADB/fastboot, stated in his words, condensed:
- **Fully native** ADB and fastboot (no bundled `adb`/`fastboot` binaries).
- Tabs: a clean **Console** where power users type CLI commands; a **Commands** tab for intermediate users; a **GUI** for the rest (device info, files, apps, logcat, fastboot flashing, reboot menu, and so on).
- **Screen mirroring with full touch support and input mapping** (like scrcpy-style desktop tools) so one phone can control another over OTG ADB.
- Everything **fully functional in one app**. Split large work into subtasks and do each individually.
- Code must be **open source, auditable, manageable, clean, reproducible**. Reproducible third-party pieces are included as-is now; the owner will refine reproducibility or replace pieces with his own implementations later.
- Decision on screen touch control: **vendor scrcpy's server first, then evolve into an own Java server** in a later step.
- Do not economize on effort or tokens when implementing.

## 2. Working rules (from the owner)

1. **Push changes directly to the GitHub repo (`main`).** Do not hand code files over in chat.
2. **After each implementation step: update this brief, commit it, and send the owner the brief as a file in chat.** (This is the one file he wants in chat each time.)
3. **After a *successful* push, trigger the "Build & Release" workflow, then stop and reply.** Do not wait for the build to finish. Always check the push result first: a dispatch builds whatever is on `main`, so dispatching after a failed push builds old code (this happened once in revision 1 and the run was cancelled).
4. The owner supplies a GitHub token (a PAT) in the chat. As of revision 2.1 it has the `workflow` scope, so `.github/workflows/*` can be changed, and it can read Actions logs and dispatch workflows. **Never write the token into any file, commit, remote URL, git config, or memory.** Use it only per command, e.g. `git -c http.extraHeader="Authorization: Basic $(printf 'x-access-token:%s' "$TOKEN" | base64 -w0)" ...` and `curl -H "Authorization: Bearer $TOKEN" ...`. If the token is expired or lacks scope, ask for a new one.
5. Release convention so far: pre-releases tagged `v1.0.1-beta.N`; v1.0.0 was the only stable. Trigger with workflow id `385272549` (Build & Release), `ref=main`, inputs `version` (e.g. `1.0.1-beta.7`), `prerelease=true`, optional `notes`. Tests workflow id `385272550` runs automatically on push to `main`.
6. Be honest about what is and is not verified. Do not claim hardware or Gradle results that were not obtained.

## 3. Codebase map (as inspected)

Existing app (Kotlin + Compose UI, package `com.bareflash.app`, minSdk 26, compileSdk 36, targetSdk 34, NDK 27.2.12479018, CMake 3.22.1, Kotlin 2.3.0, AGP 8.9.1, Gradle 8.11.1, Chaquopy 16.1.0 with Python 3.11). GPL-3.0.

| Area | Where |
|---|---|
| Modes enum (catalog, USB vendor filter, hints per engine) | `app/src/main/java/com/bareflash/app/engine/EngineMode.kt` (MTK, QDL, BROKKR) |
| MTK engine | Python `mtkclient` via Chaquopy: `app/src/main/python/` (`mtk.py`, `mtk_api.py`, `android_entry.py`, `mtkclient/`) |
| QDL engine | vendored qdl 2.8 in `app/src/main/cpp/qdl/`, JNI in `qdl_jni.c`, `engine/Qdl*.kt` |
| Samsung engine | vendored Brokkr Flash 2.4.10 in `app/src/main/cpp/brokkr/`, `samsung/*.kt`, `ui/samsung/BrokkrScreens.kt` |
| Shared native USB layer | `app/src/main/cpp/bfusb/` (libusb-based, cancellable bulk transfers, link-speed and throughput telemetry that stays local) |
| libusb | prebuilt `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/libusb-1.0.so`, header in `cpp/third_party/libusb/` |
| USB glue (Kotlin) | `UsbBridge.kt`, `LibusbNative.kt`, `console/ConsoleUsb.kt` |
| Console, command rules, safety | `console/` (`CommandRules.kt`, `ConsoleViewModel.kt`, `SafetyNet.kt`, `WriteGuard.kt`, `Preflight.kt`, `shell/` line editing and completion) |
| Command catalogs | `catalog/McCatalog.kt`, `catalog/QdlCatalog.kt`, `engine/Catalogs.kt` |
| UI | `ui/` (`MainShell.kt`, `ModeSwitcher.kt`, `ConsoleScreen.kt`, `CommandsScreen.kt`, `DumpsScreen.kt`, `Settings*.kt`, `TypedConfirmDialog.kt`, tutorial/onboarding) |
| USB attach filter | `app/src/main/res/xml/device_filter.xml` (MediaTek 0x0E8D, Qualcomm 0x05C6, Samsung 0x04E8 download-mode PIDs only) |
| CI | `.github/workflows/release.yml` (Build & Release, manual), `tests.yml` (JVM unit tests on push/PR) |
| Docs | `docs/` (`BROKKR_AND_USB.md`, `QDL_ENGINE.md`, `RELEASE_CHECKLIST.md`, `USB_PERFORMANCE.md`, `ADB_FASTBOOT_ROADMAP.md`) |

Privacy/permission facts found during the audit (unchanged so far):
- No analytics, ads, or HTTP client code; dependencies are AndroidX/Compose/Material, Chaquopy, pycryptodome(x), colorama.
- Manifest declares `INTERNET` for Samsung wireless flashing (TCP listener on 13579) and mtkclient's optional key server. An OTG-only ADB/fastboot mode needs no `INTERNET`. A strict "no internet" build would need a flavor without those two features.
- `MANAGE_EXTERNAL_STORAGE` is requested (QDL reads firmware by path), so no Google Play distribution.
- `app/debug.keystore` (with password) is committed and release builds fall back to it unless secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` are set. Real release signing is still to be set up by the owner.
- libxml2 and libzip are fetched from GitHub at build time by `qdl_engine.cmake` (FetchContent); pin or vendor for reproducibility.
- The README states most app code is AI-generated and not reviewed line by line; QDL has never run on Qualcomm hardware; the MTK package rename was not recompiled end to end at the time of writing.
- Licenses: mtkclient and Brokkr are GPL-3.0, so the combined app is GPL-3.0. scrcpy-server (Apache-2.0) is compatible with GPLv3.

## 4. What has been implemented (revision 1)

Commit "Add :usbcore and :fastboot modules (native fastboot protocol, subtask 1)". New files only, plus Gradle and CI wiring.

**Gradle wiring**
- `settings.gradle.kts`: `include(":usbcore")`, `include(":fastboot")`.
- root `build.gradle.kts`: added plugin `org.jetbrains.kotlin.jvm` version 2.3.0 (apply false).
- `app/build.gradle.kts`: `testDebugUnitTest` now `dependsOn(":usbcore:test", ":fastboot:test")`, so the existing Tests workflow runs the new modules' tests. (`tests.yml` itself was **not** changed: the owner's token lacks the `workflow` scope, so GitHub rejects pushes that touch `.github/workflows/`. Optional later: also upload `usbcore/build/reports/tests/**` and `fastboot/build/reports/tests/**` on failure.)
- Both modules use `kotlin("jvm")` plugin id `org.jetbrains.kotlin.jvm`, JVM 17, `explicitApi()`, `allWarningsAsErrors`, JUnit 4.13.2. The `:app` module does **not** yet depend on them.

**`:usbcore`** (`usbcore/src/main/kotlin/com/bareflash/usb/`)
- `UsbTransport` (write exactly N bytes or throw; read one transfer; `UsbTimeoutException`, `UsbDisconnectedException`, base `UsbTransportException : IOException`).
- `CancelToken` (`fun interface`, `NEVER`).
- `UsbInterfaceIds`: class 0xFF, subclass 0x42, protocol 0x01 = ADB, 0x03 = fastboot.

**`:fastboot`** (`fastboot/src/main/kotlin/com/bareflash/fastboot/`)
- `FastbootClient(transport, cancel, log, nanoClock)`: `command`, `getvar`/`getvarOrNull`/`getvarAll`, `maxDownloadSize`, `download(payload, onBytesSent)`, `flash(partition, image, maxDownloadSize?, progress?)`, `boot`, `erase`, `setActive`, `reboot(target, answerTimeoutMs)`, `continueBoot`, `flashing(FlashingCommand)`, `oem`, create/delete/resize logical partition. One command in flight; not thread-safe.
- Protocol behaviour: commands are printable ASCII, at most 64 bytes; responses INFO* then OKAY/FAIL/DATA; each INFO restarts the timeout; 500 ms read polling for cancellation; `download:%08x` then the device must answer DATA with the same length; partition names `[A-Za-z0-9._-]{1,48}`; slot is one letter; reboot/continue treat a dropped link or short silence (default 5 s) as success but still surface FAIL. If the device reports no `max-download-size` the client assumes 64 MiB.
- `Sparse.kt`: Android sparse format parser (strict validation, CRC chunks dropped), `Resparser` (pieces each describe the whole image and use don't-care chunks to skip other pieces' blocks; each piece is at most the limit; minimum limit is header + gap chunks + one block), `FlashPlanner.plan(image, max)` (as-is if it fits, else raw to sparse at 4096-byte blocks then split).
- `ByteSource` (`ByteArraySource`, `FileByteSource`), `Payload`/`SegmentPayload` (streams in 1 MiB buffers), `FastbootResponse`, `FastbootVars`, exceptions (`FastbootFailException` etc.).
- Docs: `fastboot/README.md`; roadmap: `docs/ADB_FASTBOOT_ROADMAP.md`.

**Tests and verification**
- 45 JUnit tests (fake device `FakeFastbootDevice`, independent reference sparse decoder and builder in `TestSupport.kt`) passed using `kotlinc` and JUnit directly.
- `fastboot/tools/crosscheck-libsparse.sh` feeds planner output to Android's real `simg2img`; all three scenarios passed (byte-identical reassembly; passthrough identical).
- **Not verified:** the Gradle/AGP build of the new modules, the Android app build with them, and any real device. These pieces were written from AOSP documentation.

## 4c. Revision 2.1: CI compile fix

Both CI runs for revision 2 (Tests and Build & Release `1.0.1-beta.8`) failed with the same single error:
`ConsoleViewModel.kt:1202: 'when' expression must be exhaustive. Add the 'ADB_FASTBOOT' branch`.
`liveEngineFor(m: EngineMode)` maps a mode to a `LiveEngine` (label and accent for live notifications) and had no case for the new mode.

Fix: added `LiveEngine.Adb("ADB / Fastboot", 0xFFB48CFF)` in `live/LiveModels.kt` and `EngineMode.ADB_FASTBOOT -> LiveEngine.Adb` in `ConsoleViewModel.liveEngineFor`. A scripted audit of every `when` containing a `BROKKR ->` branch (and every `LiveEngine` `when`) found no other non-exhaustive one. Kotlin reported only that one error in each log, so no other compile error was visible; later Gradle stages (lint, R8, packaging) had not run yet.

Lesson for future steps: every new `EngineMode` or `LiveEngine` value needs every exhaustive `when` updated; grep for `BROKKR ->` to find them.

## 5. Decisions and rationale

- Protocols in **pure Kotlin behind `UsbTransport`** (revised from an earlier idea of C on `bfusb`): memory-safe, JVM-testable with fakes, easiest to audit. A libusb-backed transport can be added later if Android's `UsbDeviceConnection` throughput or cancellation is not good enough.
- Screen mirroring: **vendor scrcpy's `scrcpy-server.jar`** (pinned version, built from source in CI if possible), write a Kotlin client for its protocol, decode H.264 with `MediaCodec` onto a Surface; open the server's `localabstract:` socket directly with an ADB `OPEN` (no `adb forward` layer). The client and server versions must match exactly. Later: replace with an own minimal Java server. Rejected: `screenrecord` + `input tap` (high latency, no real multitouch).
- Wireless ADB (TLS pairing) would require `INTERNET`; keep it out of the default build, offer as a separate flavor.
- New modes should be added through `EngineMode` so the console/commands/jobs/notification machinery is reused.

## 6. Remaining subtasks

| # | Subtask | Notes |
|---|---|---|
| 2 | Android `UsbTransport` + `EngineMode` skeleton | Implement `UsbTransport` over `UsbManager`/`UsbDeviceConnection` (claim the interface matching `UsbInterfaceIds`, find bulk IN/OUT endpoints, chunk bulk transfers, tune size, consider `UsbRequest` for throughput, map errors to `UsbTimeout/Disconnected`). Add an `ADB_FASTBOOT` entry to `EngineMode`, USB permission flow, device list. Extend `device_filter.xml` by interface class (verify Android matches `class/subclass/protocol` against interfaces; if not, handle attach differently). Add `implementation(project(":fastboot"))` (and `:adb`) to `:app`. First place fastboot runs on a real phone; ask the owner to test. |
| 3 | `:adb` core | CNXN/AUTH (RSA 2048 key in app-private storage, AUTH_TOKEN/SIGNATURE/RSAPUBLICKEY), OPEN/WRTE/OKAY/CLSE streams with flow control, shell v2 protocol, sync protocol (list/stat/push/pull), `exec:`; max payload negotiation; JVM tests against a fake ADB device. Reference: AOSP `adb/OVERVIEW.TXT`, `adb/protocol.txt`, `adb/SYNC.TXT`. |
| 4 | Console tab and Commands tab | Real CLI parser for `adb ...` / `fastboot ...`, completion/history reusing `console/shell/*`, a catalog like `McCatalog`, destructive-command confirmation via existing review/typed-confirm dialogs. |
| 5 | GUI tabs | Device info + reboot menu, Files (sync), Apps (list/install incl. split APKs/uninstall/extract/permissions), Logcat, Fastboot (getvar table, flash, erase, slots, typed confirm for unlock/lock). |
| 6 | Screen mirror | Vendored scrcpy-server + Kotlin client + MediaCodec + multitouch mapping; version-pinned. |
| 7 | Own Java server replacing scrcpy-server | |
| 8 | Hardening | Fuzzing the protocol parsers, real-device matrix in `docs/RELEASE_CHECKLIST.md`, reproducible build notes, Gradle dependency verification, release signing, optional no-`INTERNET` flavor. |

## 7. Sandbox/tooling notes (for the assistant)

The chat sandbox has JDK 21 and Python, no Gradle distribution, no Android SDK, no Maven/Google repositories. Network allows `github.com`, `api.github.com`, `codeload`/`raw.githubusercontent.com`, `release-assets.githubusercontent.com`, `archive.ubuntu.com`, PyPI, npm, crates. So Android/Gradle builds can only be verified by GitHub Actions.

Reproducing the JVM verification:
```bash
# Kotlin compiler (2.3.0) from GitHub releases
curl -sSL -o kotlin.zip https://github.com/JetBrains/kotlin/releases/download/v2.3.0/kotlin-compiler-2.3.0.zip && unzip -q kotlin.zip   # -> kotlinc/
# JUnit 4 and Hamcrest from Ubuntu
apt-get download junit4 libhamcrest-java && dpkg -x junit4_*.deb x && dpkg -x libhamcrest-java_*.deb x   # jars under x/usr/share/java
# Real libsparse tools for the cross-check
apt-get download android-sdk-libsparse-utils android-libsparse android-libbase android-liblog && dpkg -x each .deb into one root; set LD_LIBRARY_PATH to root/usr/lib/x86_64-linux-gnu(/android)
# Compile main (strict) then tests, then run
kotlinc -jvm-target 17 -Werror -Xexplicit-api=strict -d out/main usbcore/src/main/kotlin/com/bareflash/usb/*.kt fastboot/src/main/kotlin/com/bareflash/fastboot/*.kt
kotlinc -jvm-target 17 -Werror -Xfriend-paths=out/main -cp out/main:junit4.jar:hamcrest-core.jar -d out/test usbcore/src/test/kotlin/com/bareflash/usb/*.kt fastboot/src/test/kotlin/com/bareflash/fastboot/*.kt
java -cp out/main:out/test:junit4.jar:hamcrest-core.jar:kotlin-stdlib.jar org.junit.runner.JUnitCore <test classes>
```
Gotchas seen: `internal` symbols need `-Xfriend-paths` when tests are compiled separately; the silent-device reboot test must pass a short `answerTimeoutMs`.

## 8. Build log

| Revision | Commit | Build & Release | Notes |
|---|---|---|---|
| 1 | "Add :usbcore and :fastboot modules (native fastboot protocol, subtask 1)" `f7552fb` | Tests green; Build & Release `1.0.1-beta.7` green | A first dispatch ran on old `main` (push had been rejected for a workflow-file change) and was cancelled; re-dispatched after the corrected push. |
| 2 | "Add Android USB transport and ADB/fastboot mode skeleton (subtask 2)" `6426ac4` | Tests **failed**, Build & Release `1.0.1-beta.8` **failed** (same compile error in both) | Non-exhaustive `when` in `ConsoleViewModel.liveEngineFor`. |
| 2.1 | "Fix non-exhaustive EngineMode when (LiveEngine.Adb)" | dispatched as `1.0.1-beta.8` (not yet checked) | Result not awaited, per owner. If it fails, the owner pastes logs. |

## 9. Next step

1. Owner reports the result of the revision-2.1 Tests and Build & Release runs (paste logs if they fail; fix compile errors first).
2. Ask the owner to test on hardware if possible: plug a phone in fastboot mode (and one with USB debugging) via OTG, check Devices lists it, Grant works, Connect shows variables, reboot works, and whether the attach launch opens the app in ADB mode.
3. Start subtask 3: `:adb` core (see section 6).
