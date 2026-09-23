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
  x86_64)  PLATFORM="linux/amd64" ;;
  aarch64) PLATFORM="linux/arm64" ;;
  arm)     PLATFORM="linux/arm/v7" ;;
esac

if docker manifest inspect "archlinux:latest" 2>/dev/null | grep -q "\"${PLATFORM}\""; then
  echo "Pulling archlinux image for ${PLATFORM}..."
  docker pull --platform "$PLATFORM" archlinux:latest
  CID=$(docker create --platform "$PLATFORM" archlinux:latest /bin/true)
  docker export "$CID" | sudo tar -xf - -C "$ROOTFS"
  docker rm "$CID"
else
  echo "No Docker manifest for ${PLATFORM}, using tarball + docker import..."
  case "$ARCH" in
    arm)
      wget --tries=3 -L "http://fl.us.mirror.archlinuxarm.org/os/ArchLinuxARM-armv7-latest.tar.gz" -O /tmp/arch-arm.tar.gz
      docker import /tmp/arch-arm.tar.gz arch-arm-${ARCH}:latest
      CID=$(docker create arch-arm-${ARCH}:latest /bin/true)
      docker export "$CID" | sudo tar -xf - -C "$ROOTFS"
      docker rm "$CID"
      docker rmi arch-arm-${ARCH}:latest 2>/dev/null || true
      rm -f /tmp/arch-arm.tar.gz
      ;;
    *)
      echo "Unsupported architecture for tarball fallback: $ARCH"
      exit 1
      ;;
  esac
fi

sudo mkdir -p "${ROOTFS}/etc/pacman.d"
echo "Server = https://geo.mirror.pkgbuild.com/\$repo/os/\$arch" | sudo tee "${ROOTFS}/etc/pacman.d/mirrorlist" > /dev/null

sudo mkdir -p "${ROOTFS}/etc/profile.d"
echo "nameserver 8.8.8.8" | sudo tee "${ROOTFS}/etc/resolv.conf" > /dev/null
echo "nameserver 8.8.4.4" | sudo tee -a "${ROOTFS}/etc/resolv.conf" > /dev/null
echo "export LANG=C.UTF-8" | sudo tee "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null
echo "export LC_ALL=C.UTF-8" | sudo tee -a "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null

if [[ -f "$ROOTFS/etc/pacman.conf" ]]; then
  if ! grep -q "DisableSandbox" "$ROOTFS/etc/pacman.conf" 2>/dev/null; then
    sudo sed -i '/^\[options\]/a DisableSandbox' "$ROOTFS/etc/pacman.conf" 2>/dev/null || true
  fi
fi

sudo chmod 1777 "$ROOTFS/var/lib/pacman/sync" 2>/dev/null || true
sudo chmod 1777 "$ROOTFS/var/cache/pacman/pkg" 2>/dev/null || true

sudo mount --bind /proc "$ROOTFS/proc" 2>/dev/null || true
sudo chroot "$ROOTFS" /bin/bash -c "pacman --noconfirm --noprogressbar -Sy base bash curl wget sudo procps nano vim less openssl" || true
sudo umount "$ROOTFS/proc" 2>/dev/null || true

sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
