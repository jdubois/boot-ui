#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "::error::$*" >&2
  echo "exit-code=1" >> "$GITHUB_OUTPUT"
  write_offline_summary 1
  exit 0
}

write_offline_summary() {
  local status="${1:-1}"
  local heading
  local message
  [[ "${phase:-}" == offline && -n "${GITHUB_STEP_SUMMARY:-}" ]] || return 0
  case "$status" in
    0)
      heading='## BootUI offline assessment: PASS'
      message='The saved bundle passed policy validation.'
      ;;
    3)
      heading='## BootUI offline assessment: VIOLATION'
      message='The saved bundle contains findings at or above the configured threshold.'
      ;;
    *)
      heading='## BootUI offline assessment: NOT_ASSESSED'
      message='The saved bundle was invalid or incomplete; no pass was inferred.'
      ;;
  esac
  if ! printf '%s\n\n%s\n' "$heading" "$message" >> "$GITHUB_STEP_SUMMARY"; then
    echo "::warning::Could not write the BootUI offline status to the job summary." >&2
  fi
}

phase="${BOOTUI_PHASE:-}"
url="${BOOTUI_URL:-}"
assessment_id="${BOOTUI_ASSESSMENT_ID:-}"
policy="${BOOTUI_POLICY:-}"
witness="${BOOTUI_WITNESS:-}"
input_bundle="${BOOTUI_INPUT_BUNDLE:-}"
output_dir="${BOOTUI_OUTPUT_DIR:-}"
api_path="${BOOTUI_API_PATH:-/bootui/api}"
timeout="${BOOTUI_TIMEOUT:-60}"

[[ "$phase" == attach || "$phase" == assess || "$phase" == offline ]] \
  || fail "phase must be attach, assess, or offline."
[[ -n "${BOOTUI_CLI_JAR:-}" && -f "$BOOTUI_CLI_JAR" ]] || fail "Verified BootUI CLI jar is unavailable."
[[ -n "$assessment_id" && -n "$policy" && -f "$policy" ]] \
  || fail "assessment-id and a readable policy file are required."

arguments=(ci)
if [[ "$phase" == offline ]]; then
  [[ -n "$input_bundle" && -f "$input_bundle" ]] || fail "input-bundle must name a readable saved bundle."
  arguments+=(check --input "$input_bundle" --assessment-id "$assessment_id" --policy "$policy")
elif [[ "$phase" == attach || "$phase" == assess ]]; then
  [[ -n "$url" && -n "$witness" ]] || fail "url and witness are required for live assessment."
  arguments+=(
    "$phase"
    --url "$url"
    --api-path "$api_path"
    --timeout "$timeout"
    --assessment-id "$assessment_id"
    --policy "$policy"
  )
fi
if [[ "$phase" == attach ]]; then
  arguments+=(--output "$witness")
elif [[ "$phase" == assess ]]; then
  [[ "${BOOTUI_ATTACH_EXIT_CODE:-}" == 0 ]] || fail "A successful attach is required before assessment."
  [[ -n "$output_dir" ]] || fail "output-dir is required for assess."
  [[ "$output_dir" != *$'\n'* && "$output_dir" != *$'\r'* ]] || fail "output-dir must not contain a newline."
  [[ ! -e "$output_dir" ]] || fail "output-dir must be a fresh path for this assessment."
  arguments+=(--witness "$witness" --output-dir "$output_dir")
fi

export BOOTUI_NO_UPDATE_CHECK=1
status=0
java -jar "$BOOTUI_CLI_JAR" "${arguments[@]}" || status=$?
if [[ "$phase" == assess ]]; then
  if [[ "$status" == 0 || "$status" == 1 || "$status" == 3 ]] \
      && bash "$(dirname "${BASH_SOURCE[0]}")/validate-report.sh" "$output_dir"; then
    echo "summary-fresh=true" >> "$GITHUB_OUTPUT"
    echo "report-dir=$output_dir" >> "$GITHUB_OUTPUT"
  else
    status=1
  fi
fi
echo "exit-code=$status" >> "$GITHUB_OUTPUT"
write_offline_summary "$status"
exit 0
