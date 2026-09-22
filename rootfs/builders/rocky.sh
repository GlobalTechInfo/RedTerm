#!/bin/bash
set -euo pipefail
ARCH="${1:?Usage: rocky.sh <arch> <output>}"
OUTPUT="${2:?}"
ROOTFS=$(mktemp -d)
trap 'sudo rm -rf "$ROOTFS"' EXIT

SUPPORTED_ARCHS="aarch64 x86_64"

if ! echo "$SUPPORTED_ARCHS" | grep -qw "$ARCH"; then
  echo "Skipping rocky ($ARCH not supported, only: $SUPPORTED_ARCHS)"
  exit 0
fi

case "$ARCH" in
  aarch64) RPM_ARCH="aarch64" ;;
  x86_64)  RPM_ARCH="x86_64" ;;
esac

sudo mkdir -p "${ROOTFS}/etc/yum.repos.d"
sudo tee "${ROOTFS}/etc/yum.repos.d/rocky.repo" > /dev/null <<EOF
[baseos]
name=Rocky Linux 10 BaseOS - ${RPM_ARCH}
baseurl=https://dl.rockylinux.org/pub/rocky/10/BaseOS/${RPM_ARCH}/os/
enabled=1
gpgcheck=0

[appstream]
name=Rocky Linux 10 AppStream - ${RPM_ARCH}
baseurl=https://dl.rockylinux.org/pub/rocky/10/AppStream/${RPM_ARCH}/os/
enabled=1
gpgcheck=0
EOF

DNF_CONF=$(mktemp)
cat > "$DNF_CONF" <<'EOF'
[main]
clean_requirements_on_remove=False
installonly_limit=3
EOF
trap 'sudo rm -rf "$ROOTFS"; rm -f "$DNF_CONF"' EXIT

sudo dnf --releasever=10 \
  --installroot="$ROOTFS" \
  -c "$DNF_CONF" \
  --setopt=reposdir="${ROOTFS}/etc/yum.repos.d" \
  --setopt=tsflags=nodocs \
  --setopt=install_weak_deps=False \
  --nogpgcheck \
  -y install \
  bash coreutils filesystem glibc-minimal-langpack \
  rocky-release setup \
  curl wget sudo procps nano vim-minimal less shadow-utils openssl ca-certificates

sudo tee "${ROOTFS}/etc/resolv.conf" > /dev/null <<'EOF'
nameserver 8.8.8.8
nameserver 8.8.4.4
EOF

sudo tee "${ROOTFS}/etc/profile.d/locale.sh" > /dev/null <<'EOF'
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
EOF

sudo tar cJf "$OUTPUT" -C "$ROOTFS" .
