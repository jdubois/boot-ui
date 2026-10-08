#!/usr/bin/env bash

set -euo pipefail

# Usage: check-release-integrity.sh [release.yml] [pages.yml] [docker-publish.yml] [consumer-smoke-tests.sh]
#                                   [stage-release-candidate.sh] [check-central-bundle.py]
#                                   [assemble_central_bundle.py] [verify-release-signatures.sh]
# RELEASE_INTEGRITY_ROOT is a test seam for the repository checked by the release-line and POM rules;
# no workflow may set it.
readonly REPOSITORY_ROOT="${RELEASE_INTEGRITY_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPTS_DIR
readonly WORKFLOW="${1:-.github/workflows/release.yml}"
readonly PAGES_WORKFLOW="${2:-$REPOSITORY_ROOT/.github/workflows/pages.yml}"
readonly DOCKER_WORKFLOW="${3:-$REPOSITORY_ROOT/.github/workflows/docker-publish.yml}"
readonly SMOKE_SCRIPT="${4:-$SCRIPTS_DIR/consumer-smoke-tests.sh}"
readonly STAGE_SCRIPT="${5:-$SCRIPTS_DIR/stage-release-candidate.sh}"
readonly BUNDLE_CHECK="${6:-$SCRIPTS_DIR/check-central-bundle.py}"
readonly BUNDLE_ASSEMBLER="${7:-$SCRIPTS_DIR/assemble_central_bundle.py}"
readonly SIGNATURE_CHECK="${8:-$SCRIPTS_DIR/verify-release-signatures.sh}"
readonly ROOT_POM="$REPOSITORY_ROOT/pom.xml"
readonly STARTER_POM="$REPOSITORY_ROOT/bootui-spring-boot-starter/pom.xml"
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
for required in "$VERSION_POLICY" "$LINE_GATE" "$PAGES_WORKFLOW" "$DOCKER_WORKFLOW" "$SMOKE_SCRIPT" "$STAGE_SCRIPT" \
  "$BUNDLE_CHECK" "$BUNDLE_ASSEMBLER" "$SIGNATURE_CHECK" "$STARTER_POM"; do
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
readonly PUBLICATION_REACTOR='-pl .,bootui-engine,bootui-spring-boot-starter,bootui-ui,bootui-quarkus-parent,bootui-quarkus,bootui-quarkus-deployment,bootui-cli,bootui-agent-bridge,bootui-agent \'
require_literal "$PUBLICATION_REACTOR" 'publication-only Maven reactor'
require_literal 'bootui-cli/${VERSION}/bootui-cli-${VERSION}-all.jar' \
  'runnable CLI uber-jar availability check'
require_literal '"bootui-agent/${VERSION}/bootui-agent-${VERSION}.jar"' \
  'Java agent availability check'

# The published coordinates. Each published module carries a flattened, parentless consumer POM, and
# neither parent POM is published. Every list of them below must say exactly this.
readonly PUBLISHED_ARTIFACTS=(
  bootui-engine
  bootui-ui
  bootui-spring-boot-starter
  bootui-quarkus
  bootui-quarkus-deployment
  bootui-cli
  bootui-agent
)
EXPECTED_PUBLISHED="$(printf '%s\n' "${PUBLISHED_ARTIFACTS[@]}" | sort)"
readonly EXPECTED_PUBLISHED

# The consumer smoke tests run three times: against the staged candidate inside the pre-tag
# verification build (a failure there consumes nothing), against it again from the immutable tagged
# checkout before publication, and against Maven Central after it. One script holds them all.
readonly STAGED_SMOKE='bash .github/scripts/consumer-smoke-tests.sh "$VERSION" "$RUNNER_TEMP/bootui-candidate"'
readonly PREPUBLICATION_SMOKE='bash .github/scripts/consumer-smoke-tests.sh "$RELEASE_VERSION" "$RUNNER_TEMP/bootui-candidate"'
readonly CENTRAL_SMOKE='          bash .github/scripts/consumer-smoke-tests.sh "$VERSION"'
readonly STAGE_CANDIDATE='bash .github/scripts/stage-release-candidate.sh "$RUNNER_TEMP/bootui-candidate"'
require_literal "$STAGED_SMOKE" 'pre-tag consumer smoke tests against the staged candidate'
require_literal "$PREPUBLICATION_SMOKE" 'pre-publication consumer smoke tests against the staged candidate'
require_literal '      - name: Smoke test the staged release candidate' 'pre-publication staged smoke test step'
if [[ "$(grep -Fc -- "$STAGE_CANDIDATE" "$WORKFLOW" || true)" -ne 2 ]]; then
  report_error "the release candidate must be staged ('$STAGE_CANDIDATE') before tagging and before publication"
