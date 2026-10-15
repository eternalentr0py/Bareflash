#!/usr/bin/env bash
# Brokkr's real LibusbUsbTransport (+ bfusb) against the mock device.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; CPP="$HERE/../.."; B="$CPP/brokkr"; OUT="$(mktemp -d)"
JH="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
mkdir -p "$OUT/stub/android"
printf '#pragma once\nextern "C" int __android_log_write(int,const char*,const char*);\n#define ANDROID_LOG_VERBOSE 2\n#define ANDROID_LOG_DEBUG 3\n#define ANDROID_LOG_INFO 4\n#define ANDROID_LOG_WARN 5\n#define ANDROID_LOG_ERROR 6\n#define ANDROID_LOG_FATAL 7\n' > "$OUT/stub/android/log.h"
INC="-I$B/src -I$B/src-jni/shim -I$CPP -I$CPP/third_party/libusb -I$HERE -I$OUT/stub -I$JH/include -I$JH/include/linux"
for f in bfusb/bfusb.c; do clang-18 -std=gnu11 -O1 -g -fPIC -D_GNU_SOURCE -c $INC "$CPP/$f" -o "$OUT/bfusb.o"; done
clang-18 -std=gnu11 -O1 -g -fPIC -D_GNU_SOURCE -Wno-unused-parameter -c $INC "$HERE/mock_libusb.c" -o "$OUT/mock.o"
clang-18 -std=gnu11 -O1 -g -fPIC -D_GNU_SOURCE -Wno-unused-parameter -c $INC "$HERE/mock_libusb_ext.c" -o "$OUT/mockext.o"
CXX="clang++-18 -stdlib=libc++ -std=c++23 -O1 -g -DBROKKR_PLATFORM_ANDROID -D_LIBCPP_ENABLE_EXPERIMENTAL"
$CXX -c $INC "$B/src/platform/android/libusb_transport.cpp" -o "$OUT/transport.o"
$CXX -c $INC "$HERE/test_brokkr_transport.cpp" -o "$OUT/test.o"
$CXX -o "$OUT/test" "$OUT"/*.o -lpthread
"$OUT/test"
