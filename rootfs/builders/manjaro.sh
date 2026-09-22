#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: manjaro.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'sudo rm -rf "$ROOTFS"' EXIT

SUPPORTED_ARCHS="aarch64 x86_64"

if ! echo "$SUPPORTED_ARCHS" | grep -qw "$ARCH"; then
  echo "Skipping manjaro ($ARCH not supported, only: $SUPPORTED_ARCHS)"
  exit 0
fi

case "$ARCH" in
  x86_64)
    wget -q --tries=3 "https://geo.mirror.pkgbuild.com/iso/latest/archlinux-bootstrap-x86_64.tar.zst" -O /tmp/mj-bs.tar.zst
    sudo tar -I zstd -xf /tmp/mj-bs.tar.zst -C "$ROOTFS" --strip-components=1
    rm -f /tmp/mj-bs.tar.zst
    BRANCH="stable"
    ;;
  aarch64)
    wget -q --tries=3 -L "http://mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz" -O /tmp/mj-arm.tar.gz
    sudo tar xzf /tmp/mj-arm.tar.gz -C "$ROOTFS"
    rm -f /tmp/mj-arm.tar.gz
    BRANCH="arm-stable"
    ;;
esac

MIRROR="https://ftp.halifax.rwth-aachen.de/manjaro"

sudo tee "${ROOTFS}/etc/pacman.d/mirrorlist" > /dev/null <<EOF
[core]
Server = ${MIRROR}/${BRANCH}/\$repo/\$arch

[extra]
Server = ${MIRROR}/${BRANCH}/\$repo/\$arch
EOF

sudo chroot "$ROOTFS" /bin/bash -c "pacman -Sy --noconfirm base bash curl wget sudo procps nano vim less openssl"

sudo tee "${ROOTFS}/etc/resolv.conf" > /dev/null <<'EOF'
nameserver 8.8.8.8
nameserver 8.8.4.4
EOF

sudo tee "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null <<'EOF'
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
EOF

sudo rm -rf "${ROOTFS}/var/cache/pacman/pkg/"*
sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
