#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: manjaro.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'sudo rm -rf "$ROOTFS"' EXIT

case "$ARCH" in
  aarch64|arm|x86_64|i686) ;;
  *) echo "Unsupported arch: $ARCH"; exit 1 ;;
esac

if [[ "$ARCH" == "aarch64" || "$ARCH" == "arm" ]]; then
  REPO_ARCH="arm"
  MIRROR="https://mirror.clarkson.edu/manjaro"
else
  REPO_ARCH="$ARCH"
  MIRROR="https://mirror.rackspace.com/manjaro"
fi

sudo mkdir -p "${ROOTFS}/etc/pacman.d"
sudo mkdir -p "${ROOTFS}/var/lib/pacman"

sudo tee "${ROOTFS}/etc/pacman.conf" > /dev/null <<EOF
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

if ! command -v pacstrap &>/dev/null; then
  sudo apt-get install -y -qq arch-install-scripts 2>/dev/null || {
    echo "Cannot install arch-install-scripts"
    exit 1
  }
fi

sudo pacstrap -C "${ROOTFS}/etc/pacman.conf" \
  "$ROOTFS" base bash curl wget sudo procps nano vim less openssl 2>/dev/null || {
  echo "pacstrap failed for manjaro"
  exit 1
}

sudo tee "${ROOTFS}/etc/resolv.conf" > /dev/null <<'EOF'
nameserver 8.8.8.8
nameserver 8.8.4.4
EOF

sudo tee "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null <<'EOF'
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
EOF

sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
