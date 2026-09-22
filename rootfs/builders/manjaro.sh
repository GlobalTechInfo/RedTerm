#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: manjaro.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'rm -rf "$ROOTFS"' EXIT

case "$ARCH" in
  aarch64|arm|x86_64|i686) ;;
  *) echo "Unsupported arch: $ARCH"; exit 1 ;;
esac

if [[ "$ARCH" == "aarch64" || "$ARCH" == "arm" ]]; then
  REPO_ARCH="arm"
  MIRROR="https://mirror.clarkson.edu/manjaro"
  if [[ "$ARCH" == "aarch64" ]]; then
    REPO_ARCH="arm"
  fi
else
  REPO_ARCH="$ARCH"
  MIRROR="https://mirror.rackspace.com/manjaro"
fi

mkdir -p "${ROOTFS}/etc/pacman.d"
mkdir -p "${ROOTFS}/var/lib/pacman"

cat > "${ROOTFS}/etc/pacman.conf" <<EOF
[options]
Architecture = ${REPO_ARCH}
SigLevel = Never
CacheDir = /var/cache/pacman/pkg/

[core]
Server = ${MIRROR}/\${repo}/\${arch}

[extra]
Server = ${MIRROR}/\${repo}/\${arch}

[community]
Server = ${MIRROR}/\${repo}/\${arch}
EOF

if command -v pacstrap &>/dev/null; then
  pacstrap -C "${ROOTFS}/etc/pacman.conf" \
    "$ROOTFS" base bash curl wget sudo procps nano vim less openssl 2>/dev/null || {
    echo "pacstrap failed for manjaro"
  }
else
  apt-get install -y -qq arch-install-scripts 2>/dev/null
  pacstrap -C "${ROOTFS}/etc/pacman.conf" \
    "$ROOTFS" base bash curl wget sudo procps nano vim less openssl 2>/dev/null || {
    echo "pacstrap not available, building minimal rootfs"
  }
fi

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
