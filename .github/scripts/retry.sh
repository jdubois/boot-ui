#!/usr/bin/env bash
#
# Runs a command and, if it fails, runs it again, up to a fixed number of attempts, so one transient
# network or registry error does not fail a whole nightly publish. Only use it for idempotent
# commands (a pull, an image index creation, an inspect): every attempt runs the same command.
#
# Usage: retry.sh ATTEMPTS DELAY_SECONDS COMMAND [ARGUMENT...]
#
# The command's own output stays visible on every attempt, so a deterministic failure shows its error
# each time. The exit status is the last attempt's, never success unless an attempt succeeded.

set -uo pipefail

if [[ $# -lt 3 || ! "$1" =~ ^[1-9][0-9]*$ || ! "$2" =~ ^[0-9]+$ ]]; then
  echo "Usage: retry.sh ATTEMPTS DELAY_SECONDS COMMAND [ARGUMENT...]" >&2
  exit 2
fi

readonly ATTEMPTS="$1" DELAY="$2"
shift 2

status=0
for ((attempt = 1; attempt <= ATTEMPTS; attempt++)); do
  "$@" && exit 0
  status=$?
  if ((attempt < ATTEMPTS)); then
    echo "::warning::'$1' failed with status ${status} (attempt ${attempt} of ${ATTEMPTS}); retrying in ${DELAY}s" >&2
    sleep "$DELAY"
  fi
done
echo "::error::'$1' failed with status ${status} after ${ATTEMPTS} attempts" >&2
exit "$status"
