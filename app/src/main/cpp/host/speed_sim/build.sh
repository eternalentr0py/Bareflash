#!/usr/bin/env bash
# Builds libspeedsim.so: the real usb_native.c + bfusb.c from <cpp dir> linked
# against the simulated bus / DA (sim_usb.c) and the fake JNIEnv (sim_jni.c).
#   build.sh [<cpp dir>] [<output .so>]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CPP="$(cd "${1:-$HERE/../..}" && pwd)"
OUT="${2:-$HERE/build/libspeedsim.so}"
JH="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
CC="${CC:-clang-18}"
command -v "$CC" >/dev/null 2>&1 || CC=gcc
mkdir -p "$(dirname "$OUT")"
STUB="$(mktemp -d)"; trap 'rm -rf "$STUB"' EXIT
mkdir -p "$STUB/android"
printf '#pragma once\n#define ANDROID_LOG_WARN 5\n#define ANDROID_LOG_INFO 4\nint __android_log_print(int,const char*,const char*,...);\n' > "$STUB/android/log.h"
# -O2 matches the release NDK build. -U_GNU_SOURCE avoids the harmless macro redefinition note.
"$CC" -std=gnu11 -O2 -g -fPIC -shared -Wall -Wno-unused-parameter -Wno-macro-redefined \
  -I"$JH/include" -I"$JH/include/linux" -I"$STUB" -I"$CPP" -I"$CPP/third_party/libusb" -I"$HERE" \
  -o "$OUT" \
  "$CPP/usb_native.c" "$CPP/bfusb/bfusb.c" "$HERE/sim_usb.c" "$HERE/sim_jni.c" -lpthread -lm
echo "$OUT"
