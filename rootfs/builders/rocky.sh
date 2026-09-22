#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: rocky.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'sudo rm -rf "$ROOTFS"' EXIT

case "$ARCH" in
  aarch64) RPM_ARCH="aarch64" ;;
  x86_64)  RPM_ARCH="x86_64" ;;
  arm)     RPM_ARCH="armv7hl" ;;
  i686)    RPM_ARCH="i686" ;;
  *) echo "Unsupported arch: $ARCH"; exit 1 ;;
esac

sudo dnf --releasever=10 \
  --installroot="$ROOTFS" \
  --repo=baseos \
  --repo=appstream \
  --repo=crb \
  --setopt=tsflags=nodocs \
  -y install \
  bash coreutils filesystem glibc-langpack-en \
  curl wget sudo procps nano vim-minimal less shadow-utils openssl 2>/dev/null

sudo tee "${ROOTFS}/etc/resolv.conf" > /dev/null <<'EOF'
nameserver 8.8.8.8
nameserver 8.8.4.4
EOF

sudo tee "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null <<'EOF'
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
EOF

sudo rm -rf "${ROOTFS}/var/cache/dnf/"* "${ROOTFS}/var/log/dnf"*
sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