fi
if ! grep -Fxq -- "$CENTRAL_SMOKE" "$WORKFLOW"; then
  report_error "missing Maven Central consumer smoke tests ('${CENTRAL_SMOKE#"${CENTRAL_SMOKE%%[![:space:]]*}"}' on a line of its own)"
fi

smoke_test_step="$(
  sed -n '/- name: Smoke test published distributions/,/- name: Decide documentation redeploy/p' "$WORKFLOW"
)"
readonly smoke_test_step
if ! grep -Fxq -- "$CENTRAL_SMOKE" <<<"$smoke_test_step"; then
  report_error 'the Maven Central consumer smoke tests must run in the published-distribution smoke test step'
fi
for sample_module in bootui-spring-sample-app bootui-spring-webflux-sample-app; do
  if grep -Fq -- "-pl $sample_module" <<<"$smoke_test_step" || grep -Fq -- "-pl $sample_module" "$SMOKE_SCRIPT"; then
    report_error "published-distribution smoke tests must not run unpublished reactor module '$sample_module'"
  fi
done

prepublication_step="$(
  sed -n '/- name: Smoke test the staged release candidate/,/- name: Publish to Maven Central/p' "$WORKFLOW"
)"
readonly prepublication_step
if ! grep -Fq -- "$PREPUBLICATION_SMOKE" <<<"$prepublication_step" ||
  ! grep -Fq -- "$STAGE_CANDIDATE" <<<"$prepublication_step" ||
  ! grep -Fxq -- "        if: env.RESUME_AFTER_PUBLISH != 'true' && env.CANDIDATE_SMOKE_PASSED != 'true'" \
    <<<"$prepublication_step"; then
  report_error 'the pre-publication step must stage the candidate and smoke-test it, skipped only on a resumed run or when the pre-tag smoke test passed'
fi
# Only a passing pre-tag smoke test may skip the pre-publication one.
if [[ "$(grep -Fc 'CANDIDATE_SMOKE_PASSED=true' "$WORKFLOW" || true)" -ne 1 ]]; then
  report_error 'CANDIDATE_SMOKE_PASSED may be set only once, by the pre-tag staged smoke test'
fi

# The bridge is built in the publication reactor only to be shaded into bootui-agent; Central never
# receives it, so polling for it would only time out after a successful release.
availability_step="$(
  sed -n '/- name: Wait for Maven Central availability/,/- name: Smoke test published distributions/p' "$WORKFLOW"
)"
readonly availability_step
if grep -Fq 'bootui-agent-bridge/' <<<"$availability_step"; then
  report_error 'bootui-agent-bridge is never published and must not be polled on Maven Central'
fi
polled_artifacts="$(grep -oE '^[[:space:]]+"[a-z-]+/\$\{VERSION\}/' <<<"$availability_step" | tr -d ' "' | cut -d/ -f1 | sort -u)"
if [[ "$polled_artifacts" != "$EXPECTED_PUBLISHED" ]]; then
  report_error "Maven Central availability must poll exactly the published coordinates ($(tr '\n' ' ' <<<"$EXPECTED_PUBLISHED")), found: $(tr '\n' ' ' <<<"$polled_artifacts")"
fi
if grep -Eq '"bootui-(quarkus-)?parent/' <<<"$availability_step"; then
  report_error 'neither parent POM is published, so neither may be polled on Maven Central'
fi

