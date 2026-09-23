#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: alpine.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'sudo rm -rf "$ROOTFS"' EXIT

SUPPORTED_ARCHS="aarch64 arm x86_64 i686"

if ! echo "$SUPPORTED_ARCHS" | grep -qw "$ARCH"; then
  echo "Skipping alpine ($ARCH not supported, only: $SUPPORTED_ARCHS)"
  exit 0
fi

case "$ARCH" in
  arm)     ALPINE_ARCH="armv7" ;;
  i686)    ALPINE_ARCH="x86" ;;
  *)       ALPINE_ARCH="$ARCH" ;;
esac

LATEST=$(wget -qO- "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/${ALPINE_ARCH}/latest-releases.yaml" | grep -A1 "minirootfs" | grep -oP 'alpine-minirootfs-\K[0-9.]+-[a-z0-9_]+' | head -1)
if [[ -z "$LATEST" ]]; then
  echo "Failed to find latest Alpine version for $ALPINE_ARCH"
  exit 1
fi
echo "Latest Alpine: $LATEST (arch=$ALPINE_ARCH)"

wget --tries=3 "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/${ALPINE_ARCH}/alpine-minirootfs-${LATEST}.tar.gz" -O "/tmp/alpine-${ARCH}.tar.gz"
sudo tar xzf "/tmp/alpine-${ARCH}.tar.gz" -C "$ROOTFS"
rm -f "/tmp/alpine-${ARCH}.tar.gz"

echo "nameserver 8.8.8.8" | sudo tee "${ROOTFS}/etc/resolv.conf" > /dev/null
echo "nameserver 8.8.4.4" | sudo tee -a "${ROOTFS}/etc/resolv.conf" > /dev/null
echo "export LANG=C.UTF-8" | sudo tee "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null
echo "export LC_ALL=C.UTF-8" | sudo tee -a "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null

sudo chroot "$ROOTFS" /bin/sh -c "apk update && apk add --no-cache bash curl wget sudo shadow procps nano vim less openssl ca-certificates" || {
  echo "WARN: apk had issues, checking rootfs..."
  ls "$ROOTFS/bin/bash" "$ROOTFS/usr/bin/curl" "$ROOTFS/usr/bin/sudo" 2>/dev/null || { echo "FATAL: rootfs incomplete"; exit 1; }
}

sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
