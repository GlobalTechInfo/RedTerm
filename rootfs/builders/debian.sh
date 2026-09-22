#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: debian.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'rm -rf "$ROOTFS"' EXIT

case "$ARCH" in
  aarch64) DEB_ARCH="arm64" ;;
  x86_64)  DEB_ARCH="amd64" ;;
  arm)     DEB_ARCH="armhf" ;;
  i686)    DEB_ARCH="i386" ;;
  *) echo "Unsupported arch: $ARCH"; exit 1 ;;
esac

sudo debootstrap --arch="$DEB_ARCH" --variant=minbase \
  --include=bash,curl,wget,sudo,procps,nano,vim,less,openssl \
  trixie "$ROOTFS" http://deb.debian.org/debian/ 2>/dev/null

cat > "${ROOTFS}/etc/resolv.conf" <<'EOF'
nameserver 8.8.8.8
nameserver 8.8.4.4
EOF

cat > "${ROOTFS}/etc/profile.d/locale.sh" <<'EOF'
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
EOF

rm -rf "${ROOTFS}/var/cache/apt/"* "${ROOTFS}/var/lib/apt/lists/"*
chmod 644 "${ROOTFS}/etc/resolv.conf"
chmod 644 "${ROOTFS}/etc/profile.d/locale.sh"

tar cJf "$OUTPUT" -C "$ROOTFS" .
