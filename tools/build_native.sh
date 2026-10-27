#!/usr/bin/env bash
# Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later.
set -euo pipefail

# The public YUME build script owns its dependency, signature and Android
# toolchain checks. Ito records the exact selected source and output binding.
ITO_YUME_ROOT=""
ITO_NDK_ROOT=""
ITO_VCPKG_ROOT=""
ITO_NATIVE_OUT=""
ITO_NATIVE_ABIS=()
ITO_NATIVE_JOBS=2
ITO_UNVERIFIED=0
while (($#)); do
    case "$1" in
        --yume) ITO_YUME_ROOT="${2:?}"; shift 2 ;;
        --ndk) ITO_NDK_ROOT="${2:?}"; shift 2 ;;
        --vcpkg) ITO_VCPKG_ROOT="${2:?}"; shift 2 ;;
        --out) ITO_NATIVE_OUT="${2:?}"; shift 2 ;;
        --abi) ITO_NATIVE_ABIS+=("${2:?}"); shift 2 ;;
        --jobs) ITO_NATIVE_JOBS="${2:?}"; shift 2 ;;
        --unverified-source) ITO_UNVERIFIED=1; shift ;;
        *) printf 'Unknown or missing native build argument: %s\n' "$1" >&2; exit 2 ;;
    esac
done
test -n "$ITO_YUME_ROOT" && test -n "$ITO_NDK_ROOT" && test -n "$ITO_VCPKG_ROOT"
test -n "$ITO_NATIVE_OUT" && test "${#ITO_NATIVE_ABIS[@]}" -gt 0
test -f "$ITO_YUME_ROOT/include/yume/yume.h"
test -f "$ITO_YUME_ROOT/tools/build_android_shared_abi.sh"
test -f "$ITO_NDK_ROOT/source.properties"
test "$(id -u)" != 0
for ITO_NATIVE_ABI in "${ITO_NATIVE_ABIS[@]}"; do
    case "$ITO_NATIVE_ABI" in
        arm64-v8a|armeabi-v7a|x86|x86_64) ;;
        *) printf 'Unsupported Android ABI\n' >&2; exit 2 ;;
    esac
done
[[ "$ITO_NATIVE_JOBS" =~ ^[1-9][0-9]*$ ]]
mkdir -p "$ITO_NATIVE_OUT"
ITO_NATIVE_OUT="$(realpath "$ITO_NATIVE_OUT")"
ITO_YUME_ROOT="$(realpath "$ITO_YUME_ROOT")"
test "$ITO_NATIVE_OUT" != "$ITO_YUME_ROOT"
case "$ITO_YUME_ROOT/" in
    "$ITO_NATIVE_OUT/"*) printf 'Native output must not contain YUME source\n' >&2; exit 2 ;;
esac
exec 9>"$ITO_NATIVE_OUT/.ito-native-build.lock"
flock -w 30 9

python3 - "$ITO_YUME_ROOT" "$ITO_NATIVE_OUT/inputs-before.json" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys

root, destination = map(Path, sys.argv[1:])
result = subprocess.run(
    ["git", "-C", str(root), "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
    capture_output=True, check=True, timeout=30,
)
inputs = {}
for raw in sorted(set(result.stdout.split(b"\0")) - {b""}):
    relative = os.fsdecode(raw)
    path = root / relative
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) and not stat.S_ISLNK(info.st_mode):
        raise SystemExit("Native input is neither file nor link")
    data = os.fsencode(os.readlink(path)) if stat.S_ISLNK(info.st_mode) else path.read_bytes()
    inputs[relative] = {
        "sha256": hashlib.sha256(data).hexdigest(),
        "mode": stat.S_IMODE(info.st_mode),
        "kind": "link" if stat.S_ISLNK(info.st_mode) else "file",
    }
head = subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"], capture_output=True, check=True, timeout=30)
canonical = json.dumps(inputs, sort_keys=True, separators=(",", ":")).encode()
destination.write_text(json.dumps({"head": head.stdout.decode().strip(), "inputs": inputs,
    "inputs_sha256": hashlib.sha256(canonical).hexdigest()}, indent=2) + "\n")
PY

ITO_NATIVE_FLAGS=()
if ((ITO_UNVERIFIED)); then
    ITO_NATIVE_FLAGS+=(--unverified-source)
fi
for ITO_NATIVE_ABI in "${ITO_NATIVE_ABIS[@]}"; do
    ITO_NATIVE_FLAGS+=(--abi "$ITO_NATIVE_ABI")
done
bash "$ITO_YUME_ROOT/tools/build_android_shared_abi.sh" \
    --ndk "$ITO_NDK_ROOT" --vcpkg "$ITO_VCPKG_ROOT" --api 28 \
    --out "$ITO_NATIVE_OUT" --jobs "$ITO_NATIVE_JOBS" \
    "${ITO_NATIVE_FLAGS[@]}"

python3 - "$ITO_YUME_ROOT" "$ITO_NATIVE_OUT" "$ITO_NDK_ROOT" "$ITO_UNVERIFIED" "${ITO_NATIVE_ABIS[@]}" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys

root, output = map(Path, sys.argv[1:3])
ndk, unverified, *abis = sys.argv[3:]
before = json.loads((output / "inputs-before.json").read_text())
head = subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"],
    check=True, capture_output=True, timeout=30).stdout.decode().strip()
if head != before["head"]:
    raise SystemExit("Native source HEAD changed during build")
paths = subprocess.run(["git", "-C", str(root), "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
    check=True, capture_output=True, timeout=30).stdout
if set(os.fsdecode(x) for x in paths.split(b"\0") if x) != set(before["inputs"]):
    raise SystemExit("Native source paths changed during build")
for relative, expected in before["inputs"].items():
    path = root / relative
    info = path.lstat()
    data = os.fsencode(os.readlink(path)) if stat.S_ISLNK(info.st_mode) else path.read_bytes()
    if expected != {"sha256": hashlib.sha256(data).hexdigest(), "mode": stat.S_IMODE(info.st_mode),
                    "kind": "link" if stat.S_ISLNK(info.st_mode) else "file"}:
        raise SystemExit("Native source changed during build")
artifacts = {}
for relative in [*(f"{abi}/libyume.so" for abi in abis), "include/yume/yume.h"]:
    path = output / relative
    if not path.is_file() or path.is_symlink():
        raise SystemExit("Missing native output")
    data = path.read_bytes()
    artifacts[relative] = {"sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}
if artifacts["include/yume/yume.h"]["sha256"] != before["inputs"]["include/yume/yume.h"]["sha256"]:
    raise SystemExit("Copied public header differs from source")
provenance = {"schema": 1, "abis": abis, "android_api": 28, "unverified_test_source": unverified == "1",
    "source": str(root), "head": before["head"], "inputs_sha256": before["inputs_sha256"],
    "inputs": before["inputs"], "ndk": str(Path(ndk).resolve()),
    "ndk_source_properties": (Path(ndk) / "source.properties").read_text(), "artifacts": artifacts}
temporary = output / "provenance.json.new"
temporary.write_text(json.dumps(provenance, indent=2) + "\n")
temporary.replace(output / "provenance.json")
PY