# The 1-based number of the first line of the text $2 that is exactly $1, or nothing.
line_number_of() {
  local match
  match="$(grep -nFx -- "$1" <<<"$2" | head -n 1 || true)"
  printf '%s' "${match%%:*}"
}

# The staging script builds the same reactor as the publication step, and the same bundle from it, by whole
# command lines in the same order under set -e: install, assemble, unpack, then check.
if ! grep -Fq -- "$PUBLICATION_REACTOR" "$STAGE_SCRIPT"; then
  report_error "$STAGE_SCRIPT must stage the publication-only Maven reactor ('$PUBLICATION_REACTOR')"
fi
stage_script="$(cat "$STAGE_SCRIPT")"
previous_line=0
for literal in 'set -euo pipefail' './mvnw -B -ntp -Prelease clean install \' \
  'python3 "$REPOSITORY_ROOT/.github/scripts/assemble_central_bundle.py" --unsigned "$LOCAL_REPO" "$VERSION" "$BUNDLE"' \
  'unzip -q "$BUNDLE" -d "$OUTPUT"' \
  'python3 "$REPOSITORY_ROOT/.github/scripts/check-central-bundle.py" "$OUTPUT" "$VERSION"'; do
  found_line="$(line_number_of "$literal" "$stage_script")"
  if [[ -z "$found_line" ]]; then
    report_error "$STAGE_SCRIPT must use '$literal' on a line of its own"
  elif (( found_line <= previous_line )); then
    report_error "$STAGE_SCRIPT must run '$literal' after the previous staging command: install, assemble, unpack, then check"
  else
    previous_line="$found_line"
  fi
done
if sed -e ':join' -e '/\\$/N' -e 's/\\\n[[:space:]]*/ /' -e 't join' "$STAGE_SCRIPT" |
  grep -Eq 'mvnw[^#]* deploy( |$)'; then
  report_error "$STAGE_SCRIPT must stage the assembled bundle, not run the Maven deploy phase"
fi

# The consumer smoke tests and the bundle check name the same coordinates.
smoke_published="$(sed -n '/^readonly PUBLISHED_ARTIFACTS=(/,/^)/p' "$SMOKE_SCRIPT" | grep -E '^[[:space:]]+bootui-' | tr -d ' ' | sort)"
if [[ "$smoke_published" != "$EXPECTED_PUBLISHED" ]]; then
  report_error "$SMOKE_SCRIPT must expect exactly the published coordinates"
fi
bundle_published="$(sed -n '/^PUBLISHED = (/,/^)/p' "$BUNDLE_CHECK" | grep -oE '"bootui-[a-z-]+"' | tr -d '"' | sort)"
if [[ "$bundle_published" != "$EXPECTED_PUBLISHED" ]]; then
  report_error "$BUNDLE_CHECK must expect exactly the published coordinates"
fi
# The assembler keeps no list of its own: it bundles the bundle checker's PUBLISHED, checked just above.
if ! grep -Fxq -- 'ARTIFACT_IDS = _load_bundle_check().PUBLISHED' "$BUNDLE_ASSEMBLER" ||
  [[ "$(grep -c '^ARTIFACT_IDS' "$BUNDLE_ASSEMBLER" || true)" -ne 1 ]]; then
  report_error "$BUNDLE_ASSEMBLER must bundle check-central-bundle.py's PUBLISHED ('ARTIFACT_IDS = _load_bundle_check().PUBLISHED')"
fi

report_smoke_error() {
  printf '%s: %s\n' "$SMOKE_SCRIPT" "$1" >&2
  errors=$((errors + 1))
}
require_smoke_literal() {
  if ! grep -Fq -- "$1" "$SMOKE_SCRIPT"; then
    report_smoke_error "missing $2 ('$1')"
  fi
}
smoke_line_of() {
  local match
  match="$(grep -nF -- "$1" "$SMOKE_SCRIPT" | head -n 1 || true)"
  printf '%s' "${match%%:*}"
}
require_smoke_literal 'readonly EXPECTED_ORIGIN="bootui-staged"' 'staged-candidate origin'
require_smoke_literal 'readonly EXPECTED_ORIGIN="central"' 'Maven Central origin'
require_smoke_literal 'readonly EXPECTED_ORIGIN_URL="file://${STAGED_REPOSITORY}"' 'staged-candidate origin URL'
require_smoke_literal 'readonly EXPECTED_ORIGIN_URL="https://repo.maven.apache.org/maven2"' 'Maven Central origin URL'
require_smoke_literal "EXPECTED_ORIGIN_KEY=\"\${EXPECTED_ORIGIN}-\$(printf '%s' \"\$EXPECTED_ORIGIN_URL\" | sha1_hex)\"" \
  'Maven Resolver 2 origin key, the repository id and the SHA-1 of its URL'
