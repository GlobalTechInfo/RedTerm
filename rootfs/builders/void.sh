#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: void.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'sudo rm -rf "$ROOTFS"' EXIT

SUPPORTED_ARCHS="aarch64 x86_64 arm i686"

if ! echo "$SUPPORTED_ARCHS" | grep -qw "$ARCH"; then
  echo "Skipping void ($ARCH not supported, only: $SUPPORTED_ARCHS)"
  exit 0
fi

case "$ARCH" in
  x86_64)  XBPS_ARCH="x86_64"; MUSL="musl" ;;
  aarch64) XBPS_ARCH="aarch64"; MUSL="musl" ;;
  arm)     XBPS_ARCH="armv7l"; MUSL="musl" ;;
  i686)    XBPS_ARCH="i686"; MUSL="musl" ;;
esac

wget --tries=3 "https://repo-default.voidlinux.org/static/xbps-static-latest.${XBPS_ARCH}-${MUSL}.tar.xz" -O /tmp/xbps-${ARCH}.tar.xz
sudo tar xJf /tmp/xbps-${ARCH}.tar.xz -C "$ROOTFS" --strip-components=2
rm -f /tmp/xbps-${ARCH}.tar.xz

sudo mkdir -p "$ROOTFS/etc/profile.d"
echo "nameserver 8.8.8.8" | sudo tee "$ROOTFS/etc/resolv.conf" > /dev/null
echo "nameserver 8.8.4.4" | sudo tee -a "$ROOTFS/etc/resolv.conf" > /dev/null
echo "export LANG=C.UTF-8" | sudo tee "$ROOTFS/etc/profile.d/locale.sh" > /dev/null
echo "export LC_ALL=C.UTF-8" | sudo tee -a "$ROOTFS/etc/profile.d/locale.sh" > /dev/null

sudo mount --bind /proc "$ROOTFS/proc" 2>/dev/null || true
sudo chroot "$ROOTFS" /bin/sh -c "xbps-install -SySu || true"
sudo chroot "$ROOTFS" /bin/sh -c "xbps-install -y \
  bash coreutils findutils grep sed gawk \
  curl wget ca-certificates openssl \
  sudo procps nano vim less shadow" || true
sudo umount "$ROOTFS/proc" 2>/dev/null || true

sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
