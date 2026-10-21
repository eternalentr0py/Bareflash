# Bareflash: continuation brief

**Purpose:** everything needed to continue this project in a new chat with no other context. Paste this file into the new chat, supply a GitHub token (see "Working rules"), and say "continue". The repo may also need to be attached to the session (add_repo with push access) before git/gh work.

**Brief revision:** 4, written after subtask 4 (Console and Commands tabs). Revision history: 1 = `:usbcore` + `:fastboot`; 2 = Android USB transport + ADB/fastboot mode skeleton; 2.1 = CI compile fix; 3 = `:adb` protocol core; 4 = `:debugcli` + Console/Commands tabs + ADB connect in the app.

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
| Docs | `docs/` (`BROKKR_AND_USB.md`, `QDL_ENGINE.md`, `RELEASE_CHECKLIST.md`, `USB_PERFORMANCE.md`, `ADB_FASTBOOT_ROADMAP.md`), `usbcore/README.md`, `fastboot/README.md`, `adb/README.md` |
| ADB/fastboot mode (new, rev 2) | `app/.../adbfb/` (`AdbFbUsb.kt`, `AdbFbModels.kt`, `AdbFbViewModel.kt`), `app/.../ui/adbfb/AdbFbScreens.kt` |

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
- Both modules use `kotlin("jvm")` plugin id `org.jetbrains.kotlin.jvm`, JVM 17, `explicitApi()`, `allWarningsAsErrors`, JUnit 4.13.2. (Rev 2 added `implementation(project(":fastboot"))` to `:app`; rev 3 adds `:adb`.)

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

## 4b. Implemented in revision 2 (subtask 2)

