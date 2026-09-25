#!/usr/bin/env bash
set -euo pipefail

# Cross-compiles proot + talloc for Android using the NDK toolchain.
#
#   ./build-proot.sh --all  --ndk /path/to/android-ndk
#   ./build-proot.sh --arch aarch64 --ndk /path/to/android-ndk
#
# Architectures: aarch64 (arm64-v8a), armv7 (armeabi-v7a),
#                x86_64 (x86_64), i686 (x86)
#
# Installs libproot.so / libproot-loader.so / libproot-loader32.so into
# app/src/main/jniLibs/<abi>/. proot and its loaders are static, built with
# 16 KB page alignment so the APK stays loadable on 16 KB-page devices.

PROOT_REPO="https://github.com/termux/proot.git"
PROOT_VERSION="d4d2a19081c3c07f75250e4ce2980b9fa2f5720f"
TALLOC_URL="https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz"
TALLOC_VERSION="2.5.0"
API=24
PAGE_SIZE=16384

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_ROOT="$SCRIPT_DIR/build"
OUTPUT_DIR="$SCRIPT_DIR/../app/src/main/jniLibs"

usage() {
    echo "Usage: $0 (--arch <aarch64|armv7|x86_64|i686> | --all) --ndk <ndk-path>"
    exit 1
}

ARCHES=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --arch) ARCHES+=("$2"); shift 2 ;;
        --all)  ARCHES=(aarch64 armv7 x86_64 i686); shift ;;
        --ndk)  NDK="$2"; shift 2 ;;
        *) usage ;;
    esac
done

if [ ${#ARCHES[@]} -eq 0 ] || [ -z "${NDK:-}" ]; then usage; fi

TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
if [ ! -d "$TC" ]; then
    echo "ERROR: NDK toolchain not found at $TC" >&2
    exit 1
fi
SYSROOT="$TC/sysroot"
AR="$TC/bin/llvm-ar"
STRIP="$TC/bin/llvm-strip"
OBJCOPY="$TC/bin/llvm-objcopy"
OBJDUMP="$TC/bin/llvm-objdump"
READELF="$TC/bin/llvm-readelf"

# talloc's loader-info.awk needs gawk's strtonum().
GAWK=""
for candidate in gawk "$(dirname "$(command -v awk)")/gawk" /data/data/com.termux/files/usr/bin/gawk; do
    if command -v "$candidate" >/dev/null 2>&1; then GAWK="$(command -v "$candidate")"; break; fi
done
if [ -z "$GAWK" ]; then
    echo "ERROR: gawk is required (provides strtonum used by proot's loader-info.awk)" >&2
    exit 1
fi

mkdir -p "$BUILD_ROOT"
TOOLBIN="$BUILD_ROOT/toolbin"
rm -rf "$TOOLBIN"
mkdir -p "$TOOLBIN"
printf '#!/bin/sh\nexec %s "$@"\n' "$GAWK" > "$TOOLBIN/awk"
chmod +x "$TOOLBIN/awk"
export PATH="$TOOLBIN:$PATH"

# driver_wrapper <triple> -> path to an Android-targeted clang driver
driver_wrapper() {
    local triple="$1" path="$TOOLBIN/$1-clang"
    cat > "$path" <<EOF
#!/bin/sh
exec $TC/bin/clang --target=$triple --sysroot=$SYSROOT -fuse-ld=lld -B$TC/bin "\$@"
EOF
    chmod +x "$path"
    printf '%s' "$path"
}

arch_config() {
    case "$1" in
        aarch64) ABI="arm64-v8a";        TRIPLE="aarch64-linux-android$API" ;;
        armv7)   ABI="armeabi-v7a";      TRIPLE="armv7a-linux-androideabi$API" ;;
        x86_64)  ABI="x86_64";           TRIPLE="x86_64-linux-android$API" ;;
        i686)    ABI="x86";              TRIPLE="i686-linux-android$API" ;;
        *) echo "Unsupported arch: $1" >&2; exit 1 ;;
    esac
    CC="$(driver_wrapper "$TRIPLE")"
}

# ---------------------------------------------------------------- talloc ----
# Recent talloc releases build with waf, whose configure cannot run target
# binaries during a cross build. Run it once with a stub --cross-execute to
# emit config.h/replace.h, then compile the sources into a static archive
# directly (waf's shared-library target needs --rosegment, which some
# linkers crash on).
prepare_talloc_config() {
    local src="$BUILD_ROOT/talloc-$TALLOC_VERSION"
    TALLOC_SRC="$src"
    if [ -f "$src/bin/default/config.h" ]; then
        return
    fi
    echo "==> talloc $TALLOC_VERSION (generating config)"
    mkdir -p "$BUILD_ROOT"
    if [ ! -d "$src" ]; then
        curl -fsSL "$TALLOC_URL" | tar xz -C "$BUILD_ROOT"
    fi
    (cd "$src" && rm -rf bin && \
        CC="$CC" \
        CFLAGS="--sysroot=$SYSROOT -O2 -fPIC -DHAVE_GETBLKSIZE=1" \
        LDFLAGS="--sysroot=$SYSROOT" \
        ./configure --cross-compile --cross-execute=/bin/true --disable-python)
}

