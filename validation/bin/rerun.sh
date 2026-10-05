#!/usr/bin/env bash
# One measured run of one application: build, start, prove the v2 build, send the registered traffic, collect the
# evidence the reviewers judge, and stop. Evidence lands in $WORK/evidence/<label>/ with a run.json the worksheet reads.
#
# Usage: validation/bin/rerun.sh <app> [--agent] [--change] [--compare] [--skip-build] [--reason <why>] [--iterations N]
#   --agent        attach the bootui-agent jar (the agent-attached runs: JHipster and the bookstore, and Super Heroes
#                  and PetClinic with --change)
#   --change       start the application in dev mode (Quarkus dev mode, or Spring Boot DevTools), send the traffic,
#                  apply its change.patch while it runs, wait for the restart in the same JVM (a new journal run, which
#                  changed-code-not-executed compares with), send the traffic again, then collect
#   --compare      run twice with a shared baseline file and no code change, and collect the second run with its
#                  comparison under <app>-comparison (the only run that sets a BootUI property)
#   --reason why   required when the run's evidence already exists: the previous attempt is kept as
#                  <label>.attempt-N with this reason, and the worksheet reports it
#   --iterations N override the traffic's registered iteration count: a smoke test, whose evidence goes to
#                  $WORK/smoke/ and never into a rerun
source "$(dirname "$0")/lib.sh"

app="${1:?usage: rerun.sh <app> [--agent] [--change] [--compare] [--skip-build]}"
shift
agent=false
change=false
compare=false
build=true
reason=""
iterations=()
while [ $# -gt 0 ]; do
  case "$1" in
    --agent) agent=true ;;
    --change) change=true ;;
    --compare) compare=true ;;
    --skip-build) build=false ;;
    --reason)
      reason="$2"
      shift
      ;;
    --iterations)
      iterations=(--iterations "$2")
      shift
      ;;
    *) die "unknown option $1" ;;
  esac
  shift
