#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: void.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'sudo rm -rf "$ROOTFS"' EXIT

case "$ARCH" in
  aarch64|arm|x86_64|i686) ;;
  *) echo "Unsupported arch: $ARCH"; exit 1 ;;
esac

XBPS_MIRROR="https://repo-default.voidlinux.org/current"

wget -q "https://repo-default.voidlinux.org/static/xbps-static-latest.${ARCH}-musl.tar.xz" -O "/tmp/xbps-${ARCH}.tar.xz"
tar xJf "/tmp/xbps-${ARCH}.tar.xz" -C /tmp/
rm -f "/tmp/xbps-${ARCH}.tar.xz"
XBPS_BIN=$(find /tmp -name "xbps-install" -type f | head -1)

if [[ -n "$XBPS_BIN" ]]; then
  $XBPS_BIN -r "$ROOTFS" -R "${XBPS_MIRROR}" -y \
    bash coreutils curl wget sudo procps nano vim less openssl 2>/dev/null || true
fi

sudo tee "${ROOTFS}/etc/resolv.conf" > /dev/null <<'EOF'
nameserver 8.8.8.8
nameserver 8.8.4.4
EOF

sudo tee "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null <<'EOF'
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
EOF

sudo tee "${ROOTFS}/etc/shells" > /dev/null <<'EOF'
/bin/sh
/bin/bash
/bin/dash
EOF

sudo tee "${ROOTFS}/etc/passwd" > /dev/null <<'EOF'
root:x:0:0:root:/root:/bin/bash
EOF

sudo tee "${ROOTFS}/etc/group" > /dev/null <<'EOF'
root:x:0:
EOF

sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
