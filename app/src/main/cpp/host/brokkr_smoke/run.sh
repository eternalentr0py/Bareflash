#!/usr/bin/env bash
# Host smoke test for libbrokkr.so's real JNI glue (no phone needed).
#
# Builds the Android sources against a stub libusb, loads the library into a
# desktop JVM, and drives the same native entry points the app calls, using
# fake transports. It checks that every JNI class/method/field lookup resolves,
# callbacks arrive with the right types, the ODIN handshake goes out through the
# transport, failures surface as onError/onFinished, and cancel is prompt and
# safe. It does NOT talk to a real Samsung device.
#
# Needs: clang++-18 (libc++), a JDK with jni.h, python3.  e.g.
#   apt-get install clang-18 libc++-18-dev libc++abi-18-dev openjdk-21-jdk-headless
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
B="$HERE/../../brokkr"
OUT="$(mktemp -d)"
JH="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
INC="-I$B/src -I$B/src-jni/shim -I$JH/include -I$JH/include/linux -I$HERE/../../third_party/libusb -I$HERE/../.. -I$OUT/stub \
  -DBROKKR_PLATFORM_ANDROID -D_FILE_OFFSET_BITS=64 -D_LIBCPP_ENABLE_EXPERIMENTAL"

mkdir -p "$OUT/stub/android"
printf '#pragma once\nextern "C" int __android_log_write(int,const char*,const char*);\n#define ANDROID_LOG_VERBOSE 2\n#define ANDROID_LOG_DEBUG 3\n#define ANDROID_LOG_INFO 4\n#define ANDROID_LOG_WARN 5\n#define ANDROID_LOG_ERROR 6\n#define ANDROID_LOG_FATAL 7\n' > "$OUT/stub/android/log.h"
clang-18 -shared -fPIC -o "$OUT/libusbstub.so" "$HERE/usbstub.c"

# Source list is read from brokkr_engine.cmake so the test cannot drift from the real build.
SRCS=$(python3 - "$HERE/../../brokkr_engine.cmake" <<'PY'
import re,sys
print(' '.join(re.findall(r'\$\{BROKKR_DIR\}/(\S+\.(?:cpp|c))', open(sys.argv[1]).read())))
PY
)
OBJS=()
for f in $SRCS; do
  o="$OUT/$(echo "$f" | tr '/' '_').o"; src="$B/$f"
  # Desktop jni.h takes void** in AttachCurrentThread; Android's takes JNIEnv**.
  if grep -q 'AttachCurrentThread(&env, nullptr)' "$src"; then
    sed 's/AttachCurrentThread(&env, nullptr)/AttachCurrentThread(reinterpret_cast<void**>(\&env), nullptr)/' "$src" > "$OUT/patched_$(basename "$f")"
    src="$OUT/patched_$(basename "$f")"; extra="-I$(dirname "$B/$f")"
  else extra=""; fi
  case "$f" in
    *.c)   clang-18   -fPIC -O1 -c $INC "$src" -o "$o" ;;
    *.cpp) clang++-18 -stdlib=libc++ -std=c++23 -fPIC -O1 -c $INC $extra "$src" -o "$o" ;;
  esac
  OBJS+=("$o")
done
clang-18 -fPIC -O1 -c -I"$HERE/../../bfusb" -I"$HERE/../../third_party/libusb" "$HERE/../../bfusb/bfusb.c" -o "$OUT/bfusb.o"
OBJS+=("$OUT/bfusb.o")
clang++-18 -stdlib=libc++ -shared -o "$OUT/libbrokkr.so" "${OBJS[@]}" -L"$OUT" -lusbstub \
  -Wl,--no-undefined -Wl,-rpath,"$OUT"

cd "$OUT"; mkdir -p cache
cp -r "$HERE/com" "$HERE/Smoke.java" .
python3 - <<'PY'
import tarfile, io, os
with tarfile.open('AP_test.tar','w') as t:
    for n,sz in (('boot.img',4096),('recovery.img',8192)):
        ti=tarfile.TarInfo(n); ti.size=sz; t.addfile(ti, io.BytesIO(os.urandom(sz)))
PY
javac -h . com/bareflash/app/samsung/BrokkrNativeBridge.java Smoke.java
java --add-opens java.base/java.io=ALL-UNNAMED -Djava.library.path="$OUT" -cp . Smoke | tee run.log

for want in "stage ODIN handshake" "fake tcp sends=1" "finished ok=false Handshake receive failed" \
            "finished ok=false libusb_init_context failed" "cancel-after-destroy ok"; do
  grep -qF "$want" run.log || { echo "FAIL: missing '$want'"; exit 1; }
done
echo "PASS: Brokkr JNI smoke test"
