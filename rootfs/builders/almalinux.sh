#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: almalinux.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'sudo rm -rf "$ROOTFS"' EXIT

case "$ARCH" in
  aarch64) RPM_ARCH="aarch64" ;;
  x86_64)  RPM_ARCH="x86_64" ;;
  *) echo "AlmaLinux only supports x86_64 and aarch64, got: $ARCH"; exit 1 ;;
esac

# AlmaLinux 10 uses DNF4 — use -c to read host config for installroot builds
sudo dnf --releasever=10 \
  --installroot="$ROOTFS" \
  -c /etc/dnf/dnf.conf \
  --setopt=tsflags=nodocs \
  --setopt=install_weak_deps=False \
  --nogpgcheck \
  -y install \
  bash coreutils filesystem glibc-minimal-langpack \
  almalinux-release setup \
  curl wget sudo procps nano vim-minimal less shadow-utils openssl ca-certificates 2>/dev/null

sudo tee "${ROOTFS}/etc/resolv.conf" > /dev/null <<'EOF'
nameserver 8.8.8.8
nameserver 8.8.4.4
EOF

sudo tee "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null <<'EOF'
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
EOF

sudo rm -rf "${ROOTFS}/var/cache/dnf/"* "${ROOTFS}/var/log/dnf"*
sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
