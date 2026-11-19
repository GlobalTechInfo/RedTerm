#!/usr/bin/env bash
##
## Builds the device-shell toolkit: a dynamic busybox and a dynamic bash, one
## pair per shipped ABI, into app/src/main/assets/device-shell/<arch>/.
##
## The device shell (SessionDescriptor.Device) runs the phone's own
## /system/bin/sh with no install at all. The toolkit is an *optional*
## upgrade on top of it: when present, the session starts bash instead of
## sh and the user has awk, find, ps and friends. It is extracted into app
## storage and executed through proot's -L loader, exactly like the ssh
## binaries, because app storage is noexec on Android 12+.
##
## The two rules from the mosh build apply unchanged, and both are
## verified for every binary here:
##
##   - dynamically linked against bionic, never static. A static NDK
##     executable aborts on start with "TLS segment is underaligned" on
##     ARM64 bionic, and the 16 KB-era NDK makes the static path a dead
##     end for proot-executed binaries on top of that.
##
##   - every LOAD segment stays 4 KB aligned. proot loads the binary and
##     a 16 KB alignment breaks 4 KB-page devices; -z max-page-size=4096
##     is what keeps it.
##
## Usage:
##
##   native/build-device-toolkit.sh [--ndk <path>] [aarch64 armv7a x86_64 i686]
##
## With no arguments every shipped ABI is built. Set TMPDIR to move the
## work out of /tmp, which is a 2.9 GB tmpfs on this host.

set -eu

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSET_DIR="$REPO_ROOT/app/src/main/assets/device-shell"

NDK=""
ARCHES=()
while [ $# -gt 0 ]; do
    case "$1" in
        --ndk) NDK="$2"; shift 2 ;;
        *) ARCHES+=("$1"); shift ;;
    esac
done
[ -n "$NDK" ] || NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK:-/opt/ndk/android-ndk-r27c}}"
[ -d "$NDK" ] || { echo "!! NDK not found: $NDK" >&2; exit 1; }
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
[ -d "$TOOLCHAIN" ] || TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$(uname -m)"
[ -d "$TOOLCHAIN" ] || { echo "!! NDK toolchain not found under $NDK" >&2; exit 1; }

