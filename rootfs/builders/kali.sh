#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: kali.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'sudo rm -rf "$ROOTFS"' EXIT

SUPPORTED_ARCHS="aarch64 arm x86_64 i686"

if ! echo "$SUPPORTED_ARCHS" | grep -qw "$ARCH"; then
  echo "Skipping kali ($ARCH not supported, only: $SUPPORTED_ARCHS)"
  exit 0
fi

case "$ARCH" in
  x86_64)  DEB_ARCH="amd64" ;;
  aarch64) DEB_ARCH="arm64" ;;
  arm)     DEB_ARCH="armhf" ;;
  i686)    DEB_ARCH="i386" ;;
esac

sudo debootstrap \
  --arch="$DEB_ARCH" \
  --variant=minbase \
  --exclude=systemd,systemd-sysv,systemd-timesyncd,dbus,dbus-user-session,udev \
  --include=kali-archive-keyring \
  kali-rolling \
  "$ROOTFS" \
  http://http.kali.org/kali

echo "nameserver 8.8.8.8" | sudo tee "$ROOTFS/etc/resolv.conf" > /dev/null
echo "nameserver 8.8.4.4" | sudo tee -a "$ROOTFS/etc/resolv.conf" > /dev/null
sudo mkdir -p "$ROOTFS/etc/profile.d"
echo "export LANG=C.UTF-8" | sudo tee "$ROOTFS/etc/profile.d/locale.sh" > /dev/null
echo "export LC_ALL=C.UTF-8" | sudo tee -a "$ROOTFS/etc/profile.d/locale.sh" > /dev/null

sudo mount --bind /proc "$ROOTFS/proc" 2>/dev/null || true
sudo chroot "$ROOTFS" /bin/bash -c "apt-get update --allow-insecure-repositories" || true
sudo chroot "$ROOTFS" /bin/bash -c "apt-get install -y --no-install-recommends --allow-unauthenticated \
  bash coreutils findutils grep sed gawk \
  base-files base-passwd debianutils dpkg \
  curl wget ca-certificates openssl \
  sudo procps vim less" || true
sudo umount "$ROOTFS/proc" 2>/dev/null || true

sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
