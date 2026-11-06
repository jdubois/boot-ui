#!/usr/bin/env bash
set -euo pipefail

test_command="${BOOTUI_TEST_COMMAND:-}"
[[ -n "${test_command//[[:space:]]/}" ]] || {
  echo "::error::test-command is required for the default run phase." >&2
  exit 1
}
bash --noprofile --norc -eo pipefail -c "$test_command"
