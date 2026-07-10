#!/usr/bin/env bash

set -euo pipefail

readonly WORKFLOW_DIRECTORY=".github/workflows"
readonly ACTION_DIRECTORY=".github/actions"

is_trusted_action() {
  case "$1" in
    actions/checkout | \
      actions/configure-pages | \
      actions/deploy-pages | \
      actions/download-artifact | \
      actions/setup-java | \
      actions/setup-node | \
      actions/upload-artifact | \
      actions/upload-pages-artifact | \
      docker/build-push-action | \
      docker/login-action | \
      docker/metadata-action | \
      docker/setup-buildx-action | \
      github/codeql-action)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

trim() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

files=()

add_file() {
  local file="$1"
  if [[ ! -r "$file" ]]; then
    printf 'Cannot read action-reference policy input: %s\n' "$file" >&2
    exit 2
  fi
  files[${#files[@]}]="$file"
}

discover_files() {
  local directory="$1"
  shift

  [[ -d "$directory" ]] || return 0

  while IFS= read -r file; do
    add_file "$file"
  done < <(find "$directory" -type f "$@" -print | LC_ALL=C sort)
}

if [[ $# -gt 0 ]]; then
  for file in "$@"; do
    add_file "$file"
  done
else
  discover_files "$WORKFLOW_DIRECTORY" \( -name '*.yml' -o -name '*.yaml' \)
  discover_files "$ACTION_DIRECTORY" \( -name 'action.yml' -o -name 'action.yaml' \)
fi

if [[ ${#files[@]} -eq 0 ]]; then
  printf 'No workflow or composite-action files found.\n' >&2
  exit 2
fi

errors=0
reference_count=0

report_error() {
  local file="$1"
  local line_number="$2"
  local message="$3"

  printf '%s:%s: %s\n' "$file" "$line_number" "$message" >&2
  if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then
    printf '::error file=%s,line=%s::%s\n' "$file" "$line_number" "$message" >&2
  fi
  errors=$((errors + 1))
}

for file in "${files[@]}"; do
  line_number=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    line_number=$((line_number + 1))

    if [[ ! "$line" =~ ^[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]*(.*)$ ]]; then
      continue
    fi

    reference_count=$((reference_count + 1))
    raw_reference="${BASH_REMATCH[2]}"
    version_comment=""

    if [[ "$raw_reference" == *"#"* ]]; then
      version_comment="${raw_reference#*#}"
      raw_reference="${raw_reference%%#*}"
    fi

    reference="$(trim "$raw_reference")"
    version_comment="$(trim "$version_comment")"

    case "$reference" in
      \"*\")
        reference="${reference:1:${#reference}-2}"
        ;;
      \'*\')
        reference="${reference:1:${#reference}-2}"
        ;;
    esac

    if [[ "$reference" == ./* ]]; then
      continue
    fi

    if [[ "$reference" != *@* ]]; then
      report_error "$file" "$line_number" "Remote action reference '$reference' must include an @ref."
      continue
    fi

    action_path="${reference%@*}"
    action_ref="${reference##*@}"

    if [[ "$action_path" != */* || -z "$action_ref" ]]; then
      report_error "$file" "$line_number" "Remote action reference '$reference' is malformed."
      continue
    fi

    action_owner="${action_path%%/*}"
    action_remainder="${action_path#*/}"
    action_repository="${action_remainder%%/*}"

    if [[ -z "$action_owner" || -z "$action_repository" ]]; then
      report_error "$file" "$line_number" "Remote action reference '$reference' is malformed."
      continue
    fi

    action_name="$action_owner/$action_repository"

    if is_trusted_action "$action_name"; then
      if [[ ! "$action_ref" =~ ^v[0-9]+$ ]]; then
        report_error "$file" "$line_number" \
          "Trusted action '$action_name' must use a major-version tag such as @v4; found @$action_ref."
      fi
      continue
    fi

    if [[ ! "$action_ref" =~ ^[0-9a-fA-F]{40}$ ]]; then
      report_error "$file" "$line_number" \
        "Non-allowlisted action '$action_name' must use a full 40-character commit SHA."
      continue
    fi

    if [[ ! "$version_comment" =~ ^v[0-9]+([.][0-9]+){0,2}([.-][0-9A-Za-z]+)*$ ]]; then
      report_error "$file" "$line_number" \
        "SHA-pinned action '$action_name' must have an inline release comment such as '# v1.2.3'."
    fi
  done < "$file"
done

if [[ $errors -gt 0 ]]; then
  printf 'GitHub Action reference policy failed with %s error(s).\n' "$errors" >&2
  exit 1
fi

printf 'GitHub Action reference policy passed for %s reference(s) across %s file(s).\n' \
  "$reference_count" "${#files[@]}"
