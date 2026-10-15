#!/usr/bin/env bash
# Runs every host test for the shared USB layer and the Brokkr (Samsung) engine.
# No phone and no Android NDK needed. See docs/BROKKR_AND_USB.md.
#
#   bfusb_test/run.sh                 bfusb vs a mock libusb (ASan + UBSan)
#   bfusb_test/run_mtk.sh             the REAL usb_native.c (MTK) + bfusb, driven from a JVM
#   bfusb_test/run_brokkr_transport.sh  Brokkr's REAL LibusbUsbTransport + bfusb
#   brokkr_smoke/run.sh               libbrokkr.so's real JNI glue with fake transports
#
# Needs: clang-18 + libc++-18-dev + libc++abi-18-dev, a JDK (javac, jni.h), python3.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
for t in bfusb_test/run.sh bfusb_test/run_mtk.sh bfusb_test/run_brokkr_transport.sh brokkr_smoke/run.sh; do
  echo; echo "=== $t ==="
  "$HERE/$t"
done
echo; echo "All USB / Brokkr host tests passed."
echo "(QDL's own suite: host/run_tests.sh, needs libusb-1.0-dev + network on first run.)"
