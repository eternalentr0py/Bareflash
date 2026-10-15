#!/usr/bin/env bash
# Runs the REAL usb_native.c (MTK JNI layer) + bfusb against the mock device
# from a desktop JVM and checks the Stop/timeout behaviour end to end.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; CPP="$HERE/../.."; OUT="$(mktemp -d)"
JH="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
CC="${CC:-clang-18}"
mkdir -p "$OUT/stub/android"
printf '#pragma once\n#define ANDROID_LOG_WARN 5\n#define ANDROID_LOG_INFO 4\nint __android_log_print(int,const char*,const char*,...);\n' > "$OUT/stub/android/log.h"
$CC -std=gnu11 -O1 -g -fPIC -shared -D_GNU_SOURCE -Wno-unused-parameter \
  -I"$JH/include" -I"$JH/include/linux" -I"$OUT/stub" -I"$CPP" -I"$CPP/third_party/libusb" -I"$HERE" \
  -o "$OUT/libmtkusb_test.so" \
  "$CPP/usb_native.c" "$CPP/bfusb/bfusb.c" "$HERE/mock_libusb.c" "$HERE/mock_libusb_ext.c" -lpthread
mkdir -p "$OUT/cls"
javac -d "$OUT/cls" "$HERE/mtk/"*.java
java -Djava.library.path="$OUT" -cp "$OUT/cls" MtkCancelTest
