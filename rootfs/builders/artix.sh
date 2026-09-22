#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: artix.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'sudo rm -rf "$ROOTFS"' EXIT

case "$ARCH" in
  aarch64|x86_64) ;;
  *) echo "Artix only supports x86_64 and aarch64, got: $ARCH"; exit 1 ;;
esac

REPO_ARCH="$ARCH"

# Artix x86_64 and aarch64 use different mirrors
if [[ "$ARCH" == "aarch64" ]]; then
  MIRROR="https://armtix.artixlinux.org/repos"
else
  MIRROR="https://mirrors.rit.edu/artixlinux"
fi

sudo mkdir -p "${ROOTFS}/etc/pacman.d"
sudo mkdir -p "${ROOTFS}/var/lib/pacman"

sudo tee "${ROOTFS}/etc/pacman.conf" > /dev/null <<EOF
[options]
Architecture = ${REPO_ARCH}
SigLevel = Never
CacheDir = /var/cache/pacman/pkg/

[system]
Server = ${MIRROR}/\${repo}/os/\${arch}

[world]
Server = ${MIRROR}/\${repo}/os/\${arch}

[galaxy]
Server = ${MIRROR}/\${repo}/os/\${arch}
EOF

if ! command -v pacstrap &>/dev/null; then
  sudo apt-get install -y -qq arch-install-scripts || {
    echo "Cannot install arch-install-scripts"
    exit 1
  }
fi

sudo pacstrap -C "${ROOTFS}/etc/pacman.conf" \
  "$ROOTFS" base bash curl wget sudo procps nano vim less openssl || {
  echo "pacstrap failed for artix"
  echo "=== pacman.conf ==="
  cat "${ROOTFS}/etc/pacman.conf"
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
