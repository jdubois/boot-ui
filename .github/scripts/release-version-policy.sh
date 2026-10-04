#!/usr/bin/env bash
#
# Version policy for .github/workflows/release.yml. Releases are tracked per major version so a
# maintained older major (for example 1.x after 2.0.0) can still ship patches, while the
# documentation site only ever follows the newest major.
#
# Every branch declares its release line, the major version its contents belong to, in
# `.github/release-line`. A branch preparing the next major (such as `v2` before 2.0.0) still
# carries the previous major's project version, so the project version alone cannot tell its
# contents apart; the release line can.
#
# The tag-reading commands read candidate tag names from standard input, one per line. Only stable
# `vMAJOR.MINOR.PATCH` names are considered; anything else on stdin is ignored.
#
# Usage:
#   release-version-policy.sh line-major PROJECT_VERSION RELEASE_LINE_FILE
#     Prints the release line declared in RELEASE_LINE_FILE: one integer, `#` comments and blank
#     lines ignored. The file is required, and its major must be PROJECT_VERSION's major, or that
#     major plus one while a branch prepares the next major, and at least 2 when the repository
#     around RELEASE_LINE_FILE's directory contains bootui-agent, which exists only from 2.0. Exits 2
#     otherwise.
#
#   git tag --list 'v*' | release-version-policy.sh next-version VERSION CURRENT_PROJECT_VERSION [RELEASE_LINE]
#     Accepts VERSION only when it is exactly the next patch or minor after the newest stable tag
#     of its own major, or opens a new major as MAJOR.0.0 directly above every existing major.
#     VERSION's major must also match the source branch: the major of CURRENT_PROJECT_VERSION,
#     or that major plus one when VERSION opens a new major, and, when RELEASE_LINE is given, the
#     branch's release line exactly. Exits 2 when VERSION is rejected.
#
#   git ls-remote ... | release-version-policy.sh newest-major VERSION
#     Prints `true` when VERSION's major is at least the highest major among the stable tags
#     (VERSION itself included), so the documentation site may be redeployed; `false` otherwise.
#
#   git ls-remote ... | release-version-policy.sh line-tags RELEASE_LINE
#     Lists, oldest first, `line vX.Y.Z` for every stable tag of RELEASE_LINE, then `newer vX.Y.Z`
#     for every stable tag of a higher major. release-line-gate.sh checks them on Maven Central to
#     decide whether the line is released and still the newest released major.

set -euo pipefail

readonly STABLE_VERSION='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
readonly STABLE_TAG='^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'

usage() {
  printf 'Usage: %s line-major PROJECT_VERSION RELEASE_LINE_FILE\n' "${0##*/}" >&2
  printf '       %s next-version VERSION CURRENT_PROJECT_VERSION [RELEASE_LINE] < tags\n' "${0##*/}" >&2
  printf '       %s newest-major VERSION < tags\n' "${0##*/}" >&2
  printf '       %s line-tags RELEASE_LINE < tags\n' "${0##*/}" >&2
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

line_major() {
  local project_version="$1" line_file="$2" line='' entry project_major
  if [[ ! "$project_version" =~ ^(0|[1-9][0-9]*)\. ]]; then
    reject "Cannot determine the major version of project version '$project_version'"
  fi
  project_major="${BASH_REMATCH[1]}"
  if [[ ! -f "$line_file" ]]; then
    reject "Missing release line file '$line_file': every branch must declare the major its contents belong to"
  fi
  while IFS= read -r entry || [[ -n "$entry" ]]; do
    entry="${entry%%#*}"
    entry="${entry//[[:space:]]/}"
    if [[ -z "$entry" ]]; then
      continue
    fi
    if [[ -n "$line" || ! "$entry" =~ ^(0|[1-9][0-9]*)$ ]]; then
      reject "Release line file '$line_file' must hold exactly one major version number"
    fi
    line="$entry"
  done <"$line_file"
  if [[ -z "$line" ]]; then
    reject "Release line file '$line_file' must hold exactly one major version number"
  fi
  if ((line != project_major && line != project_major + 1)); then
    reject "Release line $line in '$line_file' does not match project version $project_version: expected $project_major, or $((project_major + 1)) while preparing the next major"
  fi
  # 2.x contents are recognizable whatever the project version says: the BootUI Java agent exists
  # only from 2.0. A main-to-v2 sync that resolves the release line to 1 would otherwise pass and let
  # the merged main publish 2.0 as the released 1.x line.
  local repository_root
  repository_root="$(cd "$(dirname "$line_file")/.." && pwd)"
  if [[ -f "$repository_root/bootui-agent/pom.xml" ]] && ((line < 2)); then
    reject "Release line $line in '$line_file' is too low: this branch contains bootui-agent, which exists only from 2.0, so its release line must be at least 2"
  fi
  printf '%s\n' "$line"
}

next_version() {
  local version="$1" current_version="$2" release_line="${3:-}"
  local release_major release_minor release_patch current_major
  parse_version "$version" 'release version'
  release_major="$MAJOR"
  release_minor="$MINOR"
  release_patch="$PATCH"

  # The release line keeps a branch from publishing contents under another major's coordinates:
  # `main` after `v2` merges still carries a 1.x project version but must never release 1.x, and
  # a 1.x maintenance branch must never open 2.0.0.
  if [[ -n "$release_line" ]]; then
    if [[ ! "$release_line" =~ ^(0|[1-9][0-9]*)$ ]]; then
      reject "Invalid release line '$release_line': expected a major version number"
    fi
    if ((release_major != release_line)); then
      reject "Release version '$version' does not belong to this branch's release line $release_line (.github/release-line). Release $release_major.x from the branch whose release line is $release_major."
    fi
  fi

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

line_tags() {
  local line="$1" tag major key
  if [[ ! "$line" =~ ^(0|[1-9][0-9]*)$ ]]; then
    reject "Invalid release line '$line': expected a major version number"
  fi
  local -a line_tags=() newer_tags=()
  while IFS= read -r tag || [[ -n "$tag" ]]; do
    tag="${tag//[[:space:]]/}"
    if [[ ! "$tag" =~ $STABLE_TAG ]]; then
      continue
    fi
    major="${BASH_REMATCH[1]}"
    # Zero-padded sort keys keep the order numeric without relying on `sort -V`.
    printf -v key '%09d.%09d.%09d' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}"
    if ((major == line)); then
      line_tags+=("$key $tag")
    elif ((major > line)); then
      newer_tags+=("$key $tag")
    fi
  done
  if ((${#line_tags[@]} > 0)); then
    printf '%s\n' "${line_tags[@]}" | LC_ALL=C sort -u | awk '{ print "line " $2 }'
  fi
  if ((${#newer_tags[@]} > 0)); then
    printf '%s\n' "${newer_tags[@]}" | LC_ALL=C sort -u | awk '{ print "newer " $2 }'
  fi
}

if (($# < 1)); then
  usage
fi

case "$1" in
  line-major)
    (($# == 3)) || usage
    line_major "$2" "$3"
    ;;
  next-version)
    (($# == 3 || $# == 4)) || usage
    if (($# == 4)) && [[ -z "$4" ]]; then
      reject "Empty release line: the branch's release line could not be read"
    fi
    next_version "$2" "$3" "${4:-}"
    ;;
  newest-major)
    (($# == 2)) || usage
    newest_major "$2"
    ;;
  line-tags)
    (($# == 2)) || usage
    line_tags "$2"
    ;;
  *)
    usage
    ;;
esac
