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
esac

if [[ "$DEB_ARCH" == "armhf" ]]; then
  MIRROR="http://ports.ubuntu.com/ubuntu-ports/"
else
  MIRROR="http://archive.ubuntu.com/ubuntu/"
fi

sudo debootstrap --arch="$DEB_ARCH" --variant=minbase \
  --include=bash,curl,wget,sudo,procps,vim,less,openssl,ca-certificates \
  resolute "$ROOTFS" "$MIRROR"

sudo tee "${ROOTFS}/etc/apt/sources.list" > /dev/null <<EOF
deb ${MIRROR} resolute main restricted universe multiverse
deb ${MIRROR} resolute-updates main restricted universe multiverse
deb ${MIRROR} -security resolute-security main restricted universe multiverse
EOF

sudo chroot "$ROOTFS" /bin/bash -c "apt-get update && apt-get install -y --no-install-recommends nano"

sudo tee "${ROOTFS}/etc/resolv.conf" > /dev/null <<'EOF'
nameserver 8.8.8.8
nameserver 8.8.4.4
EOF

sudo tee "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null <<'EOF'
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
EOF

sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
