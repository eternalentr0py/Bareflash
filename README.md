# Bareflash

Bareflash is an Android application that talks to a phone or tablet over USB OTG and performs low-level flash operations without a computer. It was written so that a single MediaTek device could be dumped, repaired, and written back when ordinary flashing tools and local repair shops could not preserve the data.

The project is licensed under the GNU General Public License v3. You may fork, redesign, repurpose, modify, or redistribute it under that licence. See [LICENSE](LICENSE).

## Why it exists

The original target was a Samsung Galaxy A10s (SM-A107F, MediaTek MT6765) that stopped booting after a non-original battery replacement. The screen was already broken; the phone was still usable through TalkBack until it began freezing. A few force restarts later it came up on a black screen that still accepted emergency calls, but offered no normal interface.

Flashing the latest Android 11 build with Odin introduced `set_policy_failed` on `/data/sec`. A downgrade to Android 9 was blocked by Samsung's rollback (RPMB / bit) protection. The lowest firmware still allowed for that bit then failed with `enablefilecrypto_failed`. Public write-ups of the same errors almost always ended in a factory reset. Local shops that promised data recovery typically wiped userdata and returned a freshly flashed phone.

Bareflash was built to avoid that outcome. Because no PC was available, an MTK client was ported onto Android with a custom libusb path so another phone could list and dump partitions over OTG. Multiple raw copies of userdata were taken first. Empty directories that were blocking SELinux policy application were cleared with `debugfs` and `e2fsck`. That repair accidentally removed the file-based encryption key inodes and produced `init_user0_failed`. The deleted inodes were transplanted back from an untouched dump, and the device booted past the policy and encryption failures introduced by the Odin flash.

The tool grew from that one recovery into a general flashing application.

## What it can do

The interface has four modes. The bottom bar switches the command catalog, USB filter, and tabs. The switch is locked while a job is running.

