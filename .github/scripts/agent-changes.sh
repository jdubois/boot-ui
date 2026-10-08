#!/usr/bin/env bash
# Decides whether a workflow run must exercise the BootUI agent's CI legs (PLAN-v2 M5-12): the agent-attached
# Playwright legs and the overhead benchmark in build.yml, and the JDK 21/25/27 lanes of jdk-compatibility.yml.
#
# Pushes, schedules, and manual runs always run them, so every merge to main or v2 is covered. A pull request runs them
# when it carries the `agent` label or changes a path below; any other pull request skips them, which keeps the queue
# short while many pull requests are open. Writes `agent=true|false` to $GITHUB_OUTPUT, and `extras=true|false`: the
# costlier agent measurements (the sensors' own-overhead A/Bs, about two hours) run only for pushes, manual runs, and
# pull requests labelled `agent`, never for a pull request that only changes an agent path.
#
# Inputs (environment): GITHUB_EVENT_NAME, GITHUB_OUTPUT, and for pull requests PR_NUMBER, PR_LABELS (comma
# separated), GITHUB_REPOSITORY, and GH_TOKEN. With FORCE_ALL=true it always answers true (used for pull requests into
# main, whose 1.x policy runs every lane).
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
pattern+='|bootui-quarkus/src/main/java/io/github/jdubois/bootui/quarkus/web/SideEffectsResource'
pattern+='|bootui-(spring-sample-app|spring-webflux-sample-app|quarkus-sample-app)/src/main/java/.*/sideeffects/'
pattern+='|bootui-(spring|quarkus)-sample-app/e2e/tests[^/]*/side-effects\.spec\.js$'
pattern+='|pom\.xml$'
pattern+='|\.github/workflows/(build|jdk-compatibility)\.yml$'
pattern+='|\.github/scripts/(agent-changes\.sh|test_agent_changes\.py)$'
pattern+=')'

# `--match` reads paths from standard input, prints the first one the list matches, and exits 0 when one does, 1 when
# none does; test_agent_changes.py checks the list against the files the agent legs depend on with it.
if [[ "${1:-}" == "--match" ]]; then
  grep -E -m 1 "$pattern" || exit 1
  exit 0
fi

answer() {
  echo "agent=$1" >> "${GITHUB_OUTPUT:-/dev/stdout}"
  echo "Agent legs: $1 ($2)"
}

extras() {
  echo "extras=$1" >> "${GITHUB_OUTPUT:-/dev/stdout}"
}

if [[ "${FORCE_ALL:-false}" == "true" ]]; then
  answer true "every lane runs for this branch"
  extras true
  exit 0
fi

if [[ "${GITHUB_EVENT_NAME:-}" != "pull_request" ]]; then
  answer true "${GITHUB_EVENT_NAME:-local} runs every lane"
  extras true
  exit 0
fi

if [[ ",${PR_LABELS:-}," == *",agent,"* ]]; then
  answer true "the pull request is labelled agent"
  extras true
  exit 0
fi

extras false


files="$(gh api --paginate "repos/${GITHUB_REPOSITORY}/pulls/${PR_NUMBER}/files" --jq '.[].filename')"
match="$(grep -E -m 1 "$pattern" <<< "$files" || true)"
if [[ -n "$match" ]]; then
  answer true "changes $match"
else
  answer false "no agent-related path changed; label the pull request agent to run them"
fi
