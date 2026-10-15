#!/usr/bin/env bash
# Builds the QDL engine for the desktop and runs every engine test:
#   1. shell smoke tests (version, error exit, bad fd, dry-run flash, progress, cancel)
#   2. the real JNI glue loaded by a plain JVM with -Xcheck:jni
# Needs: cmake, a C compiler, libusb-1.0 + zlib dev packages, pkg-config, JDK.
# Needs network on first run (libxml2 / libzip are fetched from GitHub).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${1:-$HERE/../../../../../build/qdl-host}"
mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"

cmake -S "$HERE" -B "$OUT" -DCMAKE_BUILD_TYPE=Release
cmake --build "$OUT" -j"$(nproc)"

"$HERE/test.sh" "$OUT/qdl_host"

if [ -f "$OUT/libqdl_jni.so" ]; then
  mkdir -p "$OUT/jt" "$OUT/fw"
  javac -d "$OUT/jt" "$HERE/jni_test/com/bareflash/app/engine/QdlNative.java" "$HERE/jni_test/JniTest.java"
  head -c 512 /dev/urandom > "$OUT/fw/prog.elf"; head -c 8192 /dev/urandom > "$OUT/fw/boot.img"
  printf '<?xml version="1.0" ?><data><program SECTOR_SIZE_IN_BYTES="4096" file_sector_offset="0" filename="boot.img" label="boot_a" num_partition_sectors="2" physical_partition_number="0" start_sector="100"/></data>' > "$OUT/fw/rawprogram0.xml"
  (cd "$OUT" && java -Xcheck:jni -Djava.library.path=. -cp jt JniTest)
else
  echo "JNI headers not found; skipping the JVM test"
fi
