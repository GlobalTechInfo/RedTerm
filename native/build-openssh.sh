#!/usr/bin/env bash
#
# Cross-compiles OpenSSH's client (ssh, ssh-keygen) for Android using the NDK.
#
#   ./build-openssh.sh --all  --ndk /path/to/android-ndk
#   ./build-openssh.sh --arch aarch64 --ndk /path/to/android-ndk
#
# Architectures: aarch64 (arm64-v8a), armv7a (armeabi-v7a),
#                x86_64, i686 (x86)
#
# Why a bundled OpenSSH: the terminal view can only drive a real child process,
# so an interactive SSH session needs an actual ssh binary. Running a distro's
# ssh through proot also works, but it would make every connection depend on a
# distro being installed and on that distro shipping openssh-client. A bundled
# client is self-contained and always available.
#
# Installs static binaries into app/src/main/assets/ssh/<arch>/, from where the
# app extracts them to private storage on first use and marks them executable.

set -euo pipefail

OPENSSL_VERSION="3.5.0"
OPENSSH_VERSION="9.9p2"
API=24
# Deliberately low: these builds run inside proot on a phone, where -j$(nproc)
# is enough to exhaust RAM and kill the shell. Override with --jobs if wanted.
JOBS="${REDUCTERM_JOBS:-2}"

ARCHS=()
NDK=""
CLEAN=0
PROBE_ONLY=0
# Segment alignment. proot's -L loader maps the target itself instead of letting
# the kernel do it, and a 16 KB-aligned ELF breaks it on an ordinary 4 KB-page
# device: the client dies with SIGSEGV before main, while the very same binary
# works when the kernel loads it. Keep 4096 unless a 16 KB-page target is wanted.
PAGE_SIZE="${REDUCTERM_PAGE_SIZE:-4096}"

while [ $# -gt 0 ]; do
    case "$1" in
        --all)  ARCHS=(aarch64 armv7a x86_64 i686) ;;
        --arch) ARCHS+=("$2"); shift ;;
        --ndk)  NDK="$2"; shift ;;
        --jobs) JOBS="$2"; shift ;;
        --clean) CLEAN=1 ;;
        --probe) PROBE_ONLY=1 ;;
        -h|--help)
            sed -n '2,20p' "$0"
            exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
    shift
done

if [ -z "$NDK" ]; then
    NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK:-}}"
    [ -n "$NDK" ] || { echo "pass --ndk or set ANDROID_NDK_ROOT" >&2; exit 2; }
fi
[ -d "$NDK" ] || { echo "NDK not found: $NDK" >&2; exit 2; }
[ ${#ARCHS[@]} -gt 0 ] || ARCHS=(aarch64 armv7a x86_64 i686)

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${TMPDIR:-/tmp}/redterm-openssh"
ASSETS="$REPO_ROOT/app/src/main/assets/ssh"
FIXES="$REPO_ROOT/native/openssh-fixes.py"

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
[ -d "$TOOLCHAIN" ] || TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$(uname -m)"
SYSROOT="$TOOLCHAIN/sysroot"
CLANG_VER="$(ls "$TOOLCHAIN/lib/clang" | sort -V | tail -1)"

# arch -> NDK triple (API-qualified)
triple_for() {
    case "$1" in
        aarch64) echo "aarch64-linux-android$API" ;;
        armv7a)  echo "armv7a-linux-androideabi$API" ;;
        x86_64)  echo "x86_64-linux-android$API" ;;
        i686)    echo "i686-linux-android$API" ;;
        *) echo "unknown arch: $1" >&2; exit 2 ;;
    esac
}

# arch -> OpenSSL generic target. The android* targets are unusable here: their
# android_ndk() helper rewrites the compiler name to "24-clang" and fails.
openssl_target_for() {
    case "$1" in
        aarch64) echo "linux-aarch64" ;;
        armv7a)  echo "linux-armv4" ;;
        x86_64)  echo "linux-x86_64" ;;
        i686)    echo "linux-generic32" ;;
    esac
}

# arch -> autoconf host triple. Without --host, configure assumes a native build
# and tries to execute its test binaries, which fails for every Android ABI on
# an x86_64 build host.
host_for() {
    triple_for "$1" | sed "s/-android$API\$/-android/; s/-androideabi$API\$/-androideabi/"
}

