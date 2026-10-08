#!/usr/bin/env bash
#
# Runs the published BootUI CLI against the Spring MVC sample app built from this checkout, so a server change
# that breaks the CLI people already have installed fails here, not on their machines. The CLI binds its options
# to the tool catalog it was built with and reads the application's JSON and exit codes, so it is the oldest
# client a server has to keep answering.
#
# Usage: published-cli-smoke.sh <spring-sample-app-jar>
#
# The CLI comes from Maven Central and must match the SHA-256 pinned below. Pin the newest published release:
# after a release, update PUBLISHED_CLI_VERSION and PUBLISHED_CLI_SHA256 together.
#
# Environment:
#   CLI_SMOKE_PORT             HTTP port of the sample app (default: 18093).
#   CLI_SMOKE_TIMEOUT_SECONDS  How long the sample app may take to serve BootUI (default: 300).
#   CLI_SMOKE_WORK_DIR         Where the CLI jar and the logs go (default: a new temporary directory).

set -euo pipefail

readonly PUBLISHED_CLI_VERSION="1.21.0"
readonly PUBLISHED_CLI_SHA256="5a207df1f8ea701878ed3c404411dfcb114fead514ab8e1a744f3693b296b0a6"
readonly PUBLISHED_CLI_URL="https://repo1.maven.org/maven2/com/julien-dubois/bootui/bootui-cli/${PUBLISHED_CLI_VERSION}/bootui-cli-${PUBLISHED_CLI_VERSION}-all.jar"

# The sample runs with this panel disabled, so its tool must exit 2. The other tool's panel needs a library the
# sample does not have, so the sample does not advertise it and the CLI must exit 1.
readonly DISABLED_PANEL="conditions"
readonly DISABLED_TOOL="get_conditions"
readonly DISABLED_COMMAND=(conditions)
readonly UNAVAILABLE_TOOL="get_jms_activity"
readonly UNAVAILABLE_COMMAND=(jms)

