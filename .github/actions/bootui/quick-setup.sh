#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "::error::$*" >&2
  exit 1
}

test_command="${BOOTUI_TEST_COMMAND:-}"
[[ -n "${test_command//[[:space:]]/}" ]] || fail "test-command is required for the default run phase."
[[ "${BOOTUI_UPLOAD_ARTIFACT:-}" == true || "${BOOTUI_UPLOAD_ARTIFACT:-}" == false ]] \
  || fail "upload-artifact must be true or false."
[[ -n "${RUNNER_TEMP:-}" && "$RUNNER_TEMP" == /* && -d "$RUNNER_TEMP" ]] \
  || fail "RUNNER_TEMP must name an absolute directory."
[[ "$RUNNER_TEMP" != *$'\n'* && "$RUNNER_TEMP" != *$'\r'* ]] \
  || fail "RUNNER_TEMP must not contain a newline."

runner_temp="$(cd "$RUNNER_TEMP" && pwd -P)"
[[ "$runner_temp" != *$'\n'* && "$runner_temp" != *$'\r'* ]] \
  || fail "The resolved RUNNER_TEMP must not contain a newline."
state="$(mktemp -d "$runner_temp/bootui-ci.XXXXXXXX")"
suffix="${state##*.}"
assessment_id="${BOOTUI_ASSESSMENT_ID:-gha-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}-$suffix}"
[[ "$assessment_id" =~ ^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$ ]] \
  || fail "assessment-id must match [A-Za-z0-9][A-Za-z0-9._:-]{0,127}."

{
  printf 'assessment-id=%s\n' "$assessment_id"
  printf 'witness=%s/witness.json\n' "$state"
  printf 'report-dir=%s/assessment\n' "$state"
  printf 'artifact-name=bootui-%s\n' "$suffix"
} >> "$GITHUB_OUTPUT"
