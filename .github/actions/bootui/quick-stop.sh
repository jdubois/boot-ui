#!/usr/bin/env bash
set -euo pipefail

pid="${BOOTUI_APP_PID:-}"
if [[ -z "$pid" ]]; then
  exit 0
fi
[[ "$pid" =~ ^[1-9][0-9]*$ ]] || {
  echo "::error::The application process ID is invalid." >&2
  exit 1
}

group="-${pid}"
group_alive() {
  ps -A -o pgid= -o stat= 2>/dev/null \
    | awk -v group="$pid" '$1 == group && $2 !~ /^Z/ { found = 1 } END { exit !found }'
}

if group_alive; then
  kill -TERM -- "$group" 2>/dev/null || true
  for _ in $(seq 1 100); do
    group_alive || break
    sleep 0.1
  done
  if group_alive; then
    kill -KILL -- "$group" 2>/dev/null || true
    for _ in $(seq 1 50); do
      group_alive || break
      sleep 0.1
    done
    if group_alive; then
      echo "::error::The application process group could not be stopped." >&2
      exit 1
    fi
  fi
fi

if [[ -n "${BOOTUI_APP_URL:-}" ]]; then
  if ! python3 - "${BOOTUI_APP_URL}" <<'PY'
from urllib.parse import urlsplit
import socket
import sys

parsed = urlsplit(sys.argv[1])
with socket.socket(socket.AF_INET6 if ":" in parsed.hostname else socket.AF_INET) as sock:
    sock.settimeout(0.25)
    raise SystemExit(0 if sock.connect_ex((parsed.hostname, parsed.port)) != 0 else 1)
PY
  then
    echo "::error::The managed application's endpoint is still occupied after shutdown." >&2
    exit 1
  fi
fi
