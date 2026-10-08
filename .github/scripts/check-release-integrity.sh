#!/usr/bin/env bash

set -euo pipefail

# Usage: check-release-integrity.sh [release.yml] [pages.yml] [docker-publish.yml]
# RELEASE_INTEGRITY_ROOT is a test seam for the repository checked by the release-line rules; no
# workflow may set it.
readonly REPOSITORY_ROOT="${RELEASE_INTEGRITY_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
readonly WORKFLOW="${1:-.github/workflows/release.yml}"
readonly PAGES_WORKFLOW="${2:-$REPOSITORY_ROOT/.github/workflows/pages.yml}"
readonly DOCKER_WORKFLOW="${3:-$REPOSITORY_ROOT/.github/workflows/docker-publish.yml}"
readonly ROOT_POM="$REPOSITORY_ROOT/pom.xml"
readonly RELEASE_LINE_FILE="$REPOSITORY_ROOT/.github/release-line"
readonly VERSION_POLICY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/release-version-policy.sh"
readonly LINE_GATE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/release-line-gate.sh"

if [[ ! -r "$WORKFLOW" ]]; then
  printf 'Cannot read release workflow: %s\n' "$WORKFLOW" >&2
  exit 2
fi
if [[ ! -r "$ROOT_POM" ]]; then
  printf 'Cannot read root POM: %s\n' "$ROOT_POM" >&2
  exit 2
fi
for required in "$VERSION_POLICY" "$LINE_GATE" "$PAGES_WORKFLOW" "$DOCKER_WORKFLOW"; do
  if [[ ! -r "$required" ]]; then
    printf 'Cannot read %s\n' "$required" >&2
    exit 2
  fi
done

errors=0

report_error() {
  printf '%s: %s\n' "$WORKFLOW" "$1" >&2
  errors=$((errors + 1))
}

require_literal() {
  local literal="$1"
  local description="$2"
  if ! grep -Fq -- "$literal" "$WORKFLOW"; then
    report_error "missing $description ('$literal')"
  fi
}

line_of() {
  local match
  match="$(grep -nF -- "$1" "$WORKFLOW" | head -n 1 || true)"
  printf '%s' "${match%%:*}"
}

require_order() {
  local earlier_literal="$1"
  local later_literal="$2"
  local description="$3"
  local earlier later
  earlier="$(line_of "$earlier_literal")"
  later="$(line_of "$later_literal")"
  if [[ -z "$earlier" || -z "$later" || "$earlier" -ge "$later" ]]; then
    report_error "$description"
  fi
}

require_literal 'resume_after_publish:' 'manual publication continuation input'
require_literal 'run: bash .github/scripts/check-release-integrity.sh' 'release preflight integrity check'
require_literal 'git verify-tag "$TAG"' 'new tag signature verification'
require_literal 'git tag -s "$TAG"' 'signed annotated release tag creation'
require_literal '--pinentry-mode loopback --passphrase-fd 3' 'headless tag-signing passphrase transport'
require_literal 'git push --atomic origin' 'atomic branch and tag push'
require_literal 'REMOTE_SOURCE_SHA' 'source branch advancement guard'
require_literal 'PREPARED_RELEASE_SHA' 'prepared release SHA handoff'
require_literal 'RELEASE_SHA="$(git rev-parse "refs/tags/$EXPECTED_TAG^{}")"' 'annotated tag peeling'
require_literal 'git verify-tag "$EXPECTED_TAG"' 'existing tag signature verification'
require_literal 'ref: ${{ env.RELEASE_SHA }}' 'immutable release checkout'
require_literal "if: env.RESUME_AFTER_PUBLISH != 'true'" 'deploy skip for publication continuation'
require_literal 'gh workflow run pages.yml --ref "$RELEASE_TAG"' 'tag-pinned documentation deployment'
require_literal 'bash .github/scripts/release-version-policy.sh next-version "$VERSION" "$CURRENT_VERSION" "$RELEASE_LINE"' \
  'per-major next-version policy'
require_literal 'RELEASE_LINE="$(bash .github/scripts/release-version-policy.sh line-major "$CURRENT_VERSION" .github/release-line)"' \
  'release line of the source branch'
require_literal 'TAGGED_RELEASE_LINE="$(bash .github/scripts/release-version-policy.sh line-major "$VERSION" .github/release-line)"' \
  'release line of the tagged contents'
