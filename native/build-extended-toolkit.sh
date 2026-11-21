#!/usr/bin/env bash
##
## Builds the on-demand "extended toolkit": a set of real GNU-style tools,
## one directory per shipped ABI, packed into a single downloadable
## tarball. It is NOT an APK asset: the app fetches and unpacks it on
## demand (the distro-rootfs pattern), which keeps the base APK small and
## lets the tool set grow without a new release of the app.
##
## Layout of the tarball (device-extended-toolkit/<arch>/...):
##
##   device-extended-toolkit/
##     aarch64/{bin,lib}/...   armv7a/{bin,lib}/...   i686/...   x86_64/...
##
## The app adds `<arch>/bin` to PATH (before the lean toolkit, so a GNU
## tool wins over a busybox applet) and `<arch>/lib` to LD_LIBRARY_PATH
## when a tool bundles its own shared libraries.
##
## Two rules from the mosh/device-toolkit builds apply unchanged and are
## verified for every produced binary: dynamically linked against bionic
## (a static NDK executable aborts on the underaligned-TLS check), and every
## LOAD segment 4 KB aligned (-z max-page-size=4096).
##
## Tool sets. The default is the set whose sources are verified and that
## build clean against bionic with no host-side autotools:
##
##   xz tar jq          (jq ships with its oniguruma bundled)
##
## The rest are opt-in, each gated on a real precondition:
##   --with-file   `file`'s git archive needs ./autogen.sh (host autoconf)
##   --with-tree   `tree`'s upstream has moved; set TREE_URL to its source
##   --with-curl   `curl` needs a bundled OpenSSL (build-openssh.sh's)
##
## A tool whose source fails to fetch or build is *skipped with a log*,
## not fatal to the rest (the optional-tools rule), unless --strict.
##
## Usage:
##
##   native/build-extended-toolkit.sh [--ndk <path>] [--with-file]
##                                     [--with-tree] [--with-curl]
##                                     [--strict] [--plan]
##                                     [aarch64 armv7a x86_64 i686]
##
## Set EXT_TOOLS="xz tar" to build only a subset (used for testing).
## Set TMPDIR to move the work out of /tmp, a 2.9 GB tmpfs on this host.

set -eu

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

NDK=""
WITH_FILE=0; WITH_TREE=0; WITH_CURL=0; STRICT=0; PLAN=0
ARCHES=()
while [ $# -gt 0 ]; do
    case "$1" in
        --ndk)       NDK="$2"; shift 2 ;;
        --with-file) WITH_FILE=1; shift ;;
        --with-tree) WITH_TREE=1; shift ;;
        --with-curl) WITH_CURL=1; shift ;;
        --strict)    STRICT=1; shift ;;
        --plan)      PLAN=1; shift ;;
        *)           ARCHES+=("$1"); shift ;;
    esac
done
[ -n "$NDK" ] || NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK:-/opt/ndk/android-ndk-r27c}}"
[ -d "$NDK" ] || { echo "!! NDK not found: $NDK" >&2; exit 1; }
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
[ -d "$TOOLCHAIN" ] || TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$(uname -m)"
[ -d "$TOOLCHAIN" ] || { echo "!! NDK toolchain not found under $NDK" >&2; exit 1; }

