#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: artix.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'sudo rm -rf "$ROOTFS"' EXIT

SUPPORTED_ARCHS="aarch64 x86_64"

if ! echo "$SUPPORTED_ARCHS" | grep -qw "$ARCH"; then
  echo "Skipping artix ($ARCH not supported, only: $SUPPORTED_ARCHS)"
  exit 0
fi

case "$ARCH" in
  x86_64)
    wget -q --tries=3 "https://geo.mirror.pkgbuild.com/iso/latest/archlinux-bootstrap-x86_64.tar.zst" -O /tmp/artix-bs.tar.zst
    sudo tar -I zstd -xf /tmp/artix-bs.tar.zst -C "$ROOTFS" --strip-components=1
    rm -f /tmp/artix-bs.tar.zst
    MIRROR="https://mirrors.rit.edu/artixlinux"
    ;;
  aarch64)
    wget -q --tries=3 -L "http://mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz" -O /tmp/artix-arm.tar.gz
    sudo tar xzf /tmp/artix-arm.tar.gz -C "$ROOTFS"
    rm -f /tmp/artix-arm.tar.gz
    MIRROR="https://armtix.artixlinux.org/repos"
    ;;
esac

sudo tee "${ROOTFS}/etc/pacman.conf" > /dev/null <<EOF
[options]
Architecture = ${ARCH}
SigLevel = Never
CacheDir = /var/cache/pacman/pkg/

[system]
Server = ${MIRROR}/\$repo/os/\$arch

[world]
Server = ${MIRROR}/\$repo/os/\$arch

[galaxy]
Server = ${MIRROR}/\$repo/os/\$arch
EOF

sudo tee "${ROOTFS}/etc/resolv.conf" > /dev/null <<'EOF'
nameserver 8.8.8.8
nameserver 8.8.4.4
EOF

sudo tee "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null <<'EOF'
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
EOF

sudo mkdir -p "$ROOTFS/var/lib/pacman/sync"
sudo chroot "$ROOTFS" /bin/bash -c "pacman -Sy --noconfirm base bash curl wget sudo procps nano vim less openssl"

sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