**`:usbcore` additions** (pure JVM, strict flags, tested)
- `UsbDescriptors.kt`: `UsbEndpointInfo`, `UsbInterfaceInfo`, `UsbDirection`, `UsbEndpointType` (plain copies of descriptor fields).
- `DebugInterface.kt`: `DebugInterfaceFinder.find(interfaces[, protocol])` picks ADB/fastboot interfaces (class 0xFF/0x42, protocol 1 or 3) with first bulk IN and OUT endpoint; lowest alternate setting per interface id wins; ordered by id. Vendor never consulted.
- `BulkTransport.kt`: `BulkBackend` (raw `bulkOut`/`bulkIn` returning negative on failure, like Android's `bulkTransfer`) and `BulkTransport : UsbTransport`. Writes are chunked to whole max-packet multiples (default chunk 16 KiB), optional trailing zero-length packet when length is an exact packet multiple (ADB will need it, fastboot not), reads capped to chunk size. Failure classification: closed or device detached -> `UsbDisconnectedException`; failure that used >= 90% of the timeout -> `UsbTimeoutException`; otherwise `UsbTransportException` (stall/IO). This is a heuristic; unmeasured on hardware.
- Tests: `BulkTransportTest` (18), `DebugInterfaceFinderTest` (10), `FakeBulkBackend`. Total JVM tests now **76 passing** (45 old + 31 new) via kotlinc+JUnit with `-Werror -Xexplicit-api=strict`.

**App module**
- `EngineMode.ADB_FASTBOOT` (id `adbfb`, label "ADB", icon `Usb`, empty `vendorIds` so the MTK/QDL console ignores it, like Samsung). All exhaustive `when`s over `EngineMode` updated (Catalogs x4, ModeSwitcher icon, ConsoleTerminalCard prompt word `adb`, SettingsScreen hint, TutorialPages, SettingsAbout now "Four modes").
- `adbfb/AdbFbUsb.kt`: converts `UsbDevice` to `:usbcore` descriptors, `scan`, `isDebugDevice`, `open(usb, device, protocol)` (checks permission, `openDevice`, `claimInterface(force=true)`, `setInterface` for alt != 0), `AdbFbConnection`, `AndroidBulkBackend` over `UsbDeviceConnection`. Compile-checked against android-34 `android.jar`; never run.
- `adbfb/AdbFbViewModel.kt`: own USB receiver (`com.bareflash.app.ADBFB_USB_PERMISSION`), permission flow, single-thread USB executor, `connect` (fastboot only: reads `getvar all`), `refreshVars`, `reboot(RebootTarget)`, `disconnect`, log. A device `FAIL` keeps the link up (protocol still in step); any other failure drops the connection. Detach while a transfer runs only marks detached (no closing under the transfer).
- UI (`ui/adbfb/AdbFbScreens.kt`): tabs `Devices` (list, Grant, Connect/Disconnect, status), `Fastboot` (reboot menu, variables table), `Log`. New `Tab` entries `AdbDevices`, `AdbFastboot`, `AdbLog`; mode tabs = those + Settings. `MainShell` takes `adbfbVm`/`adbfb`, includes the ADB busy flag in the mode lock, and does not show the console island in this mode. No flashing/erasing is reachable from the UI yet.
- `MainActivity`: third ViewModel, `setActive` on mode change; `handleIntent` routes any attach of a device with an ADB/fastboot interface to this mode before the Samsung vendor check.
- `device_filter.xml`: added `<usb-device class="255" subclass="66" protocol="1"/>` and `protocol="3"`. Android's `DeviceFilter.matches(UsbDevice)` falls back to checking each interface (verified in the Android 12 source), so this matches any vendor. Side effect: plugging in any phone with USB debugging on offers to open Bareflash.
- `app/build.gradle.kts`: `implementation(project(":fastboot"))` (which exposes `:usbcore` through `api`).
- Docs: README (four modes, honest status), new `usbcore/README.md`, roadmap updated.

**Not verified:** Gradle/AGP compile of the app and Compose code (only `AdbFbUsb.kt` was compile-checked locally; ViewModel/UI could not be, no coroutines/Compose/lifecycle jars in the sandbox), the device-filter attach behaviour, any real device. CI result is the first check.

## 4c. Revision 2.1: CI compile fix

Both CI runs for revision 2 (Tests and Build & Release `1.0.1-beta.8`) failed with the same single error:
`ConsoleViewModel.kt:1202: 'when' expression must be exhaustive. Add the 'ADB_FASTBOOT' branch`.
`liveEngineFor(m: EngineMode)` maps a mode to a `LiveEngine` (label and accent for live notifications) and had no case for the new mode.

Fix: added `LiveEngine.Adb("ADB / Fastboot", 0xFFB48CFF)` in `live/LiveModels.kt` and `EngineMode.ADB_FASTBOOT -> LiveEngine.Adb` in `ConsoleViewModel.liveEngineFor`. A scripted audit of every `when` containing a `BROKKR ->` branch (and every `LiveEngine` `when`) found no other non-exhaustive one. Kotlin reported only that one error in each log, so no other compile error was visible; later Gradle stages (lint, R8, packaging) had not run yet.

Lesson for future steps: every new `EngineMode` or `LiveEngine` value needs every exhaustive `when` updated; grep for `BROKKR ->` to find them.

## 4d. Implemented in revision 3 (subtask 3: `:adb` core)

New pure-Kotlin/JVM module `:adb` (`adb/`, package `com.bareflash.adb`, depends on `:usbcore` via `api`, same Gradle setup as `:fastboot`: JVM 17, `explicitApi()`, `allWarningsAsErrors`, JUnit 4.13.2). Wiring: `settings.gradle.kts` includes `:adb`; `app/build.gradle.kts` has `implementation(project(":adb"))` and `testDebugUnitTest` now `dependsOn(":usbcore:test", ":fastboot:test", ":adb:test")`; `.github/workflows/tests.yml` uploads all four module report dirs on failure (first workflow edit, possible since the token gained the `workflow` scope). The app does not call `:adb` yet (that is subtask 4/5).

**Production code** (`adb/src/main/kotlin/com/bareflash/adb/`)
- `AdbWire.kt`: `AdbCommand` ids (little-endian ASCII), `AdbProtocol` constants (`VERSION_SKIP_CHECKSUM` 0x01000001, host max payload 256 KiB, host features `shell_v2,stat_v2,ls_v2`), `AdbMessage`, header encode/decode with magic and length validation.
- `MessageChannel.kt` (internal): buffered message reader that tolerates any transport slicing (one USB transfer per read, byte dribbling, zero-length packets, two messages in one read); serialized writes (header and payload as separate transfers); checksum sent/verified only when the negotiated version needs it.
- `AdbConnection.kt`: `AdbConnection.connect(transport, keys, config, listener, cancel, log)` does CNXN then AUTH (sign token; if rejected offer the public key; `AdbAuthListener.onWaitingForUserApproval(fingerprint)`; wait `approvalTimeoutMs`; second rejection = `AdbAuthException`; STLS refused). Then one daemon reader thread routes OKAY/WRTE/CLSE to streams. `open(service)`, `supports(feature)`, `info` (`AdbDeviceInfo` parsed from the banner: state, properties, features, `model`/`product`/`device`), `maxPayload`, `close()` (closes the transport). The caller keeps the transport if `connect` throws.
- `AdbStream.kt`: one-message-in-flight flow control both ways; the OKAY that releases the device's next chunk is sent only after the previous chunk is consumed by `read` (backpressure); `write`, `read`, `readFully`, `readAll(maxBytes)`, `close`, `isClosed`. The reader never blocks on stream state; a stream never sends while holding its lock.
- `AdbShell.kt`: `shell(cmd, timeoutMs, maxOutputBytes)` (shell v2: separate stderr and unsigned exit code; legacy `shell:` fallback with merged output and null exit code; output cap sets `truncated`), `openShell(cmd, pty, term)` returning `AdbShellSession` (`readEvent`, `writeStdin`, `closeStdin`, `resize`), `openLegacyShell`, `exec()` (binary-safe), `reboot(AdbRebootTarget)` (tolerates the link dropping).
- `AdbSync.kt`: `openSync()` returns `AdbSyncSession` with `stat`, `list`, `push(InputStream, path, mode, mtime, cancel, onProgress)`, `pull(path, OutputStream, cancel, onProgress)`; v1 and v2 (`STA2`: 68 bytes after the id including ctime; `DNT2`/`DONE` blocks 72 bytes after the id); directory names containing `/` or NUL are refused; arguments are validated BEFORE anything is sent (a bad path does not kill the session); a device `FAIL` leaves the session usable, any other error or cancel closes it.
- `AdbKeyPair.kt`: RSA-2048 `AdbKeyPair` (`generate`, `fromPkcs8`, `toPkcs8`, `signToken` = `NONEwithRSA` over the SHA-1 DigestInfo prefix plus the raw 20-byte token, `publicKeyMessage(name)` = base64 of the 524-byte Android struct + space + name + NUL, `fingerprint()` = MD5 of the struct, colon hex), `AdbKeyStore`, `loadOrCreate()`, `FileAdbKeyStore` (atomic write, owner-only).
- `AdbExceptions.kt`: `AdbException` hierarchy (protocol, auth, timeout, closed, stream rejected, cancelled, sync failure with the device's text, unsupported).
- App change: `AdbFbUsb.open` now defaults `BulkTransportConfig(zeroLengthPacketAfterFullFinalPacket = true)` for the ADB protocol (ADB needs a ZLP after writes ending on a full packet; fastboot does not).

**Tests and verification** (76 `:adb` tests, all passing with `kotlinc` + JUnit, `-Werror`)
- Test fake: `FakeAdbd.kt` (message-level adbd: auth, shell v2 incl. interactive `cat`, legacy shell, exec, reboot, sync v1/v2, one message in flight per stream, 32 KiB shell packets like adbd), `FakeAdbUsbTransport.kt` (USB-style transfers, optional zero-length packets, detach/inject hooks), `Harness.kt`, `tools/FakeAdbdServer.kt` (same fake over TCP).
- Reference checks produced outside this code base: public-key struct byte-identical to adb-shell's `encode_pubkey`; token signature byte-identical to OpenSSL (`cryptography`, token as pre-hashed SHA-1); CNXN/OKAY header bytes identical to adb-shell's; command ids identical.
- `adb/tools/interop-real-adb.sh`: starts the fake over TCP and drives it with the REAL `adb` 34.0.4 (`apt-get install adb`): first connection (key offered and accepted, banner parsed), shell v2 output/exit code/separate stderr, pull small and 300 KB, push, push/pull round trip, reconnect where the remembered key is accepted by signature alone (asserted via server trace). All pass. This validates the fake against Google's client; it does not validate this library against a real adbd.
- A real bug was found by review before tests (STA2 block size missed ctime) and one by tests (sync argument validation inside the close-on-error guard); both fixed.
- **Not verified:** the Gradle/AGP build of `:adb` (CI is the first check), the one-line app change, and everything on real hardware (real adbd, real USB, real Android key prompt, fingerprint format shown by the device).

**Known limits / not implemented:** `cmd`/`abb` services (package install), `forward`/`reverse`, `sendrecv_v2` compressed sync, delayed ack, TLS/wireless, `root:`/`tcpip:`, `track-devices`. Private key stored as plain PKCS#8 in app storage (Android Keystore is a possible hardening step).

## 4e. Implemented in revision 4 (subtask 4: Console and Commands tabs)

**New modules** (all in `settings.gradle.kts`; `:app` depends on `:adb` and `:debugcli`; `testDebugUnitTest` now also depends on `:debugcli:test`; `tests.yml` uploads `debugcli/build/reports/tests/**` too):
- `:fakes` (`fakes/`, package `com.bareflash.fakes`, no explicit-API): the fake adbd (`FakeAdbdCore`, `FakeFs`, `ShellOutcome`), `FakeAdbUsbTransport`, `FakeFastbootDevice`, `FakeWire` (independent 24-byte header codec), `TestKeys`, and `FakeAdbdServer` (the TCP server for the real-adb interop script). Moved out of `:adb`/`:fastboot` test source sets so `:adb`, `:fastboot` and `:debugcli` tests share them; they use only PUBLIC APIs of `:adb`/`:fastboot`. `:adb` and `:fastboot` have `testImplementation(project(":fakes"))`.
- `:debugcli` (`debugcli/`, package `com.bareflash.cli`, depends on `:adb` and `:fastboot` via `api`, `explicitApi()`): see `debugcli/README.md` for the file map. Pipeline: typed line, `CommandParser`, `DeviceCommand`, `RiskAssessor`, confirm, `CommandRunner`, `TerminalSink`. Key behaviours: `adb`/`fastboot` prefix optional once connected (bare words are shell commands over ADB, fastboot verbs over fastboot); `adb shell` keeps the raw remainder of the line; `cd` is remembered by the runner (`cd '<dir>' && <cmd>`); stdin closed at once; recursive push/pull skipping symlinks; `exec-out ... > file`; `adb install`, `root`, `forward`, `tcpip` etc. are `Unsupported` with a hint; risk levels SAFE / CAUTION (one-tap confirm) / DANGEROUS (type a phrase); `RunResult.linkLost` tells the app to drop the connection (device rebooting, cable pulled, fastboot download cancelled).
- `:adb` changes: `MessageChannel.receive` now takes a `CancelToken` polled while waiting for a header (so the approval wait can be cancelled) and uses short USB timeouts only while idle and long ones (2 s) mid-message, because Android discards the data of a bulk transfer that times out half-way; `AdbRebootTarget` gained `DOWNLOAD` (Samsung) and `EDL`; 78 `:adb` tests.

**App changes** (`app/src/main/java/com/bareflash/app/`)
- `adbfb/AdbFbViewModel.kt`: ADB connect (`openAdb`: `FileAdbKeyStore(filesDir/adb/adbkey).loadOrCreate()`, key name `bareflash@<Build.MODEL>`, `AdbAuthListener` posts "Tap Allow on the USB debugging prompt..." with the key fingerprint while the link is CONNECTING, Cancel = `disconnect()` sets `cancelRequested`); `openFastboot` also builds a `DeviceSession`. Console state: `console` (ConsoleLine list), `progress`, `pending` (PendingRun), `prompt`, `adbInfo`, `showConsole` (SharedFlow). `submit(line)`: history expansion and aliases via the existing `HistoryExpansion`/`Aliases`; only `help`/`man`, `clear`/`cls`, `history`/`alias`/`unalias` are console built-ins (the existing `Shell` built-ins `echo`, `pwd`, `type`, `whoami`, `hostname` are deliberately NOT used so they reach the device); then `CommandParser`, `RiskAssessor`, `execute` on the single worker thread via `launchWork`. `runFromCatalog(line)` emits `showConsole` and submits. `dropLink(reason)` closes the connection after `RunResult.linkLost`. `complete(line)` feeds `CliCompleter` with partitions/variables from the fastboot variables. Local files: `JavaLocalFiles(home = external storage root, downloads = public Downloads)` (needs all-files access).
- `adbfb/AdbFbModels.kt`: `ConsoleProgress`, `PendingRun`.
- `ui/adbfb/AdbConsoleScreen.kt` (terminal look from the shared terminal settings, prompt row with `BasicTextField`, hardware keys Enter/Tab/Up/Down/Ctrl+C/L/U, extra-key row, progress bar, copy/share/clear, confirm dialogs: `NcAlertDialog` for CAUTION, `TypedConfirmDialog` for DANGEROUS) and `ui/adbfb/AdbCommandsScreen.kt` (search, categories, cards with argument fields built from `CommandCatalog`, Run disabled with a reason when not applicable).
- `ui/MainShell.kt`: new tabs `AdbConsole`, `AdbCommands`; ADB tabs are now Devices, Console, Commands, Fastboot, Settings (five, to fit the bottom bar); the protocol Log tab is reached from a button on the Devices screen (`AdbFbTabs` keeps a `showLog` flag); `showConsole` selects the Console tab. `ui/TutorialPages.kt` has text for the new tabs. `ui/adbfb/AdbFbScreens.kt`: Devices rows show Connect for ADB and/or Fastboot plus Cancel while connecting.

**Verification** (275 JVM tests: 154 in usbcore/fastboot/adb plus 121 in debugcli; libsparse and real-`adb` cross-checks still pass)
- The Compose screens and the ViewModel changes were NOT compiled locally (no Android SDK or Compose); they were reviewed against the real signatures of every app-internal API they call (Shell results, settings, line editing, dialogs, terminal colours, link model). CI is the first real compile.
- Real devices: not tested.

**Known limits:** no terminal emulation (no `top`/`vi`), stdin not forwarded, environment variables not kept between commands, no remote path completion, `adb install` unsupported (hint: push then `adb shell pm install`), shell risk judgement is a heuristic.

## 5. Decisions and rationale

- Protocols in **pure Kotlin behind `UsbTransport`** (revised from an earlier idea of C on `bfusb`): memory-safe, JVM-testable with fakes, easiest to audit. A libusb-backed transport can be added later if Android's `UsbDeviceConnection` throughput or cancellation is not good enough.
- Screen mirroring: **vendor scrcpy's `scrcpy-server.jar`** (pinned version, built from source in CI if possible), write a Kotlin client for its protocol, decode H.264 with `MediaCodec` onto a Surface; open the server's `localabstract:` socket directly with an ADB `OPEN` (no `adb forward` layer). The client and server versions must match exactly. Later: replace with an own minimal Java server. Rejected: `screenrecord` + `input tap` (high latency, no real multitouch).
- Wireless ADB (TLS pairing) would require `INTERNET`; keep it out of the default build, offer as a separate flavor.
- New modes should be added through `EngineMode` so the console/commands/jobs/notification machinery is reused.

## 6. Remaining subtasks

| # | Subtask | Notes |
|---|---|---|
| 2 (DONE, rev 2) | Android `UsbTransport` + `EngineMode` skeleton | Implement `UsbTransport` over `UsbManager`/`UsbDeviceConnection` (claim the interface matching `UsbInterfaceIds`, find bulk IN/OUT endpoints, chunk bulk transfers, tune size, consider `UsbRequest` for throughput, map errors to `UsbTimeout/Disconnected`). Add an `ADB_FASTBOOT` entry to `EngineMode`, USB permission flow, device list. Extend `device_filter.xml` by interface class (verify Android matches `class/subclass/protocol` against interfaces; if not, handle attach differently). Add `implementation(project(":fastboot"))` (and `:adb`) to `:app`. First place fastboot runs on a real phone; ask the owner to test. |
| 3 (DONE, rev 3) | `:adb` core | CNXN/AUTH (RSA 2048 key in app-private storage, AUTH_TOKEN/SIGNATURE/RSAPUBLICKEY), OPEN/WRTE/OKAY/CLSE streams with flow control, shell v2 protocol, sync protocol (list/stat/push/pull), `exec:`; max payload negotiation; JVM tests against a fake ADB device. Reference: AOSP `adb/OVERVIEW.TXT`, `adb/protocol.txt`, `adb/SYNC.TXT`. |
| 4 (DONE, rev 4) | Console tab and Commands tab | Real CLI parser for `adb ...` / `fastboot ...`, completion/history reusing `console/shell/*`, a catalog like `McCatalog`, destructive-command confirmation via existing review/typed-confirm dialogs. |
| 5 | GUI tabs | Device info + reboot menu, Files (sync), Apps (list/install incl. split APKs/uninstall/extract/permissions), Logcat, Fastboot (getvar table, flash, erase, slots, typed confirm for unlock/lock). |
| 6 | Screen mirror | Vendored scrcpy-server + Kotlin client + MediaCodec + multitouch mapping; version-pinned. |
| 7 | Own Java server replacing scrcpy-server | |
| 8 | Hardening | Fuzzing the protocol parsers, real-device matrix in `docs/RELEASE_CHECKLIST.md`, reproducible build notes, Gradle dependency verification, release signing, optional no-`INTERNET` flavor. |

## 7. Sandbox/tooling notes (for the assistant)

Rev 2 additions: the repo must be attached with `add_repo` (access push) and cloned via the session proxy; plain `git push` then works without a token in the URL, and `gh` works for workflow dispatch. `apt-get update` is needed before `apt-get download junit4 libhamcrest-java`. A real `android.jar` (API 34) can be fetched from `raw.githubusercontent.com/Reginer/aosp-android-jar/main/android-34/android.jar` for compile-checking Android glue with kotlinc. AOSP sources for reference: `raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-12.0.0_r1/...` (newer tags 404 for some paths).

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

Rev 3 additions for ADB: `apt-get install -y adb` gives the real `adb` 34.0.4 host binary (no `adbd` package exists on Ubuntu 24.04); `pip install --break-system-packages adb-shell rsa cryptography` gives reference implementations for key/signature/header vectors. Compile `:adb` with `-Xexplicit-api=strict` together with the `:usbcore` sources, tests with `-Xfriend-paths`. Run the real-adb check with `adb/tools/interop-real-adb.sh <main-classes> <test-classes> <kotlin-stdlib.jar>`. Gotchas: never `pkill -f` a pattern that appears in your own command (it kills the tool shell); start background servers with `setsid nohup ... &` and kill by saved pid; `adb connect` returns before the user prompt is answered (that is not a failure); JUnit test methods must return Unit (helper functions taking lambdas must return Unit too).

Rev 4 additions: build order for local verification is usbcore + fastboot + adb (strict), then `:fakes` (`-cp main`), then `:debugcli` (strict), then tests with `-Xfriend-paths` and all of those on the classpath; the real-adb script now takes `<main-classes> <fakes-classes> <stdlib>`. The app layer cannot be compiled in the sandbox: review every call against real signatures (grep the definitions) because CI is the only compiler. Design lesson: keep logic in pure modules and keep Compose files thin.

## 8. Build log

| Revision | Commit | Build & Release | Notes |
|---|---|---|---|
| 1 | "Add :usbcore and :fastboot modules (native fastboot protocol, subtask 1)" `f7552fb` | Tests green; `1.0.1-beta.7` green | A first dispatch ran on old `main` (push had been rejected) and was cancelled; re-dispatched after the corrected push. |
| 2 | "Add Android USB transport and ADB/fastboot mode skeleton (subtask 2)" `6426ac4` | Tests **failed**, `1.0.1-beta.8` **failed** | Non-exhaustive `when` in `ConsoleViewModel.liveEngineFor`. |
| 2.1 | "Fix non-exhaustive EngineMode when (LiveEngine.Adb)" `a23482d` | Tests green; `1.0.1-beta.8` green | |
| 3 | "Add :adb module (native ADB protocol core, subtask 3)" `1a3642d` | Tests green; `1.0.1-beta.9` green | |
| 4 | "Add :debugcli and the ADB Console and Commands tabs (subtask 4)" | dispatched as `1.0.1-beta.10`, not awaited | First CI compile of the Compose screens and the ViewModel changes. If it fails the owner pastes logs; fix compile errors first. |

## 9. Next step

1. Owner reports the result of the revision-4 Tests and Build & Release runs (paste logs if they fail; fix compile errors first, most likely in `AdbFbViewModel.kt`, `AdbConsoleScreen.kt`, `AdbCommandsScreen.kt` or `AdbFbScreens.kt`).
2. Ask the owner to test on real phones: ADB connect and the USB-debugging prompt, a few shell commands, `cd`, push/pull of a file and a folder, a catalog command, a risky command's confirmation, fastboot `getvar all`, a flash of a small image, reboot. Anything that differs from the fakes (timing, ZLP, prompt behaviour, fingerprint text) needs fixing in `:adb`.
3. Subtask 5: GUI tabs for the rest: Device info, Files (browser over `AdbSyncSession.list/stat/pull/push`, pick local files via the document picker or paths), Apps (list/install/uninstall/extract/permissions; install needs the `cmd`/`abb_exec` services or `pm install` of a pushed file), Logcat (filter/search/save), Fastboot (partition table, flash, slots, unlock). Keep logic in pure modules with tests; keep Compose thin.
4. Then subtask 6: screen mirror (vendor scrcpy-server, Kotlin client, `MediaCodec`, touch mapping).