if [[ $# -ne 1 || ! -f "$1" ]]; then
  printf 'Usage: %s <spring-sample-app-jar>\n' "$0" >&2
  exit 2
fi

readonly SAMPLE_JAR="$1"
readonly PORT="${CLI_SMOKE_PORT:-18093}"
readonly TIMEOUT_SECONDS="${CLI_SMOKE_TIMEOUT_SECONDS:-300}"
WORK_DIR="${CLI_SMOKE_WORK_DIR:-$(mktemp -d)}"
mkdir -p "$WORK_DIR"
WORK_DIR="$(cd "$WORK_DIR" && pwd)"
readonly WORK_DIR
readonly BASE_URL="http://127.0.0.1:${PORT}"
readonly CLI_JAR="$WORK_DIR/bootui-cli-${PUBLISHED_CLI_VERSION}-all.jar"
readonly APP_LOG="$WORK_DIR/sample-app.log"

sha256_hex() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d ' ' -f 1
}

fail() {
  echo "::error::$*"
  exit 1
}

if [[ ! -f "$CLI_JAR" ]]; then
  curl -fsSL --retry 3 -o "$CLI_JAR.part" "$PUBLISHED_CLI_URL"
  mv "$CLI_JAR.part" "$CLI_JAR"
fi
actual_sha256="$(sha256_hex "$CLI_JAR")"
if [[ "$actual_sha256" != "$PUBLISHED_CLI_SHA256" ]]; then
  rm -f "$CLI_JAR"
  fail "bootui-cli ${PUBLISHED_CLI_VERSION}-all.jar has SHA-256 ${actual_sha256}, expected ${PUBLISHED_CLI_SHA256}."
fi
echo "bootui-cli ${PUBLISHED_CLI_VERSION} (all) matches its pinned SHA-256."

port_open() {
  (echo > "/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

if port_open "$PORT"; then
  fail "Port ${PORT} is already in use; set CLI_SMOKE_PORT to a free port."
fi

APP_PID=""
stop_app() {
  if [[ -n "$APP_PID" ]]; then
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
    APP_PID=""
  fi
}
trap stop_app EXIT

# The repackaged jar has no DevTools, so BootUI activates through the explicitly active dev profile.
java -jar "$SAMPLE_JAR" \
  --spring.profiles.active=dev \
  --server.port="$PORT" \
  --spring.devtools.restart.enabled=false \
  --bootui.panels."$DISABLED_PANEL".enabled=false \
  > "$APP_LOG" 2>&1 &
APP_PID=$!

deadline=$(( SECONDS + TIMEOUT_SECONDS ))
until curl -fsS -o /dev/null "$BASE_URL/bootui/api/cli" 2>/dev/null; do
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    cat "$APP_LOG"
    fail "The sample app exited before BootUI answered at ${BASE_URL}/bootui/api/cli."
  fi
  if (( SECONDS > deadline )); then
    cat "$APP_LOG"
    fail "BootUI did not answer at ${BASE_URL}/bootui/api/cli within ${TIMEOUT_SECONDS} seconds."
  fi
  sleep 2
done
echo "The sample app answers at ${BASE_URL}."

# Usage: run_cli <expected-exit-code> <label> <cli-arguments...>
# Leaves the command's output in $WORK_DIR/<label>.out and .err.
run_cli() {
  local expected="$1" label="$2"
  shift 2
  local status=0
  java -jar "$CLI_JAR" --url "$BASE_URL" "$@" > "$WORK_DIR/$label.out" 2> "$WORK_DIR/$label.err" || status=$?
  if [[ "$status" != "$expected" ]]; then
    echo "--- stdout"; cat "$WORK_DIR/$label.out"
    echo "--- stderr"; cat "$WORK_DIR/$label.err"
    fail "bootui $* exited ${status}, expected ${expected}."
  fi
  echo "bootui $* exited ${status}, as expected."
}

# Usage: check_json <label> <python-assertion>
# Parses $WORK_DIR/<label>.out as JSON into `doc` and evaluates the assertion against it.
check_json() {
  local label="$1" assertion="$2"
  python3 - "$WORK_DIR/$label.out" "$assertion" <<'PY' || fail "bootui output for '$label' failed: $2"
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    doc = json.load(handle)
if not eval(sys.argv[2], {"doc": doc}):
    print(json.dumps(doc, indent=2)[:4000])
    sys.exit(1)
PY
}

run_cli 0 version --version
grep -qx "bootui ${PUBLISHED_CLI_VERSION}" "$WORK_DIR/version.out" \
  || fail "bootui --version printed '$(cat "$WORK_DIR/version.out")', expected 'bootui ${PUBLISHED_CLI_VERSION}'."

run_cli 0 tools tools --json
check_json tools "doc['enabled'] is True and doc['toolCount'] == len(doc['tools']) > 0"
check_json tools "any(t['name'] == 'get_overview' and t['panelEnabled'] for t in doc['tools'])"
check_json tools "any(t['name'] == '${DISABLED_TOOL}' and not t['panelEnabled'] for t in doc['tools'])"
check_json tools "not any(t['name'] == '${UNAVAILABLE_TOOL}' for t in doc['tools'])"

run_cli 0 overview overview --json
check_json overview "doc.get('serverPort') == ${PORT} and doc.get('applicationName')"

run_cli 0 beans beans --query bootUi --limit 5 --json
check_json beans "isinstance(doc, dict) and len(doc.get('beans', [])) > 0"

# A scan and a capture control answer compactly; the published CLI must still parse and print both.
run_cli 0 architecture-scan architecture scan --json
check_json architecture-scan "doc['reportTool'] == 'get_architecture_report' and isinstance(doc['topFindings'], list)"
check_json architecture-scan "isinstance(doc['severityCounts'], list) and doc['violationDetails']['scanId']"

run_cli 0 sql-clear sql clear --json
check_json sql-clear "doc['action'] == 'cleared' and doc['retained'] == 0 and isinstance(doc['totalCaptured'], int)"

run_cli 1 unavailable "${UNAVAILABLE_COMMAND[@]}" --json
[[ ! -s "$WORK_DIR/unavailable.out" ]] || fail "bootui ${UNAVAILABLE_COMMAND[*]} printed to stdout."
grep -q "does not expose '${UNAVAILABLE_TOOL}'" "$WORK_DIR/unavailable.err" \
  || fail "bootui ${UNAVAILABLE_COMMAND[*]} did not say the application does not expose ${UNAVAILABLE_TOOL}."

run_cli 2 disabled "${DISABLED_COMMAND[@]}" --json
[[ ! -s "$WORK_DIR/disabled.out" ]] || fail "bootui ${DISABLED_COMMAND[*]} printed to stdout."
grep -q "panel '${DISABLED_PANEL}'" "$WORK_DIR/disabled.err" \
  || fail "bootui ${DISABLED_COMMAND[*]} did not name the disabled '${DISABLED_PANEL}' panel."

echo "The published bootui-cli ${PUBLISHED_CLI_VERSION} works against this build."
