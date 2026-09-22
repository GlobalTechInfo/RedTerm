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

if [[ "$ARCH" == "aarch64" || "$ARCH" == "arm" ]]; then
  # Arch ARM uses $arch/$repo format
  MIRROR="http://mirror.archlinuxarm.org"
  MIRROR_LINE="Server = ${MIRROR}/\$arch/\$repo"
else
  # Standard Arch uses $repo/os/$arch format
  MIRROR="https://geo.mirror.pkgbuild.com"
  MIRROR_LINE="Server = ${MIRROR}/\$repo/os/\$arch"
fi

if ! command -v pacstrap &>/dev/null; then
  sudo apt-get install -y -qq arch-install-scripts 2>/dev/null || {
    echo "Cannot install arch-install-scripts"
    exit 1
  }
fi

sudo pacstrap -C <(echo "${MIRROR_LINE}
SigLevel = Never") \
  "$ROOTFS" base bash curl wget sudo procps nano vim less openssl 2>/dev/null || {
  echo "pacstrap failed for arch"
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
