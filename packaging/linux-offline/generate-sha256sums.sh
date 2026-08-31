#!/usr/bin/env bash
set -euo pipefail

ROOT="${1:-$(pwd)}"
OUTPUT="$ROOT/SHA256SUMS"

cd "$ROOT"
find . -type f \
  ! -path './SHA256SUMS' \
  ! -path './logs/*' \
  ! -path './run/*' \
  ! -path './app/*/conf/data/dictionary/custom/*.bin' \
  -print0 \
  | sort -z \
  | xargs -0 sha256sum > "$OUTPUT"

echo "Wrote immutable package manifest: $OUTPUT"