require_smoke_literal 'grep -Fxq -e "${name}>${EXPECTED_ORIGIN}=" -e "${name}>${EXPECTED_ORIGIN_KEY}=" \' \
  'exact origin match of a resolved BootUI artifact'
require_smoke_literal 'if ! resolved_from_source_under_test "$file"; then' \
  'per-file origin check of every resolved BootUI artifact'
require_smoke_literal 'if [[ "$RESOLVED_ARTIFACTS" != "$EXPECTED_ARTIFACTS" ]]; then' \
  'check that the consumers resolved exactly the published coordinates'
require_smoke_literal 'create_spring_smoke_project "$MVC_SMOKE_DIR" "spring-boot-starter-web" "$MVC_PORT"' \
  'standalone Spring MVC consumer smoke project'
require_smoke_literal 'create_spring_smoke_project "$WEBFLUX_SMOKE_DIR" "spring-boot-starter-webflux" "$WEBFLUX_PORT"' \
  'standalone Spring WebFlux consumer smoke project'
require_smoke_literal '<artifactId>bootui-spring-boot-starter</artifactId>' 'Spring consumers of the one starter'
require_smoke_literal 'run_spring_smoke "Spring MVC" "$MVC_SMOKE_DIR" "$MVC_PORT" SERVLET org.springframework.boot.tomcat.TomcatWebServer' \
  'Spring MVC consumer asserted SERVLET on Tomcat'
require_smoke_literal 'run_spring_smoke "Spring WebFlux" "$WEBFLUX_SMOKE_DIR" "$WEBFLUX_PORT" REACTIVE org.springframework.boot.reactor.netty.NettyWebServer' \
  'Spring WebFlux consumer asserted REACTIVE on Netty'
require_smoke_literal '  jakarta.servlet:jakarta.servlet-api \' 'Servlet API kept off the WebFlux consumer classpath'
require_smoke_literal '  org.apache.tomcat.embed:tomcat-embed-core \' 'Tomcat kept off the WebFlux consumer classpath'
require_smoke_literal '  io.projectreactor.netty:reactor-netty-http \' 'Reactor Netty kept off the Spring MVC consumer classpath'
require_smoke_literal 'consumer_mvn -q -f "$project_dir/pom.xml" -Dmaven.test.skip=true package' \
  'external Spring consumer build'
require_smoke_literal 'java -jar "$app_jar" > "$log" 2>&1 &' 'Spring consumer started as its own JVM, so it can be stopped'
require_smoke_literal 'if [[ "$CLI_CLASSPATH" != "com.julien-dubois.bootui:bootui-cli" ]]; then' \
  'dependency-free CLI client consumer assertion'
require_smoke_literal 'CLI_VERSION_OUTPUT="$(java -jar "$CLI_ALL_JAR" --version)"' 'runnable CLI uber-jar smoke test'
require_smoke_literal '-f "$AGENT_SMOKE_DIR/pom.xml"' 'external Java agent consumer invocation'
require_smoke_literal 'if [[ "$AGENT_CLASSPATH" != "bootui-agent-${VERSION}.jar" ]]; then' \
  'Java agent consumer dependency-free assertion'
require_smoke_literal 'java -javaagent:"$AGENT_JAR" -version' 'Java agent attach smoke test'
require_smoke_literal 'AGENT_DORMANT_LINE="[BootUI agent] BootUI agent ${VERSION} attached (javaagent); dormant until BootUI claims it"' \
  'Java agent dormant startup assertion'
