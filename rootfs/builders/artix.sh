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
  x86_64)  PLATFORM="linux/amd64" ;;
  aarch64) PLATFORM="linux/arm64" ;;
esac

echo "Pulling archlinux base image for $PLATFORM..."
docker pull --platform "$PLATFORM" archlinux:latest
CID=$(docker create --platform "$PLATFORM" archlinux:latest /bin/true)
docker export "$CID" | sudo tar -xf - -C "$ROOTFS"
docker rm "$CID"

MIRROR="https://mirrors.rit.edu/artixlinux"
sudo mkdir -p "${ROOTFS}/etc/pacman.d"
sudo tee "${ROOTFS}/etc/pacman.conf" > /dev/null <<EOF
[options]
Architecture = ${ARCH}
SigLevel = Never
CacheDir = /var/cache/pacman/pkg/
DisableSandbox

[system]
Server = ${MIRROR}/\$repo/os/\$arch

[world]
Server = ${MIRROR}/\$repo/os/\$arch

[galaxy]
Server = ${MIRROR}/\$repo/os/\$arch
EOF

sudo mkdir -p "${ROOTFS}/etc/profile.d"
echo "nameserver 8.8.8.8" | sudo tee "${ROOTFS}/etc/resolv.conf" > /dev/null
echo "nameserver 8.8.4.4" | sudo tee -a "${ROOTFS}/etc/resolv.conf" > /dev/null
echo "export LANG=C.UTF-8" | sudo tee "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null
echo "export LC_ALL=C.UTF-8" | sudo tee -a "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null

sudo chmod 1777 "$ROOTFS/var/lib/pacman/sync" 2>/dev/null || true
sudo chmod 1777 "$ROOTFS/var/cache/pacman/pkg" 2>/dev/null || true

sudo mount --bind /proc "$ROOTFS/proc" 2>/dev/null || true
sudo chroot "$ROOTFS" /bin/bash -c "pacman --noconfirm --noprogressbar -Sy --overwrite '*' base bash curl wget sudo procps nano vim less openssl" || true
sudo umount "$ROOTFS/proc" 2>/dev/null || true

sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
