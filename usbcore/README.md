# :usbcore

The USB layer shared by the ADB and fastboot code. A pure Kotlin/JVM module: no
Android classes, no JNI, no network. Everything here is unit-tested on a plain
JVM against fake devices.

## What is in it

| File | Purpose |
|---|---|
| `UsbTransport.kt` | The one interface the protocol code uses: write exactly N bytes, or read one transfer. Failures are `UsbTimeoutException` (link still usable), `UsbDisconnectedException`, or plain `UsbTransportException`. |
| `UsbInterfaceIds.kt` | The descriptor signature of ADB and fastboot: class `0xFF`, subclass `0x42`, protocol `0x01` (ADB) or `0x03` (fastboot). |
| `UsbDescriptors.kt` | Plain copies of the interface and endpoint descriptor fields, so selection logic does not need Android classes. |
| `DebugInterface.kt` | `DebugInterfaceFinder`: picks the ADB or fastboot interface and its bulk IN/OUT endpoints from a device's interface list. Matches on interface, never on vendor. |
| `BulkTransport.kt` | `BulkTransport`: a `UsbTransport` over a `BulkBackend` (the raw bulk transfer calls). Chunks writes into whole packets, optionally ends a write with a zero-length packet (ADB needs it), and turns Android's bare "transfer failed" into a precise exception. |
| `CancelToken.kt` | Polled by long loops so the UI can stop an operation. |

## Why `BulkTransport` guesses between timeout and failure

Android's `UsbDeviceConnection.bulkTransfer` returns `-1` for a timeout, a
stall and an I/O error alike, and gives no other signal. `BulkTransport` sorts
the cases in this order:

1. the transport was closed: `UsbDisconnectedException`;
2. the device is no longer in `UsbManager.getDeviceList()`: `UsbDisconnectedException`;
3. the call used at least 90% of the timeout: `UsbTimeoutException`;
4. anything else: `UsbTransportException` (a stall or I/O error).

This is a heuristic. It is the same trade-off other Android USB libraries make.
Its edges are covered by tests (`BulkTransportTest`), but it has not been
measured on real hardware.

## Android side

The app module (`app/src/main/java/com/bareflash/app/adbfb/AdbFbUsb.kt`)
converts `UsbDevice` descriptors into the plain types above, opens the device,
claims the interface (forcing a kernel driver to let go), and supplies the
`BulkBackend` over `UsbDeviceConnection`. It contains no protocol logic.

## Tests

```
./gradlew :usbcore:test
```

`BulkTransportTest` and `DebugInterfaceFinderTest` use `FakeBulkBackend`, which
behaves like Android's connection (transfers complete in full or return -1).