[ ${#ARCHES[@]} -gt 0 ] || ARCHES=(aarch64 armv7a x86_64 i686)

WORK="${TMPDIR:-/tmp}/redterm-toolkit"
rm -rf "$WORK"
mkdir -p "$WORK"

BUSYBOX_VERSION=1.36.1
BASH_VERSION=5.2
BUSYBOX_TAR="$WORK/busybox-$BUSYBOX_VERSION.tar.bz2"
BASH_TAR="$WORK/bash-$BASH_VERSION.tar.gz"

# Pinned sources with the checksums published beside them, so a mirror that
# moves a file is noticed here rather than in a session that runs a bash
# somebody else built.
curl -fsSL "https://busybox.net/downloads/busybox-$BUSYBOX_VERSION.tar.bz2" -o "$BUSYBOX_TAR"
curl -fsSL "https://busybox.net/downloads/busybox-$BUSYBOX_VERSION.tar.bz2.sha256" -o "$BUSYBOX_TAR.sha"
curl -fsSL "https://ftp.gnu.org/gnu/bash/bash-$BASH_VERSION.tar.gz" -o "$BASH_TAR"
curl -fsSL "https://ftp.gnu.org/gnu/bash/bash-$BASH_VERSION.tar.gz.sig" -o "$BASH_TAR.sha" 2>/dev/null || true

verify() {
    local tarball="$1"
    # busybox publishes the digest bare; a GPG .sig has no digest line, and an
    # empty check is a pass for nothing, so only a non-empty digest verifies.
    local expect
    expect="$(grep -oE '[0-9a-f]{64}' "$tarball.sha" 2>/dev/null | head -n1 || true)"
    if [ -n "$expect" ]; then
        [ "$(sha256sum "$tarball" | awk '{print $1}')" = "$expect" ] || {
            echo "!! checksum mismatch for $tarball" >&2
            exit 1
        }
    else
        echo "!! no digest available for $tarball; refusing to build" >&2
        exit 1
    fi
}
verify "$BUSYBOX_TAR"
# The .sig is a GPG signature, not a digest. Enforce it when the key is
# available; a key that is not on this host is not a source problem, so it
# is noted rather than treated as a failure.
if command -v gpg > /dev/null && gpg --verify "$BASH_TAR.sha" "$BASH_TAR" 2> "$WORK/bash-sig.log"; then
    echo "   bash tarball signature verified"
elif command -v gpg > /dev/null; then
    grep -qE "No public key|key .* not available" "$WORK/bash-sig.log" || {
        echo "!! bash signature check failed:" >&2
        cat "$WORK/bash-sig.log" >&2
        exit 1
    }
    echo "note: bash signing key is not on this host; the tarball is from ftp.gnu.org only"
else
    echo "note: no gpg on this host; the bash tarball is from ftp.gnu.org only"
fi

for arch in "${ARCHES[@]}"; do
    case "$arch" in
        aarch64)  TARGET="aarch64-linux-android" ;;
        armv7a)   TARGET="armv7a-linux-androideabi" ;;
        x86_64)   TARGET="x86_64-linux-android" ;;
        i686)     TARGET="i686-linux-android" ;;
        *) echo "!! unknown arch '$arch'" >&2; exit 1 ;;
    esac

    CC="$TOOLCHAIN/bin/clang --target=${TARGET}24"
    # -fPIE for the assembly as well: the NDK clang driver compiles the C
    # sources as position-independent (the executable links PIE) but does not
    # add the flag for .S files, and i386 relocations (R_386_32) refuse a
    # non-PIC object inside a PIE link while x86_64's equivalent relaxes,
    # so only the 32-bit x86 build fails, on the SHA-NI hash table.
    CFLAGS="-fPIE -D__ANDROID_API__=24 -Wl,-z,max-page-size=4096"
    LDFLAGS="-Wl,-z,max-page-size=4096"

    echo "=== busybox $arch ==="
    bdir="$WORK/busybox-$arch"
    rm -rf "$bdir"
    mkdir -p "$bdir"
    tar -xjf "$BUSYBOX_TAR" -C "$bdir" --strip-components=1
    cd "$bdir"
    # defconfig is built for glibc; bionic is missing whole families of the
    # calls defconfig's applets make. Every applet that needs one of them is
    # off here. The list is the output of a full `make -k` pass, not a guess:
    #  - kernel headers (sys/kd.h, linux/*.h): the vt and filesystem tools
    #  - glibc-only functions: gethostid, getlogin_r, syncfs, fallocate,
    #    glob (hush's fallback), addmntent/hasmntopt, the SysV msg/sem/shm
    #    control calls, seedrng's getrandom, the minix tools, swaponoff's
    #    bit ops, ether_hostton, adjtimex, conspy, runlevel
    # HUSH goes off for the glob() reason: its fallback path calls libc's
    # glob unconditionally, and bionic has no glob.h. The toolkit's shells
    # are bash and the system sh, not hush, so nothing loses a shell. ASH
    # stays: its globbing is the internal implementation (ASH_INTERNAL_GLOB).
    make defconfig > /dev/null
    for c in LOADFONT SETFONT LOADKMAP OPENVT CHVT DEALLOCVT DUMPKMAP \
             FGCONSOLE KBD_MODE RESET RESIZE SETCONSOLE SETKEYCODES \
             SETLOGCONS SHOWKEY HOSTID LOGNAME SU SYNC FALLOCATE \
             FATATTR FATLABEL UDHCP6D HALT POWEROFF REBOOT INIT \
             FEATURE_WTMP TC ADJTIMEX CONSPY RUNLEVEL SEEDRNG \
             ETHER_WAKE IFCONFIG IFUP IFDOWN HUSH LOGREAD SYSLOGD \
             FSCK_MINIX MKFS_MINIX IPCRM IPCS MOUNT UMNOUNT \
             SWAPON SWAPOFF SHELL_HUSH ARP IFENSLAVE NSLOOKUP FEATURE_NSLOOKUP_BIG \
             TLS; do
        # Plain "# CONFIG_X is not set", with no annotation: kconfig's parser
        # recognises only the bare form, and an annotated line reads as a
        # *missing* symbol that the pre-build auto-update re-adds.
        sed -i "s/^CONFIG_$c=y/# CONFIG_$c is not set/" .config
    done
    if [ "$arch" = i686 ]; then
        # 32-bit x86-specific build breaks, none of which the other ABIs hit:
        #  - PSTM's comba inline assembly needs more registers than i386 has.
        #    TLS itself can be turned off, but SSL_CLIENT and
        #    FEATURE_WGET_HTTPS both `select` it, so kconfig forces it back
        #    on no matter how often it is commented out; the two selectors go,
        #    and TLS follows.
        #  - The x86-32 SHA-NI .S files (always in lib-y, content guarded by
        #    ENABLE_..._HWACCEL) assemble position-independent on nothing, and
        #    their K256 table gives R_386_32 relocations that the PIE link
        #    refuses. Disabling the two HWACCEL options empties the .S files;
        #    the portable C hash in hash_md5_sha.c covers it.
        for c in SSL_CLIENT FEATURE_WGET_HTTPS TLS SHA1_HWACCEL SHA256_HWACCEL; do
            sed -i "s/^CONFIG_$c=y/# CONFIG_$c is not set/" .config
        done
    fi
    # i386's FAST_FUNC carries stdcall, and re-declaring a bionic-provided
    # function with a different calling convention is a hard error. The
    # attribute buys nothing on a phone, so the i386 line becomes the plain
    # fallback. No other ABI matches this text, so it is a no-op for them.
    sed -i 's/^#  define FAST_FUNC __attribute__((regparm(3),stdcall))$/#  define FAST_FUNC/' include/platform.h || true
    # Dynamic, never static: the static gate is a dead end (see the header).
    sed -i 's/^CONFIG_STATIC=y/# CONFIG_STATIC is not set by default/' .config
    # STRIP is the NDK's, not the host's: an aarch64 host's binutils cannot
    # recognise an x86_64 ELF ("Unable to recognise the architecture"), and
    # the build dies after a link that had already succeeded.
    # CC is a command-line variable, which beats the Makefile's own
    # CC = $(CROSS_COMPILE)gcc no matter what the environment holds, so
    # every ABI is driven by the NDK clang. (An environment-only CC is
    # silently lost there; that is how one manual resume once produced a
    # host-architecture binary.)
    make -e -j1 -k CROSS_COMPILE= \
        STRIP="$TOOLCHAIN/bin/llvm-strip" \
        CC="$CC" CFLAGS="$CFLAGS" LDFLAGS="$LDFLAGS" busybox > "$WORK/busybox-$arch.log" 2>&1 \
        || { tail -20 "$WORK/busybox-$arch.log"; echo "!! busybox build failed for $arch" >&2; exit 1; }

    echo "=== bash $arch ==="
    gdir="$WORK/bash-$arch"
    rm -rf "$gdir"
    mkdir -p "$gdir"
    tar -xzf "$BASH_TAR" -C "$gdir" --strip-components=1
    cd "$gdir"
    # The three autotools traps from the mosh build: timestamps that did not
    # survive packaging make make try to regenerate its own input.
    touch aclocal.m4 || true
    sleep 1; touch configure config.h.in 2>/dev/null || true
    sleep 1; find . -name 'Makefile.in' -exec touch {} + 2>/dev/null || true
    # mkbuiltins and friends are host tools bash 5.2 still writes in K&R
    # style, and a K&R call is a constraint violation under the C99+ that
    # modern host compilers default to. Building the host tools in -std=gnu89
    # is how this code has built for thirty years.
    #
    # Three source patches for bionic, applied to the pinned tree before
    # configure (Makefile-level patches go after it: configure is what
    # creates the Makefile):
    #  - tparam.c: write() with no <unistd.h> (the include it had was inside
    #    a guard the build defines away);
    #  - locale.c: mblen is API 26+, and bash's configure probes for mbrlen,
    #    which exists earlier, then uses mblen unguarded under multibyte;
    #  - bashline.c: getgrent and friends are API 26+, so group completion
    #    is a no-op on this target.
    python3 - <<'EOF'