readonly SMOKE_PURGE='rm -rf "$LOCAL_REPO/com/julien-dubois/bootui"'
for consumer in 'create_spring_smoke_project "$MVC_SMOKE_DIR"' '-f "$CLI_SMOKE_DIR/pom.xml"' '-f "$QUARKUS_SMOKE_DIR/pom.xml"' \
  '-f "$AGENT_SMOKE_DIR/pom.xml"'; do
  purge_line="$(smoke_line_of "$SMOKE_PURGE")"
  consumer_line="$(smoke_line_of "$consumer")"
  if [[ -z "$purge_line" || -z "$consumer_line" || "$purge_line" -ge "$consumer_line" ]]; then
    report_smoke_error "every consumer ($consumer) must resolve BootUI after the local BootUI artifacts are dropped"
  fi
done
if [[ "$(grep -Fc -- "$SMOKE_PURGE" "$SMOKE_SCRIPT" || true)" -ne 1 ]]; then
  report_smoke_error 'the local BootUI artifacts must be dropped exactly once, before every consumer'
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
if grep -Fq -- '--unsigned' <<<"$publish_step"; then
  report_error 'Maven Central publication must bundle the signed release, never with --unsigned'
fi

# The bundle is assembled, unpacked, checked, signature-verified and uploaded by whole command lines of the
# publication step, in that order, under set -e: a commented-out command or one suffixed with "|| true" would let a bundle the check refuses
# reach Central.
step_line_of() {
  line_number_of "$1" "$publish_step"
}
previous_line=0
for entry in \
  '          set -euo pipefail|errexit for the Maven Central publication step' \
  '          python3 .github/scripts/assemble_central_bundle.py "$LOCAL_REPO" "$VERSION" target/central-bundle.zip|Central bundle assembled from the installed release' \
  '          unzip -q target/central-bundle.zip -d "$BUNDLE_DIR"|unpacked Central bundle for its check' \
  '          python3 .github/scripts/check-central-bundle.py "$BUNDLE_DIR" "$VERSION"|check of the assembled Central bundle before its upload' \
  '          bash .github/scripts/verify-release-signatures.sh bundle "$BUNDLE_DIR"|verification of every bundle signature by the release key before its upload' \
  '          python3 .github/scripts/publish_central_bundle.py target/central-bundle.zip "bootui-$VERSION" "$CENTRAL_AUTO_PUBLISH"|Central Portal bundle upload'; do
  command_line="${entry%|*}"
  description="${entry##*|}"
  found_line="$(step_line_of "$command_line")"
  if [[ -z "$found_line" ]]; then
    report_error "missing $description ('${command_line#"${command_line%%[![:space:]]*}"}' on a line of its own in the publication step)"
  elif (( found_line <= previous_line )); then
    report_error "the publication step must run $description after the previous bundle command: assemble, unpack, check, verify signatures, then upload"
  else
    previous_line="$found_line"
  fi
done

# The release key is pinned once for the whole job, and the imported key is checked against it before anything is
# signed, tagged, or published. A rotation changes PINNED_RELEASE_KEY_FINGERPRINT here and in release.yml together.
readonly PINNED_RELEASE_KEY_FINGERPRINT='7B7C0BD038603E5A9F1476D0498BA5AC9BABBAF9'
workflow_text="$(cat "$WORKFLOW")"
if ! grep -Fxq -- "      RELEASE_KEY_FINGERPRINT: $PINNED_RELEASE_KEY_FINGERPRINT" <<<"$workflow_text" ||
  [[ "$(grep -c 'RELEASE_KEY_FINGERPRINT[:=]' <<<"$workflow_text" || true)" -ne 1 ]]; then
  # Fails closed: any other definition, or even a `${RELEASE_KEY_FINGERPRINT:=...}` default, is refused.
  report_error "the job must pin RELEASE_KEY_FINGERPRINT to $PINNED_RELEASE_KEY_FINGERPRINT once, and nothing may redefine it"