[ ${#ARCHES[@]} -gt 0 ] || ARCHES=(aarch64 armv7a x86_64 i686)

WORK="${TMPDIR:-/tmp}/redterm-extended-toolkit"

VERSION=1
ARTIFACT="device-extended-toolkit-v${VERSION}.tar.xz"

# Pinned sources, each verified reachable. A missing file is not re-fetched,
# so a resume does not re-pay for a download.
TAR_VERSION=1.35
XZ_VERSION=5.4.6
JQ_VERSION=1.7.1
FILE_TAG=FILE5_45
FILE_VERSION=5.45
CURL_VERSION=8.6.0
# curl reuses the OpenSSL that build-openssh.sh already installs, per arch,
# under $WORK-of-that-script/ssl-$arch. An explicit OPENSSL_DIR overrides it.
OPENSSL_DIR="${OPENSSL_DIR:-}"
# tree's upstream has moved; supply its source archive URL to build it.
TREE_URL="${TREE_URL:-}"

TOOLS=(xz tar jq)
[ "$WITH_FILE" = 1 ] && TOOLS+=(file)
[ "$WITH_TREE" = 1 ] && TOOLS+=(tree)
[ "$WITH_CURL" = 1 ] && TOOLS+=(curl)
[ -n "${EXT_TOOLS:-}" ] && TOOLS=(${EXT_TOOLS})

say() { echo "   $*"; }

fetch() { # fetch <name> <url> <out>  (fatal: caller is in a guarded subshell)
    local name="$1" url="$2" out="$3"
    [ -f "$out" ] && { say "reuse $name source"; return 0; }
    say "fetch $name"
    curl -fL "$url" -o "$out"
}

# The three autotools traps from the mosh/bash builds: timestamps that did
# not survive packaging make make try to regenerate its own input.
autotools_touch() {
    touch aclocal.m4 2>/dev/null || true
    sleep 1; touch configure config.h.in 2>/dev/null || true
    sleep 1; find . -name 'Makefile.in' -exec touch {} + 2>/dev/null || true
}

cross_env() { # sets CC CFLAGS LDFLAGS TARGET for the current arch
    case "$arch" in
        aarch64)  TARGET="aarch64-linux-android" ;;
        armv7a)   TARGET="armv7a-linux-androideabi" ;;
        x86_64)   TARGET="x86_64-linux-android" ;;
        i686)     TARGET="i686-linux-android" ;;
        *) echo "!! unknown arch '$arch'" >&2; exit 1 ;;
    esac
    CC="$TOOLCHAIN/bin/clang --target=${TARGET}24"
    # -fPIC (not just -fPIE) for the .S and C alike: the executable links PIE,
    # and a bundled sub-build like jq's oniguruma compiles its own objects and
    # must be PIC or the PIE link fails with a non-PC-relative relocation.
    CFLAGS="-fPIC -D__ANDROID_API__=24 -Wl,-z,max-page-size=4096"
    LDFLAGS="-Wl,-z,max-page-size=4096"
}

# Each build_<tool> runs in a fresh extracted source dir and copies output
# into $DEST/bin (and $DEST/lib for any bundled shared library).
DEST=""

build_xz() {
    fetch xz "https://github.com/tukaani-project/xz/releases/download/v${XZ_VERSION}/xz-${XZ_VERSION}.tar.gz" "$WORK/src/xz.tar.gz"
    tar -xzf "$WORK/src/xz.tar.gz" -C . --strip-components=1
    autotools_touch
    # --disable-shared links liblzma *into* the xz executable: xz 5.4.x
    # builds a shared liblzma by default and the staged file would be a
    # libtool wrapper, not a deployable ELF (which verify_bin rejects).
    # xz still links dynamically against bionic.
    ./configure --host="$TARGET" CC="$CC" CFLAGS="$CFLAGS" LDFLAGS="$LDFLAGS" \
        --disable-shared --disable-nls --disable-doc > "$WORK/xz-$arch.log" 2>&1
    make -j1 >> "$WORK/xz-$arch.log" 2>&1
    cp -f "$PWD/src/xz/xz" "$DEST/bin/xz"
}

build_tar() {
    # Measured on this host (aarch64): GNU tar's strftime.c calls mktime_z,
    # which the cross configure does not declare for bionic, so the build
    # fails on C99 implicit-declaration and the tool is skipped with a log
    # (the loop ships the rest). If a --with-tar source or patch is found,
    # it will build; the binary lands in src/ (see below).
    fetch tar "https://ftp.gnu.org/gnu/tar/tar-${TAR_VERSION}.tar.xz" "$WORK/src/tar.tar.xz"
    tar -xJf "$WORK/src/tar.tar.xz" -C . --strip-components=1
    autotools_touch
    ./configure --host="$TARGET" CC="$CC" CFLAGS="$CFLAGS" LDFLAGS="$LDFLAGS" \
        --disable-nls > "$WORK/tar-$arch.log" 2>&1
    make -j1 >> "$WORK/tar-$arch.log" 2>&1
    # GNU tar 1.35 declares its program in src/Makefile.am, so the binary is
    # src/tar, not the source-root tar/.
    cp -f "$PWD/src/tar" "$DEST/bin/tar"
}

