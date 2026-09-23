#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: arch.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'sudo rm -rf "$ROOTFS"' EXIT

SUPPORTED_ARCHS="aarch64 arm x86_64"

if ! echo "$SUPPORTED_ARCHS" | grep -qw "$ARCH"; then
  echo "Skipping arch ($ARCH not supported, only: $SUPPORTED_ARCHS)"
  exit 0
fi

case "$ARCH" in
  x86_64)
    wget --tries=3 "https://geo.mirror.pkgbuild.com/iso/latest/archlinux-bootstrap-x86_64.tar.zst" -O /tmp/arch-bs.tar.zst
    zstd -d /tmp/arch-bs.tar.zst -o /tmp/arch-bs.tar
    sudo tar xf /tmp/arch-bs.tar -C "$ROOTFS" --strip-components=1 --no-same-owner --no-same-permissions
    rm -f /tmp/arch-bs.tar
    echo "Server = https://geo.mirror.pkgbuild.com/\$repo/os/\$arch" | sudo tee "$ROOTFS/etc/pacman.d/mirrorlist" > /dev/null
    ;;
  aarch64)
    wget --tries=3 -L "http://fl.us.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz" -O /tmp/arch-arm.tar.gz
    sudo tar xzf /tmp/arch-arm.tar.gz -C "$ROOTFS" --no-same-owner --no-same-permissions
    rm -f /tmp/arch-arm.tar.gz
    ;;
  arm)
    wget --tries=3 -L "http://fl.us.mirror.archlinuxarm.org/os/ArchLinuxARM-armv7-latest.tar.gz" -O /tmp/arch-arm.tar.gz
    sudo tar xzf /tmp/arch-arm.tar.gz -C "$ROOTFS" --no-same-owner --no-same-permissions
    rm -f /tmp/arch-arm.tar.gz
    ;;
esac

sudo mkdir -p "${ROOTFS}/etc/profile.d"
echo "nameserver 8.8.8.8" | sudo tee "${ROOTFS}/etc/resolv.conf" > /dev/null
echo "nameserver 8.8.4.4" | sudo tee -a "${ROOTFS}/etc/resolv.conf" > /dev/null
echo "export LANG=C.UTF-8" | sudo tee "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null
echo "export LC_ALL=C.UTF-8" | sudo tee -a "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null
echo "rootfs / rootfs rw 0 0" | sudo tee "${ROOTFS}/etc/mtab" > /dev/null

if ! grep -q "DisableSandbox" "$ROOTFS/etc/pacman.conf" 2>/dev/null; then
  sudo sed -i '/^\[options\]/a DisableSandbox' "$ROOTFS/etc/pacman.conf" 2>/dev/null || true
fi

sudo chmod 1777 "$ROOTFS/var/lib/pacman/sync" 2>/dev/null || true
sudo chmod 1777 "$ROOTFS/var/cache/pacman/pkg" 2>/dev/null || true

sudo mount --bind /proc "$ROOTFS/proc" 2>/dev/null || true
sudo chroot "$ROOTFS" /bin/bash -c "pacman --noconfirm --noprogressbar -Sy base bash curl wget sudo procps nano vim less openssl" || true
sudo umount "$ROOTFS/proc" 2>/dev/null || true

sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
