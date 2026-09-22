#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: alpine.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'sudo rm -rf "$ROOTFS"' EXIT

case "$ARCH" in
  aarch64|arm|x86_64|i686) ;;
  *) echo "Unsupported arch: $ARCH"; exit 1 ;;
esac

# Alpine uses different arch names in URLs
case "$ARCH" in
  arm)     ALPINE_ARCH="armv7" ;;
  i686)    ALPINE_ARCH="x86" ;;
  *)       ALPINE_ARCH="$ARCH" ;;
esac

LATEST=$(wget -qO- "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/${ALPINE_ARCH}/latest-releases.yaml" 2>/dev/null | grep -A1 "minirootfs" | grep -oP 'alpine-minirootfs-\K[0-9.]+-[a-z0-9]+' | head -1)
if [[ -z "$LATEST" ]]; then
  echo "Failed to find latest Alpine version for $ALPINE_ARCH"
  exit 1
fi
echo "Latest Alpine: $LATEST (arch=$ALPINE_ARCH)"
wget -q "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/${ALPINE_ARCH}/alpine-minirootfs-${LATEST}.tar.gz" \
  -O "/tmp/alpine-${ARCH}.tar.gz"

sudo tar xzf "/tmp/alpine-${ARCH}.tar.gz" -C "$ROOTFS"
rm -f "/tmp/alpine-${ARCH}.tar.gz"

sudo tee "${ROOTFS}/etc/resolv.conf" > /dev/null <<'EOF'
nameserver 8.8.8.8
nameserver 8.8.4.4
EOF

sudo tee "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null <<'EOF'
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
EOF

sudo chroot "$ROOTFS" /bin/sh -c "apk update && apk add --no-cache bash curl wget sudo shadow procps nano vim less openssl" 2>/dev/null || true

sudo rm -rf "${ROOTFS}/var/cache/apk/"*
sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
