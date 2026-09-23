#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: almalinux.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'sudo rm -rf "$ROOTFS"' EXIT

SUPPORTED_ARCHS="aarch64 x86_64"

if ! echo "$SUPPORTED_ARCHS" | grep -qw "$ARCH"; then
  echo "Skipping almalinux ($ARCH not supported, only: $SUPPORTED_ARCHS)"
  exit 0
fi

case "$ARCH" in
  aarch64) RPM_ARCH="aarch64" ;;
  x86_64)  RPM_ARCH="x86_64" ;;
esac

sudo mkdir -p "${ROOTFS}/etc/yum.repos.d"
sudo tee "${ROOTFS}/etc/yum.repos.d/almalinux.repo" > /dev/null <<EOF
[baseos]
name=AlmaLinux 10 BaseOS - ${RPM_ARCH}
baseurl=https://repo.almalinux.org/almalinux/10/BaseOS/${RPM_ARCH}/os/
enabled=1
gpgcheck=0

[appstream]
name=AlmaLinux 10 AppStream - ${RPM_ARCH}
baseurl=https://repo.almalinux.org/almalinux/10/AppStream/${RPM_ARCH}/os/
enabled=1
gpgcheck=0

[crb]
name=AlmaLinux 10 CRB - ${RPM_ARCH}
baseurl=https://repo.almalinux.org/almalinux/10/CRB/${RPM_ARCH}/os/
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

sudo dnf --releasever=10 \
  --installroot="$ROOTFS" \
  --forcearch="$RPM_ARCH" \
  -c "$DNF_CONF" \
  --setopt=reposdir="${ROOTFS}/etc/yum.repos.d" \
  --setopt=tsflags=nodocs \
  --setopt=install_weak_deps=False \
  --nogpgcheck \
  -y install \
  bash coreutils filesystem glibc-minimal-langpack \
  almalinux-release setup \
  curl wget sudo procps-ng nano vim-minimal less shadow-utils openssl ca-certificates

echo "export LANG=C.UTF-8" | sudo tee "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null
echo "export LC_ALL=C.UTF-8" | sudo tee -a "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null

sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
