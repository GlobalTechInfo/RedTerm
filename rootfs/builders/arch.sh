#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: arch.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'sudo rm -rf "$ROOTFS"' EXIT

case "$ARCH" in
  aarch64|arm|x86_64) ;;
  *) echo "Arch only supports x86_64, aarch64, and arm, got: $ARCH"; exit 1 ;;
esac

if ! command -v pacstrap &>/dev/null; then
  sudo apt-get install -y -qq arch-install-scripts || {
    echo "Cannot install arch-install-scripts"
    exit 1
  }
fi

sudo mkdir -p "${ROOTFS}/etc/pacman.d"
sudo mkdir -p "${ROOTFS}/var/lib/pacman"

if [[ "$ARCH" == "aarch64" || "$ARCH" == "arm" ]]; then
  sudo tee "${ROOTFS}/etc/pacman.conf" > /dev/null <<EOF
[options]
Architecture = ${ARCH}
SigLevel = Never
CacheDir = /var/cache/pacman/pkg/

[core]
Server = http://mirror.archlinuxarm.org/\$arch/\$repo

[extra]
Server = http://mirror.archlinuxarm.org/\$arch/\$repo
EOF
else
  sudo tee "${ROOTFS}/etc/pacman.conf" > /dev/null <<EOF
[options]
Architecture = ${ARCH}
SigLevel = Never
CacheDir = /var/cache/pacman/pkg/

[core]
Server = https://geo.mirror.pkgbuild.com/\$repo/os/\$arch

[extra]
Server = https://geo.mirror.pkgbuild.com/\$repo/os/\$arch
EOF
fi

sudo pacstrap -C "${ROOTFS}/etc/pacman.conf" \
  "$ROOTFS" base bash curl wget sudo procps nano vim less openssl || {
  echo "pacstrap failed for arch"
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
