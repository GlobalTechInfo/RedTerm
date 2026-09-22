#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: manjaro.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'sudo rm -rf "$ROOTFS"' EXIT

case "$ARCH" in
  aarch64|x86_64) ;;
  *) echo "Manjaro only supports x86_64 and aarch64, got: $ARCH"; exit 1 ;;
esac

MIRROR="https://ftp.halifax.rwth-aachen.de/manjaro"

# aarch64 uses arm-stable branch, x86_64 uses stable
if [[ "$ARCH" == "aarch64" ]]; then
  BRANCH="arm-stable"
else
  BRANCH="stable"
fi

sudo mkdir -p "${ROOTFS}/etc/pacman.d"
sudo mkdir -p "${ROOTFS}/var/lib/pacman"

sudo tee "${ROOTFS}/etc/pacman.conf" > /dev/null <<EOF
[options]
Architecture = ${ARCH}
SigLevel = Never
CacheDir = /var/cache/pacman/pkg/

[core]
Server = ${MIRROR}/${BRANCH}/\${repo}/\${arch}

[extra]
Server = ${MIRROR}/${BRANCH}/\${repo}/\${arch}
EOF

if ! command -v pacstrap &>/dev/null; then
  sudo apt-get install -y -qq arch-install-scripts || {
    echo "Cannot install arch-install-scripts"
    exit 1
  }
fi

sudo pacstrap -C "${ROOTFS}/etc/pacman.conf" \
  "$ROOTFS" base bash curl wget sudo procps nano vim less openssl || {
  echo "pacstrap failed for manjaro"
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
