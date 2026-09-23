#!/bin/bash
set -euo pipefail

ARCH="${1:?Usage: build.sh <arch> <distro|all>}"
DISTRO="${2:-all}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_DIR="${SCRIPT_DIR}/../output"
BUILDERS_DIR="${SCRIPT_DIR}/builders"

mkdir -p "$OUTPUT_DIR"

DISTROS=(alpine debian ubuntu arch artix manjaro almalinux fedora rocky kali void)
FAILED=()
PASSED=()

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

  # Check if builder supports this arch
  SUPPORTED_ARCHS=$(grep "^SUPPORTED_ARCHS=" "$builder" | cut -d'"' -f2)
  if [[ -n "$SUPPORTED_ARCHS" ]]; then
    if ! echo "$SUPPORTED_ARCHS" | grep -qw "$ARCH"; then
      echo "=== Skipping $d ($ARCH not supported, only: $SUPPORTED_ARCHS) ==="
      continue
    fi
  fi

  OUTPUT_FILE="${OUTPUT_DIR}/${d}-${ARCH}-rootfs.tar.xz"

  if bash "$builder" "$ARCH" "$OUTPUT_FILE"; then
    if [[ -f "$OUTPUT_FILE" ]]; then
      SIZE=$(du -h "$OUTPUT_FILE" | cut -f1)
      echo "✓ $d ($ARCH): $SIZE"
      PASSED+=("$d")
    else
      echo "✗ $d ($ARCH): FAILED (builder succeeded but no output)"
      FAILED+=("$d")
    fi
  else
    echo "✗ $d ($ARCH): FAILED (builder exited with error)"
    FAILED+=("$d")
  fi
done

echo ""
echo "=== Summary ==="
echo "Passed: ${#PASSED[@]} - ${PASSED[*]:-none}"
echo "Failed: ${#FAILED[@]} - ${FAILED[*]:-none}"
echo ""
echo "=== Done ==="
ls -lh "$OUTPUT_DIR/"*.tar.xz || echo "No rootfs built"

if [[ ${#FAILED[@]} -gt 0 ]]; then
  exit 1
fi