# arch -> sysroot library directory (the one holding the static CRT objects)
apidir_for() {
    case "$1" in
        aarch64) echo "aarch64-linux-android" ;;
        armv7a)  echo "arm-linux-androideabi" ;;
        x86_64)  echo "x86_64-linux-android" ;;
        i686)    echo "i686-linux-android" ;;
    esac
}

fetch_sources() {
    mkdir -p "$WORK"
    if [ "${CLEAN:-0}" != 0 ]; then
        echo ">> --clean: discarding the cached build tree"
        rm -rf "$WORK/openssl-build-"* "$WORK/openssh-build-"* \
            "$WORK/ssl-"* "$WORK/stage-"*
    fi
    if [ ! -d "$WORK/openssl-$OPENSSL_VERSION" ]; then
        echo ">> fetching OpenSSL $OPENSSL_VERSION"
        curl -sSL --retry 3 -o "$WORK/openssl.tar.gz" \
            "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz"
        tar xzf "$WORK/openssl.tar.gz" -C "$WORK"
    fi
    if [ ! -d "$WORK/openssh-portable-$OPENSSH_VERSION" ]; then
        echo ">> fetching OpenSSH $OPENSSH_VERSION"
        # 9.9p2 -> V_9_9_P2
        local tag="V_$(printf '%s' "$OPENSSH_VERSION" | sed 's/p/_P/; s/\./_/g')"
        curl -sSL --retry 3 -o "$WORK/openssh.tar.gz" \
            "https://github.com/openssh/openssh-portable/archive/refs/tags/$tag.tar.gz"
        tar xzf "$WORK/openssh.tar.gz" -C "$WORK"
        local extracted
        extracted="$(find "$WORK" -maxdepth 1 -type d -name 'openssh-portable-*' | head -1)"
        [ -n "$extracted" ] && mv "$extracted" "$WORK/openssh-portable-$OPENSSH_VERSION"
    fi
}

# Writes a compiler wrapper for one architecture.
#
# Three jobs:
#  * force --target/--sysroot, so the generic OpenSSL config cannot silently
#    fall back to the host's glibc headers;
#  * supply a working *static* link line, because the NDK's clang driver cannot
#    locate its own crtbegin_static.o and ships no libgcc.a (compiler-rt provides
#    the builtins).
#
# A dynamic link is deliberately left entirely to the driver. It used to get the
# same crt objects and -nostartfiles as the static one, which silently dropped
# crtbegin_so.o and made every binary SIGSEGV under proot at load time. Do not
# "helpfully" add crt objects or -nostartfiles to the dynamic path.
#
# Compile-only invocations are passed through untouched because autotools
# feature probes rely on them.
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
# Generated by native/build-openssh.sh -- do not edit.
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
  # A static link needs the crt objects spelled out, because the NDK's clang
  # driver cannot locate its own crtbegin_static.o and ships no libgcc.a
  # (compiler-rt provides the builtins).
  exec "\$REALCC" "\$CRT1" "\$CRT2" "\$@" --target=$triple --sysroot="\$SYSROOT" \\
    -nostartfiles -L"\$STAGE" -lgcc \\
    -L"\$SYSROOT/usr/lib/\$APIARCH" -lc -lm -ldl \\
    -Wl,-z,max-page-size=$PAGE_SIZE
fi
# A dynamic link must be left to the driver. Injecting -nostartfiles here drops
# crtbegin_so.o, which supplies the .init_array framing/terminator and
# __dso_handle: the dynamic linker then runs off the end of .init_array and
# SIGSEGVs while loading the binary, before main. Do not strip -pie either, the
# driver links Android targets as PIE natively.
exec "\$REALCC" "\$@" --target=$triple --sysroot="\$SYSROOT" \\
  -Wl,-z,max-page-size=$PAGE_SIZE
EOF
    chmod +x "$stage/cc"
}

