#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "::error::$*" >&2
  exit 1
}

report="${1:-}"
while [[ "$report" != / && "$report" == */ ]]; do
  report="${report%/}"
done
[[ -n "$report" && -d "$report" && ! -L "$report" ]] || fail "A valid report directory is unavailable."
for entry in bundle.json:16777216 summary.md:262144 junit.xml:262144 sarif.json:4194304; do
  name="${entry%:*}"
  limit="${entry#*:}"
  file="$report/$name"
  [[ -f "$file" && ! -L "$file" ]] || fail "The report is missing a regular $name file."
  bytes="$(wc -c < "$file")"
  (( bytes > 0 && bytes <= limit )) || fail "The $name report is empty or exceeds its upload bound."
done
