#!/usr/bin/env bash
# Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later.
#
# Builds Ito's call core (native/call) for Android: stock OpenSSL 3.5.9 from
# its pinned, hash-checked archive as static libraries, then libitocall.so
# with the NDK's CMake toolchain. Checks that the library exports only the C
# interface and imports no OpenSSL symbol, and records the exact inputs and
# outputs in provenance.json.
#
#   tools/build_call_core.sh --ndk NDK --openssl-archive FILE \
#       --abi x86_64 [--abi arm64-v8a] --out DIR [--jobs N]
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
checkout=$(cd "$here/.." && pwd)
core="$checkout/native/call"
spec="$here/security-component.json"
ndk= archive= out= jobs=4
abis=()

die() { echo "build_call_core: $*" >&2; exit 1; }

while [ "$#" -gt 0 ]; do
    case "$1" in
        --ndk) ndk=$2; shift 2 ;;
        --openssl-archive) archive=$2; shift 2 ;;
        --abi) abis+=("$2"); shift 2 ;;
        --out) out=$2; shift 2 ;;
        --jobs) jobs=$2; shift 2 ;;
        *) die "unknown argument $1" ;;
    esac
done
[ -n "$ndk" ] && [ -d "$ndk" ] || die "--ndk must name the NDK"
[ -n "$archive" ] && [ -f "$archive" ] || die "--openssl-archive must name the pinned archive"
[ -n "$out" ] || die "--out is required"
[ "${#abis[@]}" -gt 0 ] || die "at least one --abi"
[[ "$jobs" =~ ^[1-9][0-9]*$ ]] || die "--jobs must be a positive integer"

field() { python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$spec" "$1"; }
expected=$(field archive_sha256)
version=$(field version)
actual=$(sha256sum "$archive" | cut -d' ' -f1)
[ "$actual" = "$expected" ] || die "the OpenSSL archive does not match $spec"
ndk_revision=$(sed -n 's/^Pkg.Revision *= *//p' "$ndk/source.properties")
[ "$ndk_revision" = 28.2.13676358 ] || die "NDK 28.2.13676358 is required, found $ndk_revision"

mkdir -p "$out"
work="$out/work"
mkdir -p "$work"
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"

for abi in "${abis[@]}"; do
    case "$abi" in
        x86_64) target=android-x86_64 ;;
        arm64-v8a) target=android-arm64 ;;
        *) die "unsupported ABI $abi" ;;
    esac
    # OpenSSL depends only on the archive, the ABI and the NDK.
    prefix="$work/openssl-$version-$abi-$actual"
    if [ ! -f "$prefix/lib/libcrypto.a" ]; then
        source_dir="$work/openssl-src-$abi"
        rm -rf "$source_dir" "$prefix"
        mkdir -p "$source_dir"
        tar -xzf "$archive" -C "$source_dir"
        (
            cd "$source_dir/openssl-$version"
            export ANDROID_NDK_ROOT="$ndk" PATH="$toolchain:$PATH"
            perl Configure "$target" no-shared no-module no-tests -D__ANDROID_API__=28 \
                --libdir=lib "--prefix=$prefix" >"$work/openssl-$abi-configure.log"
            make -j"$jobs" build_libs >"$work/openssl-$abi-build.log"
            make install_dev >"$work/openssl-$abi-install.log"
        )
        cp "$source_dir/openssl-$version/LICENSE.txt" "$prefix/LICENSE.txt"
    fi

    build="$work/core-$abi"
    rm -rf "$build"
    cmake -S "$core" -B "$build" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared \
        -DCMAKE_BUILD_TYPE=RelWithDebInfo -DITO_CALL_TESTS=OFF \
        -DITO_CALL_OPENSSL_ROOT="$prefix" -DITO_CALL_OPENSSL_VERSION="$version" \
        >"$work/core-$abi-configure.log"
    cmake --build "$build" --target itocall --parallel "$jobs" >"$work/core-$abi-build.log"

    library="$build/libitocall.so"
    defined=$("$toolchain/llvm-nm" -D --defined-only "$library" | awk '{print $NF}' | sed 's/@.*//')
    unexpected=$(printf '%s\n' "$defined" | grep -v -E '^(ito_[a-z_]+|ITO_CALL_1)$' || true)
    [ -z "$unexpected" ] || die "$abi exports more than the C interface: $unexpected"
    imported=$("$toolchain/llvm-nm" -D --undefined-only "$library" | awk '{print $NF}' |
        grep -E '^(SSL|EVP|OPENSSL|OSSL|CRYPTO|ERR|BIO|X509|RAND|ossl)_' || true)
    [ -z "$imported" ] || die "$abi imports OpenSSL symbols: $imported"

    mkdir -p "$out/$abi"
    cp "$library" "$out/$abi/libitocall.so"
done
mkdir -p "$out/include/ito"
cp "$core/include/ito/call.h" "$out/include/ito/call.h"
cp "$core/include/ito/content.h" "$out/include/ito/content.h"

python3 -I - "$out" "$core" "$archive" "$actual" "$ndk_revision" "${abis[@]}" <<'PY'
import hashlib, json, os, sys
from pathlib import Path
out, core, archive, archive_sha, ndk = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3], sys.argv[4], sys.argv[5]
abis = sys.argv[6:]
def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()
sources = {str(path.relative_to(core)): digest(path) for path in sorted(core.rglob('*'))
           if path.is_file() and 'tests' not in path.relative_to(core).parts}
record = {'schema': 1, 'component': 'OpenSSL', 'openssl_archive_sha256': archive_sha,
          'ndk': ndk, 'core_sources': sources,
          'core_sources_sha256': hashlib.sha256(json.dumps(sources, sort_keys=True).encode()).hexdigest(),
          'header_sha256': digest(out / 'include/ito/call.h'),
          'libraries': {abi: digest(out / abi / 'libitocall.so') for abi in abis},
          'openssl_license_sha256': {abi: digest(next((out / 'work').glob(f'openssl-*-{abi}-{archive_sha}')) / 'LICENSE.txt')
                                     for abi in abis}}
(out / 'provenance.json').write_text(json.dumps(record, indent=2, sort_keys=True) + '\n')
PY
echo "build_call_core: ${abis[*]} built into $out"
