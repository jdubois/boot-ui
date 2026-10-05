#!/usr/bin/env bash
# Builds the BootUI checkout this harness lives in (the v2 branch) into the harness Maven repository and records what
# was built: version, commit, and the SHA-256 of the bootui-engine jar every application must resolve.
#
# Usage: validation/bin/build-v2.sh [--no-build]
#   --no-build  record an existing installation without rebuilding (it must match the checkout's target/ jar)
source "$(dirname "$0")/lib.sh"

build=true
if [ "${1:-}" = "--no-build" ]; then build=false; fi

require_cmd git
cd "$REPO_ROOT"
commit="$(git rev-parse HEAD)"
branch="$(git rev-parse --abbrev-ref HEAD)"
dirty="$(git status --porcelain --untracked-files=no | grep -v '^.. validation/' || true)"
[ -z "$dirty" ] || log "warning: the checkout has uncommitted changes outside validation/; they are part of the build"
version="$(./mvnw -q -DforceStdout -Dmaven.repo.local="$M2" help:evaluate -Dexpression=project.version)"

if $build; then
  log "building BootUI $version ($branch at ${commit:0:9}) into $M2"
  ./mvnw -B -ntp -Dmaven.repo.local="$M2" -DskipTests install
fi

installed="$M2/com/julien-dubois/bootui/bootui-engine/$version/bootui-engine-$version.jar"
built="$REPO_ROOT/bootui-engine/target/bootui-engine-$version.jar"
[ -f "$installed" ] || die "$installed is missing; run without --no-build"
[ -f "$built" ] || die "$built is missing; run without --no-build"
installed_sha="$(sha256_of "$installed")"
built_sha="$(sha256_of "$built")"
[ "$installed_sha" = "$built_sha" ] || die "the installed engine jar ($installed_sha) is not this checkout's build ($built_sha)"
# Maven marks an artifact it downloaded with its repository id; a locally installed one carries none.
if grep -q 'central' "$(dirname "$installed")/_remote.repositories" 2>/dev/null; then
  die "bootui-engine $version in $M2 came from Maven Central, not from this checkout"
fi

cat >"$BUILD_ENV" <<EOF
BOOTUI_VERSION=$version
BOOTUI_COMMIT=$commit
BOOTUI_BRANCH=$branch
BOOTUI_ENGINE_SHA256=$built_sha
BOOTUI_AGENT_JAR=$M2/com/julien-dubois/bootui/bootui-agent/$version/bootui-agent-$version.jar
BOOTUI_BUILT_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
log "recorded $BUILD_ENV"
cat "$BUILD_ENV"