| | MediaTek | Qualcomm | Samsung | ADB / Fastboot |
|---|---|---|---|---|
| Target | BROM, Preloader, Download Agent | EDL 9008 (Sahara / Firehose) | Download mode (Odin protocol) | Any Android device with USB debugging on, or in fastboot mode |
| Engine | [mtkclient](https://github.com/bkerler/mtkclient) via Chaquopy | [qdl](https://github.com/linux-msm/qdl) via JNI | [Brokkr Flash](https://github.com/Gabriel2392/brokkr-flash) via JNI | Own pure-Kotlin ADB and fastboot ([`:usbcore`](usbcore/README.md), [`:fastboot`](fastboot/README.md)), no bundled binaries |
| Tabs | Console, Commands, Dumps, Settings | Console, Commands, Session, Dumps, Settings | Flash, Progress, Devices, Log, Settings | Devices, Fastboot, Log, Settings (more are planned) |

### MediaTek

On many MediaTek chipsets the app can reach BootROM or Preloader without the usual download-agent authentication. That path exists so a device you own can be read when it will not boot. It is also capable of writing and erasing storage. Treat it as destructive.

Supported operations include:

- Crash Preloader into BootROM, run a bypass payload (kamakiri, kamakiri2, carbonara), brute-force `var1`, and run stage-2 payloads.
- Read eFuses, generate hardware keys, dump BootROM through the Download Agent, and start the key server.
- Unlock or lock the bootloader (`seccfg`), patch vbmeta, peek and poke memory, and read, write, or erase RPMB.
- Read or write IMEI through the Download Agent.
- Save the GPT, read or write named partitions, read or write every partition from a folder, and read or write the raw flash, a sector range, or a byte range.
- Read the userdata crypto footer.
- Erase named partitions, a sector count inside a partition, or a raw sector range.
- Reset the device, enter META mode, print the partition table, show target configuration, and list known devices.
- Dump Preloader, BootROM, and SRAM where the chipset allows it, read target logs, and run a script or a sequence of commands.

A full NAND or eMMC dump is the safer backup. Individual partitions are useful afterwards, but they are not a substitute for a complete image when the data matters.

### Qualcomm

The Qualcomm path vendors qdl 2.8, patched for Android, and exposes:

`flash`, `xml`, `read`, `readxml`, `write`, `sha256`, `erase`, `reset`, `chipinfo`, `ramdump`, `digests`, `createzip`, and `version`.

The Session tab sets the programmer, firmware folder, storage type, target serial or slot, and flash options used by those commands. This engine has been exercised on a desktop (dry-run flash, cancel, error handling, JNI). It has not been built with the NDK against a phone, and it has not been run on Qualcomm hardware. Start with read-only commands on a device you can afford to lose.

### Samsung (Odin)

Download mode can flash official or compatible images using the Odin protocol. Progress, attached devices, and a log are separate tabs. A Dynamic Island status pill follows flashes in every mode, including Samsung.

### ADB and fastboot (in progress)

A native ADB and fastboot client is being built in steps, with no bundled `adb` or `fastboot` binaries and no network access. The other device is found by its USB interface (class 0xFF, subclass 0x42), not by vendor, so any manufacturer works.

What exists today: the fastboot protocol (commands, `getvar`, download, flash with Android sparse-image splitting, reboot), the Android USB transport, and a Devices / Fastboot / Log UI that lists devices, asks for USB access, connects to a device in fastboot mode, shows its variables and offers the reboot menu. Flashing and erasing are not in the UI yet. The ADB client, a Console and Commands tab, a file manager, an app manager, logcat and screen mirroring are planned; see [docs/ADB_FASTBOOT_ROADMAP.md](docs/ADB_FASTBOOT_ROADMAP.md).

The protocol code is unit-tested against fake devices on a plain JVM. It has not yet been run against a real phone.

### Shared behaviour

- Live console with history, one-tap actions, and job progress.
- Searchable, grouped command list with pinnable favourites.
- Partition images written to a folder you choose, with a free-space check before large dumps.
- Review-and-confirm step before destructive commands.
- First launch shows a warning (Exit / I understand) before any USB access. An interactive tutorial then covers the modes, tabs, connecting, the island, Settings, and a pre-flash checklist. Reopen it from Settings > Tutorial.
- Crash reporting, a keep-alive service so long jobs are not killed, and appearance settings including the Dynamic Island.

## Dumps and checking a backup

Images stream straight into the dump folder. While an image is written the app hashes it, and saves two small files beside it: `<name>.sha256` (works with `sha256sum -c`) and `<name>.manifest.json` (chip, command, date, source partition and a SHA-256 for every 64 MiB region). These sidecars are hidden from the Dumps list. Images that read back as almost entirely zeros or 0xFF get a warning, since that usually means the read returned nothing.

Each image in the Dumps tab has these actions:

| Action | What it does |
|---|---|
| **Verify file** | Re-reads the stored file and compares its SHA-256 with the saved one. Finds a damaged or truncated file. Does not touch the device. |
| **Verify on device** | Reads the region of flash the image came from and compares it with the saved region hashes. The data is hashed as it arrives and thrown away, so nothing is written and almost no free space is needed. A mismatch names the regions that differ. MTK mode, with the device connected and identified. If the phone has booted since the dump, changed data is expected. |
| **Restore** | Writes the image back through the normal review-and-confirm path. |
| `#` icon | Shows the full SHA-256 of the stored file so it can be copied. |

## How the code was written

Most of this app's code was generated with AI. I described what I needed, the AI wrote the Kotlin, Compose UI, USB bridge and the Android port of the MTK client, and I tested, edited and corrected the results by hand, mostly against the one real device it was built to rescue. The native Qualcomm engine is a patched vendor of upstream qdl, and the Samsung path uses Brokkr Flash. I am not hiding this and I do not consider it a flaw: it is how a single person got this built at all.

It does mean generated code has not been reviewed line by line. Do not assume every path has been tested on hardware.

Current engineering status:

| Part | State |
|---|---|
| MTK engine | Carried forward from the original recovery app. The package rename has not been recompiled end to end. |
| Mode switch, UI, QDL catalog, Session tab, Samsung Odin UI | Implemented. |
| QDL native engine | Vendored and desktop-tested. Not yet proven on a device. See [docs/QDL_ENGINE.md](docs/QDL_ENGINE.md). |
| ADB / fastboot | Fastboot protocol and Android USB transport written from the AOSP specification and unit-tested against fake devices. Not yet run on a real device. ADB client not started. See [docs/ADB_FASTBOOT_ROADMAP.md](docs/ADB_FASTBOOT_ROADMAP.md). |

Which hardware has actually been tested, and what is still open before a stable release, is tracked in [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md). Until that list is clear, treat published builds as pre-releases.

## Permissions and privacy

Bareflash has no accounts, analytics, advertising, or telemetry code, and the app does not upload anything. Crash and diagnostic logs are written on the device. The permissions it asks for, and why:

| Permission | Why |
|---|---|
| USB host (hardware feature) | Talk to the device you are flashing over OTG. Required. |
| `INTERNET` | Samsung wireless flashing (the app listens on TCP port 13579 for a watch on your network) and mtkclient's optional key server. Both are started by you. |
| `POST_NOTIFICATIONS`, `POST_PROMOTED_NOTIFICATIONS` | Progress notification and Android 16 Live Update for running jobs. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK` | Keep long reads and writes alive with the screen off. |
| `VIBRATE` | Haptic feedback. |
| `MANAGE_EXTERNAL_STORAGE` | Qualcomm mode: qdl reads firmware straight from disk and needs real file paths. Google Play restricts this permission; Bareflash is distributed as a sideloaded APK. |

Android backup is switched off (`allowBackup="false"`), so logs, settings, and dump folders are not copied into device or cloud backups.

## Versioning

Versions are `MAJOR.MINOR.PATCH` (for example `1.0.0`), typed into the Build & Release workflow as the version name. The version code is the workflow run number, so it only ever goes up and a newer build always installs over an older one signed with the same key. For test builds use a suffix such as `1.0.0-beta.1` with "Mark as pre-release" ticked, and move to a plain version once reports come back clean.

## Build and release

```bash
./gradlew assembleDebug     # or assembleRelease
./gradlew testDebugUnitTest # JVM unit tests (also run by the Tests workflow)
```

You need JDK 17, Python 3.11 (Chaquopy), the Android SDK, the NDK, and CMake 3.22.1. The first build downloads Python packages through Chaquopy.

To publish an APK:

1. Push the branch you intend to build.
2. Open Actions, Build & Release, and run the workflow with a version such as `1.0.0`.
3. The APK and its SHA-256 are attached to the `v1.0.0` release.

The debug keystore in the tree is only for local builds and pre-release test builds. A stable release needs your own signing key: set the repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`. The workflow refuses to publish anything that is not marked pre-release without them, because an APK signed with the public debug key cannot be updated later without users uninstalling it first (and losing the app's data). Back up the keystore and its passwords outside the repository; if they are lost, existing installs can never receive an update. Step-by-step instructions are in [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md).

## Credits

Bareflash would not exist without the open-source projects it is built on. The heavy lifting is theirs, and I am very grateful to everyone who wrote and maintains them:

- **[mtkclient](https://github.com/bkerler/mtkclient)** by B. Kerler: the MediaTek engine and the reason the MTK mode works at all.
- **[bypass_utility](https://github.com/MTK-bypass/bypass_utility)** by Dinolek: the MediaTek BROM bypass.
- **[Brokkr Flash](https://github.com/Gabriel2392/brokkr-flash)** by Gabriel2392: the Samsung (Odin protocol) engine.
- **[qdl](https://github.com/linux-msm/qdl)** by Linaro Ltd. and contributors: the Qualcomm engine.
- **[libusb](https://libusb.info)**, **[Chaquopy](https://chaquo.com/chaquopy/)**, libxml2, libzip, LZ4, PyCryptodome, colorama, AndroidX and Jetpack Compose, and the Oxanium and IBM Plex typefaces.

Licences and copyright notices are in [NOTICE.md](NOTICE.md) and under Settings, About.

If you would rather use these tools on a computer, or want the original, actively maintained versions, go to those projects: mtkclient and qdl run on a PC, and Brokkr Flash is a standalone Samsung flashing tool. They are the real thing; this app is a packaged way to use their work from a phone.

If the author of any of these projects has a problem with how Bareflash uses their work, is not comfortable with it, or wants something changed, contact me through my GitHub profile ([eternalentr0py](https://github.com/eternalentr0py)) and I will fix it.

## Who this is for, and what to expect

This is a compiled app for people who want the result without doing the work themselves: install the APK, connect a device, run the commands. That is all it is meant to be.

I am not asking anyone to use it. If you don't like how it works, how it was written or how it looks, build your own. Criticism without a replacement is noise I will not be answering.

**No further updates are planned.** There will be no bug-fix releases, no feature requests handled and **no pull requests accepted**. If you want to change it, fix it, update it or turn it into something new, fork the repository and carry on from your fork. The GPLv3 licence lets you do exactly that.

## Responsibility, risk, and legal limits

Bareflash can read private storage, write arbitrary images, erase partitions, alter bootloader and verified-boot state, and, on supported MediaTek chipsets, do so without the vendor's download-agent authentication. Used incorrectly it will wipe data or leave a device unable to boot. Flashing may void a warranty.

You are responsible for every command you run and for every device you connect. Only use it on hardware you own or are explicitly authorised to service. Do not use it to wipe, unlock, or resell a device that is not yours. Back up first, keep more than one copy of any dump, and only write images built for that exact model and binary version.

**The author assumes no responsibility for misuse, data loss, bricked hardware, warranty claims, or any other consequence of using, modifying, or redistributing this software.** That includes attempts to wipe stolen devices for resale. The software is provided without warranty of any kind, to the extent permitted by the GPL and applicable law. If you fork it, you assume the same risk for your own changes.

No further updates are planned and pull requests will not be accepted. If you hit a bug, fork the repository and fix it there.
