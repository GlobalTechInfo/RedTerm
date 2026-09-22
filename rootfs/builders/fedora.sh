#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: fedora.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'sudo rm -rf "$ROOTFS"' EXIT

SUPPORTED_ARCHS="aarch64 x86_64"

if ! echo "$SUPPORTED_ARCHS" | grep -qw "$ARCH"; then
  echo "Skipping fedora ($ARCH not supported, only: $SUPPORTED_ARCHS)"
  exit 0
fi

case "$ARCH" in
  aarch64) RPM_ARCH="aarch64" ;;
  x86_64)  RPM_ARCH="x86_64" ;;
  *) echo "Fedora only supports x86_64 and aarch64, got: $ARCH"; exit 1 ;;
esac

# Use dnf (dnf4) with host config for installroot builds
sudo dnf --releasever=44 \
  --installroot="$ROOTFS" \
  -c /etc/dnf/dnf.conf \
  --setopt=tsflags=nodocs \
  --setopt=install_weak_deps=False \
  --nogpgcheck \
  -y install \
  bash coreutils filesystem glibc-minimal-langpack \
  fedora-release fedora-release-common fedora-repos setup \
  curl wget sudo procps nano vim-minimal less shadow-utils openssl ca-certificates

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
