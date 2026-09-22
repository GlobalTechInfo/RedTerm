#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: almalinux.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'rm -rf "$ROOTFS"' EXIT

case "$ARCH" in
  aarch64) RPM_ARCH="aarch64" ;;
  x86_64)  RPM_ARCH="x86_64" ;;
  arm)     RPM_ARCH="armv7hl" ;;
  i686)    RPM_ARCH="i686" ;;
  *) echo "Unsupported arch: $ARCH"; exit 1 ;;
esac

sudo dnf --releasever=10 \
  --installroot="$ROOTFS" \
  --repo=almalinux-baseos \
  --repo=almalinux-appstream \
  --repo=almalinux-crb \
  --setopt=tsflags=nodocs \
  -y install \
  bash coreutils filesystem glibc-langpack-en \
  curl wget sudo procps nano vim-minimal less shadow-utils openssl 2>/dev/null

cat > "${ROOTFS}/etc/resolv.conf" <<'EOF'
nameserver 8.8.8.8
nameserver 8.8.4.4
EOF

cat > "${ROOTFS}/etc/profile.d/locale.sh" <<'EOF'
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
EOF

rm -rf "${ROOTFS}/var/cache/dnf/"* "${ROOTFS}/var/log/dnf"*
chmod 644 "${ROOTFS}/etc/resolv.conf"
chmod 644 "${ROOTFS}/etc/profile.d/locale.sh"

tar cJf "$OUTPUT" -C "$ROOTFS" .