# talloc is arch-specific, so build one static archive per ABI.
build_talloc() {
    local inst="$BUILD_ROOT/talloc-install-$ARCH_NAME"
    local objs="$BUILD_ROOT/talloc-obj-$ARCH_NAME"
    TALLOC_INSTALL="$inst"
    if [ -f "$inst/lib/libtalloc.a" ]; then
        return
    fi
    echo "==> [$ABI] talloc static archive"
    rm -rf "$inst" "$objs"
    mkdir -p "$inst/lib" "$inst/include" "$objs"
    local cf="-I $TALLOC_SRC/bin/default -I $TALLOC_SRC/lib/replace -I $TALLOC_SRC -O2 -fPIC -DHAVE_GETBLKSIZE=1 -D__STDC_WANT_LIB_EXT1__=1 -Wno-implicit-function-declaration -Wno-implicit-int"
    for f in "$TALLOC_SRC/talloc.c" "$TALLOC_SRC"/lib/replace/*.c; do
        "$CC" $cf -c "$f" -o "$objs/$(basename "$f").o"
    done
    "$AR" rcs "$inst/lib/libtalloc.a" "$objs"/*.o
    cp "$TALLOC_SRC/talloc.h" "$TALLOC_SRC/lib/replace/replace.h" \
       "$TALLOC_SRC/bin/default/config.h" "$inst/include/"
}

# ---------------------------------------------------------------- proot -----
build_proot() {
    local work="$BUILD_ROOT/$ARCH_NAME"
    mkdir -p "$work" "$OUTPUT_DIR/$ABI"

    prepare_talloc_config
    build_talloc

    echo "==> [$ABI] proot $PROOT_VERSION"
    if [ ! -d "$work/proot" ]; then
        git clone "$PROOT_REPO" "$work/proot"
    fi
    (
        cd "$work/proot"
        git fetch --all --tags --quiet
        git checkout --quiet "$PROOT_VERSION"
        cd src
        make clean >/dev/null 2>&1 || true
        # The loader is linked with its own -nostdlib/-Ttext flags, so the
        # global LDFLAGS above do not reach it; add page alignment there too.
        python3 - "$PAGE_SIZE" <<'PYEOF'
import re, sys
page = sys.argv[1]
p = "GNUmakefile"
s = open(p).read()
new = re.sub(
    r'(LOADER_LDFLAGS\$1 \+= -static -nostdlib )',
    r'\1-Wl,-z,max-page-size=' + page + ' ',
    s,
)
if new == s:
    sys.exit("ERROR: could not inject loader page size into GNUmakefile")
open(p, "w").write(new)
PYEOF
        cat > loader/loader-fix.h << 'EOF'
#ifndef PATH_MAX
#define PATH_MAX 4096
#endif
EOF
        CC="$CC" AR="$AR" STRIP="$STRIP" OBJCOPY="$OBJCOPY" OBJDUMP="$OBJDUMP" \
        CFLAGS="-I$TALLOC_INSTALL/include -O2 -fPIC -DARG_MAX=131072 -Wno-implicit-function-declaration -Wno-implicit-int" \
        LDFLAGS="-L$TALLOC_INSTALL/lib -static -Wl,-z,max-page-size=$PAGE_SIZE" \
        make -j"$(nproc)"
    )

    echo "==> [$ABI] installing"
    install -m 0755 "$work/proot/src/proot" "$OUTPUT_DIR/$ABI/libproot.so"
    install -m 0755 "$work/proot/src/loader/loader" "$OUTPUT_DIR/$ABI/libproot-loader.so"
    if [ -f "$work/proot/src/loader/loader-m32" ]; then
        install -m 0755 "$work/proot/src/loader/loader-m32" "$OUTPUT_DIR/$ABI/libproot-loader32.so"
    else
        rm -f "$OUTPUT_DIR/$ABI/libproot-loader32.so"
    fi
    FAILED=0
    for f in "$OUTPUT_DIR/$ABI"/*.so; do
        "$STRIP" --strip-unneeded "$f"
        while read -r align; do
            if [ $((align)) -lt "$PAGE_SIZE" ]; then
                echo "ERROR: $(basename "$f") LOAD align $align < $PAGE_SIZE" >&2
                FAILED=1
            fi
        done < <("$READELF" -lW "$f" | awk '$1 == "LOAD" {print $NF}')
    done
    if [ "$FAILED" -ne 0 ]; then
        echo "ERROR: [$ABI] 16 KB alignment check failed" >&2
        exit 1
    fi
    echo "==> [$ABI] done (all binaries 16 KB aligned)"
}

for ARCH_NAME in "${ARCHES[@]}"; do
    arch_config "$ARCH_NAME"
    build_proot
done

echo
echo "Built into $OUTPUT_DIR:"
for d in "$OUTPUT_DIR"/*/; do
    echo "  $(basename "$d")"
    ls -1 "$d" | sed 's/^/    /'
done
