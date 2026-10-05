#!/usr/bin/env bash
# Builds, starts, proves, and stops one application of the validation set.
#
# Usage: validation/bin/run-app.sh <app> <command> [options]
#   build                     build the prepared application against the harness Maven repository
#   start [--agent] [--compare] [--run <name>]
#                             start it (and its containers) on its port, wait until it answers, and prove it runs
#                             the recorded v2 build. --agent attaches the bootui-agent jar; --compare keeps a run
#                             summary in a baseline file shared by the application's runs, so the second run
#                             compares with the first. The run name defaults to "main".
#   prove [--run <name>]      prove a started run uses the recorded build (start does it too)
#   stop [--run <name>]       stop what the run started, containers included
#
# VALIDATION_APP_ARGS adds arguments to a Spring application's command line, word-split, for a documented workaround
# only; a measured run never sets a BootUI property this way.
# The port comes from PORT, or the application's default (18181 to 18189). 8080 is refused.
source "$(dirname "$0")/lib.sh"

app="${1:?usage: run-app.sh <app> build|start|prove|stop [options]}"
command="${2:?usage: run-app.sh <app> build|start|prove|stop [options]}"
shift 2
agent=false
compare=false
run=main
while [ $# -gt 0 ]; do
  case "$1" in
    --agent) agent=true ;;
    --compare) compare=true ;;
    --run)
      run="$2"
      shift
      ;;
    *) die "unknown option $1" ;;
  esac
  shift
done

load_pin "$app"
load_build
SRC="$(app_src "$app")"
PORT="$APP_PORT"
check_port "$PORT"
RUN_DIR="$WORK/runs/$app/$run"
BASE_URL="http://localhost:$PORT"
# Never empty, so "${JVM_OPTS[@]}" expands under set -u on the bash 3.2 macOS ships; it also names the process.
JVM_OPTS=("-Dvalidation.app=$app")
# shellcheck disable=SC2206
APP_ARGS=(--validation.app="$app" ${VALIDATION_APP_ARGS:-})
APP_MODULE="$SRC"
[ -d "$SRC" ] || die "$app is not prepared; run validation/bin/prepare-app.sh $app"

# The application's own build, start, and containers.
# shellcheck disable=SC1090
source "$(app_home "$app")/app.sh"

prove() {
  local status jar engine sha classpath shas=()
  status="$(curl -s -o "$RUN_DIR/prove-runtime-insights.json" -w '%{http_code}' "$BASE_URL/bootui/api/runtime-insights")"
  [ "$status" = 200 ] || die "GET $BASE_URL/bootui/api/runtime-insights answered $status; 1.x has no such endpoint"
  if [ -f "$RUN_DIR/jars" ]; then
    # A Spring Boot jar carries its dependencies: hash the engine jar the running application loads.
    while read -r jar; do
      engine="$jar!/BOOT-INF/lib/bootui-engine-$BOOTUI_VERSION.jar"
      sha="$(unzip -p "$jar" "BOOT-INF/lib/bootui-engine-$BOOTUI_VERSION.jar" | (sha256sum 2>/dev/null || shasum -a 256) | awk '{print $1}')"
      [ "$sha" = "$BOOTUI_ENGINE_SHA256" ] || die "$jar packs bootui-engine with SHA-256 $sha, not the recorded build $BOOTUI_ENGINE_SHA256"
      shas+=("$sha")
    done <"$RUN_DIR/jars"
  else
    # Dev mode runs from the Maven repository: hash the engine jar the application resolves there.
    classpath="$RUN_DIR/classpath.txt"
    app_mvn "$APP_MODULE" -q dependency:build-classpath -Dmdep.outputFile="$classpath" -Dmdep.includeScope=runtime \
      >"$RUN_DIR/logs/classpath.log" 2>&1 || die "could not resolve the classpath of $APP_MODULE (see $RUN_DIR/logs/classpath.log)"
    engine="$(tr ':' '\n' <"$classpath" | grep "/bootui-engine-$BOOTUI_VERSION.jar\$" | head -1)"
    [ -n "$engine" ] || die "$app does not resolve bootui-engine $BOOTUI_VERSION"
    case "$engine" in "$M2"/*) ;; *) die "$app resolves $engine outside the harness repository $M2" ;; esac
    sha="$(sha256_of "$engine")"
    [ "$sha" = "$BOOTUI_ENGINE_SHA256" ] || die "$app resolves bootui-engine with SHA-256 $sha, not the recorded build $BOOTUI_ENGINE_SHA256"
  fi
  cat >"$RUN_DIR/proof.env" <<EOF
APP=$app
APP_COMMIT=$APP_COMMIT
RUN=$run
BASE_URL=$BASE_URL
AGENT=$agent
BOOTUI_VERSION=$BOOTUI_VERSION
BOOTUI_COMMIT=$BOOTUI_COMMIT
ENGINE_JAR=$engine
ENGINE_SHA256=$sha
PROVED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
  log "proved: $app answers runtime-insights and resolves bootui-engine $sha (the recorded build)"
}

case "$command" in
  build)
    app_build
    ;;
  start)
    if port_in_use "$PORT"; then die "port $PORT is already in use; set PORT to a free port"; fi
    rm -rf "$RUN_DIR"
    mkdir -p "$RUN_DIR/logs"
    echo "$PORT" >"$RUN_DIR/ports"
    if $agent; then
      [ -f "$BOOTUI_AGENT_JAR" ] || die "$BOOTUI_AGENT_JAR is missing"
      JVM_OPTS+=("-javaagent:$BOOTUI_AGENT_JAR")
    fi
    if $compare; then
      JVM_OPTS+=("-Dbootui.runtime-journal.baseline-file=$WORK/runs/$app/baseline.bin")
    fi
    started="$(date +%s)"
    app_start
    wait_http "$BASE_URL${APP_READY_PATH:-/}" "${APP_READY_TIMEOUT:-600}"
    echo "STARTED_SECONDS=$(($(date +%s) - started))" >"$RUN_DIR/started.env"
    prove
    ;;
  prove)
    prove
    ;;
  stop)
    stop_run "$RUN_DIR"
    if declare -F app_cleanup >/dev/null; then app_cleanup; fi
    ;;
  *) die "unknown command $command" ;;
esac
