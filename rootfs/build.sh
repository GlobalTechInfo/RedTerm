#!/bin/bash
set -euo pipefail

ARCH="${1:?Usage: build.sh <arch> <distro|all>}"
DISTRO="${2:-all}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_DIR="${SCRIPT_DIR}/../output"
BUILDERS_DIR="${SCRIPT_DIR}/builders"

mkdir -p "$OUTPUT_DIR"

# Install prerequisites
sudo apt-get update -qq && sudo apt-get install -y -qq qemu-user-static systemd-container

DISTROS=(alpine debian ubuntu arch artix manjaro almalinux fedora rocky kali void)

for d in "${DISTROS[@]}"; do
  if [[ "$DISTRO" != "all" && "$DISTRO" != "$d" ]]; then
    continue
  fi

  echo "=== Building $d ($ARCH) ==="
  builder="${BUILDERS_DIR}/${d}.sh"
  if [[ ! -f "$builder" ]]; then
    echo "No builder for $d, skipping"
    continue
  fi

  OUTPUT_FILE="${OUTPUT_DIR}/${d}-${ARCH}-rootfs.tar.xz"
  if [[ -f "$OUTPUT_FILE" ]]; then
    echo "Already exists: $OUTPUT_FILE, skipping"
    continue
  fi

  bash "$builder" "$ARCH" "$OUTPUT_FILE"

  if [[ -f "$OUTPUT_FILE" ]]; then
    SIZE=$(du -h "$OUTPUT_FILE" | cut -f1)
    echo "✓ $d ($ARCH): $SIZE"
  else
    echo "✗ $d ($ARCH): FAILED"
  fi
done

echo "=== Done ==="
ls -lh "$OUTPUT_DIR/"*.tar.xz || echo "No rootfs built"
