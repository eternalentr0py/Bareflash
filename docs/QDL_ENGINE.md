# QDL engine

Bareflash's Qualcomm engine is [linux-msm/qdl](https://github.com/linux-msm/qdl)
**v2.8**, vendored in `app/src/main/cpp/qdl/` and built into `libqdl_jni.so`.
It speaks Sahara and Firehose to a phone in EDL mode (USB `05C6:9008`).

## How it runs

```
Kotlin (QdlEngine)         UsbManager.openDevice() -> file descriptor
   |  JNI nativeRun(fd, argv, sink)
qdl_jni.c                  converts arguments, forwards output lines
   |
android/qdl_runner.c       fork()  -------------------------+
   |  reads the child's stdout/stderr pipe                  |  child process
   |  nativeCancel() -> SIGTERM, then SIGKILL               |  qdl_main(argv)
   v                                                        |  libusb_wrap_sys_device(fd)
lines -> Kotlin -> terminal + progress bar                  +-----------------------
```

Why a child process: qdl ends the process on many errors (`exit()`, `errx()`),
keeps global state, and blocks inside USB transfers. In a forked child, a fatal
qdl error only ends the child, every run starts clean, and cancel is a `kill`.

## Android patches to qdl

| File | Change |
|---|---|
| `src/usb.c` | When `qdl_android_usb_fd >= 0`: disable libusb device discovery and wrap that descriptor instead of scanning the bus. Never waits for a device. |
| `src/auto.c` | The default backend does not retry when a descriptor was handed in. |
| `src/ux.c` | Progress is printed as `@@progress <value> <max> <bytes-per-unit> <task>` lines for the app instead of a terminal bar. |
| `src/firehose.c`, `src/sahara.c`, `include/qdl.h` | Set the unit (sector size or bytes) for those progress lines. |
| `android/qdl_android_compat.h` | Force-included into the qdl sources: `exit`/`err`/`errx` end the child immediately; `warn` prints `qdl:` instead of the app name; own `__progname`. |

Everything else is upstream, unmodified.

## Build

`cpp/qdl_engine.cmake` builds qdl, libxml2 v2.14.6 and libzip v1.11.4 as static
libraries from pinned GitHub tags. The first CMake configure therefore needs
network access; no prebuilt binaries are committed. `libusb-1.0.so` is the
prebuilt one in `jniLibs/`; only its header (`third_party/libusb/libusb.h`,
v1.0.27) is vendored.

## Tests (no Android needed)

```
app/src/main/cpp/host/run_tests.sh
```

builds the same sources on a desktop and checks: version, error exit stays in
the child, a bad USB descriptor is reported and does not hang, a dry-run flash
through qdl's simulator, progress lines, prompt cancel, and the real JNI glue
loaded in a JVM with `-Xcheck:jni`. It is a local check: the release workflow builds the APK only and does not run it.

## What has NOT been verified

- The NDK build (cross-compiling libxml2/libzip, bionic differences).
- `fork()` under ART.
- Any transfer to a real Qualcomm device, in either direction.

Treat the first flash on hardware as a test: start with read-only commands
(`chipinfo`, `read`, `sha256`) and keep a way to recover the device.

## Upgrading qdl

Replace `cpp/qdl/src` and `cpp/qdl/include/qdl.h` with the new tag, re-apply the
patches in the table above (search for `QDL_ANDROID`), run `run_tests.sh`.