fi
key_check_step="$(sed -n '/^      - name: Check the release signing key$/,/^      - name: /p' "$WORKFLOW")"
# The step runs unconditionally and its failure fails the job: no `if:`, no `continue-on-error`.
if [[ "$(sed -n '2p' <<<"$key_check_step")" != '        run: |' ]]; then
  report_error "the release signing key check must run unconditionally ('run: |' right after its name)"
fi
if grep -Eq '^[[:space:]]*continue-on-error:' "$WORKFLOW"; then
  report_error 'no release step may set continue-on-error: a failed check must stop the release'
fi
previous_line=0
for literal in \
  '          set -euo pipefail' \
  '          secret_keys="$(gpg --batch --with-colons --list-secret-keys)"' \
  '          [[ "$(grep -c '"'"'^sec:'"'"' <<<"$secret_keys" || true)" == "1" ]] || { echo "::error::Exactly one secret signing key must be imported"; exit 1; }' \
  '          IMPORTED_KEY_FINGERPRINT="$(awk -F: '"'"'$1 == "fpr" { print $10; exit }'"'"' <<<"$secret_keys")"' \
  '          [[ "$IMPORTED_KEY_FINGERPRINT" == "$RELEASE_KEY_FINGERPRINT" ]] || { echo "::error::The imported signing key ${IMPORTED_KEY_FINGERPRINT:-(none)} is not the pinned release key $RELEASE_KEY_FINGERPRINT"; exit 1; }'; do
  found_line="$(line_number_of "$literal" "$key_check_step")"
  if [[ -z "$found_line" ]]; then
    report_error "the release signing key check must use '${literal#"${literal%%[![:space:]]*}"}' on a line of its own"
  elif (( found_line <= previous_line )); then
    report_error "the release signing key check must run '${literal#"${literal%%[![:space:]]*}"}' after its previous line"
  else
    previous_line="$found_line"
  fi
done
require_order '- name: Set up JDK 17' '- name: Check the release signing key' \
  'the release signing key must be checked after it is imported'
require_order '- name: Check the release signing key' '- name: Prepare release version' \
  'the release signing key must be checked before anything is signed or tagged'

# Before the tag exists, the signatures the verification build wrote are checked: a whole line of the preparation,
# after the build and before the release commit.
readonly BUILD_SIGNATURE_CHECK='            bash .github/scripts/verify-release-signatures.sh build'
build_signature_line="$(line_number_of "$BUILD_SIGNATURE_CHECK" "$(cat "$WORKFLOW")")"
release_verify_line="$(line_of './mvnw -B -ntp -Prelease clean verify')"
release_commit_line="$(line_of 'git commit -m "Release $TAG"')"
if [[ -z "$build_signature_line" ]]; then
  report_error "missing pre-tag verification of the build's signatures ('${BUILD_SIGNATURE_CHECK#"${BUILD_SIGNATURE_CHECK%%[![:space:]]*}"}' on a line of its own)"
elif [[ -z "$release_verify_line" || -z "$release_commit_line" ]] ||
  (( build_signature_line <= release_verify_line || build_signature_line >= release_commit_line )); then
  report_error "the build's signatures must be verified after the verification build and before the release commit"
fi

# The signature check itself: every failure is one guarded line, in this order, under set -e. It reads the pin from
# the job and nothing else: no other code line may name RELEASE_KEY_FINGERPRINT, such as one deriving it from the keyring.
signature_check="$(cat "$SIGNATURE_CHECK")"
pin_uses="$(grep -v '^[[:space:]]*#' <<<"$signature_check" | grep -F 'RELEASE_KEY_FINGERPRINT' | grep -vFx \
  -e '[[ "${RELEASE_KEY_FINGERPRINT:-}" =~ ^[0-9A-F]{40}$ ]] || { echo "::error::RELEASE_KEY_FINGERPRINT must be the pinned release key'"'"'s 40-digit fingerprint"; exit 1; }' \
  -e 'readonly RELEASE_KEY_FINGERPRINT' \
  -e '  grep -Eq "^\[GNUPG:\] VALIDSIG .* ${RELEASE_KEY_FINGERPRINT}\$" <<<"$verification" || { echo "::error::${signature#"$SEARCH_ROOT"/} is not a valid signature by the release key"; exit 1; }' \
  -e 'echo "Verified ${signatures} release signatures by ${RELEASE_KEY_FINGERPRINT} under ${SEARCH_ROOT}."' || true)"
