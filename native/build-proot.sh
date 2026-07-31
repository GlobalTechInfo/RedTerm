#!/usr/bin/env bash
set -euo pipefail

# Cross-compiles proot + talloc for Android using NDK
# Usage: ./build-proot.sh --arch aarch64 --ndk /path/to/android-ndk-r26c

PROOT_VERSION="a89b373"
TALLOC_VERSION="2.4.3"
BUILD_DIR="$(dirname "$0")/build"
OUTPUT_DIR="$(dirname "$0")/../app/src/main/jniLibs"

usage() {
    echo "Usage: $0 --arch <aarch64|armv7|x86_64> --ndk <ndk-path>"
    exit 1
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --arch) ARCH="$2"; shift 2 ;;
        --ndk)  NDK="$2"; shift 2 ;;
        *) usage ;;
    esac
done

if [ -z "${ARCH:-}" ] || [ -z "${NDK:-}" ]; then usage; fi

case "$ARCH" in
    aarch64)
        TOOLCHAIN_HOST="aarch64-linux-android"
        ABI="arm64-v8a"
        API=24
        ;;
    armv7)
        TOOLCHAIN_HOST="armv7a-linux-androideabi"
        ABI="armeabi-v7a"
        API=24
        ;;
    x86_64)
        TOOLCHAIN_HOST="x86_64-linux-android"
        ABI="x86_64"
        API=24
        ;;
    *) usage ;;
esac

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
CC="$TOOLCHAIN/bin/${TOOLCHAIN_HOST}${API}-clang"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"
SYSROOT="$TOOLCHAIN/sysroot"

mkdir -p "$BUILD_DIR" "$OUTPUT_DIR/$ABI"

echo "==> Building talloc (static) for $ARCH"
cd "$BUILD_DIR"
if [ ! -d "talloc-$TALLOC_VERSION" ]; then
    curl -L "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz" | tar xz
fi
cd "talloc-$TALLOC_VERSION"

CC="$CC" AR="$AR" RANLIB="$RANLIB" \
CFLAGS="--sysroot=$SYSROOT -O2 -fPIC -DHAVE_GETBLKSIZE=1" \
./configure --cross-compile --prefix="$BUILD_DIR/talloc-install" --host="$TOOLCHAIN_HOST" \
    --disable-python --disable-talloc-tests --disable-rpath

make -j$(nproc) install

echo "==> Building proot for $ARCH"
cd "$BUILD_DIR"
if [ ! -d "proot" ]; then
    git clone https://github.com/termux/proot.git
    cd proot
    git checkout "$PROOT_VERSION"
fi
cd proot/src

# Patch PATH_MAX if needed on newer NDKs
cat > "loader/loader-fix.h" << 'EOF'
#ifndef PATH_MAX
#define PATH_MAX 4096
#endif
EOF

CC="$CC" AR="$AR" STRIP="$STRIP" \
CFLAGS="--sysroot=$SYSROOT -I$BUILD_DIR/talloc-install/include -O2 -fPIC -DARG_MAX=131072" \
LDFLAGS="-L$BUILD_DIR/talloc-install/lib -static" \
make clean
CC="$CC" AR="$AR" STRIP="$STRIP" \
CFLAGS="--sysroot=$SYSROOT -I$BUILD_DIR/talloc-install/include -O2 -fPIC -DARG_MAX=131072" \
LDFLAGS="-L$BUILD_DIR/talloc-install/lib -static" \
make -j$(nproc)

echo "==> Installing to $OUTPUT_DIR/$ABI"
cp "proot" "$OUTPUT_DIR/$ABI/proot"
cp "loader/loader" "$OUTPUT_DIR/$ABI/loader"
[ -f "loader/loader-m32" ] && cp "loader/loader-m32" "$OUTPUT_DIR/$ABI/loader32" || true

"$STRIP" "$OUTPUT_DIR/$ABI/proot"
"$STRIP" "$OUTPUT_DIR/$ABI/loader" 2>/dev/null || true

echo "==> Done! Built for $ARCH ($ABI)"
echo "    Output: $OUTPUT_DIR/$ABI/"
ls -lh "$OUTPUT_DIR/$ABI/"
