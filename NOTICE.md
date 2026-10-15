# Third-party components

| Component | Use | Licence |
|---|---|---|
| [mtkclient](https://github.com/bkerler/mtkclient) © B. Kerler | MTK engine (Python, via Chaquopy) | GPL-3.0 |
| [bypass_utility](https://github.com/MTK-bypass/bypass_utility) © Dinolek | MTK engine | MIT |
| [Brokkr Flash](https://github.com/Gabriel2392/brokkr-flash) © 2026 Gabriel2392 | Brokkr (Samsung / Odin) engine, vendored v2.4.10 in `app/src/main/cpp/brokkr/` (Android platform layer only; JNI symbols renamed to `com.bareflash.app.samsung`) | GPL-3.0-or-later |
| LZ4, MD5 (bundled with Brokkr) | Brokkr engine; see `app/src/main/cpp/brokkr/THIRD_PARTY_NOTICES.txt` | BSD-2-Clause / public domain |
| [qdl](https://github.com/linux-msm/qdl) © Linaro Ltd. and contributors | QDL engine, vendored v2.8 in `app/src/main/cpp/qdl/` with Android patches | BSD-3-Clause |
| [libxml2](https://gitlab.gnome.org/GNOME/libxml2) | Linked statically into the QDL engine (v2.14.6, fetched at build time) | MIT |
| [libzip](https://libzip.org) | Linked statically into the QDL engine (v1.11.4, fetched at build time) | BSD-3-Clause |
| [libusb](https://libusb.info) | USB access (prebuilt, `jniLibs/`; v1.0.27 `libusb.h` vendored for compiling) | LGPL-2.1+ |
| [Chaquopy](https://chaquo.com/chaquopy/) | Embedded Python runtime | MIT |
| [PyCryptodome / PyCryptodomex](https://www.pycryptodome.org) | Cryptography for the MTK engine (installed by Chaquopy at build time) | BSD-2-Clause (some files public domain) |
| [colorama](https://github.com/tartley/colorama) | Console colour handling for the MTK engine (installed by Chaquopy at build time) | BSD-3-Clause |
| [AndroidX, Jetpack Compose, Material Components](https://developer.android.com/jetpack) | App framework and UI | Apache-2.0 |
| Oxanium, IBM Plex Sans, IBM Plex Mono | Typefaces | SIL OFL 1.1 |