if [[ -n "$pin_uses" ]]; then
  report_error "$SIGNATURE_CHECK must use RELEASE_KEY_FINGERPRINT only as the job pins it, found: $pin_uses"
fi
previous_line=0
for literal in \
  'set -euo pipefail' \
  "    readonly FIND_FILTER=(-mindepth 2 -maxdepth 3 -path '*/target/*.asc')" \
  "    readonly FIND_FILTER=(-name '*.asc')" \
  '[[ "${RELEASE_KEY_FINGERPRINT:-}" =~ ^[0-9A-F]{40}$ ]] || { echo "::error::RELEASE_KEY_FINGERPRINT must be the pinned release key'"'"'s 40-digit fingerprint"; exit 1; }' \
  'readonly RELEASE_KEY_FINGERPRINT' \
  '  verification="$(gpg --batch --status-fd 1 --verify "$signature" "${signature%.asc}")" || { echo "::error::${signature#"$SEARCH_ROOT"/} does not verify"; exit 1; }' \
  '  grep -Eq "^\[GNUPG:\] VALIDSIG .* ${RELEASE_KEY_FINGERPRINT}\$" <<<"$verification" || { echo "::error::${signature#"$SEARCH_ROOT"/} is not a valid signature by the release key"; exit 1; }' \
  'done < <(find "$SEARCH_ROOT" "${FIND_FILTER[@]}" -type f -print0)' \
  '(( signatures > 0 )) || { echo "::error::Found no release signature to verify under $SEARCH_ROOT"; exit 1; }'; do
  found_line="$(line_number_of "$literal" "$signature_check")"
  if [[ -z "$found_line" ]]; then
    report_error "$SIGNATURE_CHECK must use '$literal' on a line of its own"
  elif (( found_line <= previous_line )); then
    report_error "$SIGNATURE_CHECK must run '$literal' after the previous signature check line"
  else
    previous_line="$found_line"
  fi
done

