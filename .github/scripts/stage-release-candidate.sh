#!/usr/bin/env bash
#
# Stages a BootUI release candidate as a local Maven file repository, from the working tree.
#
# Usage: stage-release-candidate.sh <output-directory>
#
# Builds the publication-only reactor exactly as release.yml publishes it, unsigned: it installs the reactor, then
# assembles the Central bundle from the installed files with assemble_central_bundle.py, as the "Publish to Maven
# Central" step does before uploading it. bootui-agent-bridge and both parent POMs are installed with the reactor but
# never bundled. The bundle is a Maven repository layout, unzipped into <output-directory> and checked by
# check-central-bundle.py (the published coordinates, no other file, and the flattened, parentless POMs).
# consumer-smoke-tests.sh <version> <output-directory> then runs the consumer smoke tests against it, before anything
# reaches Maven Central.
#
# The reactor is installed into the local Maven repository Maven is configured with (MAVEN_OPTS
# -Dmaven.repo.local=... selects another), replacing any earlier install of the same version.

set -euo pipefail

if [[ $# -ne 1 ]]; then
  printf 'Usage: %s <output-directory>\n' "$0" >&2
  exit 2
fi

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly REPOSITORY_ROOT
readonly OUTPUT="$1"

cd "$REPOSITORY_ROOT"
VERSION="$(./mvnw -B -ntp -q -N -DforceStdout help:evaluate -Dexpression=project.version | tail -n 1)"
readonly VERSION

LOCAL_REPO="$(./mvnw -B -ntp -q -N -DforceStdout help:evaluate -Dexpression=settings.localRepository | tail -n 1)"
readonly LOCAL_REPO
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
readonly BUNDLE="$WORK/central-bundle.zip"

# Like the publication step, bundle only this build, never an earlier install of the same version.
rm -rf "$LOCAL_REPO"/com/julien-dubois/bootui/*/"$VERSION"

# The same reactor as release.yml's "Publish to Maven Central" step; check-release-integrity.sh keeps them equal.
./mvnw -B -ntp -Prelease clean install \
  -pl .,bootui-engine,bootui-spring-boot-starter,bootui-ui,bootui-quarkus-parent,bootui-quarkus,bootui-quarkus-deployment,bootui-cli,bootui-agent-bridge,bootui-agent \
  -am \
  -DskipTests \
  -Dgpg.skip=true

python3 "$REPOSITORY_ROOT/.github/scripts/assemble_central_bundle.py" --unsigned "$LOCAL_REPO" "$VERSION" "$BUNDLE"

rm -rf "$OUTPUT"
mkdir -p "$OUTPUT"
unzip -q "$BUNDLE" -d "$OUTPUT"
python3 "$REPOSITORY_ROOT/.github/scripts/check-central-bundle.py" "$OUTPUT" "$VERSION"
printf 'Staged BootUI %s release candidate in %s\n' "$VERSION" "$OUTPUT"
