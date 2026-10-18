# :adb

Native (pure Kotlin, JVM-only) host implementation of the ADB protocol. No
`adb` binary, no JNI, no Android dependencies, no network. It talks to
hardware only through `com.bareflash.usb.UsbTransport` (module `:usbcore`), so
it runs and is tested on a plain JVM.

## What is here

| File | Purpose |
|---|---|
| `AdbConnection` | CNXN/AUTH handshake, one reader thread, stream multiplexing, `open(service)` |
| `AdbStream` | One service connection; one-message-in-flight flow control in both directions; reads ack only when consumed (backpressure) |
| `AdbShell.kt` | `shell()` (shell v2: separate stderr, exit code; falls back to legacy), `openShell()` (interactive, pty, stdin, resize), `exec()` (binary-safe), `reboot()` |
| `AdbSync.kt` | `openSync()`: `stat`, `list`, `push`, `pull` with progress and cancel; v1 and v2 (`stat_v2`, `ls_v2`) |
| `AdbKeyPair.kt` | RSA-2048 identity, token signing, Android public-key encoding, fingerprint, `AdbKeyStore` / `FileAdbKeyStore` |
| `AdbWire.kt`, `MessageChannel.kt` | Message framing, checksums, buffered reads that cope with USB transfer boundaries and zero-length packets |

## Integration notes

- **ADB over USB needs zero-length packets.** Build the transport with `BulkTransportConfig(zeroLengthPacketAfterFullFinalPacket = true)`. `AdbFbUsb.open` does this by default for the ADB protocol.
- **Ownership.** `AdbConnection.connect` leaves the transport with the caller if it throws; on success the connection owns it and `close()` closes it.
- **First connection.** An unknown key makes the device show "Allow USB debugging?". `connect` calls `AdbAuthListener.onWaitingForUserApproval(fingerprint)` and waits up to `approvalTimeoutMs` (60 s). Store the key with an `AdbKeyStore` so "Always allow" sticks. The private key is plain PKCS#8 in app storage; moving it into the Android Keystore is a possible hardening step.
- **Threads.** Any thread may open streams; one stream is for one reader and one writer. A connection runs a daemon reader thread.
- **Hostile devices.** Message length, shell packet length, sync block length, directory entry names (no `/`, no NUL) and failure-message length are all bounded and validated.
- **Features used:** `shell_v2`, `stat_v2`, `ls_v2`. Advertised to the device only if implemented.

## Verification status

| Check | Status |
|---|---|
| 76 JUnit tests against a fake adbd (`./gradlew :adb:test`) | passing when last run, using `kotlinc` and JUnit directly |
| Public-key struct and token signature byte-identical to references made outside this code (adb-shell, OpenSSL via `cryptography`) | passing (`AdbKeyPairTest`) |
| Message header bytes identical to adb-shell's | passing (`AdbWireTest`) |
| The fake adbd driven by the real `adb` 34.0.4 client (`tools/interop-real-adb.sh`): auth with a new and a remembered key, shell v2 exit codes and stderr, sync v2 pull/push | passing |
| Gradle / Android Studio build of this module | run by CI |
| Real adbd over a real USB cable | **not tested** |

The interop script proves the *fake* is faithful to Google's client; it does
not prove this library against a real adbd. Differences between the fake and
real devices can only show up on hardware.

## Not implemented yet

`cmd`/`abb` services (package install), `forward`/`reverse`, `sendrecv_v2`
(compressed sync), delayed acknowledgements, TLS/wireless pairing,
`root:`/`unroot`, `tcpip:`, `track-devices`.
