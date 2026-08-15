#!/usr/bin/env bash

set -euo pipefail

readonly WORKFLOW="${1:-.github/workflows/release.yml}"
readonly ROOT_POM="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/pom.xml"

if [[ ! -r "$WORKFLOW" ]]; then
  printf 'Cannot read release workflow: %s\n' "$WORKFLOW" >&2
  exit 2
fi
if [[ ! -r "$ROOT_POM" ]]; then
  printf 'Cannot read root POM: %s\n' "$ROOT_POM" >&2
  exit 2
fi

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
require_literal '-pl .,bootui-core,bootui-engine,bootui-spring-autoconfigure,bootui-spring-boot-starter,bootui-spring-boot-starter-reactive,bootui-ui,bootui-quarkus-parent,bootui-quarkus,bootui-quarkus-deployment' \
  'publication-only Maven reactor'

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
require_order '- name: Publish to Maven Central' '- name: Wait for Maven Central availability' \
  'Maven Central availability polling must follow publication'
require_order '- name: Wait for Maven Central availability' '- name: Smoke test published distributions' \
  'consumer smoke tests must follow Maven Central availability'

if grep -Eq '^[[:space:]]*git[[:space:]]+rebase([[:space:]]|$)' "$WORKFLOW"; then
  report_error 'release contents must never be rebased'
fi

if grep -Eq -- '(-Dgpg[.]passphrase=|--passphrase([=[:space:]]))' "$WORKFLOW"; then
  report_error 'GPG passphrases must not be exposed in process arguments'
fi

if [[ $errors -gt 0 ]]; then
  printf 'Release integrity policy failed with %s error(s).\n' "$errors" >&2
  exit 1
fi

printf 'Release integrity policy passed for %s.\n' "$WORKFLOW"
