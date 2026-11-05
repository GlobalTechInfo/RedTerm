#!/usr/bin/env bash
set -euo pipefail

# Builds the redterm-api helper (the guest-side half of RedTerm's termux:API
# bridge) for every shipped ABI and installs it as
# app/src/main/assets/redterm-api/<arch>/redterm-api.
#
# The helper is executed by a distro session through proot, the same way
# the mosh client executable is. It therefore keeps the plain 4 KB page
# alignment a proot-loaded ELF needs (a 16 KB one breaks on 4 KB-page
# devices because proot maps the target itself), and it links DYNAMICALLY
# against bionic's shared libraries: a full static link drags in bionic's
# own 16 KB-aligned .bss (the 16 KB-era NDK) plus its static-TLS layout,
# which bionic aborts on start ("TLS segment is underaligned ... 64 for
# ARM64 Bionic"). The dynamic path is what native/build-mosh.sh ships for
# the mosh client, and it is the one measured on a real device.
#
# Usage:
#   native/build-redterm-api.sh --ndk /path/to/android-ndk

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC="$SCRIPT_DIR/redterm-api/redterm-api.c"
OUTPUT_ROOT="$REPO_ROOT/app/src/main/assets/redterm-api"

API=24

usage() {
    echo "Usage: $0 --ndk <ndk-path>"
    exit 1
}

NDK=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --ndk) NDK="$2"; shift 2 ;;
        *) usage ;;
    esac
done
[ -n "$NDK" ] || usage

TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
[ -d "$TC" ] || { echo "ERROR: NDK toolchain not found at $TC" >&2; exit 1; }
SYSROOT="$TC/sysroot"

triple_for() {
    case "$1" in
        aarch64) echo "aarch64-linux-android$API" ;;
        armv7)   echo "armv7a-linux-androideabi$API" ;;
        x86_64)  echo "x86_64-linux-android$API" ;;
        i686)    echo "i686-linux-android$API" ;;
    esac
}

asset_for() {
    case "$1" in
        aarch64) echo "aarch64" ;;
        armv7)   echo "armv7a" ;;
        x86_64)  echo "x86_64" ;;
        i686)    echo "i686" ;;
    esac
}

build_one() {
    local arch="$1"
    local triple asset out
    triple="$(triple_for "$arch")"
    asset="$(asset_for "$arch")"
    out="$OUTPUT_ROOT/$asset/redterm-api"
    echo "==> [$asset] redterm-api"
    mkdir -p "$(dirname "$out")"
    "$TC/bin/clang" --target="$triple" --sysroot="$SYSROOT" \
        -fuse-ld=lld -B"$TC/bin" \
        -O2 -Wl,-z,max-page-size=4096 \
        "$SRC" -o "$out"

    # The loader maps this binary itself, so every LOAD segment must keep
    # 4 KB alignment. A 16 KB one would break 4 KB-page devices; the mosh
    # build enforces the same check.
    local readelf="$TC/bin/llvm-readelf"
    local load_aligns
    load_aligns="$("$readelf" -lW "$out" | awk '$1 == "LOAD" {print $NF}')"
    while read -r a; do
        [ $(( a )) -le 4096 ] || { echo "ERROR: [$asset] LOAD align $a > 4096 (proot maps this binary itself)" >&2; exit 1; }
    done <<< "$load_aligns"

    local size
    size="$(stat -c%s "$out")"
    [ "$size" -gt 0 ] || { echo "ERROR: empty binary" >&2; exit 1; }
    echo "==> [$asset] done ($size bytes, dynamic)"
}

for arch in aarch64 armv7 x86_64 i686; do
    build_one "$arch"
done

echo
echo "Built into $OUTPUT_ROOT:"
for d in "$OUTPUT_ROOT"/*/; do
    echo "  $(basename "$d")"
    ls -1 "$d" | sed 's/^/    /'
done
