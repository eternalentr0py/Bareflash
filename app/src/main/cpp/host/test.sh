#!/usr/bin/env bash
# Smoke tests for the patched qdl + fork runner. Needs no device and no root.
# usage: test.sh <path-to-qdl_host>
set -u
BIN="$(realpath "${1:?usage: test.sh <qdl_host>}")"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
pass=0; failn=0
check() { # name, expected-substring, actual-output
  if grep -qF -- "$2" <<<"$3"; then echo "ok   $1"; pass=$((pass+1));
  else echo "FAIL $1 (wanted: $2)"; echo "$3" | head -8; failn=$((failn+1)); fi
}

head -c 8192 /dev/urandom > "$W/boot.img"; head -c 512 /dev/urandom > "$W/prog.elf"
cat > "$W/rawprogram0.xml" <<X
<?xml version="1.0" ?><data>
<program SECTOR_SIZE_IN_BYTES="4096" file_sector_offset="0" filename="boot.img" label="boot_a" num_partition_sectors="2" physical_partition_number="0" start_sector="100"/>
</data>
X
cat > "$W/patch0.xml" <<X
<?xml version="1.0" ?><patches>
<patch SECTOR_SIZE_IN_BYTES="4096" byte_offset="16" filename="DISK" function="CRC32" physical_partition_number="0" size_in_bytes="4" start_sector="1" value="0" what="x"/>
</patches>
X
cd "$W"
out=$("$BIN" --version);                       check "version"            "[line] qdl version v2.8" "$out"
check "version exit 0" "[exit] 0" "$out"
out=$("$BIN" -s bogus prog.elf rawprogram0.xml);                    check "errx stays in child" "unknown storage type" "$out"
check "errx exit code"  "[exit] 1" "$out"
out=$("$BIN" --fd 0 -s ufs prog.elf rawprogram0.xml);               check "bad usb fd is reported, not fatal" "failed to wrap the Android USB descriptor" "$out"

out=$("$BIN" -n -s ufs prog.elf rawprogram0.xml patch0.xml)
check "dry-run flash succeeds"     "flashed \"boot_a\" successfully" "$out"
check "progress is emitted machine-readable" "@@progress 2 2 4096 boot_a" "$out"
check "dry-run exit 0" "[exit] 0" "$out"

start=$(date +%s)
out=$("$BIN" --cancel-after 1 -s ufs prog.elf rawprogram0.xml)
elapsed=$(( $(date +%s) - start ))
check "cancel returns -3"          "[exit] -3" "$out"
[ "$elapsed" -le 4 ] && { echo "ok   cancel is prompt (${elapsed}s)"; pass=$((pass+1)); } || { echo "FAIL cancel took ${elapsed}s"; failn=$((failn+1)); }

echo "$pass passed, $failn failed"; [ "$failn" -eq 0 ]
