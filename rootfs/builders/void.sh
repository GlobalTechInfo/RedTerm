#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: void.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'rm -rf "$ROOTFS"' EXIT

case "$ARCH" in
  aarch64|arm|x86_64|i686) ;;
  *) echo "Unsupported arch: $ARCH"; exit 1 ;;
esac

XBPS_MIRROR="https://repo.voidlinux.org/current"

# Download xbps static
wget -q "${XBPS_MIRROR}/${ARCH}/xbps-static-latest.${ARCH}.tar.xz" -O "/tmp/xbps.tar.xz"
tar xJf "/tmp/xbps.tar.xz" -C /tmp/
XBPS_BIN=$(find /tmp -name "xbps-install" -type f | head -1)

if [[ -n "$XBPS_BIN" ]]; then
  $XBPS_BIN -r "$ROOTFS" -R "${XBPS_MIRROR}" -y \
    bash coreutils curl wget sudo procps nano vim less openssl 2>/dev/null || true
fi

mkdir -p "${ROOTFS}/etc"
cat > "${ROOTFS}/etc/resolv.conf" <<'EOF'
nameserver 8.8.8.8
nameserver 8.8.4.4
EOF

cat > "${ROOTFS}/etc/profile.d/locale.sh" <<'EOF'
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
EOF

cat > "${ROOTFS}/etc/shells" <<'EOF'
/bin/sh
/bin/bash
/bin/dash
EOF

cat > "${ROOTFS}/etc/passwd" <<'EOF'
root:x:0:0:root:/root:/bin/bash
EOF

cat > "${ROOTFS}/etc/group" <<'EOF'
root:x:0:
EOF

chmod 644 "${ROOTFS}/etc/resolv.conf"
chmod 644 "${ROOTFS}/etc/profile.d/locale.sh"

tar cJf "$OUTPUT" -C "$ROOTFS" .
