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
esac

sudo mkdir -p "${ROOTFS}/etc/yum.repos.d"
sudo tee "${ROOTFS}/etc/yum.repos.d/fedora.repo" > /dev/null <<EOF
[fedora]
name=Fedora 44 - ${RPM_ARCH}
baseurl=https://dl.fedoraproject.org/pub/fedora/linux/releases/44/Everything/${RPM_ARCH}/os/
enabled=1
gpgcheck=0
EOF

echo "nameserver 8.8.8.8" | sudo tee "${ROOTFS}/etc/resolv.conf" > /dev/null
echo "nameserver 8.8.4.4" | sudo tee -a "${ROOTFS}/etc/resolv.conf" > /dev/null

DNF_CONF=$(mktemp)
cat > "$DNF_CONF" <<EOF
[main]
clean_requirements_on_remove=False
installonly_limit=3
arch=${RPM_ARCH}
basearch=${RPM_ARCH}
EOF
trap 'sudo rm -rf "$ROOTFS"; rm -f "$DNF_CONF"' EXIT

sudo dnf --releasever=44 \
  --installroot="$ROOTFS" \
  --forcearch="$RPM_ARCH" \
  -c "$DNF_CONF" \
  --setopt=reposdir="${ROOTFS}/etc/yum.repos.d" \
  --setopt=tsflags=nodocs \
  --setopt=install_weak_deps=False \
  --nogpgcheck \
  -y install \
  bash coreutils filesystem glibc-minimal-langpack \
  fedora-release fedora-release-common fedora-repos setup \
  curl wget2 sudo procps-ng nano vim-minimal less shadow-utils openssl ca-certificates

sudo rm -f "${ROOTFS}/etc/yum.repos.d/fedora.repo.rpmnew"

echo "export LANG=C.UTF-8" | sudo tee "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null
echo "export LC_ALL=C.UTF-8" | sudo tee -a "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null

sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
