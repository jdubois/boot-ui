#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf '%s\n' '## BootUI assessment unavailable' '' \
    'No complete, bounded report was produced; check the action output.' >> "$GITHUB_STEP_SUMMARY"
  echo "::error::$*" >&2
  exit 1
}

report="${BOOTUI_REPORT_DIR:-}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
bash "$script_dir/validate-report.sh" "$report" || fail "A complete, bounded report is unavailable."
cat "$report/summary.md" >> "$GITHUB_STEP_SUMMARY"
printf 'report-dir=%s\n' "$report" >> "$GITHUB_OUTPUT"
