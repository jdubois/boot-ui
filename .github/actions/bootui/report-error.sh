#!/usr/bin/env bash
set -euo pipefail

output_dir="${BOOTUI_OUTPUT_DIR:-}"
assessment_id="${BOOTUI_ASSESSMENT_ID:-}"
[[ -n "$output_dir" && "$output_dir" != *$'\n'* && "$output_dir" != *$'\r'* ]] || {
  echo "::error::A valid output-dir is required for the failure report." >&2
  exit 1
}
[[ "$assessment_id" =~ ^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$ ]] || assessment_id="unavailable"
if [[ "${BOOTUI_APP_CONFIG_ERROR:-false}" == true ]]; then
  code="APP_START_FAILED"
elif [[ "${BOOTUI_PREPARE_OUTCOME:-success}" == failure ]]; then
  code="CLI_SETUP_FAILED"
elif [[ "${BOOTUI_START_OUTCOME:-success}" == failure ]]; then
  code="APP_START_FAILED"
elif [[ "${BOOTUI_ATTACH_EXIT_CODE:-}" != 0 ]]; then
  code="ATTACH_FAILED"
elif [[ "${BOOTUI_PREPARE_OUTCOME:-success}" != success ]]; then
  code="CLI_SETUP_FAILED"
else
  code="REPORT_UNAVAILABLE"
fi

report_dir="${output_dir}.action-error"
mkdir -p "$(dirname "$report_dir")"
mkdir "$report_dir" || {
  echo "::error::Failure report directory is not fresh or cannot be created." >&2
  exit 1
}
printf '{"schemaVersion":1,"assessmentId":"%s","status":"NOT_ASSESSED","errors":[{"code":"%s"}]}\n' \
  "$assessment_id" "$code" > "$report_dir/bundle.json"
printf '# BootUI assessment: NOT_ASSESSED\n\nThe assessment could not be completed (%s).\n' \
  "$code" > "$report_dir/summary.md"
printf '%s\n' '<testsuite name="bootui-ci" tests="1" failures="0" errors="1" skipped="0">' \
  '  <testcase classname="bootui.ci" name="assessment"><error message="BootUI assessment could not be completed"/></testcase>' \
  '</testsuite>' > "$report_dir/junit.xml"
echo "exit-code=1" >> "$GITHUB_OUTPUT"
echo "report-dir=$report_dir" >> "$GITHUB_OUTPUT"