require_literal 'if [[ "$TAGGED_RELEASE_LINE" != "${VERSION%%.*}" ]]; then' \
  'tagged contents restricted to their own release line'
require_literal 'git merge-base --is-ancestor "$RELEASE_SHA" "refs/remotes/origin/$candidate"' \
  'release restricted to main or its maintenance branch'
require_literal 'for candidate in main "${RELEASE_MAJOR}.x"; do' 'release branch candidates'
require_literal 'if [[ "$SOURCE_BRANCH" != "main" && "$SOURCE_BRANCH" != "${VERSION%%.*}.x" ]]; then' \
  'release preparation restricted to main or its maintenance branch'
require_literal 'if [[ "$DEPLOY_CONCLUSION" != "success" ]]; then' \
  'documentation deployment confirmed from the deploy job, not the run'
require_literal 'bash .github/scripts/release-version-policy.sh newest-major "$RELEASE_VERSION"' \
  'newest-major documentation redeploy decision'
require_literal "git ls-remote --tags --refs origin 'refs/tags/v*'" \
  'documentation redeploy decision from the tags on origin'
require_literal "if: env.CENTRAL_AUTO_PUBLISH == 'true' && env.REDEPLOY_DOCS == 'true'" \
  'documentation redeploy restricted to the newest major'
require_literal '-pl .,bootui-core,bootui-engine,bootui-spring-autoconfigure,bootui-spring-boot-starter,bootui-spring-boot-starter-reactive,bootui-ui,bootui-quarkus-parent,bootui-quarkus,bootui-quarkus-deployment,bootui-client,bootui-cli' \
  'publication-only Maven reactor'
require_literal 'bootui-cli/${VERSION}/bootui-cli-${VERSION}-all.jar' \
  'runnable CLI uber-jar availability check'
require_literal 'python3 .github/scripts/assemble_central_bundle.py "$LOCAL_REPO" "$VERSION" target/central-bundle.zip' \
  'Central bundle assembled from the installed release'
require_literal 'python3 .github/scripts/publish_central_bundle.py target/central-bundle.zip' \
  'Central Portal bundle upload'
require_literal 'create_spring_smoke_project "$MVC_SMOKE_DIR" "bootui-spring-boot-starter" "8080"' \
  'standalone Spring MVC consumer smoke project'
require_literal 'create_spring_smoke_project "$WEBFLUX_SMOKE_DIR" "bootui-spring-boot-starter-reactive" "8081"' \
  'standalone Spring WebFlux consumer smoke project'
require_literal '-f "$MVC_SMOKE_DIR/pom.xml"' 'external Spring MVC consumer invocation'
require_literal '-f "$WEBFLUX_SMOKE_DIR/pom.xml"' 'external Spring WebFlux consumer invocation'

smoke_test_step="$(
  sed -n '/- name: Smoke test published distributions/,/- name: Decide documentation redeploy/p' "$WORKFLOW"
)"
readonly smoke_test_step
for sample_module in bootui-spring-sample-app bootui-spring-webflux-sample-app; do
  if grep -Fq -- "-pl $sample_module" <<<"$smoke_test_step"; then
    report_error "published-distribution smoke tests must not run unpublished reactor module '$sample_module'"
  fi
done

# The bridge is built in the publication reactor only to be shaded into bootui-agent; Central never
# receives it, so polling for it would only time out after a successful release.
availability_step="$(
  sed -n '/- name: Wait for Maven Central availability/,/- name: Smoke test published distributions/p' "$WORKFLOW"
)"
readonly availability_step
if grep -Fq 'bootui-agent-bridge/' <<<"$availability_step"; then
  report_error 'bootui-agent-bridge is never published and must not be polled on Maven Central'
fi

# Under Maven 3.10, central-publishing-maven-plugin stages POM-less resolver bookkeeping that Central
# rejects, so publication installs the release and uploads a bundle assembled from it instead.
publish_step="$(
  sed -n '/- name: Publish to Maven Central/,/- name: Wait for Maven Central availability/p' "$WORKFLOW" |
    sed -e ':join' -e '/\\$/N' -e 's/\\\n[[:space:]]*/ /' -e 't join'
)"
readonly publish_step
if grep -Eq 'mvnw[^#]* deploy( |$)' <<<"$publish_step"; then
  report_error 'Maven Central publication must upload the assembled bundle, not run the Maven deploy phase'
fi

