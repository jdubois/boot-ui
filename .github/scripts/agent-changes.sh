#!/usr/bin/env bash
# Decides whether a workflow run must exercise the BootUI agent's CI legs (PLAN-v2 M5-12): the agent-attached
# Playwright legs and the overhead benchmark in build.yml, and the JDK 21/25/27 lanes of jdk-compatibility.yml.
#
# Pushes, schedules, and manual runs always run them, so every merge to main or v2 is covered. A pull request runs them
# when it carries the `agent` label or changes a path below; any other pull request skips them, which keeps the queue
# short while many pull requests are open. Writes `agent=true|false` to $GITHUB_OUTPUT, and `extras=true|false`: the
# costlier agent measurements (the network sensor's own overhead A/B) run only for pushes, manual runs, and pull
# requests labelled `agent`, never for a pull request that only changes an agent path.
#
# Inputs (environment): GITHUB_EVENT_NAME, GITHUB_OUTPUT, and for pull requests PR_NUMBER, PR_LABELS (comma
# separated), GITHUB_REPOSITORY, and GH_TOKEN. With FORCE_ALL=true it always answers true (used for pull requests into
# main, whose 1.x policy runs every lane).
set -euo pipefail

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

# Paths whose changes can change what the agent legs observe: the agent and its bridge, the engine and adapter code
# that claims it, drains it, or opens the scopes it reads, the agent-backed panels, their sample seeds and specs, the
# agent ITs, the build (dependency versions such as Byte Buddy's), and these workflows.
pattern='^('
pattern+='bootui-agent-bridge/|bootui-agent/'
pattern+='|bootui-engine/src/main/java/io/github/jdubois/bootui/engine/(javaagent|codepaths|inventory|sideeffects|correlation)/'
pattern+='|bootui-engine/src/main/java/io/github/jdubois/bootui/engine/journal/AgentEvidence'
pattern+='|bootui-engine/src/main/java/io/github/jdubois/bootui/engine/model/(SideEffectAccess|RuntimeModelService|RuntimeModelProjection)'
pattern+='|bootui-engine/src/main/java/io/github/jdubois/bootui/engine/(model/HostOpen|support/BootUiHttpClients)'
pattern+='|bootui-spring-autoconfigure/src/main/java/io/github/jdubois/bootui/autoconfigure/(javaagent|codepaths|inventory|sideeffects)/'
pattern+='|bootui-spring-autoconfigure/src/main/java/io/github/jdubois/bootui/autoconfigure/(activity/RequestCorrelationFilter|reactive/ReactiveRequestCorrelationFilter)'
pattern+='|bootui-quarkus(-deployment)?/src/main/java/io/github/jdubois/bootui/quarkus/(deployment/)?(agent|javaagent|codepaths|inventory|sideeffects|correlation)/'
pattern+='|bootui-ui/src/main/frontend/src/views/(JavaAgent|CodeInventory|CodePaths|SideEffects|MethodProbes)'
pattern+='|bootui-ui/src/main/frontend/src/views/components/(MethodProbes|RequestCodePath|ChangeImpact|RunComparison)'
pattern+='|bootui-spring-sample-app/e2e/(tests-agent|tests-webflux-agent|playwright\.(agent|webflux-agent)\.config)'
pattern+='|bootui-quarkus-sample-app/e2e/(tests-agent|playwright\.agent\.config)'
pattern+='|bootui-spring-sample-app/src/test/java/.*Agent'
pattern+='|bootui-quarkus/src/main/java/io/github/jdubois/bootui/quarkus/web/SideEffectsResource'
pattern+='|bootui-(spring-sample-app|spring-webflux-sample-app|quarkus-sample-app)/src/main/java/.*/sideeffects/'
pattern+='|bootui-(spring|quarkus)-sample-app/e2e/tests[^/]*/side-effects\.spec\.js$'
pattern+='|pom\.xml$'
pattern+='|\.github/workflows/(build|jdk-compatibility)\.yml$'
pattern+='|\.github/scripts/agent-changes\.sh$'
pattern+=')'

files="$(gh api --paginate "repos/${GITHUB_REPOSITORY}/pulls/${PR_NUMBER}/files" --jq '.[].filename')"
match="$(grep -E -m 1 "$pattern" <<< "$files" || true)"
if [[ -n "$match" ]]; then
  answer true "changes $match"
else
  answer false "no agent-related path changed; label the pull request agent to run them"
fi
