#!/usr/bin/env bash
# Builds the simulator and runs the MTK read/write speed tests against it.
#   run_speed_sim.sh [extra speed_sim.py args]    e.g.  --call-us 2000 --mib 64
# Needs: clang or gcc, a JDK (for jni.h), python3 with pycryptodomex, pyserial,
# pyusb, colorama, capstone, fusepy (a stub `fuse` module is fine).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PY_SRC="$HERE/../../../python"
"$HERE/build.sh" >/dev/null
exec "${PYTHON:-python3}" "$HERE/speed_sim.py" --lib "$HERE/build/libspeedsim.so" --py "$PY_SRC" "$@"