excluded_artifacts="$(
  sed -n '/<excludeArtifacts>/,/<\/excludeArtifacts>/p' "$ROOT_POM"
)"
readonly excluded_artifacts
readonly expected_exclusions=(
  bootui-parent
  bootui-quarkus-parent
  bootui-conformance
  bootui-agent-bridge
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

report_pom_error() {
  printf '%s: %s\n' "$1" "$2" >&2
  errors=$((errors + 1))
}

# Flattened consumer POMs: no parent, resolved versions, and the root's Central metadata unchanged. The
# flattened file must stay beside pom.xml (no <outputDirectory>): Quarkus 3.33 takes the directory of a
# module's POM file as its base directory.
flatten_plugin="$(sed -n '/<artifactId>flatten-maven-plugin<\/artifactId>/,/<\/plugin>/p' "$ROOT_POM")"
for literal in '<flattenMode>ossrh</flattenMode>' '<flattenDependencyMode>direct</flattenDependencyMode>' \
  '<goal>flatten</goal>' '<phase>process-resources</phase>'; do
  if ! grep -Fq -- "$literal" <<<"$flatten_plugin"; then
    report_pom_error "$ROOT_POM" "flatten-maven-plugin must be configured with $literal"
  fi
done
if grep -Fq '<outputDirectory>' <<<"$flatten_plugin"; then
  report_pom_error "$ROOT_POM" 'the flattened POM must stay beside pom.xml; Quarkus 3.33 in-reactor builds break otherwise'
fi
for literal in 'child.project.url.inherit.append.path="false"' 'child.scm.connection.inherit.append.path="false"' \
  'child.scm.developerConnection.inherit.append.path="false"' 'child.scm.url.inherit.append.path="false"'; do
  if ! grep -Fq -- "$literal" "$ROOT_POM"; then
    report_pom_error "$ROOT_POM" "the published POMs must keep the root url and scm ($literal)"
  fi
done
for module in "${PUBLISHED_ARTIFACTS[@]}"; do
  if ! grep -Fq '<artifactId>flatten-maven-plugin</artifactId>' "$REPOSITORY_ROOT/$module/pom.xml" 2>/dev/null; then
    report_pom_error "$REPOSITORY_ROOT/$module/pom.xml" 'a published module must declare flatten-maven-plugin'
  fi
done
for removed in bootui-core bootui-spring-autoconfigure bootui-spring-boot-starter-reactive bootui-client; do
  if grep -Fq "<module>$removed</module>" "$ROOT_POM" || grep -Fq "<artifactId>$removed</artifactId>" "$ROOT_POM"; then
    report_pom_error "$ROOT_POM" "$removed was merged into another published module and must not return"
  fi
done

# The one Spring starter never chooses the application's web stack: both stacks are provided-scope, and
# the enforcer execution fails the build if either reaches its compile or runtime dependencies.
starter_web_scopes="$(
  perl -0ne 'while (m{<dependency>(.*?)</dependency>}sg) { my $d = $1; if ($d =~ m{<artifactId>(spring-boot-starter-(?:web|webmvc|webflux|tomcat|jetty|undertow|reactor-netty)|spring-webmvc|spring-boot-(?:webmvc|webflux|tomcat|jetty|reactor-netty)|jakarta\.servlet-api|reactor-netty-http|tomcat-embed-[a-z]+)</artifactId>}) { my $a = $1; my $s = $d =~ m{<scope>([^<]+)</scope>} ? $1 : "compile"; print "$a:$s\n"; } }' "$STARTER_POM"
)"
if [[ "$(sort <<<"$starter_web_scopes")" != $'spring-boot-starter-web:provided\nspring-boot-starter-webflux:provided' ]]; then
  report_pom_error "$STARTER_POM" "the starter may compile against spring-boot-starter-web and spring-boot-starter-webflux only at provided scope, found: $(tr '\n' ' ' <<<"$starter_web_scopes")"
fi
starter_enforcer="$(sed -n '/<artifactId>maven-enforcer-plugin<\/artifactId>/,/<\/plugin>/p' "$STARTER_POM")"
for literal in '<id>no-web-stack</id>' '<bannedDependencies>' '<searchTransitive>true</searchTransitive>' \
  'org.springframework.boot:spring-boot-starter-web:*:*:compile' 'org.springframework.boot:spring-boot-starter-webflux:*:*:runtime' \
  'org.springframework.boot:spring-boot-starter-tomcat:*:*:runtime' 'org.apache.tomcat.embed:*:*:*:runtime' \
  'jakarta.servlet:jakarta.servlet-api:*:*:compile' 'io.projectreactor.netty:reactor-netty-http:*:*:runtime' \
  'org.springframework.boot:spring-boot-starter-jetty:*:*:compile' 'org.springframework.boot:spring-boot-starter-undertow:*:*:compile'; do
  if ! grep -Fq -- "$literal" <<<"$starter_enforcer"; then
    report_pom_error "$STARTER_POM" "the no-web-stack enforcer rule must keep $literal"
  fi
done

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
require_order './mvnw -B -ntp -Prelease clean verify' "$STAGED_SMOKE" \
  'the staged candidate must be smoke-tested after the verification build'
require_order "$STAGED_SMOKE" 'git commit -m "Release $TAG"' \
  'the staged candidate must be smoke-tested before the release commit and tag exist'
require_order "$STAGED_SMOKE" 'echo "CANDIDATE_SMOKE_PASSED=true" >> "$GITHUB_ENV"' \
  'only a passing pre-tag staged smoke test may skip the pre-publication one'
require_order '- name: Checkout immutable release' '- name: Smoke test the staged release candidate' \
  'the pre-publication staged smoke test must run from the immutable release checkout'
require_order '- name: Smoke test the staged release candidate' '- name: Publish to Maven Central' \
  'the staged candidate must be smoke-tested before Maven Central publication'
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
