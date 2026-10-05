#!/usr/bin/env bash
#
# Decides whether the checked-out branch may publish the documentation site (pages.yml) or the
# Docker Hub sample images (docker-publish.yml). Run it from the repository root.
#
# Both follow the newest released major, and both build from a branch, not from a release tag. A
# branch may publish only when its release line (.github/release-line) is released, meaning one of
# its tags has its artifacts on Maven Central, and no newer major is released. So merging `v2` into
# `main` publishes nothing until the signed v2.0.0 tag and its Maven Central artifacts exist, and a
# 1.x maintenance branch publishes nothing once 2.x is out. The decision reads the tags on origin
# and Maven Central itself, the published world, and fails closed: an unreadable answer is an error,
# never a publication.
#
# Writes `publish=true|false`, `release-line=N`, and `reason=...` to $GITHUB_OUTPUT when it is set.
#
# Test seams, never set by a workflow (check-release-integrity.sh rejects them there):
#   BOOTUI_RELEASE_TAGS_FILE  read tag names from this file instead of `git ls-remote origin`
#   BOOTUI_CENTRAL_URL        Maven repository base URL instead of https://repo1.maven.org/maven2

set -euo pipefail

readonly POLICY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/release-version-policy.sh"
readonly CENTRAL_URL="${BOOTUI_CENTRAL_URL:-https://repo1.maven.org/maven2}"
readonly GROUP_PATH='com/julien-dubois/bootui'

fail() {
  printf '::error::%s\n' "$1" >&2
  exit 1
}

decide() {
  local publish="$1" reason="$2"
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    {
      printf 'publish=%s\n' "$publish"
      printf 'release-line=%s\n' "$LINE"
      printf 'reason=%s\n' "$reason"
    } >>"$GITHUB_OUTPUT"
  fi
  if [[ "$publish" == "true" ]]; then
    printf 'publish=true: %s\n' "$reason"
  else
    printf '::notice::publish=false: %s\n' "$reason"
  fi
  exit 0
}

# Prints `present` when every sentinel artifact of VERSION is downloadable, `absent` when one is
# missing, and fails on any other answer, so a network error never reads as either. The sentinels are
# published on every line: 2.0 stopped publishing the parent POMs, so bootui-parent cannot be one.
central_state() {
  local version="$1" artifact url code
  for artifact in \
    "bootui-core/${version}/bootui-core-${version}.jar" \
    "bootui-spring-boot-starter/${version}/bootui-spring-boot-starter-${version}.jar"; do
    url="${CENTRAL_URL}/${GROUP_PATH}/${artifact}"
    code="$(curl -sS -I -o /dev/null -w '%{http_code}' --max-time 30 --retry 2 --retry-connrefused "$url" || true)"
    case "$code" in
      200) ;;
      404)
        printf 'absent\n'
        return
        ;;
      *) fail "Could not tell whether $url is on Maven Central (HTTP status '${code:-none}'); refusing to decide" ;;
    esac
  done
  printf 'present\n'
}

if [[ ! -f pom.xml ]]; then
  fail "Run release-line-gate.sh from the repository root"
fi
PROJECT_VERSION="$(perl -0ne 'print $1 and exit if m{<artifactId>bootui-parent</artifactId>\s*<version>([^<]+)</version>}' pom.xml)"
if [[ -z "$PROJECT_VERSION" ]]; then
  fail "Could not determine the project version from pom.xml"
fi
LINE="$(bash "$POLICY" line-major "$PROJECT_VERSION" .github/release-line)"

if [[ -n "${BOOTUI_RELEASE_TAGS_FILE:-}" ]]; then
  TAGS="$(cat "$BOOTUI_RELEASE_TAGS_FILE")"
elif ! TAGS="$(git ls-remote --tags --refs origin 'refs/tags/v*')"; then
  fail "Could not read the release tags on origin"
else
  TAGS="$(awk '{ sub("^refs/tags/", "", $2); print $2 }' <<<"$TAGS")"
fi
CANDIDATES="$(bash "$POLICY" line-tags "$LINE" <<<"$TAGS")"

# A newer major counts only once it is on Maven Central: a stray or failed tag must not retire the
# documentation of the line that is actually released.
while read -r kind tag; do
  if [[ "$kind" == "newer" ]]; then
    # An assignment, so a failed check stops the script under `set -e` instead of reading as absent.
    state="$(central_state "${tag#v}")"
    if [[ "$state" == "present" ]]; then
      decide false "release line $LINE is superseded by $tag on Maven Central"
    fi
  fi
done <<<"$CANDIDATES"

# Any released version of the line proves the line is out. Requiring the newest one would block
# every later publication behind one failed or still-propagating patch.
line_tags=0
while read -r kind tag; do
  if [[ "$kind" == "line" ]]; then
    line_tags=$((line_tags + 1))
    state="$(central_state "${tag#v}")"
    if [[ "$state" == "present" ]]; then
      decide true "release line $LINE is released ($tag is on Maven Central) and no newer major is"
    fi
  fi
done <<<"$CANDIDATES"

if ((line_tags == 0)); then
  decide false "release line $LINE has no release tag yet (project version $PROJECT_VERSION)"
fi
decide false "release line $LINE has $line_tags tag(s) but none with its artifacts on Maven Central yet"
