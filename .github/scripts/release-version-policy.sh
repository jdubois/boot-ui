#!/usr/bin/env bash
#
# Version policy for .github/workflows/release.yml. Releases are tracked per major version so a
# maintained older major (for example 1.x after 2.0.0) can still ship patches, while the
# documentation site only ever follows the newest major.
#
# Both commands read candidate tag names from standard input, one per line. Only stable
# `vMAJOR.MINOR.PATCH` names are considered; anything else on stdin is ignored.
#
# Usage:
#   git tag --list 'v*' | release-version-policy.sh next-version VERSION CURRENT_PROJECT_VERSION
#     Accepts VERSION only when it is exactly the next patch or minor after the newest stable tag
#     of its own major, or opens a new major as MAJOR.0.0 directly above every existing major.
#     VERSION's major must also match the source branch: the major of CURRENT_PROJECT_VERSION,
#     or that major plus one when VERSION opens a new major. Exits 2 when VERSION is rejected.
#
#   git ls-remote ... | release-version-policy.sh newest-major VERSION
#     Prints `true` when VERSION's major is at least the highest major among the stable tags
#     (VERSION itself included), so the documentation site may be redeployed; `false` otherwise.

set -euo pipefail

readonly STABLE_VERSION='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
readonly STABLE_TAG='^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'

usage() {
  printf 'Usage: %s next-version VERSION CURRENT_PROJECT_VERSION < tags\n' "${0##*/}" >&2
  printf '       %s newest-major VERSION < tags\n' "${0##*/}" >&2
  exit 64
}

reject() {
  printf '::error::%s\n' "$1" >&2
  exit 2
}

# Parses a stable version into the globals MAJOR, MINOR, and PATCH.
parse_version() {
  local version="$1" label="$2"
  if [[ ! "$version" =~ $STABLE_VERSION ]]; then
    reject "Invalid $label '$version': expected a stable major.minor.patch version"
  fi
  MAJOR="${BASH_REMATCH[1]}"
  MINOR="${BASH_REMATCH[2]}"
  PATCH="${BASH_REMATCH[3]}"
}

# Reads stdin and sets STABLE_TAG_COUNT, HIGHEST_MAJOR, and, for the major passed as $1, the
# newest tag of that major as SAME_MAJOR_TAG with its SAME_MAJOR_MINOR and SAME_MAJOR_PATCH.
# Comparisons are numeric, so v1.10.0 sorts after v1.9.9 without relying on `sort -V`.
scan_tags() {
  local wanted_major="$1" tag major minor patch
  STABLE_TAG_COUNT=0
  HIGHEST_MAJOR=-1
  SAME_MAJOR_TAG=''
  SAME_MAJOR_MINOR=-1
  SAME_MAJOR_PATCH=-1
  while IFS= read -r tag || [[ -n "$tag" ]]; do
    tag="${tag//[[:space:]]/}"
    if [[ ! "$tag" =~ $STABLE_TAG ]]; then
      continue
    fi
    major="${BASH_REMATCH[1]}"
    minor="${BASH_REMATCH[2]}"
    patch="${BASH_REMATCH[3]}"
    STABLE_TAG_COUNT=$((STABLE_TAG_COUNT + 1))
    if ((major > HIGHEST_MAJOR)); then
      HIGHEST_MAJOR="$major"
    fi
    if ((major == wanted_major)) &&
      ((minor > SAME_MAJOR_MINOR || (minor == SAME_MAJOR_MINOR && patch > SAME_MAJOR_PATCH))); then
      SAME_MAJOR_TAG="$tag"
      SAME_MAJOR_MINOR="$minor"
      SAME_MAJOR_PATCH="$patch"
    fi
  done
}

next_version() {
  local version="$1" current_version="$2"
  local release_major release_minor release_patch current_major
  parse_version "$version" 'release version'
  release_major="$MAJOR"
  release_minor="$MINOR"
  release_patch="$PATCH"

  # The branch's project version may be a snapshot or the previous release; only its major matters.
  if [[ ! "$current_version" =~ ^(0|[1-9][0-9]*)\. ]]; then
    reject "Cannot determine the major version of the source branch from project version '$current_version'"
  fi
  current_major="${BASH_REMATCH[1]}"

  scan_tags "$release_major"
  if ((STABLE_TAG_COUNT == 0)); then
    reject "Cannot determine the latest stable release tag"
  fi

  if [[ -n "$SAME_MAJOR_TAG" ]]; then
    local next_patch="$release_major.$SAME_MAJOR_MINOR.$((SAME_MAJOR_PATCH + 1))"
    local next_minor="$release_major.$((SAME_MAJOR_MINOR + 1)).0"
    if [[ "$version" != "$next_patch" && "$version" != "$next_minor" ]]; then
      reject "Invalid release version '$version'. Latest $release_major.x release is $SAME_MAJOR_TAG; expected $next_patch (patch) or $next_minor (minor)."
    fi
    if ((release_major != current_major)); then
      reject "Release version '$version' does not belong to the source branch, whose project version $current_version is on major $current_major. Release $release_major.x from a branch on that major."
    fi
    printf 'Release %s follows %s, the latest %s.x release.\n' "$version" "$SAME_MAJOR_TAG" "$release_major"
    return
  fi

  local next_major="$((HIGHEST_MAJOR + 1)).0.0"
  if ((release_major <= HIGHEST_MAJOR)) || ((release_minor != 0 || release_patch != 0)); then
    reject "Invalid release version '$version'. No $release_major.x release exists, so only a new major directly above the latest major $HIGHEST_MAJOR is allowed: expected $next_major (major)."
  fi
  if ((release_major != HIGHEST_MAJOR + 1)); then
    reject "Invalid release version '$version'. The latest major is $HIGHEST_MAJOR; expected $next_major (major)."
  fi
  if ((release_major != current_major && release_major != current_major + 1)); then
    reject "Release version '$version' does not belong to the source branch, whose project version $current_version is on major $current_major."
  fi
  printf 'Release %s opens major %s after the latest major %s.\n' "$version" "$release_major" "$HIGHEST_MAJOR"
}

newest_major() {
  local version="$1"
  parse_version "$version" 'release version'
  scan_tags "$MAJOR"
  if ((MAJOR >= HIGHEST_MAJOR)); then
    printf 'true\n'
  else
    printf 'false\n'
  fi
}

if (($# < 1)); then
  usage
fi

case "$1" in
  next-version)
    (($# == 3)) || usage
    next_version "$2" "$3"
    ;;
  newest-major)
    (($# == 2)) || usage
    newest_major "$2"
    ;;
  *)
    usage
    ;;
esac