import re
t = open('lib/termcap/tparam.c').read()
t = t.replace('#endif /* not HAVE_CONFIG_H */\n\n#include "ltcap.h"',
              '#endif /* not HAVE_CONFIG_H */\n\n#include <unistd.h>\n#include "ltcap.h"')
open('lib/termcap/tparam.c','w').write(t)
l = open('locale.c').read()
l = l.replace('#include "bashansi.h"\n',
              '#include "bashansi.h"\n\n'
              '#if defined(__ANDROID__) && __ANDROID_API__ < 26\n'
              'int mblen (const char *s, size_t n)\n'
              '{\n'
              '  (void) s;\n'
              '  (void) n;\n'
              '  return 1;\n'
              '}\n'
              '#endif\n', 1)
open('locale.c','w').write(l)
b = open('bashline.c').read()
b = b.replace('''      setgrent ();
''', '''#if !defined(__ANDROID__)
      setgrent ();
#endif
''')
b = b.replace('''  while (grent = getgrent ())
    {
      if (gnamelen == 0 || (STREQN (gname, grent->gr_name, gnamelen)))
        break;
    }
''', '''#if defined(__ANDROID__)
  /* bionic has no group database; group completion is a no-op there. */
  grent = 0;
#else
  while (grent = getgrent ())
    {
      if (gnamelen == 0 || (STREQN (gname, grent->gr_name, gnamelen)))
        break;
    }
#endif
''')
b = b.replace('''      endgrent ();
      return ((char *)NULL);''', '''#if !defined(__ANDROID__)
      endgrent ();
#endif
      return ((char *)NULL);''')
