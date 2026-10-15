#!/usr/bin/env bash
# Host unit test for bfusb against a mock libusb (no phone, no NDK needed).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CPP="$HERE/../.."
OUT="$(mktemp -d)"
CC="${CC:-clang-18}"
FLAGS="-std=c11 -O1 -g -Wall -Wextra -Wno-unused-parameter -D_GNU_SOURCE -I$CPP/bfusb -I$CPP/third_party/libusb -I$HERE"
$CC $FLAGS -fsanitize=address,undefined -o "$OUT/test_bfusb" \
  "$CPP/bfusb/bfusb.c" "$HERE/mock_libusb.c" "$HERE/test_bfusb.c" -lpthread
"$OUT/test_bfusb"