done
require_cmd node docker curl git
load_pin "$app"
load_build
if [ ${#iterations[@]} -eq 0 ]; then
  # A measured run uses the registered harness and the recorded build, from a clean checkout of one commit.
  [ -z "${VALIDATION_APP_ARGS:-}" ] || die "VALIDATION_APP_ARGS is set; a measured run takes no workaround argument"
  [ -z "$(git -C "$REPO_ROOT" status --porcelain)" ] || die "the checkout has uncommitted or untracked changes"
  [ "$(git -C "$REPO_ROOT" rev-parse HEAD)" = "$BOOTUI_COMMIT" ] ||
    die "the checkout is not at the recorded build $BOOTUI_COMMIT; run validation/bin/build-v2.sh"
fi
harness="$(node "$VALIDATION_HOME/scoring/harness.mjs")"
PORT="$APP_PORT"
check_port "$PORT"
home="$(app_home "$app")"
label="$app"
$agent && label="$app+agent"
$compare && label="$app-comparison"
evidence_root="$WORK/evidence"
[ ${#iterations[@]} -eq 0 ] || evidence_root="$WORK/smoke"
evidence="$evidence_root/$label"
run_args=()
$agent && run_args+=(--agent)
$change && run_args+=(--dev)
if $change && [ ! -f "$home/change.patch" ]; then die "$app has no change.patch"; fi

"$VALIDATION_HOME/bin/prepare-app.sh" "$app"
$build && "$VALIDATION_HOME/bin/run-app.sh" "$app" build

traffic() {
  local name="$1"
  node "$VALIDATION_HOME/traffic/$app.mjs" --base-url "http://localhost:$PORT" ${iterations[@]+"${iterations[@]}"} \
    --summary "$evidence/traffic-$name.json"
}

services() {
  # shellcheck disable=SC1090
  (
    SRC="$(app_src "$app")"
    RUN_DIR=/dev/null
    JVM_OPTS=()
    APP_ARGS=()
    source "$home/app.sh"
    echo "${APP_SERVICES:-}"
  )
}

collect() {
  local list entry
  list="$(services)"
  if [ -z "$list" ]; then
    node "$VALIDATION_HOME/bin/collect.mjs" --base-url "http://localhost:$PORT" --out "$evidence"
    echo '[]' >"$evidence/.services"
  else
    : >"$evidence/.services"
    for entry in $list; do
      node "$VALIDATION_HOME/bin/collect.mjs" --base-url "${entry#*=}" --out "$evidence/${entry%%=*}"
      echo "${entry%%=*}" >>"$evidence/.services"
    done
  fi
}

run_once() {
  local run="$1"
  shift
  "$VALIDATION_HOME/bin/run-app.sh" "$app" start --run "$run" "${run_args[@]+"${run_args[@]}"}" "$@"
}

if [ -d "$evidence" ] && [ ${#iterations[@]} -eq 0 ]; then
  # A measured run is never silently replaced: the previous attempt stays, with why it was superseded.
  [ -n "$reason" ] || die "$evidence exists; pass --reason to keep it as a superseded attempt and run again"
  attempt=1
  while [ -e "$evidence.attempt-$attempt" ]; do attempt=$((attempt + 1)); done
  if [ -f "$evidence/run.json" ]; then
    node -e 'const fs=require("fs");const [f,r]=process.argv.slice(1);const j=JSON.parse(fs.readFileSync(f,"utf8"));j.supersededBecause=r;fs.writeFileSync(f,JSON.stringify(j,null,1)+"\n")' \
      "$evidence/run.json" "$reason"
  else
    printf '{"app": "%s", "supersededBecause": %s}\n' "$app" "$(node -e 'console.log(JSON.stringify(process.argv[1]))' "$reason")" \
      >"$evidence/run.json"
  fi
  mv "$evidence" "$evidence.attempt-$attempt"
  log "kept the previous attempt as $evidence.attempt-$attempt"
fi
rm -rf "$evidence"
mkdir -p "$evidence"
trap '"$VALIDATION_HOME/bin/run-app.sh" "$app" stop --run "${current_run:-main}" >/dev/null 2>&1 || true' EXIT

if $compare; then
  rm -f "$WORK/runs/$app/baseline.bin"
  current_run=baseline
  run_once baseline --compare
  traffic baseline
  "$VALIDATION_HOME/bin/run-app.sh" "$app" stop --run baseline
  current_run=main
  run_once main --compare
  traffic main
else
  current_run=main
  run_once main
  traffic main
  if $change; then
    journal_run() {
      curl -s --max-time 10 "http://localhost:$PORT/bootui/api/activity/journal" |
        node -e 'let t="";process.stdin.on("data",d=>t+=d).on("end",()=>{try{console.log(JSON.parse(t).runId||"")}catch{console.log("")}})'
    }
    before="$(journal_run)"
    log "applying change.patch while $app runs (journal run $before)"
    git -C "$(app_src "$app")" apply --whitespace=nowarn "$home/change.patch"
    "$VALIDATION_HOME/bin/run-app.sh" "$app" reload --run main
    waited=0
    until [ -n "$(journal_run)" ] && [ "$(journal_run)" != "$before" ]; do
      [ "$waited" -lt 300 ] || die "$app did not restart into a new run within 300 s after the change"
      sleep 3
      waited=$((waited + 3))
    done
    log "restarted into journal run $(journal_run)"
    traffic after-change
  fi
fi
sleep 10
collect
cp "$WORK/runs/$app/$current_run/proof.env" "$evidence/proof.env"
engine_sha="$(sed -n 's/^ENGINE_SHA256=//p' "$evidence/proof.env")"
services_json="$(node -e 'const fs=require("fs");const l=fs.readFileSync(process.argv[1],"utf8").trim();console.log(JSON.stringify(l.startsWith("[")?JSON.parse(l):l.split("\n").filter(Boolean)))' "$evidence/.services")"
rm -f "$evidence/.services"
cat >"$evidence/run.json" <<EOF
{"app": "$app", "role": "$APP_ROLE", "stack": "$APP_STACK", "agent": $agent, "change": $change, "comparison": $compare,
 "iterationsOverride": ${iterations[1]:-null},
 "services": $services_json, "appCommit": "$APP_COMMIT", "bootuiVersion": "$BOOTUI_VERSION",
 "bootuiCommit": "$BOOTUI_COMMIT", "engineSha256": "$engine_sha", "harnessSha256": "$harness",
 "collectedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"}
EOF
"$VALIDATION_HOME/bin/run-app.sh" "$app" stop --run "$current_run"
trap - EXIT
log "evidence for $label is in $evidence"
