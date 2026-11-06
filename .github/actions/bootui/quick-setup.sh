#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "::error::$*" >&2
  exit 1
}

test_command="${BOOTUI_TEST_COMMAND:-none}"
[[ -n "${test_command//[[:space:]]/}" ]] || fail "test-command must be none, auto, or a shell command."
app_command="${BOOTUI_APP_COMMAND:-auto}"
[[ -n "${app_command//[[:space:]]/}" ]] || fail "app-command must be auto, none, or a shell command."
startup_timeout="${BOOTUI_STARTUP_TIMEOUT:-120}"
[[ "$startup_timeout" =~ ^[1-9][0-9]*$ && "$startup_timeout" -le 600 ]] \
  || fail "startup-timeout must be between 1 and 600 seconds."
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
  printf 'state-dir=%s\n' "$state"
  printf 'witness=%s/witness.json\n' "$state"
  printf 'report-dir=%s/assessment\n' "$state"
  printf 'artifact-name=bootui-%s\n' "$suffix"
} >> "$GITHUB_OUTPUT"
