#!/usr/bin/env bash
set -euo pipefail

assessment="${BOOTUI_ASSESS_EXIT_CODE:-1}"
case "$assessment" in
  0|1|3) ;;
  *) assessment=1 ;;
esac

failed=0
if [[ "${BOOTUI_SETUP_OUTCOME:-}" != success ]]; then
  echo "::error::BootUI Action input or runner setup failed." >&2
  assessment=1
  failed=1
elif [[ "${BOOTUI_PREPARE_OUTCOME:-}" != success ]]; then
  echo "::error::BootUI CLI setup failed." >&2
  assessment=1
  failed=1
elif [[ "${BOOTUI_START_OUTCOME:-success}" != success ]]; then
  echo "::error::BootUI could not start the application." >&2
  assessment=1
  failed=1
elif [[ "${BOOTUI_ATTACH_EXIT_CODE:-}" != 0 ]]; then
  echo "::error::BootUI could not attach to the application." >&2
  assessment=1
  failed=1
fi
if [[ "${BOOTUI_STOP_OUTCOME:-skipped}" != success && "${BOOTUI_STOP_OUTCOME:-skipped}" != skipped ]]; then
  echo "::error::BootUI could not stop the application it started." >&2
  failed=1
fi
if [[ "${BOOTUI_WORKLOAD_OUTCOME:-}" != success ]]; then
  echo "::error::The test command failed." >&2
  failed=1
fi
if [[ "${BOOTUI_REPORT_OUTCOME:-}" != success ]]; then
  echo "::error::BootUI could not publish a complete summary." >&2
  assessment=1
  failed=1
fi
case "$assessment" in
  0) ;;
  3) echo "::error::BootUI found a policy-threshold violation." >&2; failed=1 ;;
  *) echo "::error::BootUI could not complete a trustworthy assessment." >&2; failed=1 ;;
esac
if [[ "${BOOTUI_UPLOAD_ARTIFACT:-}" == true && "${BOOTUI_UPLOAD_OUTCOME:-}" != success ]]; then
  echo "::error::BootUI could not upload its reports." >&2
  failed=1
fi
printf 'exit-code=%s\n' "$assessment" >> "$GITHUB_OUTPUT"
exit "$failed"
