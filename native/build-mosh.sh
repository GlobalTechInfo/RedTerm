#!/bin/bash
# Builds mosh's protocol libraries into libredtermmosh.so, one per ABI.
#
# Why native at all, rather than a Kotlin mosh client: mosh's State Synchronization
# Protocol is fiddly and getting it wrong is subtle, so the wire protocol comes from
# upstream's own C++ and the frontend is ours.
#
# What is NOT built: mosh-client, mosh-server and libmoshterminal's curses frontend.
# --disable-client does that, and nothing in the tree references TerminalDisplayInit,
# so the terminfo-dependent object is never pulled into the library. See the note on
# native/mosh_curses_stub/curses.h.
#
# What IS built, and why these versions:
#
#   mosh 1.4.0      the wire protocol, statesync, terminal state, and the crypto.
#   protobuf 3.21.12 mosh's three .proto files are optimize_for = LITE_RUNTIME, so
#                   only libprotobuf-lite is needed. The full C++ runtime and the
#                   protoc compiler are not.
#   nettle 3.9.1    mosh's AES. NOT OpenSSL: OpenSSL 3.5 contributes 3393 symbols to
#                   the linked object against mosh's own 31, costs 7,837s to build for
#                   one ABI against nettle's 1,159s, and takes the .so from 978,496
#                   bytes to 5,351,472. OpenSSH keeps its own OpenSSL and this script
#                   does not touch it.
#
# Install: app/src/main/jniLibs/<abi>/libredtermmosh.so
#
# Usage:
#   native/build-mosh.sh [--arch <aarch64|armv7|x86_64|i686> | --all] [--ndk <path>]
#                        [--jobs <n>] [--clean]
#
# Everything is written under $WORK first and installed only after a link succeeds, so
# a failed build cannot leave a half-written library in the APK.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# Not under /tmp. Two reasons, and the second only became obvious by losing it:
#   - /tmp is a 2.9 GB tmpfs on this host, and four ABIs of nettle, protobuf and
#     mosh do not fit in that even one at a time;
#   - it was wiped mid-build once, taking an hour of single-core cross-compilation
#     with it. The tree is a cache keyed on a sha256 per source, so it rebuilds, but
#     the cost is real on one core.
# Override with REDTERM_WORK, or TMPDIR if that is somewhere durable.
WORK="${REDUCTERM_WORK:-${TMPDIR:-/home/ubuntu/.cache}/redterm-mosh}"
OUTPUT_DIR="$REPO_ROOT/app/src/main/jniLibs"

MOSH_VERSION=1.4.0
PROTOBUF_VERSION=3.21.12
# protobuf's release tag is "v21.12" while its version is "3.21.12" and its asset is
# named "protobuf-cpp-3.21.12.tar.gz". Interpolating one from the other is how the tag
# comes out as v3.21.12 and 404s.
PROTOBUF_TAG=21.12
NETTLE_VERSION=3.9.1

API=24
# 16384, not the 4096 that native/build-openssh.sh uses, and the difference is not
# cosmetic. An executable loaded by proot's -L has to be 4 KB aligned, because proot
# maps the target itself and a 16 KB-aligned ELF breaks it on a 4 KB-page device. A
# jniLibs .so is loaded by Android's own linker, which wants 16 KB for a device with
# 16 KB pages -- that is what the Aligned16KB lint check is about, and why
# libproot.so is built at 16384.
PAGE_SIZE=16384

# The executable wants the *other* alignment, and the reason is the loading mechanism
# rather than the file type. RedTerm already runs cross-built binaries (ssh, ssh-keygen)
# from assets/<name>/<arch>/ through proot's -L loader, and proot maps the target itself,
# so those must be 4 KB aligned; a jniLibs .so is loaded by Android's own linker instead,
# which is why it wants 16 KB. Same source tree, two different requirements, one of them
# the opposite of the other.
BINARY_PAGE_SIZE=4096

# Assets use build-openssh.sh's directory names, which are not the jniLibs ones.
BINARY_OUTPUT_DIR="$REPO_ROOT/app/src/main/assets/mosh"

JOBS="${REDUCTERM_JOBS:-2}"

