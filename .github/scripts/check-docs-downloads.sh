#!/usr/bin/env bash
#
# The documentation site hands out installer scripts and the CI starter kit as static assets.
# Check that referenced downloads reach the built site and that installers stay version-free.
#
# Usage: check-docs-downloads.sh [built-site-directory]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
readonly REPO_ROOT
readonly DIST="${1:-$REPO_ROOT/docs/.vuepress/dist}"
readonly PUBLIC="$REPO_ROOT/docs/.vuepress/public"
readonly SITE_URL="https://www.julien-dubois.com/boot-ui"

errors=0

report_error() {
  printf 'check-docs-downloads: %s\n' "$1" >&2
  errors=$((errors + 1))
}

# Every advertised download must be present in the static assets and, when available, the built site.
referenced="$(
  grep -rhoE "${SITE_URL}/[A-Za-z0-9._/-]+\.(sh|ps1|zip)" \
    "$REPO_ROOT/README.md" "$REPO_ROOT/docs" --include='*.md' 2>/dev/null |
    sort -u || true
)"

if [[ -z "$referenced" ]]; then
  report_error "no install script URLs are referenced in the documentation; did they move?"
fi

while IFS= read -r url; do
  [[ -n "$url" ]] || continue
  asset="${url#"$SITE_URL"/}"
  if [[ ! -f "$PUBLIC/$asset" ]]; then
    report_error "the documentation links to $url but docs/.vuepress/public/$asset does not exist"
    continue
  fi
  if [[ -d "$DIST" && ! -f "$DIST/$asset" ]]; then
    report_error "$asset is not in the built site, so $url would return 404"
  fi
  printf 'ok: %s is included in documentation assets\n' "$url"
done <<<"$referenced"

# The installers resolve the version to install from the repository at run time. A
# literal version in them would not be rewritten by a release and would quietly rot.
for script in "$PUBLIC"/install.sh "$PUBLIC"/install.ps1; do
  [[ -f "$script" ]] || continue
  pinned="$(grep -nE '[0-9]+\.[0-9]+\.[0-9]+' "$script" || true)"
  if [[ -n "$pinned" ]]; then
    report_error "$(basename "$script") pins a version, which a release would not update:
$pinned"
  fi
done

if [[ ! -f "$PUBLIC/install.sh" ]]; then
  : # Its absence is already reported above.
elif command -v shellcheck >/dev/null 2>&1; then
  if shellcheck -s sh "$PUBLIC/install.sh"; then
    printf 'ok: install.sh passes shellcheck\n'
  else
    report_error 'install.sh fails shellcheck'
  fi
else
  printf 'note: shellcheck is unavailable, skipping the installer lint\n'
fi

if [[ $errors -gt 0 ]]; then
  printf 'Documentation download check failed with %s error(s).\n' "$errors" >&2
  exit 1
fi

printf 'Documentation download check passed.\n'
