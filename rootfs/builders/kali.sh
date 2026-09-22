#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: kali.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'sudo rm -rf "$ROOTFS"' EXIT

case "$ARCH" in
  aarch64) DEB_ARCH="arm64" ;;
  x86_64)  DEB_ARCH="amd64" ;;
  arm)     DEB_ARCH="armhf" ;;
  i686)    DEB_ARCH="i386" ;;
  *) echo "Unsupported arch: $ARCH"; exit 1 ;;
esac

sudo debootstrap --arch="$DEB_ARCH" --variant=minbase \
  --include=bash,curl,wget,sudo,procps,nano,vim,less,openssl \
  kali-rolling "$ROOTFS" http://mirror.kakao.com/kali/

sudo tee "${ROOTFS}/etc/resolv.conf" > /dev/null <<'EOF'
nameserver 8.8.8.8
nameserver 8.8.4.4
EOF

sudo tee "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null <<'EOF'
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
EOF

sudo rm -rf "${ROOTFS}/var/cache/apt/"* "${ROOTFS}/var/lib/apt/lists/"*
sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
