#!/usr/bin/env bash
# Points the real `adb` host binary at the fake adbd used in the unit tests.
#
# What it proves: the fake device speaks the protocol well enough for Google's
# own client (auth with a fresh key and with a remembered key, shell v2 with
# exit codes and stderr, exec, sync v2 stat/pull/push, banner parsing). That in
# turn means the unit tests exercise this library against a faithful model.
# What it does not prove: anything about a real adbd or a real USB link.
#
# Usage: interop-real-adb.sh <main-classes-dir> <test-classes-dir> <kotlin-stdlib.jar>
# Needs: adb (Debian/Ubuntu package `adb`), java, python3.
set -euo pipefail
MAIN=${1:?main classes}; TEST=${2:?test classes}; STDLIB=${3:?kotlin-stdlib.jar}
PORT=${PORT:-6123}; S=127.0.0.1:$PORT
W=$(mktemp -d); export HOME=$W/home; mkdir -p "$HOME"
fail() { echo "FAIL: $*"; exit 1; }
cleanup() { adb kill-server >/dev/null 2>&1 || true; [ -n "${PID:-}" ] && kill "$PID" 2>/dev/null || true; rm -rf "$W"; }
trap cleanup EXIT

FAKE_ADBD_TRACE=1 setsid java -cp "$MAIN:$TEST:$STDLIB" com.bareflash.adb.tools.FakeAdbdServerKt "$PORT" auth > "$W/server.log" 2>&1 < /dev/null &
PID=$!
for _ in $(seq 1 50); do grep -q listening "$W/server.log" && break; sleep 0.1; done

adb start-server >/dev/null 2>&1
adb connect "$S" >/dev/null 2>&1 || true          # returns before the prompt would be answered
for _ in $(seq 1 30); do adb devices | grep -q "$S.*device$" && break; sleep 0.2; done
adb devices -l | grep -q "model:Fake_Phone device:fakedev" || fail "device not listed with its banner"
echo "PASS first connection: key offered, accepted, banner parsed"

[ "$(adb -s $S shell echo hi there)" = "hi there" ] || fail "shell echo"
adb -s $S shell exit 3 && fail "exit code lost" || [ $? -eq 3 ] || fail "exit code != 3"
[ "$(adb -s $S shell err oops 2>&1 >/dev/null)" = "oops" ] || fail "stderr not separate"
echo "PASS shell v2: output, exit code, separate stderr"

adb -s $S pull /sdcard/hello.txt "$W/h.txt" >/dev/null && [ "$(cat "$W/h.txt")" = "hello from the fake device" ] || fail "pull small"
adb -s $S pull /sdcard/big.bin "$W/big.out" >/dev/null
python3 - "$W/big.out" <<'PY' || fail "pull 300 KB"
import sys
sys.exit(0 if open(sys.argv[1],'rb').read() == bytes((i*31+7) & 255 for i in range(300000)) else 1)
PY
head -c 200000 /dev/urandom > "$W/up.bin"
adb -s $S push "$W/up.bin" /sdcard/up.bin >/dev/null || fail "push"
adb -s $S pull /sdcard/up.bin "$W/up.back" >/dev/null && cmp "$W/up.bin" "$W/up.back" || fail "push/pull round trip"
echo "PASS sync: pull, 300 KB pull, push, round trip"

adb kill-server >/dev/null 2>&1; adb start-server >/dev/null 2>&1
adb connect "$S" 2>&1 | grep -q "connected to" || fail "reconnect with remembered key"
[ "$(grep -c '^<- AUTH(arg0=3' "$W/server.log")" = 1 ] || fail "key was offered again instead of being recognised"
[ "$(grep -c '^<- AUTH(arg0=2' "$W/server.log")" -ge 2 ] || fail "second connection did not sign the token"
echo "PASS reconnect: remembered key accepted by signature alone"
