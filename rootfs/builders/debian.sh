#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: debian.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'sudo rm -rf "$ROOTFS"' EXIT

SUPPORTED_ARCHS="aarch64 x86_64 arm i686"

if ! echo "$SUPPORTED_ARCHS" | grep -qw "$ARCH"; then
  echo "Skipping debian ($ARCH not supported, only: $SUPPORTED_ARCHS)"
  exit 0
fi

case "$ARCH" in
  aarch64) DEB_ARCH="arm64" ;;
  x86_64)  DEB_ARCH="amd64" ;;
  arm)     DEB_ARCH="armhf" ;;
  i686)    DEB_ARCH="i386" ;;
esac

sudo debootstrap --arch="$DEB_ARCH" --variant=minbase \
  --include=bash,curl,wget,sudo,procps,nano,vim-tiny,less,openssl,ca-certificates,perl-base,adduser,libpam-runtime,locales \
  trixie "$ROOTFS" http://deb.debian.org/debian/

echo "nameserver 8.8.8.8" | sudo tee "${ROOTFS}/etc/resolv.conf" > /dev/null
echo "nameserver 8.8.4.4" | sudo tee -a "${ROOTFS}/etc/resolv.conf" > /dev/null

sudo tee "${ROOTFS}/etc/apt/sources.list" > /dev/null <<'EOF'
deb http://deb.debian.org/debian/ trixie main contrib non-free non-free-firmware
deb http://deb.debian.org/debian/ trixie-updates main contrib non-free non-free-firmware
deb http://security.debian.org/debian-security trixie-security main contrib non-free non-free-firmware
EOF

sudo chroot "$ROOTFS" /bin/bash -c "apt-get update -o APT::Sandbox::User=root"

echo "export LANG=C.UTF-8" | sudo tee "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null
echo "export LC_ALL=C.UTF-8" | sudo tee -a "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null

sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