# Recorded and verified. build-openssh.sh records these without checking them, which
# means a changed tarball is only noticed by whatever breaks.
MOSH_SHA256=872e4b134e5df29c8933dff12350785054d2fd2839b5ae6b5587b14db1465ddd
PROTOBUF_SHA256=4eab9b524aa5913c6fffb20b2a8abf5ef7f95a80bc0701f3a6dbb4c607f73460
NETTLE_SHA256=ccfeff981b0ca71bbd6fbcb054f407c60ffb644389a5be80d6716d5b550c6ce3

ARCHES=()
NDK=""
CLEAN=0

usage() {
    sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --all) ARCHES=(aarch64 armv7 x86_64 i686); shift ;;
        --arch) ARCHES=("$2"); shift 2 ;;
        --ndk) NDK="$2"; shift 2 ;;
        --jobs) JOBS="$2"; shift 2 ;;
        --clean) CLEAN=1; shift ;;
        -h|--help) usage 0 ;;
        *) echo "unknown option: $1" >&2; usage 1 ;;
    esac
done
[ ${#ARCHES[@]} -gt 0 ] || ARCHES=(aarch64)

[ -n "$NDK" ] || NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK:-/opt/ndk/android-ndk-r27c}}"
[ -d "$NDK" ] || { echo "!! NDK not found: $NDK" >&2; exit 1; }

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
[ -d "$TOOLCHAIN" ] || TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$(uname -m)"
SYSROOT="$TOOLCHAIN/sysroot"
CLANG_VER="$(ls "$TOOLCHAIN/lib/clang" | sort -V | tail -1)"

abi_for() {
    case "$1" in
        aarch64) echo "arm64-v8a" ;;
        armv7)   echo "armeabi-v7a" ;;
        x86_64)  echo "x86_64" ;;
        i686)    echo "x86" ;;
        *) echo "unknown arch: $1" >&2; exit 2 ;;
    esac
}

asset_dir_for() {
    case "$1" in
        aarch64) echo "aarch64" ;;
        armv7)   echo "armv7a" ;;
        x86_64)  echo "x86_64" ;;
        i686)    echo "i686" ;;
        *) echo "unknown arch: $1" >&2; exit 2 ;;
    esac
}

triple_for() {
    case "$1" in
        aarch64) echo "aarch64-linux-android$API" ;;
        armv7)   echo "armv7a-linux-androideabi$API" ;;
        x86_64)  echo "x86_64-linux-android$API" ;;
        i686)    echo "i686-linux-android$API" ;;
    esac
}

# Without --host, configure assumes a native build and tries to run its test
# binaries, which fails for every Android ABI.
host_for() {
    triple_for "$1" | sed "s/-android$API\$/-android/; s/-androideabi$API\$/-androideabi/"
}

apidir_for() {
    case "$1" in
        aarch64) echo "aarch64-linux-android" ;;
        armv7)   echo "arm-linux-androideabi" ;;
        x86_64)  echo "x86_64-linux-android" ;;
        i686)    echo "i686-linux-android" ;;
    esac
}

say() { printf '\n=== %s ===\n' "$*"; }

# ------------------------------------------------------------------ sources

