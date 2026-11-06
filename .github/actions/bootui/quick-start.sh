#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "::error::$*" >&2
  exit 1
}

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
state_dir="${BOOTUI_STATE_DIR:-}"
configured="${BOOTUI_APP_COMMAND:-auto}"
url="${BOOTUI_URL:-http://127.0.0.1:8080}"
api_path="${BOOTUI_API_PATH:-/bootui/api}"
startup_timeout="${BOOTUI_STARTUP_TIMEOUT:-120}"

[[ -n "$state_dir" && -d "$state_dir" && ! -L "$state_dir" ]] \
  || fail "A fresh state directory is required to start the application."
[[ "$startup_timeout" =~ ^[1-9][0-9]*$ && "$startup_timeout" -le 600 ]] \
  || fail "startup-timeout must be between 1 and 600 seconds."
[[ "$url" != *$'\n'* && "$url" != *$'\r'* && "$api_path" != *$'\n'* && "$api_path" != *$'\r'* ]] \
  || fail "url and api-path must not contain a newline."
for required in curl python3; do
  command -v "$required" >/dev/null 2>&1 || fail "Required command is unavailable: $required."
done

if [[ "$configured" == none ]]; then
  echo "managed=false" >> "$GITHUB_OUTPUT"
  echo "BootUI will assess the externally managed application."
  exit 0
fi

command="$(BOOTUI_WORKING_DIRECTORY="${GITHUB_WORKSPACE:-$PWD}" bash "$script_dir/detect-command.sh" app "$configured")"
[[ -n "${command//[[:space:]]/}" ]] || fail "The detected application command is empty."

target="$(python3 "$script_dir/validate-target.py" "$url" "$api_path")" \
  || fail "url and api-path must name an explicit literal-loopback BootUI endpoint."
scheme="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["scheme"])' <<<"$target")"
[[ "$scheme" == http ]] || fail "Managed applications must use http; use app-command: none for a separately configured HTTPS target."
host="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["host"])' <<<"$target")"
port="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["port"])' <<<"$target")"

if python3 - "$host" "$port" <<'PY'
import socket
import sys

with socket.socket(socket.AF_INET6 if ":" in sys.argv[1] else socket.AF_INET) as sock:
    sock.settimeout(0.25)
    raise SystemExit(0 if sock.connect_ex((sys.argv[1], int(sys.argv[2]))) == 0 else 1)
PY
then
  fail "The configured endpoint is already occupied; use app-command: none to assess an externally managed application."
fi

log="$state_dir/application.log"
pid_file="$state_dir/application.pid"
overrides="$state_dir/boot-ui.yml"
: > "$overrides"
export BOOTUI_ENABLED=ON
export BOOTUI_CLI_ENABLED=true
export BOOTUI_EXPOSE_VALUES=METADATA_ONLY
export BOOTUI_OVERRIDES_FILE="$overrides"
export SERVER_ADDRESS="$host"
export SERVER_PORT="$port"
export QUARKUS_HTTP_HOST="$host"
export QUARKUS_HTTP_PORT="$port"
export QUARKUS_CONSOLE_ENABLED=false
export QUARKUS_TEST_CONTINUOUS_TESTING=disabled
export QUARKUS_ANALYTICS_DISABLED=true

pid=
trap 'if (( $? != 0 )) && [[ -n "$pid" ]]; then BOOTUI_APP_PID="$pid" bash "$script_dir/quick-stop.sh" >/dev/null 2>&1 || true; fi' EXIT

python3 - "$command" "$log" "$pid_file" <<'PY'
import os
from pathlib import Path
import subprocess
import sys

command, log, pid_file = sys.argv[1:]
with open(log, "ab", buffering=0) as output:
    process = subprocess.Popen(
        ["/bin/bash", "-e", "-o", "pipefail", "-c", command],
        cwd=os.environ.get("GITHUB_WORKSPACE") or os.getcwd(),
        env=os.environ.copy(),
        stdin=subprocess.DEVNULL,
        stdout=output,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )
Path(pid_file).write_text(f"{process.pid}\n", encoding="ascii")
PY

pid="$(tr -d '[:space:]' < "$pid_file")"
[[ "$pid" =~ ^[0-9]+$ ]] || fail "The application launcher did not return a valid process ID."
{
  echo "managed=true"
  printf 'pid=%s\n' "$pid"
  printf 'log=%s\n' "$log"
} >> "$GITHUB_OUTPUT"

endpoint="${url%/}${api_path%/}/cli"
ready="$state_dir/ready.json"
deadline=$((SECONDS + startup_timeout))
while (( SECONDS < deadline )); do
  process_stat="$(ps -o stat= -p "$pid" 2>/dev/null | tr -d '[:space:]')"
  if [[ -z "$process_stat" || "$process_stat" == Z* ]]; then
    fail "The application command exited before BootUI became ready."
  fi
  if curl --fail --silent --show-error --noproxy '*' --connect-timeout 2 --max-time 5 \
      --output "$ready" "$endpoint" 2>/dev/null \
      && python3 - "$ready" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    document = json.load(stream)
if document.get("enabled") is not True:
    raise SystemExit(1)
PY
  then
    trap - EXIT
    echo "BootUI is ready at the configured loopback endpoint."
    exit 0
  fi
  sleep 1
done

process_stat="$(ps -o stat= -p "$pid" 2>/dev/null | tr -d '[:space:]')"
if [[ -z "$process_stat" || "$process_stat" == Z* ]]; then
  fail "The application command exited before BootUI became ready."
fi
fail "BootUI did not become ready within $startup_timeout seconds."