build_arch() {
    local arch="$1"
    local triple; triple="$(triple_for "$arch")"
    local ossl_target; ossl_target="$(openssl_target_for "$arch")"
    local apidir; apidir="$(apidir_for "$arch")"

    local stage="$WORK/stage-$arch"
    local sslprefix="$WORK/ssl-$arch"
    mkdir -p "$sslprefix"

    echo
    echo "=============================================================="
    echo "== $arch  ($triple, OpenSSL $ossl_target)"
    echo "=============================================================="

    write_wrapper "$arch" "$stage" "$triple" "$apidir"

    # --probe builds just native/ssh_probe.c through the wrapper above, so it
    # shares the client's crt objects, -nostartfiles, -static and page size. It
    # needs neither OpenSSL nor OpenSSH, so it costs a second per architecture.
    if [ "$PROBE_ONLY" = 1 ]; then
        echo ">> probe only (arch $arch)"
        mkdir -p "$REPO_ROOT/app/src/main/assets/ssh/$arch"
        # The flags OpenSSH's configure probed and enabled, minus the ones this
        # target rejects: configure only *links* its feature tests and never runs
        # them, so a flag can pass that check and still fault at runtime. The set
        # is per-architecture (-fzero-call-used-regs does not exist on armv7), so
        # it is rebuilt the same way configure would build it.
        local hard="-Os -pipe -fno-strict-aliasing"
        local hardsrc="$WORK/probe-flagtest-$arch.c"
        printf 'int main(void) { return 0; }\n' > "$hardsrc"
        local flag
        for flag in -D_FORTIFY_SOURCE=2 -ftrapv -fzero-call-used-regs=used \
                    -ftrivial-auto-var-init=zero -mretpoline -fno-builtin-memset \
                    -fstack-protector-strong; do
            if "$stage/cc" $hard "$flag" -c -o /dev/null "$hardsrc" >/dev/null 2>&1; then
                hard="$hard $flag"
            fi
        done
        echo "   flags for $arch:$hard"
        # shellcheck disable=SC2086
        "$stage/cc" $hard -o "$WORK/probe_hard-$arch" \
            "$REPO_ROOT/native/ssh_probe_hard.c"
        cp "$WORK/probe_hard-$arch" \
            "$REPO_ROOT/app/src/main/assets/ssh/$arch/probe_hard"
        echo "   -> assets/ssh/$arch/probe_hard" \
            "($(stat -c%s "$REPO_ROOT/app/src/main/assets/ssh/$arch/probe_hard") bytes)"
        return 0
    fi

    # ---------------------------------------------------------------- OpenSSL
    # libcrypto.a/libssl.a do not depend on the final link mode, so a cached
    # build stays valid when only the linking changes. OpenSSL is also by far the
    # slowest stage, which makes this the difference between minutes and hours
    # across four architectures.
    local stamp_file="$sslprefix/.stamp"
    # Reuse whenever a usable libcrypto.a is already installed. A missing stamp
    # only means the build predates stamping, so adopt it instead of throwing a
    # good OpenSSL away: the archives do not depend on how the client is linked.
    openssl_reusable() {
        [ "${CLEAN:-0}" = 0 ] || return 1
        { [ -f "$sslprefix/lib/libcrypto.a" ] ||
          [ -f "$sslprefix/lib64/libcrypto.a" ]; } || return 1
        if [ ! -f "$stamp_file" ]; then
            echo "   (adopting unstamped OpenSSL in $sslprefix)"
            echo "$OPENSSL_VERSION" > "$stamp_file"
            return 0
        fi
        [ "$(cat "$stamp_file")" = "$OPENSSL_VERSION" ]
    }
    if openssl_reusable; then
        echo ">> OpenSSL $OPENSSL_VERSION: reusing cached build"
    else
    echo ">> OpenSSL $OPENSSL_VERSION (building)"
    rm -rf "$WORK/openssl-build-$arch"
    cp -r "$WORK/openssl-$OPENSSL_VERSION" "$WORK/openssl-build-$arch"
    (
        cd "$WORK/openssl-build-$arch"
        perl ./Configure "$ossl_target" \
            --prefix="$sslprefix" \
            --openssldir="$sslprefix/ssl" \
            --libdir=lib \
            no-tests no-asm no-shared no-engine no-docs no-module no-legacy \
            CC="$stage/cc" \
            AR="$TOOLCHAIN/bin/llvm-ar" \
            RANLIB="$TOOLCHAIN/bin/llvm-ranlib" \
            >"$WORK/ssl-config-$arch.log" 2>&1
        make -j"$JOBS" >"$WORK/ssl-build-$arch.log" 2>&1 || true
        make install_sw >>"$WORK/ssl-build-$arch.log" 2>&1 || true
    )
    # Older runs may have installed into lib64 (the Red Hat convention the
    # generic x86_64 target defaults to), so accept either.
    if [ -f "$sslprefix/lib64/libcrypto.a" ] && [ ! -f "$sslprefix/lib/libcrypto.a" ]; then
        echo "   (OpenSSL installed into lib64; using that)"
    fi
    { [ -f "$sslprefix/lib/libcrypto.a" ] || [ -f "$sslprefix/lib64/libcrypto.a" ]; } || {
        echo "!! OpenSSL failed for $arch; see $WORK/ssl-build-$arch.log" >&2
        tail -20 "$WORK/ssl-build-$arch.log" >&2
        return 1
    }
    echo "$OPENSSL_VERSION" > "$stamp_file"
    fi

    # ---------------------------------------------------------------- OpenSSH
    echo ">> OpenSSH $OPENSSH_VERSION (ssh, ssh-keygen)"
    rm -rf "$WORK/openssh-build-$arch"
    cp -r "$WORK/openssh-portable-$OPENSSH_VERSION" "$WORK/openssh-build-$arch"
    (
        cd "$WORK/openssh-build-$arch"
        # Release tarballs ship configure with an older mtime than configure.ac,
        # which makes autoconf demand a regenerate. autoreconf is not available
        # here, so mark the generated files as current.
        touch configure config.h.in aclocal.m4 2>/dev/null || true
        ./configure --prefix="$WORK/ssh-$arch" \
            --host="$(host_for "$arch")" \
            --sysconfdir="$WORK/ssh-$arch/etc" \
            --with-ssl-dir="$sslprefix" \
            --without-zlib --without-pam \
            --disable-strip \
            CC="$stage/cc" \
            CFLAGS="-Os" \
            >"$WORK/ssh-config-$arch.log" 2>&1
        # Our explicit_bzero() replacement, compiled for this architecture.
        "$stage/cc" -Os -fPIE -c -o ssh_compat.o "$REPO_ROOT/native/ssh_compat.c"
        REDUCTERM_COMPAT_OBJ=ssh_compat.o python3 "$FIXES" .
        # Every probe passes yet the client still dies before printing anything,
        # and a ptrace'd child leaves no tombstone, so the client narrates its own
        # progress with raw write(2) markers. REDUCTERM_TRACE=0 turns them off.
        if [ "${REDUCTERM_TRACE:-1}" != 0 ]; then
            python3 "$REPO_ROOT/native/mark-client.py" ssh.c
        fi
        make -j"$JOBS" ssh ssh-keygen >"$WORK/ssh-build-$arch.log" 2>&1 || true
    )
    [ -f "$WORK/openssh-build-$arch/ssh" ] || {
        echo "!! OpenSSH failed for $arch; see $WORK/ssh-build-$arch.log" >&2
        grep -m5 -E 'error:|undefined reference' "$WORK/ssh-build-$arch.log" >&2 || true
        return 1
    }

    # ----------------------------------------------------------------- install
    local outdir="$ASSETS/$arch"
    mkdir -p "$outdir"
    for prog in ssh ssh-keygen; do
        cp "$WORK/openssh-build-$arch/$prog" "$outdir/$prog"
        "$TOOLCHAIN/bin/llvm-strip" "$outdir/$prog" || true
        chmod 755 "$outdir/$prog"
        printf '   %-46s %8s bytes\n' "$outdir/$prog" "$(stat -c%s "$outdir/$prog")"
    done
}

fetch_sources
for arch in "${ARCHS[@]}"; do
    build_arch "$arch"
done

echo
echo ">> all requested architectures built."
echo ">> bundled clients:"
find "$ASSETS" -type f -name 'ssh*' | sort | while read -r f; do
    printf '   %-56s %8s bytes\n' "$f" "$(stat -c%s "$f")"
done
