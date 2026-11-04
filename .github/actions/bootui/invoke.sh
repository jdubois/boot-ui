#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "::error::$*" >&2
  echo "exit-code=1" >> "$GITHUB_OUTPUT"
  exit 0
}

phase="${BOOTUI_PHASE:-}"
url="${BOOTUI_URL:-}"
assessment_id="${BOOTUI_ASSESSMENT_ID:-}"
policy="${BOOTUI_POLICY:-}"
witness="${BOOTUI_WITNESS:-}"
output_dir="${BOOTUI_OUTPUT_DIR:-}"
api_path="${BOOTUI_API_PATH:-/bootui/api}"
timeout="${BOOTUI_TIMEOUT:-60}"

[[ "$phase" == attach || "$phase" == assess ]] || fail "phase must be attach or assess."
[[ -n "${BOOTUI_CLI_JAR:-}" && -f "$BOOTUI_CLI_JAR" ]] || fail "Verified BootUI CLI jar is unavailable."
[[ -n "$url" && -n "$assessment_id" && -n "$policy" && -f "$policy" && -n "$witness" ]] \
  || fail "url, assessment-id, policy file, and witness are required."

arguments=(
  ci "$phase"
  --url "$url"
  --api-path "$api_path"
  --timeout "$timeout"
  --assessment-id "$assessment_id"
  --policy "$policy"
)
if [[ "$phase" == attach ]]; then
  arguments+=(--output "$witness")
else
  [[ "${BOOTUI_ATTACH_EXIT_CODE:-}" == 0 ]] || fail "A successful attach is required before assessment."
  [[ -n "$output_dir" ]] || fail "output-dir is required for assess."
  [[ "$output_dir" != *$'\n'* && "$output_dir" != *$'\r'* ]] || fail "output-dir must not contain a newline."
  [[ ! -e "$output_dir" ]] || fail "output-dir must be a fresh path for this assessment."
  arguments+=(--witness "$witness" --output-dir "$output_dir")
fi

export BOOTUI_NO_UPDATE_CHECK=1
status=0
java -jar "$BOOTUI_CLI_JAR" "${arguments[@]}" || status=$?
echo "exit-code=$status" >> "$GITHUB_OUTPUT"
if [[ "$phase" == assess && ( "$status" == 0 || "$status" == 1 || "$status" == 3 ) \
      && -f "$output_dir/bundle.json" && -f "$output_dir/summary.md" && -f "$output_dir/junit.xml" ]]; then
  echo "summary-fresh=true" >> "$GITHUB_OUTPUT"
  echo "report-dir=$output_dir" >> "$GITHUB_OUTPUT"
fi
exit 0
