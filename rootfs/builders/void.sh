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
  x86_64)  XBPS_ARCH="x86_64";    REPO_URL="https://repo-default.voidlinux.org/current/musl" ;;
  aarch64) XBPS_ARCH="aarch64";   REPO_URL="https://repo-default.voidlinux.org/current/aarch64" ;;
  arm)     XBPS_ARCH="armv7l";    REPO_URL="https://repo-default.voidlinux.org/current/armv7l" ;;
  i686)    XBPS_ARCH="i686";      REPO_URL="https://repo-default.voidlinux.org/current/i686" ;;
esac

wget --tries=3 "https://repo-default.voidlinux.org/static/xbps-static-latest.${XBPS_ARCH}-musl.tar.xz" -O /tmp/xbps-${ARCH}.tar.xz

sudo mkdir -p "$ROOTFS"
tar xJf /tmp/xbps-${ARCH}.tar.xz -C "$ROOTFS" --strip-components=1
rm -f /tmp/xbps-${ARCH}.tar.xz

sudo mkdir -p "$ROOTFS/etc/xbps.d"
sudo mkdir -p "$ROOTFS/etc/profile.d"
sudo mkdir -p "$ROOTFS/var/lib/xbps"
sudo mkdir -p "$ROOTFS/var/cache"
sudo mkdir -p "$ROOTFS/tmp"
sudo mkdir -p "$ROOTFS/run"
sudo mkdir -p "$ROOTFS/dev"
sudo mkdir -p "$ROOTFS/proc"
sudo mkdir -p "$ROOTFS/sys"

echo "architecture=${XBPS_ARCH}" | sudo tee "$ROOTFS/etc/xbps.d/00-architecture.conf" > /dev/null
echo "repository=${REPO_URL}" | sudo tee "$ROOTFS/etc/xbps.d/00-repository.conf" > /dev/null

echo "nameserver 8.8.8.8" | sudo tee "$ROOTFS/etc/resolv.conf" > /dev/null
echo "nameserver 8.8.4.4" | sudo tee -a "$ROOTFS/etc/resolv.conf" > /dev/null
echo "export LANG=C.UTF-8" | sudo tee "$ROOTFS/etc/profile.d/locale.sh" > /dev/null
echo "export LC_ALL=C.UTF-8" | sudo tee -a "$ROOTFS/etc/profile.d/locale.sh" > /dev/null

sudo mount --bind /proc "$ROOTFS/proc" 2>/dev/null || true

sudo XBPS_ARCH="$XBPS_ARCH" "$ROOTFS/usr/bin/xbps-install" -Sy \
  -C "$ROOTFS/etc/xbps.d" \
  -r "$ROOTFS" \
  -R "$REPO_URL" || true
sudo XBPS_ARCH="$XBPS_ARCH" "$ROOTFS/usr/bin/xbps-install" -y \
  -C "$ROOTFS/etc/xbps.d" \
  -r "$ROOTFS" \
  -R "$REPO_URL" \
  bash coreutils findutils grep sed gawk \
  curl wget ca-certificates openssl \
  sudo procps nano vim less shadow || true

sudo umount "$ROOTFS/proc" 2>/dev/null || true

sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
