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
    wget -q --tries=3 "https://geo.mirror.pkgbuild.com/iso/latest/archlinux-bootstrap-x86_64.tar.zst" -O /tmp/arch-bs.tar.zst
    sudo tar -I zstd -xf /tmp/arch-bs.tar.zst -C "$ROOTFS" --strip-components=1
    rm -f /tmp/arch-bs.tar.zst
    echo "Server = https://geo.mirror.pkgbuild.com/\$repo/os/\$arch" | sudo tee "$ROOTFS/etc/pacman.d/mirrorlist" > /dev/null
    ;;
  aarch64)
    wget -q --tries=3 -L "http://mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz" -O /tmp/arch-arm.tar.gz
    sudo tar xzf /tmp/arch-arm.tar.gz -C "$ROOTFS"
    rm -f /tmp/arch-arm.tar.gz
    ;;
  arm)
    wget -q --tries=3 -L "http://mirror.archlinuxarm.org/os/ArchLinuxARM-armv7-latest.tar.gz" -O /tmp/arch-arm.tar.gz
    sudo tar xzf /tmp/arch-arm.tar.gz -C "$ROOTFS"
    rm -f /tmp/arch-arm.tar.gz
    ;;
esac

sudo tee "${ROOTFS}/etc/resolv.conf" > /dev/null <<'EOF'
nameserver 8.8.8.8
nameserver 8.8.4.4
EOF

sudo tee "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null <<'EOF'
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
EOF

sudo sed -i 's/^#DisableSandbox.*/DisableSandbox/' "$ROOTFS/etc/pacman.conf" 2>/dev/null || \
  sudo sed -i '/^\[options\]/a DisableSandbox' "$ROOTFS/etc/pacman.conf" 2>/dev/null || true

sudo chown -R root:root "$ROOTFS/var/lib/pacman" 2>/dev/null || true
sudo chmod -R 777 "$ROOTFS/var/lib/pacman/sync" 2>/dev/null || true
sudo chmod -R 777 "$ROOTFS/var/cache/pacman" 2>/dev/null || true

sudo chroot "$ROOTFS" /bin/bash -c "pacman --noconfirm --noprogressbar -Sy base bash curl wget sudo procps nano vim less openssl"

sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