excluded_artifacts="$(
  sed -n '/<excludeArtifacts>/,/<\/excludeArtifacts>/p' "$ROOT_POM"
)"
readonly excluded_artifacts
readonly expected_exclusions=(
  bootui-conformance
  bootui-coverage
  bootui-spring-sample-app
  bootui-spring-webflux-sample-app
  bootui-quarkus-sample-app
  bootui-quarkus-integration-tests-aggregator
  bootui-quarkus-integration-tests
  bootui-quarkus-cache-integration-tests
  bootui-quarkus-datasource-integration-tests
  bootui-quarkus-email-integration-tests
  bootui-quarkus-fault-tolerance-integration-tests
  bootui-quarkus-flyway-integration-tests
  bootui-quarkus-health-integration-tests
  bootui-quarkus-hibernate-integration-tests
  bootui-quarkus-liquibase-integration-tests
  bootui-quarkus-micrometer-integration-tests
  bootui-quarkus-otel-integration-tests
  bootui-quarkus-prod-shell-guard-integration-tests
  bootui-quarkus-rabbit-integration-tests
  bootui-quarkus-rest-client-integration-tests
  bootui-quarkus-scheduler-integration-tests
  bootui-quarkus-security-integration-tests
  bootui-quarkus-websockets-integration-tests
)
for artifact in "${expected_exclusions[@]}"; do
  if ! grep -Fq "<excludeArtifact>${artifact}</excludeArtifact>" <<<"$excluded_artifacts"; then
    report_error "root POM must exclude non-distribution artifact '$artifact' from Central publishing"
  fi
done

actual_exclusion_count="$(grep -c '<excludeArtifact>' <<<"$excluded_artifacts" || true)"
readonly actual_exclusion_count
if [[ "$actual_exclusion_count" -ne "${#expected_exclusions[@]}" ]]; then
  report_error "root POM Central exclusion list contains unexpected artifacts"
fi

if grep -Fq '<skip>${maven.deploy.skip}</skip>' "$ROOT_POM"; then
  report_error "Central publishing does not support the legacy per-module <skip> configuration"
fi

require_order '- name: Check release workflow integrity' '- name: Set up JDK 17' \
  'release integrity must be checked before importing signing credentials or preparing a version'
require_order './mvnw -B -ntp -Prelease clean verify' 'git commit -m "Release $TAG"' \
  'release verification must happen before the release commit'
require_order 'REMOTE_SOURCE_SHA=' 'git tag -s "$TAG"' \
  'the source branch advancement guard must run before tag creation'
require_order 'git commit -m "Release $TAG"' 'git tag -s "$TAG"' \
  'the signed tag must point at the release commit'
require_order 'git tag -s "$TAG"' 'git push --atomic origin' \
  'the release tag must be created before the atomic push'
require_order 'git push --atomic origin' '- name: Resolve immutable release' \
  'the release commit and tag must be pushed before resolving publication identity'
require_order '- name: Resolve immutable release' '- name: Checkout immutable release' \
  'the signed tag must be resolved before checking out the publication SHA'
require_order '- name: Checkout immutable release' '- name: Publish to Maven Central' \
  'the immutable release SHA must be checked out before Maven Central publication'
require_order 'python3 .github/scripts/assemble_central_bundle.py' 'python3 .github/scripts/publish_central_bundle.py' \
  'the Central bundle must be assembled before it is uploaded'
require_order '- name: Publish to Maven Central' '- name: Wait for Maven Central availability' \
  'Maven Central availability polling must follow publication'
require_order '- name: Wait for Maven Central availability' '- name: Smoke test published distributions' \
  'consumer smoke tests must follow Maven Central availability'
require_order 'if [[ "$SOURCE_BRANCH" != "main"' './mvnw -B -ntp versions:set' \
  'the source branch must be checked before any project file is rewritten'
require_order 'release-version-policy.sh next-version' './mvnw -B -ntp versions:set' \
  'the release version must be validated against its major before any project file is rewritten'
require_order 'TAGGED_RELEASE_LINE=' '- name: Publish to Maven Central' \
  'the tagged contents must be checked against their release line before Maven Central publication'
require_order '- name: Checkout immutable release' 'TAGGED_RELEASE_LINE=' \
  'the release line must be read from the immutable release checkout'
require_order 'git merge-base --is-ancestor "$RELEASE_SHA"' '- name: Publish to Maven Central' \
  'the release branch must be checked before Maven Central publication'
require_order '- name: Smoke test published distributions' '- name: Decide documentation redeploy' \
  'the documentation redeploy decision must follow the consumer smoke tests'
