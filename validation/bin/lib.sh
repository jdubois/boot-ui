# Shared shell helpers for the BootUI 2.0 validation harness. Source it; do not run it.
# shellcheck shell=bash

set -euo pipefail

VALIDATION_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$VALIDATION_HOME/.." && pwd)"
WORK="${BOOTUI_VALIDATION_WORK:-$VALIDATION_HOME/.work}"
mkdir -p "$WORK"
WORK="$(cd "$WORK" && pwd)"
# One Maven repository for the BootUI build and every application, never ~/.m2: the v2 branch carries the version of
# a released 1.x artifact, so only a repository of its own guarantees the application resolves the v2 build.
M2="${BOOTUI_VALIDATION_M2:-$WORK/m2}"
mkdir -p "$M2"
M2="$(cd "$M2" && pwd)"
BUILD_ENV="$WORK/bootui-build.env"

log() { printf '[validation] %s\n' "$*" >&2; }
die() {
  printf '[validation] error: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  local cmd
  for cmd in "$@"; do
    command -v "$cmd" >/dev/null 2>&1 || die "'$cmd' is required"
  done
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

# Port 8080 belongs to the developer's own application: the harness never listens on it, probes it, or stops it.
check_port() {
  local port="$1"
  [[ "$port" =~ ^[0-9]+$ ]] || die "port '$port' is not a number"
  [ "$port" != 8080 ] || die "port 8080 is reserved for the developer's own application; choose another port"
}

port_in_use() {
  lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
}

load_build() {
  [ -f "$BUILD_ENV" ] || die "no BootUI build recorded in $BUILD_ENV; run validation/bin/build-v2.sh first"
  # shellcheck disable=SC1090
  source "$BUILD_ENV"
}

app_home() { printf '%s/apps/%s' "$VALIDATION_HOME" "$1"; }
app_src() { printf '%s/apps/%s' "$WORK" "$1"; }

load_pin() {
  local pin
  pin="$(app_home "$1")/pin.env"
  [ -f "$pin" ] || die "unknown application '$1' (no $pin)"
  # shellcheck disable=SC1090
  source "$pin"
}

# Maven for an application, always against the harness repository.
app_mvn() {
  local dir="$1"
  shift
  local mvn=mvn
  if [ -x "$dir/mvnw" ]; then mvn=./mvnw; fi
  (cd "$dir" && "$mvn" -B -ntp -Dmaven.repo.local="$M2" "$@")
}

# Starts a long-lived process in the background, records its PID under the run directory, and logs to a file.
start_bg() {
  local run_dir="$1" name="$2"
  shift 2
  mkdir -p "$run_dir/logs" "$run_dir/pids"
  log "starting $name: $*"
  nohup "$@" >"$run_dir/logs/$name.log" 2>&1 &
  echo $! >"$run_dir/pids/$name.pid"
}

# Stops every process this run started, then anything still listening on the run's ports. Never port 8080.
stop_run() {
  local run_dir="$1" pid_file pid port
  if [ -d "$run_dir/pids" ]; then
    for pid_file in "$run_dir"/pids/*.pid; do
      [ -e "$pid_file" ] || continue
      pid="$(cat "$pid_file")"
      if kill -0 "$pid" 2>/dev/null; then
        log "stopping $(basename "$pid_file" .pid) (pid $pid)"
        kill "$pid" 2>/dev/null || true
      fi
      rm -f "$pid_file"
    done
  fi
  sleep 3
  if [ -f "$run_dir/ports" ]; then
    while read -r port; do
      [ -n "$port" ] && [ "$port" != 8080 ] || continue
      for pid in $(lsof -nP -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true); do
        log "stopping pid $pid still listening on $port"
        kill "$pid" 2>/dev/null || true
      done
    done <"$run_dir/ports"
  fi
}

# The runnable jar under a target directory, by file name pattern; fails clearly when the build has not run.
find_jar() {
  local jar
  jar="$(find "$1" -maxdepth 1 -name "$2" ! -name '*-plain.jar' ! -name '*-sources.jar' 2>/dev/null | sort | head -1)"
  [ -n "$jar" ] || die "no $2 under $1; run validation/bin/run-app.sh <app> build"
  printf '%s' "$jar"
}

# Records a Spring Boot jar the run starts, so the proof reads the bootui-engine jar packed inside it.
record_jar() {
  echo "$1" >>"$RUN_DIR/jars"
}

wait_http() {
  local url="$1" timeout="${2:-300}" start
  start="$(date +%s)"
  until curl -fsS -o /dev/null --max-time 5 "$url" 2>/dev/null; do
    if [ $(($(date +%s) - start)) -ge "$timeout" ]; then
      die "$url did not answer within ${timeout}s"
    fi
    sleep 2
  done
}

# Starts a throwaway container the harness owns; its name always starts with bootui-validation-.
start_container() {
  local name="bootui-validation-$1"
  shift
  docker rm -f "$name" >/dev/null 2>&1 || true
  log "starting container $name"
  docker run -d --name "$name" "$@" >/dev/null
}

stop_container() {
  docker rm -f "bootui-validation-$1" >/dev/null 2>&1 || true
}
