#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: kali.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'sudo rm -rf "$ROOTFS"' EXIT

SUPPORTED_ARCHS="aarch64 x86_64 arm i686"

if ! echo "$SUPPORTED_ARCHS" | grep -qw "$ARCH"; then
  echo "Skipping kali ($ARCH not supported, only: $SUPPORTED_ARCHS)"
  exit 0
fi

case "$ARCH" in
  aarch64) DEB_ARCH="arm64" ;;
  x86_64)  DEB_ARCH="amd64" ;;
  arm)     DEB_ARCH="armhf" ;;
  i686)    DEB_ARCH="i386" ;;
esac

sudo debootstrap --arch="$DEB_ARCH" --variant=minbase \
  --include=bash,curl,wget,sudo,procps,nano,vim,less,openssl,ca-certificates \
  --no-check-gpg \
  kali-rolling "$ROOTFS" http://http.kali.org/kali/

echo "nameserver 8.8.8.8" | sudo tee "${ROOTFS}/etc/resolv.conf" > /dev/null
echo "nameserver 8.8.4.4" | sudo tee -a "${ROOTFS}/etc/resolv.conf" > /dev/null

sudo tee "${ROOTFS}/etc/apt/sources.list" > /dev/null <<'EOF'
deb http://http.kali.org/kali/ kali-rolling main contrib non-free non-free-firmware
EOF

sudo chroot "$ROOTFS" /bin/bash -c "apt-get update" || echo "WARN: apt-get update had issues, continuing"

echo "export LANG=C.UTF-8" | sudo tee "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null
echo "export LC_ALL=C.UTF-8" | sudo tee -a "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null

sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
