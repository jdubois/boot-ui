#!/usr/bin/env bash
# Decides whether a workflow run must exercise the BootUI agent's CI legs (PLAN-v2 M5-12): the agent-attached
# integration/Playwright legs and overhead benchmarks in build.yml, and the JDK-specific agent checks.
#
# Pull requests and pushes run only the legs affected by their changed paths. `agent` covers integration and browser
# tests, including the engine/adapter wiring; `overhead` covers the agent runtime and benchmark inputs, not every
# consumer of agent evidence. The costlier `extras` measurements run only on relevant pushes or with the `agent`
# label. Schedules, manual runs, and the label run everything.
#
# Inputs (environment): GITHUB_EVENT_NAME, GITHUB_OUTPUT; for pull requests PR_NUMBER, PR_LABELS (comma separated),
# GITHUB_REPOSITORY, GH_TOKEN; for pushes PUSH_BEFORE and PUSH_AFTER (with both commits in the checkout).
set -euo pipefail

# Paths whose changes can change what the agent legs observe: the agent and its bridge, the engine and adapter code
# that claims it, drains it, or opens the scopes it reads, the agent-backed panels, their sample seeds and specs, the
# agent ITs, the build (dependency versions such as Byte Buddy's), and these workflows.
pattern='^('
pattern+='bootui-agent-bridge/|bootui-agent/'
pattern+='|bootui-engine/src/main/java/io/github/jdubois/bootui/engine/(javaagent|codepaths|inventory|sideeffects|correlation)/'
# The journal records and summarizes agent evidence (the run summary's side effects, async hand-offs, the work-end
# capture), the runtime model diffs it, and Insights reads it (change impact, run comparison, work after response), so
# their whole packages count; the agent legs then run without the costlier `extras` measurements.
pattern+='|bootui-engine/src/main/java/io/github/jdubois/bootui/engine/(journal|model|insights)/'
pattern+='|bootui-engine/src/main/java/io/github/jdubois/bootui/engine/support/BootUiHttpClients'
# Caught exceptions, the Live Activity badges and execution profiles, and the agent's MCP views read the agent's evidence.
pattern+='|bootui-engine/src/main/java/io/github/jdubois/bootui/engine/exceptions/(CaughtExceptionsReader|CaughtOutcomes)'
pattern+='|bootui-engine/src/main/java/io/github/jdubois/bootui/engine/web/(LiveActivityAssembler|ExecutionProfileAssembler|ProfileCapabilities)'
pattern+='|bootui-engine/src/main/java/io/github/jdubois/bootui/engine/mcp/McpAgentViews'
pattern+='|bootui-spring-boot-starter/src/main/java/io/github/jdubois/bootui/autoconfigure/(javaagent|codepaths|inventory|sideeffects)/'
pattern+='|bootui-spring-boot-starter/src/main/java/io/github/jdubois/bootui/autoconfigure/(activity/RequestCorrelationFilter|reactive/ReactiveRequestCorrelationFilter)'
# Where the adapters register their event loops with the blocking sensor (M5-5c).
pattern+='|bootui-spring-boot-starter/src/main/java/io/github/jdubois/bootui/autoconfigure/(reactive/ReactiveThreadKinds|restclienttrace/RestClientTraceExchangeFilter)'
pattern+='|bootui-quarkus/src/main/java/io/github/jdubois/bootui/quarkus/web/QuarkusHttpExchangeCaptureFilter'
# Where the adapters wire request ends to the thread-activity and resources sensors (M5-5e, M5-5g).
pattern+='|bootui-spring-boot-starter/src/main/java/io/github/jdubois/bootui/autoconfigure/BootUiEngineConfiguration'
pattern+='|bootui-quarkus/src/main/java/io/github/jdubois/bootui/quarkus/BootUiEngineProducer'
# Where the adapters open the thread-locals sensor's scopes (M5-5f), and BootUI's own thread locals' marker class.
pattern+='|bootui-spring-boot-starter/src/main/java/io/github/jdubois/bootui/autoconfigure/(BootUiAutoConfiguration|reactive/(BootUiCorrelationThreadLocalAccessor|ReactorThreadLocalsScopes)|scheduled/ScheduledTaskRunObservationHandler)'
pattern+='|bootui-quarkus/src/main/java/io/github/jdubois/bootui/quarkus/(web/QuarkusThreadLocalsFilter|scheduled/QuarkusScheduledExecutionInterceptor)'
pattern+='|bootui-engine/src/main/java/io/github/jdubois/bootui/engine/support/BootUiThreadLocal'
pattern+='|bootui-quarkus(-deployment)?/src/main/java/io/github/jdubois/bootui/quarkus/(deployment/)?(agent|javaagent|codepaths|inventory|sideeffects|correlation)/'
pattern+='|bootui-ui/src/main/frontend/src/views/(JavaAgent|CodeInventory|CodePaths|SideEffects|MethodProbes)'
pattern+='|bootui-ui/src/main/frontend/src/views/components/(MethodProbes|RequestCodePath|ChangeImpact|RunComparison|CaughtInCode)'
pattern+='|bootui-spring-sample-app/e2e/(tests-agent|tests-webflux-agent|agent-(config|jar)\.js$|playwright\.(agent[^/]*|webflux-agent)\.config)'
# The MVC agent leg runs the whole MVC suite and its scripts, the sample's POM copies the companion agents' jars, and the
# e2e package.json holds the agent legs' test:agent* scripts.
pattern+='|bootui-spring-sample-app/e2e/(tests|scripts)/'
pattern+='|bootui-spring-sample-app/(pom\.xml|e2e/package\.json)$'
pattern+='|bootui-quarkus-sample-app/e2e/(tests-agent|playwright\.agent\.config)'
pattern+='|bootui-spring-sample-app/src/test/java/.*Agent'
pattern+='|bootui-quarkus-deployment/src/test/java/.*/BootUiAgentLiveReloadLeakIT\.java$'
pattern+='|bootui-(quarkus(-parent|-deployment)?|spring-webflux-sample-app)/pom\.xml$'
pattern+='|bootui-spring-sample-app/src/test/java/io/github/jdubois/bootui/sample/SampleExecutableJar(ReadinessTests)?\.java$'
pattern+='|bootui-quarkus/src/main/java/io/github/jdubois/bootui/quarkus/web/SideEffectsResource'
pattern+='|bootui-(spring-sample-app|spring-webflux-sample-app|quarkus-sample-app)/src/main/java/.*/sideeffects/'
pattern+='|bootui-(spring|quarkus)-sample-app/e2e/tests[^/]*/side-effects\.spec\.js$'
pattern+=')'

