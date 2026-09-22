#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: arch.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'rm -rf "$ROOTFS"' EXIT

case "$ARCH" in
  aarch64|arm|x86_64|i686) ;;
  *) echo "Unsupported arch: $ARCH"; exit 1 ;;
esac

# Arch Linux ARM for aarch64/arm, Arch Linux for x86_64/i686
if [[ "$ARCH" == "aarch64" || "$ARCH" == "arm" ]]; then
  MIRROR="http://ftp.gwdg.de/pub/linux/archlinux/arm"
else
  MIRROR="https://geo.mirror.pkgbuild.com"
fi

# Install pacstrap if not present
if ! command -v pacstrap &>/dev/null; then
  apt-get install -y -qq arch-install-scripts 2>/dev/null || {
    echo "Cannot install arch-install-scripts, trying manual method"
  }
fi

if command -v pacstrap &>/dev/null; then
  pacstrap -C <(echo "Server = ${MIRROR}/\$arch/\$repo
SigLevel = Never") \
    "$ROOTFS" base bash curl wget sudo procps nano vim less openssl 2>/dev/null || {
    echo "pacstrap failed, trying minimal install"
  }
fi

# Ensure minimal files exist
mkdir -p "${ROOTFS}/etc"
cat > "${ROOTFS}/etc/resolv.conf" <<'EOF'
nameserver 8.8.8.8
nameserver 8.8.4.4
EOF

cat > "${ROOTFS}/etc/profile.d/locale.sh" <<'EOF'
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
EOF

chmod 644 "${ROOTFS}/etc/resolv.conf"
chmod 644 "${ROOTFS}/etc/profile.d/locale.sh"

tar cJf "$OUTPUT" -C "$ROOTFS" .
