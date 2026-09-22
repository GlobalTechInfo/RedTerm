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

# Alpine minirootfs URLs
LATEST=$(wget -qO- "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/${ARCH}/latest-releases.yaml" 2>/dev/null | grep -A1 "minirootfs" | grep -oP 'alpine-minirootfs-\K[0-9.]+-[a-z0-9]+' | head -1)
wget -q "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/${ARCH}/alpine-minirootfs-${LATEST}.tar.gz" \
  -O "/tmp/alpine.tar.gz"

tar xzf "/tmp/alpine.tar.gz" -C "$ROOTFS"

cat > "${ROOTFS}/etc/resolv.conf" <<'EOF'
nameserver 8.8.8.8
nameserver 8.8.4.4
EOF

cat > "${ROOTFS}/etc/profile.d/locale.sh" <<'EOF'
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
EOF

sudo chroot "$ROOTFS" /bin/sh -c "apk update && apk add --no-cache bash curl wget sudo shadow procps nano vim less openssl" 2>/dev/null || true

sudo rm -rf "${ROOTFS}/var/cache/apk/"*
sudo chmod 644 "${ROOTFS}/etc/resolv.conf"
sudo chmod 644 "${ROOTFS}/etc/profile.d/locale.sh"

sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
