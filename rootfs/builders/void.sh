#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: void.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'sudo rm -rf "$ROOTFS"' EXIT

SUPPORTED_ARCHS="aarch64 arm x86_64 i686"

if ! echo "$SUPPORTED_ARCHS" | grep -qw "$ARCH"; then
  echo "Skipping void ($ARCH not supported, only: $SUPPORTED_ARCHS)"
  exit 0
fi

XBPS_MIRROR="https://repo-default.voidlinux.org/current"

wget --tries=3 "https://repo-default.voidlinux.org/static/xbps-static-latest.${ARCH}-musl.tar.xz" -O "/tmp/xbps-${ARCH}.tar.xz"
sudo tar xJf "/tmp/xbps-${ARCH}.tar.xz" -C /tmp/ --no-same-permissions --no-same-owner
sudo rm -f "/tmp/xbps-${ARCH}.tar.xz"

XBPS_DIR=$(sudo find /tmp -maxdepth 1 -type d -name "xbps-static-*" | head -1)
XBPS_BIN="${XBPS_DIR}/usr/bin/xbps-install"

if [[ -n "$XBPS_DIR" && -x "$XBPS_BIN" ]]; then
  echo "nameserver 8.8.8.8" | sudo tee "${ROOTFS}/etc/resolv.conf" > /dev/null
  echo "nameserver 8.8.4.4" | sudo tee -a "${ROOTFS}/etc/resolv.conf" > /dev/null

  sudo $XBPS_BIN -r "$ROOTFS" -R "${XBPS_MIRROR}" -y \
    bash coreutils curl wget sudo procps nano vim less openssl ca-certificates
fi

echo "export LANG=C.UTF-8" | sudo tee "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null
echo "export LC_ALL=C.UTF-8" | sudo tee -a "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null

sudo tee "${ROOTFS}/etc/shells" > /dev/null <<'EOF'
/bin/sh
/bin/bash
/bin/dash
EOF

echo "root:x:0:0:root:/root:/bin/bash" | sudo tee "${ROOTFS}/etc/passwd" > /dev/null
echo "root:x:0:" | sudo tee "${ROOTFS}/etc/group" > /dev/null

sudo tar cJf "$OUTPUT" --warning=no-file-changed -C "$ROOTFS" .