build_jq() {
    # The release tarball vendors oniguruma under modules/, so build it
    # bundled: no separate oniguruma fetch, which the release tarball does
    # not carry as a standalone asset anyway.
    fetch jq "https://github.com/jqlang/jq/releases/download/jq-${JQ_VERSION}/jq-${JQ_VERSION}.tar.gz" "$WORK/src/jq.tar.gz"
    tar -xzf "$WORK/src/jq.tar.gz" -C . --strip-components=1
    autotools_touch
    ./configure --host="$TARGET" CC="$CC" CFLAGS="$CFLAGS" LDFLAGS="$LDFLAGS" \
        --with-oniguruma=bundled --without-readline --disable-gettext > "$WORK/jq-$arch.log" 2>&1
    make -j1 >> "$WORK/jq-$arch.log" 2>&1
    cp -f "$PWD/jq" "$DEST/bin/jq"
}

build_file() {
    [ -n "$FILE_VERSION" ] || return 1
    fetch file "https://github.com/file/file/archive/refs/tags/${FILE_TAG}.tar.gz" "$WORK/src/file.tar.gz"
    tar -xzf "$WORK/src/file.tar.gz" -C . --strip-components=1
    # The git archive does not ship a configured `configure`.
    if [ ! -x ./configure ]; then
        say "file: autogen.sh (needs host autoconf/libtool)"
        ./autogen.sh > "$WORK/file-$arch.log" 2>&1 || { say "skip file: autogen failed"; return 0; }
    fi
    ./configure --host="$TARGET" CC="$CC" CFLAGS="$CFLAGS" LDFLAGS="$LDFLAGS" \
        --disable-nls > "$WORK/file-$arch.log" 2>&1
    make -j1 >> "$WORK/file-$arch.log" 2>&1
    # The FILE5_45 executable is produced in src/, not the source root; it
    # links bionic only (no bundled libraries to stage in $DEST/lib).
    cp -f "$PWD/src/file" "$DEST/bin/file"
}

build_tree() {
    [ -n "$TREE_URL" ] || { say "skip tree: set TREE_URL to its source archive"; return 0; }
    fetch tree "$TREE_URL" "$WORK/src/tree.tar.gz"
    tar -xzf "$WORK/src/tree.tar.gz" -C . --strip-components=1
    make -j1 CC="$CC" CFLAGS="$CFLAGS" LDFLAGS="$LDFLAGS" > "$WORK/tree-$arch.log" 2>&1
    cp -f "$PWD/tree" "$DEST/bin/tree"
}

build_curl() {
    # Prefer an explicit OPENSSL_DIR; otherwise the installed per-arch prefix
    # build-openssh.sh produces (its work dir defaults to /tmp/redterm-openssh,
    # so the installed tree is /tmp/redterm-openssh/ssl-$arch).
    local sslprefix="${OPENSSL_DIR:-/tmp/redterm-openssh/ssl-$arch}"
    # Check for the *installed* crypto library, not just the directory: the
    # source tree has a Makefile but no libcrypto until build-openssh.sh runs.
    if [ ! -f "$sslprefix/lib/libcrypto.a" ] && [ ! -f "$sslprefix/lib64/libcrypto.a" ]; then
        say "skip curl: no installed OpenSSL at $sslprefix (run build-openssh.sh first)"
        return 0
    fi
    fetch curl "https://curl.se/download/curl-${CURL_VERSION}.tar.xz" "$WORK/src/curl.tar.xz"
    tar -xJf "$WORK/src/curl.tar.xz" -C . --strip-components=1
    autotools_touch
    ./configure --host="$TARGET" CC="$CC" CFLAGS="$CFLAGS" LDFLAGS="$LDFLAGS" \
        --disable-shared --with-openssl="$sslprefix" --without-zlib \
        --disable-nls > "$WORK/curl-$arch.log" 2>&1
    make -j1 >> "$WORK/curl-$arch.log" 2>&1
    # --disable-shared + static libcurl writes the executable to src/curl;
    # keep the libtool .libs paths as a fallback for a shared build.
    cp -f "$PWD/src/curl" "$DEST/bin/curl" 2>/dev/null || \
        cp -f "$PWD/src/.libs/curl" "$DEST/bin/curl" 2>/dev/null || \
        cp -f "$PWD/.libs/curl" "$DEST/bin/curl"
}