open('bashline.c','w').write(b)
EOF
    CC="$CC" CFLAGS="$CFLAGS" LDFLAGS="$LDFLAGS" \
    CC_FOR_BUILD=gcc \
    ./configure --host="${TARGET%24}" --prefix=/usr > "$WORK/bash-$arch-conf.log" 2>&1 || {
        tail -20 "$WORK/bash-$arch-conf.log"
        echo "!! bash configure failed for $arch" >&2
        exit 1
    }
    # The support tool's object (signames.o) must not be linked into the
    # shell: it and trap.c both hold the generated signal_names table, and
    # the link dies on the duplicate. Only now, once configure has written
    # the Makefile.
    sed -i 's|^SIGNAMES_O = signames.o$|SIGNAMES_O =|' Makefile
    make -j1 CFLAGS_FOR_BUILD="-std=gnu89" > "$WORK/bash-$arch.log" 2>&1 || {
        tail -20 "$WORK/bash-$arch.log"
        echo "!! bash build failed for $arch" >&2
        exit 1
    }

    echo "=== placing $arch ==="
    mkdir -p "$ASSET_DIR/$arch"
    install -m 0755 "$bdir/busybox" "$ASSET_DIR/$arch/busybox"
    install -m 0755 "$gdir/bash" "$ASSET_DIR/$arch/bash"

    for bin in "$ASSET_DIR/$arch/busybox" "$ASSET_DIR/$arch/bash"; do
        name="$(basename "$bin")"
        # The architecture itself is checked, not assumed: the one build that
        # got this wrong (a Makefile CC that beat the environment, every ABI
        # coming out host-aarch64) produced binaries that passed every
        # other check here and would have died on first exec.
        case "$arch" in
            aarch64) want="ARM aarch64" ;;
            armv7a)  want="ARM, EABI" ;;
            x86_64)  want="x86-64" ;;
            i686)    want="Intel i386" ;;
        esac
        file "$bin" | grep -q "$want" || {
            echo "!! $arch/$name is not a $want ELF: $(file "$bin" | cut -c1-80)" >&2
            exit 1
        }
        # Not statically linked: the loader path requires bionic's shared libc.
        "$TOOLCHAIN/bin/llvm-readelf" -d "$bin" | grep -q "NEEDED.*libc.so" || {
            echo "!! $arch/$name is not dynamically linked against libc" >&2
            exit 1
        }
        # Every LOAD stays 4 KB aligned, the proot rule. readelf prints the
        # alignment in hex, so convert each one to decimal and take the max:
        # a hex string in a numeric test is the kind of thing that reads as
        # 10 and "passes" a 0x1000 that is exactly the limit.
        max_align=0
        # Unquoted on purpose: a quoted command substitution in a word list
        # keeps its newlines inside one word, so every "0x1000" would land
        # in $a and $((a)) would see a multi-line string and fail. The tokens
        # are hex digits only, so glob characters are not a risk here.
        for a in $("$TOOLCHAIN/bin/llvm-readelf" -lW "$bin" | awk '/LOAD/ {getline; print $NF}'); do
            a_dec=$((a))
            [ "$a_dec" -gt "$max_align" ] && max_align=$a_dec
        done
        [ "$max_align" -gt 0 ] || { echo "!! no LOAD segments in $arch/$name" >&2; exit 1; }
        [ "$max_align" -le 4096 ] || {
            echo "!! $arch/$name LOAD align $max_align exceeds 4096" >&2
            exit 1
        }
        echo "   $arch/$name: $(stat -c%s "$bin") bytes, LOAD align $max_align"
    done
    cd "$WORK"
done

# A native smoke test where the host can run the build: aarch64 on this host.
case " ${ARCHES[*]} " in
    *" aarch64 "*)
        if [ "$(uname -m)" = "aarch64" ]; then
            echo "=== smoke: aarch64 pair ==="
            sysroot="$TOOLCHAIN/sysroot"
            [ -d "$sysroot" ] || sysroot="$TOOLCHAIN/../sysroot"
            LD_LIBRARY_PATH="$sysroot/usr/lib" "$ASSET_DIR/aarch64/busybox" echo busybox-runs
            LD_LIBRARY_PATH="$sysroot/usr/lib" "$ASSET_DIR/aarch64/bash" -c 'echo bash-runs; echo $BASH_VERSION'
        else
            echo "note: host is not aarch64; the smoke test is skipped"
        fi
        ;;
esac

echo "done: $(ls "$ASSET_DIR" | tr '\n' ' ')"
