#!/usr/bin/env bash
# Time to first observation (§2.2), measured the same way on every stack: from adding the dependency to reading a first
# observation, with tracing off and no BootUI property.
#
#   1. Preconditions, not timed: the BootUI build is installed (build-v2.sh), and the application was built once
#      without BootUI, so its own dependencies are downloaded, as on a developer's machine.
#   2. The stopwatch starts, and bootui.patch is applied: the dependency, exactly as docs/SETUP.md says.
#   3. The application is built and started the way it is run (a jar for Spring, dev mode for Quarkus).
#   4. Once it answers, one iteration of its traffic is sent.
#   5. Runtime Insights is read every 2 s; the stopwatch stops at the first default-visible row, of any status, and
#      the time to the first OBSERVED one is recorded too.
#
# Usage: validation/bin/ttfo.sh <app> [--reason <why>]
#   Appends one JSON line to $WORK/ttfo.jsonl and prints it. Each application is measured once on the rerun's commit;
#   measuring it again needs --reason, which the scorer prints beside the superseded measurement.
source "$(dirname "$0")/lib.sh"

app="${1:?usage: ttfo.sh <app> [--reason <why>]}"
reason=""
if [ "${2:-}" = "--reason" ]; then reason="${3:?--reason needs a value}"; fi
require_cmd node curl git
load_pin "$app"
load_build
[ -z "${VALIDATION_APP_ARGS:-}" ] || die "VALIDATION_APP_ARGS is set; a measured run takes no workaround argument"
[ "${BOOTUI_TREE_CLEAN:-false}" = true ] || die "the recorded build was made from a dirty checkout; rebuild it with build-v2.sh"
[ -z "$(git -C "$REPO_ROOT" status --porcelain)" ] || die "the checkout has uncommitted or untracked changes"
[ "$(git -C "$REPO_ROOT" rev-parse HEAD)" = "$BOOTUI_COMMIT" ] ||
  die "the checkout is not at the recorded build $BOOTUI_COMMIT; run validation/bin/build-v2.sh"
PORT="$APP_PORT"
check_port "$PORT"
home="$(app_home "$app")"
if [ -f "$WORK/ttfo.jsonl" ] && [ -z "$reason" ] &&
  node -e 'const [f,a,c]=process.argv.slice(1);const rows=require("fs").readFileSync(f,"utf8").split("\n").filter(Boolean).map(JSON.parse);process.exit(rows.some(r=>r.app===a&&r.bootuiCommit===c)?0:1)' \
    "$WORK/ttfo.jsonl" "$app" "$BOOTUI_COMMIT"; then
  die "$app was already measured on $BOOTUI_COMMIT; pass --reason to measure it again"
fi

log "preparing $app without BootUI and building it once (not timed)"
"$VALIDATION_HOME/bin/prepare-app.sh" "$app" --without-bootui
if [ "$APP_STACK" != quarkus ]; then
  # Without BootUI the build is the baseline; for Quarkus dev mode compiles the application itself.
  "$VALIDATION_HOME/bin/run-app.sh" "$app" build >"$WORK/ttfo-$app-baseline.log" 2>&1 ||
    die "the baseline build failed, see $WORK/ttfo-$app-baseline.log"
fi

started="$(node -e 'console.log(Date.now())')"
sed "s/@BOOTUI_VERSION@/$BOOTUI_VERSION/g" "$home/bootui.patch" | git -C "$(app_src "$app")" apply --whitespace=nowarn -
if [ "$APP_STACK" != quarkus ]; then "$VALIDATION_HOME/bin/run-app.sh" "$app" build >"$WORK/ttfo-$app-build.log" 2>&1; fi
built="$(node -e 'console.log(Date.now())')"
trap '"$VALIDATION_HOME/bin/run-app.sh" "$app" stop --run ttfo >/dev/null 2>&1 || true' EXIT
"$VALIDATION_HOME/bin/run-app.sh" "$app" start --run ttfo
ready="$(node -e 'console.log(Date.now())')"
node "$VALIDATION_HOME/traffic/$app.mjs" --base-url "http://localhost:$PORT" --iterations 1 --pause-ms 0 \
  --allow-unexpected >/dev/null

node --input-type=module - "http://localhost:$PORT/bootui/api/runtime-insights" "$started" "$built" "$ready" "$app" "$APP_STACK" \
  "$BOOTUI_COMMIT" "$reason" >>"$WORK/ttfo.jsonl" <<'EOF'
const [url, started, built, ready, app, stack, commit, reason] = process.argv.slice(2)
const deadline = Date.now() + 20 * 60_000
let first = null
let observed = null
while (Date.now() < deadline && !observed) {
  try {
    const report = await (await fetch(url)).json()
    const rows = (report.observations || []).filter((o) => o.listed !== false)
    if (rows.length && !first) first = {at: Date.now(), status: rows[0].status, kind: rows[0].kind}
    if (rows.some((o) => o.status === 'OBSERVED')) observed = Date.now()
  } catch {}
  if (!observed) await new Promise((r) => setTimeout(r, 2000))
}
const s = (t) => (t ? Math.round((t - Number(started)) / 100) / 10 : null)
console.log(JSON.stringify({
  app, stack, bootuiCommit: commit, measuredAt: new Date().toISOString(),
  buildSeconds: s(Number(built)), readySeconds: s(Number(ready)),
  seconds: s(first?.at), firstStatus: first?.status ?? null, firstKind: first?.kind ?? null,
  firstObservedSeconds: s(observed),
  ...(reason ? {reason} : {})
}))
EOF
tail -1 "$WORK/ttfo.jsonl"
