#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: ubuntu.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'sudo rm -rf "$ROOTFS"' EXIT

SUPPORTED_ARCHS="aarch64 x86_64 arm i686"

if ! echo "$SUPPORTED_ARCHS" | grep -qw "$ARCH"; then
  echo "Skipping ubuntu ($ARCH not supported, only: $SUPPORTED_ARCHS)"
  exit 0
fi

case "$ARCH" in
  aarch64) DEB_ARCH="arm64" ;;
  x86_64)  DEB_ARCH="amd64" ;;
  arm)     DEB_ARCH="armhf" ;;
  i686)    DEB_ARCH="i386" ;;
  *) echo "Unsupported arch: $ARCH"; exit 1 ;;
esac

# Ubuntu armhf is only on ports.ubuntu.com, all others on archive.ubuntu.com
if [[ "$DEB_ARCH" == "armhf" ]]; then
  MIRROR="http://ports.ubuntu.com/ubuntu-ports/"
else
  MIRROR="http://archive.ubuntu.com/ubuntu/"
fi

sudo debootstrap --arch="$DEB_ARCH" --variant=minbase \
  --include=bash,curl,wget,sudo,procps,vim,less,openssl \
  resolute "$ROOTFS" "$MIRROR"

# nano not available for i386 in 26.04, install via chroot
sudo chroot "$ROOTFS" /bin/bash -c "apt-get update && apt-get install -y --no-install-recommends nano" || true

sudo tee "${ROOTFS}/etc/resolv.conf" > /dev/null <<'EOF'
nameserver 8.8.8.8
nameserver 8.8.4.4
EOF

sudo tee "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null <<'EOF'
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
EOF

sudo rm -rf "${ROOTFS}/var/cache/apt/"* "${ROOTFS}/var/lib/apt/lists/"*
sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
