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

wget -q "https://repo-default.voidlinux.org/static/xbps-static-latest.${ARCH}-musl.tar.xz" -O "/tmp/xbps-${ARCH}.tar.xz"
sudo tar xJf "/tmp/xbps-${ARCH}.tar.xz" -C /tmp/ --no-same-permissions --no-same-owner
rm -f "/tmp/xbps-${ARCH}.tar.xz"
XBPS_BIN=$(find /tmp -name "xbps-install" -type f | head -1)

if [[ -n "$XBPS_BIN" ]]; then
  sudo $XBPS_BIN -r "$ROOTFS" -R "${XBPS_MIRROR}" -y \
    bash coreutils curl wget sudo procps nano vim less openssl ca-certificates
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

sudo tar cJf "$OUTPUT" --warning=no-file-changed -C "$ROOTFS" .