build_pattern='^(pom\.xml$|bootui-(engine|spring-boot-starter|spring-sample-app|ui)/pom\.xml$'
build_pattern+='|\.mvn/|mvnw(\.cmd)?$'
build_pattern+='|\.github/workflows/(build|jdk-compatibility)\.yml$'
build_pattern+='|\.github/scripts/(agent-changes\.sh|test_agent_changes\.py)$)'

overhead_pattern='^(bootui-agent(-bridge)?/'
overhead_pattern+='|bootui-engine/src/main/java/io/github/jdubois/bootui/engine/(javaagent|journal)/'
overhead_pattern+='|bootui-spring-boot-starter/src/main/java/io/github/jdubois/bootui/autoconfigure/(javaagent/|BootUiEngineConfiguration\.java$)'
overhead_pattern+='|bootui-spring-sample-app/src/main/'
overhead_pattern+='|bootui-spring-sample-app/src/test/java/io/github/jdubois/bootui/sample/(AgentOverheadBenchmarkIT|JournalOverheadBenchmarkIT|CaptureOverheadBenchmarkTest|SampleExecutableJar(ReadinessTests)?)\.java$)'
overhead_pattern+="|$build_pattern"
pattern+="|$overhead_pattern"

# `--match` reads paths from standard input, prints the first one the list matches, and exits 0 when one does, 1 when
# none does; test_agent_changes.py checks the list against the files the agent legs depend on with it.
if [[ "${1:-}" == "--match" ]]; then
  grep -E -m 1 "$pattern" || exit 1
  exit 0
fi
if [[ "${1:-}" == "--match-overhead" ]]; then
  grep -E -m 1 "$overhead_pattern" || exit 1
  exit 0
fi

answer() {
  {
    echo "agent=$1"
    echo "overhead=$2"
    echo "extras=$3"
  } >> "${GITHUB_OUTPUT:-/dev/stdout}"
  echo "Agent legs: $1; overhead: $2; extras: $3 ($4)"
}

if [[ "${GITHUB_EVENT_NAME:-}" != "pull_request" && "${GITHUB_EVENT_NAME:-}" != "push" ]]; then
  answer true true true "${GITHUB_EVENT_NAME:-local} runs every lane"
  exit 0
fi

if [[ "${GITHUB_EVENT_NAME}" == "pull_request" && ",${PR_LABELS:-}," == *",agent,"* ]]; then
  answer true true true "the pull request is labelled agent"
  exit 0
fi

if [[ "${GITHUB_EVENT_NAME}" == "pull_request" ]]; then
  # Include both sides of a rename so moving a file out of an agent path cannot bypass its tests.
  files="$(gh api --paginate "repos/${GITHUB_REPOSITORY}/pulls/${PR_NUMBER}/files" \
    --jq '.[] | .filename, (.previous_filename // empty)')"
else
  : "${PUSH_BEFORE:?The push must supply its before commit}"
  : "${PUSH_AFTER:?The push must supply its after commit}"
  if [[ "$PUSH_BEFORE" =~ ^0+$ ]]; then
    answer true true true "a new branch has no previous commit to compare"
    exit 0
  fi
  # Compare the whole push, not just HEAD^; --no-renames keeps both paths of renamed files.
  files="$(git diff --name-only --no-renames "$PUSH_BEFORE" "$PUSH_AFTER" --)"
fi

match="$(grep -E -m 1 "$pattern" <<< "$files" || true)"
overhead_match="$(grep -E -m 1 "$overhead_pattern" <<< "$files" || true)"
if [[ -n "$overhead_match" ]]; then
  extras=false
  if [[ "${GITHUB_EVENT_NAME}" == "push" ]]; then
    extras=true
  fi
  answer true true "$extras" "changes $overhead_match"
elif [[ -n "$match" ]]; then
  answer true false false "changes $match"
else
  answer false false false "no agent-related path changed; label the pull request agent to run them"
fi