fetch_sources() {
    mkdir -p "$WORK/src" "$WORK/logs"
    if [ "$CLEAN" = 1 ]; then
        echo "--clean: discarding the cached tree"
        rm -rf "$WORK"/*
        mkdir -p "$WORK/src" "$WORK/logs"
    fi

    # Each is fetched once into a shared name, verified, and unpacked only if absent.
    # The root directory each tarball produces is named what the build expects, which
    # is not the same as the download name for protobuf.
    local pair
    for pair in \
        "mosh-$MOSH_VERSION https://github.com/mobile-shell/mosh/releases/download/mosh-$MOSH_VERSION/mosh-$MOSH_VERSION.tar.gz $MOSH_SHA256" \
        "protobuf-$PROTOBUF_VERSION https://github.com/protocolbuffers/protobuf/releases/download/v$PROTOBUF_TAG/protobuf-cpp-$PROTOBUF_VERSION.tar.gz $PROTOBUF_SHA256" \
        "nettle-$NETTLE_VERSION https://ftp.gnu.org/gnu/nettle/nettle-$NETTLE_VERSION.tar.gz $NETTLE_SHA256"
    do
        set -- $pair
        local name="$1" url="$2" want="$3"
        if [ ! -f "$WORK/src/$name.tar.gz" ]; then
            echo "-- fetching $name"
            curl -sfL "$url" -o "$WORK/src/$name.tar.gz" || {
                echo "!! could not download $url" >&2
                exit 1
            }
        fi
        local got
        got="$(sha256sum "$WORK/src/$name.tar.gz" | cut -d' ' -f1)"
        if [ "$got" != "$want" ]; then
            echo "!! $name sha256 mismatch" >&2
            echo "   expected $want" >&2
            echo "   got      $got" >&2
            exit 1
        fi
        if [ ! -d "$WORK/$name" ]; then
            echo "-- unpacking $name"
            tar xf "$WORK/src/$name.tar.gz" -C "$WORK" || exit 1
        fi
        [ -d "$WORK/$name" ] || { echo "!! $name.tar.gz did not unpack to $name/" >&2; exit 1; }
    done
}

# ------------------------------------------------------------------ wrapper

# Proven by native/build-openssh.sh, which cannot cross-compile without it:
#
#  - the NDK's clang cannot locate its own crtbegin_static.o for a static link, and
#    ships no libgcc.a, so compiler-rt's builtins archive is symlinked into place.
#  - a *dynamic* link must be left to the driver. Injecting -nostartfiles there drops
#    crtbegin_so.o, which supplies the .init_array framing and __dso_handle; the
#    dynamic linker then runs off the end of .init_array and SIGSEGVs while loading,
#    before main ever runs.
write_wrapper() {
    local arch="$1" stage="$2" triple="$3" apidir="$4"
    local crtdir="$SYSROOT/usr/lib/$apidir/$API"
    [ -f "$crtdir/crtbegin_static.o" ] || crtdir="$SYSROOT/usr/lib/$apidir/21"
    mkdir -p "$stage"

    local builtins="$TOOLCHAIN/lib/clang/$CLANG_VER/lib/linux/libclang_rt.builtins-$triple.a"
    [ -f "$builtins" ] || builtins="$TOOLCHAIN/lib/clang/$CLANG_VER/lib/linux/libclang_rt.builtins-$arch-android.a"
    ln -sf "$builtins" "$stage/libgcc.a"

    cat > "$stage/cc" <<EOF
#!/bin/sh
# Generated by native/build-mosh.sh -- do not edit. Based on build-openssh.sh's.
REALCC="$TOOLCHAIN/bin/$triple-clang"
SYSROOT="$SYSROOT"
CRT1="$crtdir/crtbegin_static.o"
CRT2="$crtdir/crtend_android.o"
STAGE="$stage"
APIARCH="$apidir"
compile_only=0
want_static=0
for a in "\$@"; do
  [ "\$a" = "-c" ] && compile_only=1
  case "\$a" in -static) want_static=1 ;; esac
done
if [ "\$compile_only" = 1 ]; then
  exec "\$REALCC" --target=$triple --sysroot="\$SYSROOT" "\$@"
fi
if [ "\$want_static" = 1 ]; then
  exec "\$REALCC" "\$CRT1" "\$CRT2" "\$@" --target=$triple --sysroot="\$SYSROOT" \\
    -nostartfiles -L"\$STAGE" -lgcc \\
    -L"\$SYSROOT/usr/lib/\$APIARCH" -lc -lm -ldl \\
    -Wl,-z,max-page-size=$PAGE_SIZE
fi
# As above: only supply the default when the caller did not.
chosen=0
for a in "\$@"; do
  case "\$a" in *max-page-size*) chosen=1 ;; esac
done
if [ "\$chosen" = 1 ]; then
  exec "\$REALCC" "\$@" --target=$triple --sysroot="\$SYSROOT"
fi
exec "\$REALCC" "\$@" --target=$triple --sysroot="\$SYSROOT" \\
  -Wl,-z,max-page-size=$PAGE_SIZE
EOF
    chmod +x "$stage/cc"

    # C++, because mosh is C++. -static-libstdc++ keeps libc++ inside the .so, so no
    # libc++_shared.so has to ship alongside it.
    # The page-size flag is added only when the caller has not already chosen one.
    #
    # It used to be appended unconditionally, which silently overrode a caller that
    # asked for something else: build_binary needs 4096 for an executable proot loads
    # itself, passed 4096 on the command line, and got 16384 because the wrapper's
    # flag came afterwards. Nothing failed -- the link succeeded and the binary was
    # simply aligned the other way -- so the only sign was a check comparing against a
    # number the wrapper had just changed. A wrapper that adds a flag has to let the
    # caller win, or it is not a default.
    cat > "$stage/cxx" <<EOF
#!/bin/sh
REALCXX="$TOOLCHAIN/bin/$triple-clang++"
chosen=0
for a in "\$@"; do
  case "\$a" in *max-page-size*) chosen=1 ;; esac
done
if [ "\$chosen" = 1 ]; then
  exec "\$REALCXX" "\$@" --target=$triple --sysroot="$SYSROOT" -static-libstdc++
fi
exec "\$REALCXX" "\$@" --target=$triple --sysroot="$SYSROOT" \\
  -static-libstdc++ -Wl,-z,max-page-size=$PAGE_SIZE
EOF
    chmod +x "$stage/cxx"
}

# ------------------------------------------------------------------ nettle

build_nettle() {
    local arch="$1" stage="$2" nettle="$3" t0=$SECONDS
    if [ -f "$nettle/lib/libnettle.a" ]; then
        echo "   nettle: already built"
        return 0
    fi
    echo "-- nettle $NETTLE_VERSION for $arch"
    rm -rf "$WORK/nettle-build-$arch"
    cp -r "$WORK/nettle-$NETTLE_VERSION" "$WORK/nettle-build-$arch"
    (
        cd "$WORK/nettle-build-$arch" || exit 1
        # Three autotools traps, one per dependency, all the same shape: the release
        # tarball ships working generated files, but make tries to regenerate them and
        # automake is not installed.
        #
        #     make[1]: *** [Makefile:405: Makefile.in] Error 1
        #     missing: line 81: automake-1.16: command not found
        #     make[1]: *** [Makefile:1699: aclocal.m4] Error 127
        #     cd . && autoheader
        #
        # Touch in dependency order -- aclocal.m4 oldest, then configure/config.h.in,
        # then every Makefile.in newest -- so make considers them all current.
        # stamp-h.in is mosh's, protobuf's and nettle's version of the same idea and is
        # what make install reaches for otherwise.
        touch aclocal.m4 || true
        sleep 1; touch configure config.h.in stamp-h.in 2>/dev/null || true
        sleep 1; find . -name 'Makefile.in' -exec touch {} + 2>/dev/null || true

        ./configure --prefix="$nettle" --host="$(host_for "$arch")" \
            --disable-shared --enable-static --disable-documentation --disable-assembler \
            CC="$stage/cc" CC_FOR_BUILD=gcc \
            AR="$TOOLCHAIN/bin/llvm-ar" RANLIB="$TOOLCHAIN/bin/llvm-ranlib" \
            > "$WORK/logs/nettle-config-$arch.log" 2>&1 || exit 1

        # desdata is a HOST program: it generates rotors.h. Built with the cross
        # wrapper it would be an aarch64 binary and could not run here, which is what
        # `/bin/sh: 2: ./desdata: not found` under make -j2 was really about -- a
        # parallel-make race, not a missing tool.
        make -j"$JOBS" desdata > "$WORK/logs/nettle-build-$arch.log" 2>&1 || exit 1
        make -j"$JOBS" >> "$WORK/logs/nettle-build-$arch.log" 2>&1 || exit 1
        make install >> "$WORK/logs/nettle-build-$arch.log" 2>&1 || exit 1
    ) || {
        echo "!! nettle failed for $arch; see $WORK/logs/nettle-build-$arch.log" >&2
        tail -20 "$WORK/logs/nettle-build-$arch.log" >&2
        return 1
    }
    [ -f "$nettle/lib/libnettle.a" ] || { echo "!! nettle produced no libnettle.a" >&2; return 1; }
    echo "   nettle: $(stat -c%s "$nettle/lib/libnettle.a") bytes in $((SECONDS-t0))s"
}

# ------------------------------------------------------------------ protobuf-lite

build_protobuf_lite() {
    local arch="$1" stage="$2" pblite="$3" t0=$SECONDS
    if [ -f "$pblite/lib/libprotobuf-lite.a" ]; then
        echo "   protobuf-lite: already built"
        return 0
    fi
    echo "-- libprotobuf-lite $PROTOBUF_VERSION for $arch"
    rm -rf "$WORK/protobuf-build-$arch"
    cp -r "$WORK/protobuf-$PROTOBUF_VERSION" "$WORK/protobuf-build-$arch"
    (
        cd "$WORK/protobuf-build-$arch" || exit 1
        # Same autotools trap as nettle. protoc is generated by make depend.
        touch aclocal.m4 || true
        sleep 1; touch configure config.h.in || true
        sleep 1; find . -name 'Makefile.in' -exec touch {} + 2>/dev/null || true

        ./configure --prefix="$pblite" --host="$(host_for "$arch")" \
            --disable-shared --enable-static --disable-tests --disable-java \
            --with-protoc=/usr/bin/protoc \
            CC="$stage/cc" CXX="$stage/cxx" \
            AR="$TOOLCHAIN/bin/llvm-ar" RANLIB="$TOOLCHAIN/bin/llvm-ranlib" \
            CFLAGS="-Os -fPIC" CXXFLAGS="-Os -fPIC" \
            > "$WORK/logs/pb-config-$arch.log" 2>&1 || exit 1

        # ONLY the lite target. automake's install-libLTLIBRARIES installs libprotobuf,
        # libprotobuf-lite, libprotoc AND libupb, so asking for it builds the protoc
        # compiler as well -- over an hour of a single-core host for two libraries mosh
        # never links. mosh's .proto files are optimize_for = LITE_RUNTIME.
        make -C src -j"$JOBS" libprotobuf-lite.la > "$WORK/logs/pb-build-$arch.log" 2>&1 || exit 1
    ) || {
        echo "!! protobuf-lite failed for $arch; see $WORK/logs/pb-build-$arch.log" >&2
        tail -20 "$WORK/logs/pb-build-$arch.log" >&2
        return 1
    }

    # Installed by hand, for the same reason. The headers include .inc files as well
    # as .h, and mosh's configure reports the unhelpful "Could not build output
    # generated by protoc" when one is missing.
    mkdir -p "$pblite/lib" "$pblite/include"
    cp "$WORK/protobuf-build-$arch/src/.libs/libprotobuf-lite.a" "$pblite/lib/" || return 1
    ( cd "$WORK/protobuf-build-$arch/src" && \
      find . -path './google/protobuf/*' \( -name '*.h' -o -name '*.inc' \) \
        -exec cp --parents {} "$pblite/include/" \; ) || return 1
    echo "   protobuf-lite: $(stat -c%s "$pblite/lib/libprotobuf-lite.a") bytes in $((SECONDS-t0))s"
}

# ------------------------------------------------------------------ mosh

build_mosh() {
    local arch="$1" stage="$2" sslp="$3" pblite="$4" nettle="$5" moshp="$6" t0=$SECONDS
    if [ -f "$moshp/lib/libmoshstatesync.a" ]; then
        echo "   mosh: already built"
        return 0
    fi
    echo "-- mosh $MOSH_VERSION protocol libraries for $arch"

    # mosh's configure probes for tinfo/ncurses unconditionally and errors out with
    # "requires either tinfo, NcursesW or Ncurses library" -- even under
    # --disable-client, which compiles no curses code at all. Satisfy the probe with a
    # stub rather than cross-compiling ncurses we would not use.
    mkdir -p "$WORK/stub-pc"
    cat > "$WORK/stub-pc/tinfo.pc" <<'EOF'
Name: tinfo
Description: stub. mosh is configured --disable-client, so no curses code is compiled.
Version: 0.0
Libs:
Cflags:
EOF

    rm -rf "$WORK/mosh-build-$arch"
    cp -r "$WORK/mosh-$MOSH_VERSION" "$WORK/mosh-build-$arch"
    (
        cd "$WORK/mosh-build-$arch" || exit 1
        touch aclocal.m4 || true
        sleep 1; touch configure config.h.in || true
        sleep 1; find . -name 'Makefile.in' -exec touch {} + 2>/dev/null || true

        PKG_CONFIG_PATH="$WORK/stub-pc:$nettle/lib/pkgconfig" ./configure \
            --prefix="$moshp" --host="$(host_for "$arch")" \
            --disable-client --disable-server --disable-examples \
            --without-utempter --disable-syslog \
            --with-crypto-library=nettle \
            CC="$stage/cc" CXX="$stage/cxx" \
            AR="$TOOLCHAIN/bin/llvm-ar" RANLIB="$TOOLCHAIN/bin/llvm-ranlib" \
            PROTOC=/usr/bin/protoc \
            CFLAGS="-Os -fPIC -I$nettle/include -I$SCRIPT_DIR/mosh_curses_stub -include $SCRIPT_DIR/mosh_compat.h" \
            CXXFLAGS="-Os -fPIC -I$nettle/include -I$SCRIPT_DIR/mosh_curses_stub -include $SCRIPT_DIR/mosh_compat.h" \
            LDFLAGS="-L$nettle/lib" \
            protobuf_CFLAGS="-I$pblite/include" \
            protobuf_LIBS="-L$pblite/lib -lprotobuf-lite" \
            > "$WORK/logs/mosh-config-$arch.log" 2>&1 || exit 1

        grep -E "Cryptography:" "$WORK/logs/mosh-config-$arch.log" || exit 1

        # Spelled out rather than derived from the directory name, because
        # src/protobufs builds libmoshprotos.a and not libmoshprotobufs.a -- the
        # obvious loop asks for the wrong name and fails in a way that reads like a
        # broken toolchain.
        for pair in crypto:libmoshcrypto util:libmoshutil protobufs:libmoshprotos \
                    network:libmoshnetwork statesync:libmoshstatesync terminal:libmoshterminal
        do
            d="${pair%%:*}"; lib="${pair##*:}"
            make -C "src/$d" -j"$JOBS" "$lib.a" >> "$WORK/logs/mosh-build-$arch.log" 2>&1 || exit 1
        done

        # Belt and braces for the curses-free object. A static archive only pulls in
        # members that are referenced, so this is not the mechanism -- it is here so a
        # reference creeping in fails at the archive rather than at link time.
        "$TOOLCHAIN/bin/llvm-ar" d src/terminal/libmoshterminal.a terminaldisplayinit.o 2>/dev/null || true
    ) || {
        echo "!! mosh failed for $arch; see $WORK/logs/mosh-build-$arch.log" >&2
        tail -20 "$WORK/logs/mosh-build-$arch.log" >&2
        return 1
    }
    mkdir -p "$moshp/lib"
    local d
    for d in crypto util protobufs network statesync terminal; do
        cp "$WORK/mosh-build-$arch/src/$d"/*.a "$moshp/lib/" 2>/dev/null || true
    done
    cp -r "$WORK/mosh-build-$arch/src" "$moshp/src" 2>/dev/null || true
    echo "   mosh: $(ls "$moshp/lib"/*.a 2>/dev/null | wc -l) archives in $((SECONDS-t0))s"
}

# ------------------------------------------------------------------ library

# The JNI surface. Phase 1's whole job is a library that loads and works on a device;
# a .so with no exported symbols links fine and proves nothing, which is exactly what
# an earlier probe of this build did: 5,216 bytes and one dynamic symbol.
build_library() {
    local arch="$1" stage="$2" pblite="$3" nettle="$4" moshp="$5" out="$6"
    echo "-- linking libredtermmosh.so for $arch"

    # The stand-ins for what bionic lacks at API 24.
    "$stage/cc" -Os -fPIC -c -o "$WORK/mosh_compat-$arch.o" \
        "$SCRIPT_DIR/mosh_compat.c" -include "$SCRIPT_DIR/mosh_compat.h" \
        2> "$WORK/logs/compat-$arch.log" || {
            echo "!! mosh_compat.c failed" >&2
            cat "$WORK/logs/compat-$arch.log" >&2
            return 1
        }

    "$stage/cxx" -std=gnu++11 -Os -fPIC -shared -o "$out" \
        -I "$moshp/src" -I "$SYSROOT/usr/include" -I "$pblite/include" -I "$nettle/include" \
        -I "$moshp/src/include" -I "$moshp/src/statesync" -I "$moshp/src/terminal" \
        -I "$moshp/src/network" -I "$moshp/src/crypto" -I "$moshp/src/protobufs" \
        -I "$moshp/src/util" \
        -I "$SCRIPT_DIR/mosh_curses_stub" \
        -include "$SCRIPT_DIR/mosh_compat.h" \
        "$SCRIPT_DIR/redterm_mosh.c" \
        "$SCRIPT_DIR/redterm_mosh_client.cpp" \
        "$SCRIPT_DIR/mosh_display_compat.cpp" \
        "$moshp/lib/libmoshcrypto.a" "$moshp/lib/libmoshutil.a" \
        "$moshp/lib/libmoshprotos.a" "$moshp/lib/libmoshnetwork.a" \
        "$moshp/lib/libmoshstatesync.a" "$moshp/lib/libmoshterminal.a" \
        "$nettle/lib/libnettle.a" "$pblite/lib/libprotobuf-lite.a" \
        "$WORK/mosh_compat-$arch.o" \
        -lz -llog -ldl -lm -lc++_static \
        -Wl,--no-undefined -Wl,-z,max-page-size=$PAGE_SIZE \
        2> "$WORK/logs/link-$arch.log"
    local rc=$?
    if [ $rc -ne 0 ]; then
        echo "!! link failed for $arch" >&2
        tail -30 "$WORK/logs/link-$arch.log" >&2
        return 1
    fi

    # --no-undefined is the check that matters: it is what turned a 5 KB empty shell
    # into a failure the moment a real symbol was referenced.
    local undef
    undef="$("$TOOLCHAIN/bin/llvm-readelf" -d "$out" | grep NEEDED | sed 's/.*\[\(.*\)\]/\1/' | tr '\n' ' ')"
    echo "   NEEDED: $undef"

    "$TOOLCHAIN/bin/llvm-strip" "$out"
    # 16 KB page alignment is the point of a jniLibs .so; say so if it is missing
    # rather than leaving a lint warning that names something else.
    local align
    align="$("$TOOLCHAIN/bin/llvm-readelf" -l "$out" | awk '/LOAD/{getline; print $NF; exit}' | tr -d '\r')"
    echo "   $(stat -c%s "$out") bytes, LOAD align ${align:-?}"
    return 0
}

# ------------------------------------------------------------------ main

build_arch() {
    local arch="$1"
    local abi; abi="$(abi_for "$arch")"
    local stage="$WORK/stage-$arch"
    local nettle="$WORK/nettle-$arch"
    local pblite="$WORK/pb-lite-$arch"
    local moshp="$WORK/mosh-$arch"

    mkdir -p "$stage" "$OUTPUT_DIR/$abi"
    write_wrapper "$arch" "$stage" "$(triple_for "$arch")" "$(apidir_for "$arch")"

    build_nettle     "$arch" "$stage" "$nettle"      || return 1
    build_protobuf_lite "$arch" "$stage" "$pblite"  || return 1
    build_mosh       "$arch" "$stage" "" "$pblite" "$nettle" "$moshp" || return 1

    # Built into a temporary name and moved into place, so an interrupted link cannot
    # leave a truncated library in the APK where the loader would find it and die.
    local tmp="$WORK/libredtermmosh-$arch.so"
    build_library "$arch" "$stage" "$pblite" "$nettle" "$moshp" "$tmp" || return 1
    mv "$tmp" "$OUTPUT_DIR/$abi/libredtermmosh.so"
    echo "   installed $OUTPUT_DIR/$abi/libredtermmosh.so"

    local bin_tmp="$WORK/redterm-mosh-$arch"
    mkdir -p "$BINARY_OUTPUT_DIR/$(asset_dir_for "$arch")"
    build_binary "$arch" "$stage" "$pblite" "$nettle" "$moshp" "$bin_tmp" || return 1
    mv "$bin_tmp" "$BINARY_OUTPUT_DIR/$(asset_dir_for "$arch")/redterm-mosh"
    chmod +x "$BINARY_OUTPUT_DIR/$(asset_dir_for "$arch")/redterm-mosh"
    echo "   installed $BINARY_OUTPUT_DIR/$(asset_dir_for "$arch")/redterm-mosh"
}

# The client executable.
#
# This exists because TerminalSession is final and its constructor takes a shell *path*:
# it builds the subprocess itself through JNI.createSubprocess, so a mosh session that is
# not a process cannot be a session at all. The app already ships real executables as
# per-ABI assets and runs them through proot's loader, so mosh takes that same route --
# which means the session machinery, the emulator, split panes and resize need no change
# at all. The sources are identical to the library's; only main() is added, and it is the
# one that reads the endpoint from the environment rather than from argv.
build_binary() {
    local arch="$1" stage="$2" pblite="$3" nettle="$4" moshp="$5" out="$6"
    echo "-- linking redterm-mosh executable for $arch"

    "$stage/cxx" -std=gnu++11 -Os -fPIC -o "$out" \
        -I "$moshp/src" -I "$SYSROOT/usr/include" -I "$pblite/include" -I "$nettle/include" \
        -I "$moshp/src/include" -I "$moshp/src/statesync" -I "$moshp/src/terminal" \
        -I "$moshp/src/network" -I "$moshp/src/crypto" -I "$moshp/src/protobufs" \
        -I "$moshp/src/util" \
        -I "$SCRIPT_DIR/mosh_curses_stub" \
        -include "$SCRIPT_DIR/mosh_compat.h" \
        "$SCRIPT_DIR/redterm_mosh_main.cpp" \
        "$SCRIPT_DIR/redterm_mosh_client.cpp" \
        "$SCRIPT_DIR/mosh_display_compat.cpp" \
        "$moshp/lib/libmoshcrypto.a" "$moshp/lib/libmoshutil.a" \
        "$moshp/lib/libmoshprotos.a" "$moshp/lib/libmoshnetwork.a" \
        "$moshp/lib/libmoshstatesync.a" "$moshp/lib/libmoshterminal.a" \
        "$nettle/lib/libnettle.a" "$pblite/lib/libprotobuf-lite.a" \
        "$WORK/mosh_compat-$arch.o" \
        -lz -llog -ldl -lm \
        -static-libstdc++ \
        -Wl,-z,max-page-size=$BINARY_PAGE_SIZE \
        2> "$WORK/logs/binary-$arch.log"
    local rc=$?
    if [ $rc -ne 0 ]; then
        echo "!! executable link failed for $arch" >&2
        tail -30 "$WORK/logs/binary-$arch.log" >&2
        return 1
    fi

    "$TOOLCHAIN/bin/llvm-strip" "$out"

    local align
    align="$("$TOOLCHAIN/bin/llvm-readelf" -l "$out" | awk '/LOAD/{getline; print $NF; exit}' | tr -d '\r')"
    if [ "$align" != "0x$(printf '%x' $BINARY_PAGE_SIZE)" ]; then
        echo "!! $arch executable is LOAD align ${align:-?}, proot's loader needs $(printf '0x%x' $BINARY_PAGE_SIZE)" >&2
        return 1
    fi
    echo "   $(stat -c%s "$out") bytes, LOAD align $align (proot loader)"
    return 0
}

T0=$SECONDS
command -v protoc >/dev/null || { echo "!! protoc not found (apt install protobuf-compiler)" >&2; exit 1; }
command -v pkg-config >/dev/null || { echo "!! pkg-config not found" >&2; exit 1; }

fetch_sources || exit 1

for arch in "${ARCHES[@]}"; do
    say "$arch ($(abi_for "$arch"))"
    build_arch "$arch" || { echo "!! FAILED: $arch" >&2; exit 1; }
done

say "done in $((SECONDS-T0))s"
for arch in "${ARCHES[@]}"; do
    abi="$(abi_for "$arch")"
    f="$OUTPUT_DIR/$abi/libredtermmosh.so"
    [ -f "$f" ] && printf '  %-14s %-14s %10d bytes\n' "$arch" "$abi" "$(stat -c%s "$f")"
done