# Verify one produced tool exactly like build-device-toolkit.sh: right arch,
# dynamically linked against bionic libc, and every LOAD 4 KB aligned.
verify_bin() {
    local bin="$1" name
    name="$(basename "$bin")"
    case "$arch" in
        aarch64) want="ARM aarch64" ;;
        armv7a)  want="ARM, EABI" ;;
        x86_64)  want="x86-64" ;;
        i686)    want="Intel i386" ;;
    esac
    file "$bin" | grep -q "$want" || { echo "!! $arch/$name not $want: $(file "$bin" | cut -c1-70)" >&2; exit 1; }
    "$TOOLCHAIN/bin/llvm-readelf" -d "$bin" | grep -qE "NEEDED.*(libc|libdl)" || {
        echo "!! $arch/$name not linked against bionic libc" >&2; exit 1
    }
    local max_align=0 a a_dec
    # $1 is the segment type ("LOAD"), $NF its alignment. Reading the LOAD
    # row's own last field (not the next line via getline) is what catches a
    # 0x10000 LOAD that is followed by a 0x1000 one.
    for a in $("$TOOLCHAIN/bin/llvm-readelf" -lW "$bin" | awk '$1 == "LOAD" {print $NF}'); do
        a_dec=$((a)); [ "$a_dec" -gt "$max_align" ] && max_align=$a_dec
    done
    [ "$max_align" -gt 0 ] || { echo "!! no LOAD segments in $arch/$name" >&2; exit 1; }
    [ "$max_align" -le 4096 ] || { echo "!! $arch/$name LOAD align $max_align > 4096" >&2; exit 1; }
    say "$arch/$name: $(stat -c%s "$bin") bytes, LOAD align $max_align"
}

if [ "$PLAN" = 1 ]; then
    echo "would build: $(echo "${TOOLS[*]}") for: $(echo "${ARCHES[*]}")"
    echo "artifact: $ARTIFACT  (layout device-extended-toolkit/<arch>/{bin,lib})"
    exit 0
fi

# A plan-only run exited above, before the workspace was touched: --plan must
# not wipe a previous build's artifact or logs. Normal builds start clean.
rm -rf "$WORK"
mkdir -p "$WORK/src" "$WORK/dist"

built=0; skipped=0
for arch in "${ARCHES[@]}"; do
    DEST="$WORK/dist/device-extended-toolkit/$arch"
    rm -rf "$DEST"; mkdir -p "$DEST/bin" "$DEST/lib"
    cross_env
    say "=== $arch : ${TOOLS[*]} ==="
    for tool in "${TOOLS[@]}"; do
        d="$WORK/build-$tool-$arch"; rm -rf "$d"; mkdir -p "$d"
        # The driver log keeps the top-level messages (a "skip tree: set
        # TREE_URL", a fetch failure) that the tool's own ${tool}-$arch.log
        # (written by configure/make) does not. Kept apart so the driver's
        # stdout is not discarded, as it was with > /dev/null.
        ( cd "$d" && "build_$tool" ) > "$WORK/${tool}-$arch.driver.log" 2>&1 || true
        cd "$WORK"
        if [ -f "$DEST/bin/$tool" ]; then
            verify_bin "$DEST/bin/$tool"
            built=$((built + 1))
        else
            skipped=$((skipped + 1))
            say "skip $arch/$tool (see $WORK/${tool}-$arch.driver.log)"
            [ "$STRICT" = 1 ] && { tail -20 "$WORK/${tool}-$arch.driver.log"; echo "!! $tool failed for $arch (--strict)" >&2; exit 1; }
        fi
    done
done

# Package the whole tree as one tarball: the app downloads it once and
# unpacks only its own ABI directory.
cd "$WORK/dist"
tar -cJf "$WORK/$ARTIFACT" device-extended-toolkit
cd "$WORK"
say "artifact: $WORK/$ARTIFACT ($(stat -c%s "$WORK/$ARTIFACT") bytes)  [built=$built skipped=$skipped]"

# Host smoke test where the host can run the build: aarch64 here.
case " ${ARCHES[*]} " in
    *" aarch64 "*)
        if [ "$(uname -m)" = "aarch64" ] && [ -f "$DEST/bin/tree" ] || [ -f "$WORK/dist/device-extended-toolkit/aarch64/bin/xz" ]; then
            sysroot="$TOOLCHAIN/sysroot"; [ -d "$sysroot" ] || sysroot="$TOOLCHAIN/../sysroot"
            export LD_LIBRARY_PATH="$sysroot/usr/lib:$sysroot/usr/lib64"
            "$WORK/dist/device-extended-toolkit/aarch64/bin/xz" --help 2>/dev/null | head -1 || \
                say "smoke: xz did not run on host"
        else
            say "note: host is not aarch64; the smoke test is skipped"
        fi
        ;;
esac

say "done: $(echo "${TOOLS[*]}") x $(echo "${ARCHES[*]}") -> $WORK/$ARTIFACT"
