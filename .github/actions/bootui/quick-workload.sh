#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
working_directory="${GITHUB_WORKSPACE:-$PWD}"
configured="${BOOTUI_TEST_COMMAND:-none}"
if [[ "$configured" == none ]]; then
  echo "No workload command configured; BootUI will assess the started application as-is."
  exit 0
fi
test_command="$(BOOTUI_WORKING_DIRECTORY="$working_directory" \
  bash "$script_dir/detect-command.sh" test "$configured")"
[[ -n "${test_command//[[:space:]]/}" ]] || {
  echo "::error::The detected test command is empty." >&2
  exit 1
}
cd "$working_directory"
bash --noprofile --norc -eo pipefail -c "$test_command"