require_order '- name: Decide documentation redeploy' '- name: Redeploy documentation site' \
  'the documentation redeploy decision must precede the documentation deployment'

# Version arithmetic lives in the tested policy script. Picking the newest tag across the whole
# repository would reject every patch to an older major once a newer major is tagged.
if grep -Eq 'sort[[:space:]]+-V|LATEST_TAG=' "$WORKFLOW"; then
  report_error 'release versions must be computed per major by release-version-policy.sh, not from the newest tag overall'
fi

# Only the newest-major gate may decide whether documentation is redeployed.
if [[ "$(grep -c -- '- name: Redeploy documentation site' "$WORKFLOW" || true)" -ne 1 ]] ||
  [[ "$(grep -c -- 'gh workflow run pages.yml' "$WORKFLOW" || true)" -ne 1 ]]; then
  report_error 'the documentation site must be dispatched from exactly one newest-major-gated step'
fi
redeploy_condition="$(
  sed -n '/- name: Redeploy documentation site/,/run:/p' "$WORKFLOW" | grep -E '^[[:space:]]*if:' || true
)"
readonly redeploy_condition
if ! grep -Fq "env.REDEPLOY_DOCS == 'true'" <<<"$redeploy_condition"; then
  report_error 'the documentation redeploy step must run only when REDEPLOY_DOCS is true'
fi

if grep -Eq '^[[:space:]]*git[[:space:]]+rebase([[:space:]]|$)' "$WORKFLOW"; then
  report_error 'release contents must never be rebased'
fi

if grep -Eq -- '(-Dgpg[.]passphrase=|--passphrase([=[:space:]]))' "$WORKFLOW"; then
  report_error 'GPG passphrases must not be exposed in process arguments'
fi

# The documentation site and the Docker Hub images build from a branch, so they follow the newest
# released major through release-line-gate.sh rather than through a release tag: merging `v2` into
# `main` must publish neither before v2.0.0 and its Maven Central artifacts exist.
report_workflow_error() {
  printf '%s: %s\n' "$1" "$2" >&2
  errors=$((errors + 1))
}

require_workflow_literal() {
  local file="$1" literal="$2" description="$3"
  if ! grep -Fq -- "$literal" "$file"; then
    report_workflow_error "$file" "missing $description ('$literal')"
  fi
}

# Prints the lines of one job under `jobs:`, from its `  name:` key to the next job.
job_block() {
  awk -v job="  $2:" '
    /^jobs:/ { jobs = 1; next }
    jobs && $0 == job { inside = 1; print; next }
    inside && /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { exit }
    inside { print }
  ' "$1"
}

job_names() {
  awk '/^jobs:/ { jobs = 1; next } jobs && /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { gsub(/[ :]/, ""); print }' "$1"
}

readonly GATE_RUN='run: bash .github/scripts/release-line-gate.sh'
for file in "$WORKFLOW" "$PAGES_WORKFLOW" "$DOCKER_WORKFLOW"; do
  if grep -Eq 'BOOTUI_RELEASE_TAGS_FILE|BOOTUI_CENTRAL_URL|RELEASE_INTEGRITY_ROOT' "$file"; then
    report_workflow_error "$file" 'release-line-gate.sh test seams must never be set by a workflow'
  fi
done

# Whole-line matches, so a gate step that appends a forced answer (`...gate.sh; echo publish=true`)
# does not pass.
readonly GATE_STEP_LINE="        $GATE_RUN"
if ! grep -Fxq -- "$GATE_STEP_LINE" "$PAGES_WORKFLOW"; then
  report_workflow_error "$PAGES_WORKFLOW" "missing documentation site release-line gate ('$GATE_RUN' on a line of its own)"
fi
require_workflow_literal "$PAGES_WORKFLOW" 'publish: ${{ steps.gate.outputs.publish }}' \
  'documentation site gate output'
pages_build="$(job_block "$PAGES_WORKFLOW" build)"
pages_deploy="$(job_block "$PAGES_WORKFLOW" deploy)"
if ! grep -Fxq -- "$GATE_STEP_LINE" <<<"$pages_build"; then
  report_workflow_error "$PAGES_WORKFLOW" 'the release-line gate must run in the build job'
fi
if ! grep -Fq -- "if: github.event_name != 'pull_request' && steps.gate.outputs.publish == 'true'" \
  <<<"$(sed -n '/- name: Upload Pages artifact/,/uses:/p' "$PAGES_WORKFLOW")"; then
  report_workflow_error "$PAGES_WORKFLOW" 'the Pages artifact must be uploaded only when the release-line gate allows it'
