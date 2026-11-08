#!/usr/bin/env bash
# Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later.
#
# Builds Ito's release APK and signs it with the release key. Passwords come
# only from the environment or an owner-only secret file, never from arguments,
# and reach apksigner through its environment.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
secret_file=$root/.release-signing.env
abi= output= apk_dir= skip_clean=0 skip_sign=0
gradle_args=()

die() { printf 'build.sh: %s\n' "$*" >&2; exit 1; }

usage() {
    cat <<'USAGE'
Usage: ./build.sh [options] [-- GRADLE_ARGS...]

  --abi LIST          ABIs to package: arm64-v8a, x86_64 or both, comma separated
  --output DIR        where the signed APK goes; default is signed/ beside the unsigned one
  --secret-file PATH  signing values; default ./.release-signing.env
  --apk-dir DIR       sign unsigned APKs built elsewhere and skip Gradle
  --skip-clean        skip Gradle clean
  --skip-sign         build the unsigned APK only

Signing values, from the environment first and then the secret file:
  ITO_RELEASE_STORE_FILE, ITO_RELEASE_KEY_ALIAS,
  ITO_RELEASE_STORE_PASSWORD_B64, ITO_RELEASE_KEY_PASSWORD_B64

Arguments after -- go to Gradle, such as -PitoProviders=true and a
distribution's provider registration.
USAGE
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --abi) abi=${2:?}; shift 2 ;;
        --output) output=${2:?}; shift 2 ;;
        --secret-file) secret_file=${2:?}; shift 2 ;;
        --apk-dir) apk_dir=${2:?}; shift 2 ;;
        --skip-clean) skip_clean=1; shift ;;
        --skip-sign) skip_sign=1; shift ;;
        --help|-h) usage; exit 0 ;;
        --) shift; gradle_args=("$@"); break ;;
        *) usage >&2; die "unknown argument $1" ;;
    esac
done
[ "$(id -u)" -ne 0 ] || die "run this as the checkout owner, not as root"

signing_value() {
    local name=$1
    if [ -n "${!name:-}" ]; then
        printf '%s' "${!name}"
    elif [ -f "$secret_file" ]; then
        sed -n "s/^$name=//p" "$secret_file" | tail -n 1
    fi
}

resolve_tool() {
    local sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}} newest
    newest=$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1)
    [ -x "$newest/$1" ] || die "$1 not found under $sdk/build-tools"
    printf '%s/%s' "$newest" "$1"
}

if [ "$skip_sign" -eq 0 ]; then
    if [ -f "$secret_file" ] && [ -n "$(find "$secret_file" -perm /077)" ]; then
        die "$secret_file must be readable only by its owner"
    fi
    store_file=$(signing_value ITO_RELEASE_STORE_FILE)
    key_alias=$(signing_value ITO_RELEASE_KEY_ALIAS)
    store_password_b64=$(signing_value ITO_RELEASE_STORE_PASSWORD_B64)
    key_password_b64=$(signing_value ITO_RELEASE_KEY_PASSWORD_B64)
    [ -f "$store_file" ] || die "ITO_RELEASE_STORE_FILE does not name a keystore"
    [ -n "$key_alias" ] || die "ITO_RELEASE_KEY_ALIAS is not set"
    [ -n "$store_password_b64" ] && [ -n "$key_password_b64" ] || die "the signing passwords are not set"
    zipalign=$(resolve_tool zipalign)
    apksigner=$(resolve_tool apksigner)
fi

if [ -n "$apk_dir" ]; then
    apk_output=$(cd "$apk_dir" && pwd)
else
    cd "$root"
    apk_output=$root/app/build/outputs/apk/release
    [ -z "$abi" ] || gradle_args+=("-PitoAbi=$abi")
    [ "$skip_clean" -eq 1 ] || ./gradlew :app:clean "${gradle_args[@]+"${gradle_args[@]}"}"
    # Clean and assemble stay separate runs. Clean destroys outputs assemble declares.
    ./gradlew :app:assembleRelease "${gradle_args[@]+"${gradle_args[@]}"}"
fi

shopt -s nullglob
unsigned=("$apk_output"/*-unsigned.apk)
[ "${#unsigned[@]}" -gt 0 ] || die "no unsigned APK in $apk_output"
if [ "$skip_sign" -eq 1 ]; then
    printf '%s\n' "${unsigned[@]}"
    exit 0
fi

output=${output:-$apk_output/signed}
mkdir -p "$output"
for apk in "${unsigned[@]}"; do
    signed=$output/$(basename "${apk/-unsigned/}")
    aligned=$output/.aligned-$(basename "$apk")
    # AGP already placed native libraries on 16 KiB boundaries. -P keeps them there.
    "$zipalign" -P 16 -f 4 "$apk" "$aligned"
    ITO_SIGN_STORE_PASSWORD=$(printf '%s' "$store_password_b64" | base64 --decode) \
    ITO_SIGN_KEY_PASSWORD=$(printf '%s' "$key_password_b64" | base64 --decode) \
        "$apksigner" sign --ks "$store_file" --ks-key-alias "$key_alias" \
        --ks-pass env:ITO_SIGN_STORE_PASSWORD --key-pass env:ITO_SIGN_KEY_PASSWORD \
        --out "$signed" "$aligned"
    rm -f "$aligned" "$signed.idsig"
    "$zipalign" -c -P 16 4 "$signed" || die "$signed lost its 16 KiB alignment"
    "$apksigner" verify --print-certs "$signed" | grep -F 'SHA-256 digest'
    printf '%s\n' "$signed"
done
