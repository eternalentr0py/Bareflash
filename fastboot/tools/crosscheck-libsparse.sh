#!/usr/bin/env bash
# Cross-checks the sparse-image splitter against Android's own libsparse tools.
#
# What it proves: pieces produced by FlashPlanner are accepted by the real
# simg2img and reassemble to exactly the original image, for both a raw input
# and an img2simg-produced sparse input.
# What it does not prove: anything about a real device's bootloader.
#
# Usage: crosscheck-libsparse.sh <kotlinc> <classes-dir> <kotlin-stdlib.jar>
#   kotlinc       path to the Kotlin compiler (2.3.x)
#   classes-dir   compiled :usbcore + :fastboot main classes
#   stdlib        kotlin-stdlib.jar
# Needs img2simg and simg2img on PATH (Debian/Ubuntu: android-sdk-libsparse-utils).
set -euo pipefail
KOTLINC=${1:?kotlinc}; CLASSES=${2:?classes dir}; STDLIB=${3:?kotlin-stdlib.jar}
HERE=$(cd "$(dirname "$0")" && pwd)
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT

"$KOTLINC" -jvm-target 17 -cp "$CLASSES" -d "$W/xcheck" "$HERE/XCheck.kt"
run() { java -cp "$W/xcheck:$CLASSES:$STDLIB" XCheckKt "$@"; }

python3 - "$W/raw.img" <<'PY'
import random, sys
random.seed(3)
b = bytearray(random.randbytes(3 * 1024 * 1024 + 777))   # unaligned tail
b[500000:900000] = bytes(400000)                          # zero hole
b[2000000:2004096] = b'\xAA' * 4096
open(sys.argv[1], 'wb').write(b)
PY

same() { python3 - "$1" "$2" <<'PY'
import sys
raw = open(sys.argv[1], 'rb').read(); out = open(sys.argv[2], 'rb').read()
sys.exit(0 if out == raw + bytes((-len(raw)) % 4096) else 1)
PY
}

cd "$W"
run raw.img 102400 a;  simg2img a_*.simg a_out.img; same raw.img a_out.img && echo "PASS raw -> split -> simg2img"
img2simg raw.img lib.simg 4096
run lib.simg 200000 b; simg2img b_*.simg b_out.img; same raw.img b_out.img && echo "PASS img2simg -> split -> simg2img"
run lib.simg 100000000 c; cmp lib.simg c_000.simg && echo "PASS small sparse image passes through unchanged"