fi
if [[ "$(grep -Fc 'actions/deploy-pages' "$PAGES_WORKFLOW" || true)" -ne 1 ]] ||
  ! grep -Fq 'actions/deploy-pages' <<<"$pages_deploy"; then
  report_workflow_error "$PAGES_WORKFLOW" 'the site must be deployed from exactly one deploy job'
fi
# release.yml confirms the deployment by selecting this job by its name.
if ! grep -Fxq '    name: Deploy documentation site' <<<"$pages_deploy"; then
  report_workflow_error "$PAGES_WORKFLOW" "the deploy job must keep the name 'Deploy documentation site', which release.yml selects"
fi
if ! grep -Eq "^    if: github.event_name != 'pull_request' && needs.build.outputs.publish == 'true'$" \
  <<<"$pages_deploy"; then
  report_workflow_error "$PAGES_WORKFLOW" 'the deploy job must run only when the release-line gate allows it'
fi
if [[ "$(grep -c 'upload-pages-artifact' "$PAGES_WORKFLOW" || true)" -ne 1 ]]; then
  report_workflow_error "$PAGES_WORKFLOW" 'the site must be uploaded from exactly one gated step'
fi

docker_gate="$(job_block "$DOCKER_WORKFLOW" gate)"
docker_config="$(job_block "$DOCKER_WORKFLOW" docker-config)"
docker_build="$(job_block "$DOCKER_WORKFLOW" build)"
docker_merge="$(job_block "$DOCKER_WORKFLOW" merge)"
if ! grep -Fxq -- "$GATE_STEP_LINE" <<<"$docker_gate" ||
  ! grep -Fq 'publish: ${{ steps.gate.outputs.publish }}' <<<"$docker_gate"; then
  report_workflow_error "$DOCKER_WORKFLOW" 'missing the gate job running release-line-gate.sh'
fi
if ! grep -Eq '^    needs: gate$' <<<"$docker_config" ||
  [[ "$(grep -E '^    if:' <<<"$docker_config" || true)" != "    if: \${{ !inputs.cleanup_only && needs.gate.outputs.publish == 'true' }}" ]]; then
  report_workflow_error "$DOCKER_WORKFLOW" 'docker-config must run only when the release-line gate allows it'
fi
if ! grep -Eq '^    needs: docker-config$' <<<"$docker_build" || ! grep -Eq '^    needs: build$' <<<"$docker_merge"; then
  report_workflow_error "$DOCKER_WORKFLOW" 'image builds and tags must follow the gated docker-config job'
fi
# A status function such as always() would run a job after a skipped docker-config, so the image
# jobs keep exactly the repository condition and nothing else.
readonly DOCKER_JOB_CONDITION="    if: github.repository == 'jdubois/boot-ui'"
for job in build merge; do
  block="$(job_block "$DOCKER_WORKFLOW" "$job")"
  if [[ "$(grep -E '^    if:' <<<"$block" || true)" != "$DOCKER_JOB_CONDITION" ]]; then
    report_workflow_error "$DOCKER_WORKFLOW" \
      "the $job job must keep exactly \"if: github.repository == 'jdubois/boot-ui'\" so it never runs after a skipped docker-config"
  fi
done
for job in $(job_names "$DOCKER_WORKFLOW"); do
  case "$job" in
    gate | docker-config | build | merge | prune) ;;
    *) report_workflow_error "$DOCKER_WORKFLOW" "unexpected job '$job' outside the release-line gate" ;;
  esac
done

# Every branch declares its release line, consistent with its project version.
project_version="$(perl -0ne 'print $1 and exit if m{<artifactId>bootui-parent</artifactId>\s*<version>([^<]+)</version>}' "$ROOT_POM")"
if ! bash "$VERSION_POLICY" line-major "$project_version" "$RELEASE_LINE_FILE" >/dev/null; then
  printf '%s: invalid release line for project version %s\n' "$RELEASE_LINE_FILE" "$project_version" >&2
  errors=$((errors + 1))
fi

if [[ $errors -gt 0 ]]; then
  printf 'Release integrity policy failed with %s error(s).\n' "$errors" >&2
  exit 1
fi

printf 'Release integrity policy passed for %s, %s, and %s.\n' "$WORKFLOW" "$PAGES_WORKFLOW" "$DOCKER_WORKFLOW"
