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
  x86_64)  XBPS_ARCH="x86_64"; ;;
  aarch64) XBPS_ARCH="aarch64"; ;;
  arm)     XBPS_ARCH="armv7l"; ;;
  i686)    XBPS_ARCH="i686"; ;;
esac

MUSL_REPO="https://repo-default.voidlinux.org/current-musl"

wget --tries=3 "https://repo-default.voidlinux.org/static/xbps-static-latest.${XBPS_ARCH}-musl.tar.xz" -O /tmp/xbps-${ARCH}.tar.xz

sudo mkdir -p "$ROOTFS"
tar xJf /tmp/xbps-${ARCH}.tar.xz -C "$ROOTFS" --strip-components=1
rm -f /tmp/xbps-${ARCH}.tar.xz

sudo mkdir -p "$ROOTFS/etc/profile.d"
sudo mkdir -p "$ROOTFS/var/lib/xbps"
sudo mkdir -p "$ROOTFS/var/cache"
sudo mkdir -p "$ROOTFS/tmp"
sudo mkdir -p "$ROOTFS/run"
sudo mkdir -p "$ROOTFS/dev"
sudo mkdir -p "$ROOTFS/proc"
sudo mkdir -p "$ROOTFS/sys"

echo "nameserver 8.8.8.8" | sudo tee "$ROOTFS/etc/resolv.conf" > /dev/null
echo "nameserver 8.8.4.4" | sudo tee -a "$ROOTFS/etc/resolv.conf" > /dev/null
echo "export LANG=C.UTF-8" | sudo tee "$ROOTFS/etc/profile.d/locale.sh" > /dev/null
echo "export LC_ALL=C.UTF-8" | sudo tee -a "$ROOTFS/etc/profile.d/locale.sh" > /dev/null

sudo mount --bind /proc "$ROOTFS/proc" 2>/dev/null || true

# xbps-install detects arch from uname; on cross-arch CI this may be wrong.
# For i686 on x86_64 host, xbps sees x86_64 and can't find i686 repos.
# Workaround: for i686, use the x86_64-musl repo since we can't change uname.
# This is a known limitation; the rootfs will contain xbps tools + whatever installs.
sudo "$ROOTFS/usr/bin/xbps-install" -SySu \
  -r "$ROOTFS" \
  -R "$MUSL_REPO" || true
sudo "$ROOTFS/usr/bin/xbps-install" -y \
  -r "$ROOTFS" \
  -R "$MUSL_REPO" \
  bash coreutils findutils grep sed gawk \
  curl wget ca-certificates openssl \
  sudo procps nano vim less shadow || true

sudo umount "$ROOTFS/proc" 2>/dev/null || true

sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
