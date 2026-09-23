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
    BRANCH="stable"
    wget --tries=3 "https://geo.mirror.pkgbuild.com/iso/latest/archlinux-bootstrap-x86_64.tar.zst" -O /tmp/mj-bs.tar.zst
    zstd -d /tmp/mj-bs.tar.zst -o /tmp/mj-bs.tar
    docker import /tmp/mj-bs.tar manjaro-img:latest
    CID=$(docker create manjaro-img:latest /bin/true)
    docker export "$CID" | sudo tar -xf - -C "$ROOTFS"
    docker rm "$CID"
    docker rmi manjaro-img:latest 2>/dev/null || true
    rm -f /tmp/mj-bs.tar /tmp/mj-bs.tar.zst
    ;;
  aarch64)
    BRANCH="arm-stable"
    wget --tries=3 -L "http://fl.us.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz" -O /tmp/mj-arm.tar.gz
    docker import /tmp/mj-arm.tar.gz manjaro-img:latest
    CID=$(docker create manjaro-img:latest /bin/true)
    docker export "$CID" | sudo tar -xf - -C "$ROOTFS"
    docker rm "$CID"
    docker rmi manjaro-img:latest 2>/dev/null || true
    rm -f /tmp/mj-arm.tar.gz
    ;;
esac

MIRROR="https://mirror.math.princeton.edu/pub/manjaro"
sudo mkdir -p "${ROOTFS}/etc/pacman.d"
sudo tee "${ROOTFS}/etc/pacman.conf" > /dev/null <<EOF
[options]
Architecture = ${ARCH}
SigLevel = Never
CacheDir = /var/cache/pacman/pkg/
DisableSandbox

[core]
Server = ${MIRROR}/${BRANCH}/\$repo/\$arch

[extra]
Server = ${MIRROR}/${BRANCH}/\$repo/\$arch
EOF

sudo mkdir -p "${ROOTFS}/etc/profile.d"
echo "nameserver 8.8.8.8" | sudo tee "${ROOTFS}/etc/resolv.conf" > /dev/null
echo "nameserver 8.8.4.4" | sudo tee -a "${ROOTFS}/etc/resolv.conf" > /dev/null
echo "export LANG=C.UTF-8" | sudo tee "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null
echo "export LC_ALL=C.UTF-8" | sudo tee -a "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null

sudo chmod 1777 "$ROOTFS/var/lib/pacman/sync" 2>/dev/null || true
sudo chmod 1777 "$ROOTFS/var/cache/pacman/pkg" 2>/dev/null || true

sudo mount --bind /proc "$ROOTFS/proc" 2>/dev/null || true
sudo chroot "$ROOTFS" /bin/sh -c "pacman --noconfirm --noprogressbar -Sy --overwrite '*' base bash curl wget sudo procps nano vim less openssl ca-certificates" || true
sudo umount "$ROOTFS/proc" 2>/dev/null || true

sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
