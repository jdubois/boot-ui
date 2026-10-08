#!/usr/bin/env bash
# Installs Playwright's Chromium for the e2e project in the current directory, leaving what
# `playwright install --with-deps chromium` leaves, without depending on a package mirror when nothing is missing.
#
# The browser download is skipped when the workflow restored ~/.cache/ms-playwright for this Playwright version.
# Playwright's own dry run simulates the install of Chromium's system packages with `apt-get install -s` and exits
# non-zero when one is missing; on ubuntu-latest only a few fonts are. Those are installed from the image's package lists
# with the .deb files kept in ~/.cache/playwright-apt, which the workflow caches beside the browsers, so a cached run
# needs neither `apt-get update` nor the mirror. Anything still missing afterwards, or a dry run that cannot tell, falls
# back to `playwright install-deps chromium` (apt-get update, then install), which is what --with-deps runs.
set -euo pipefail

playwright=./node_modules/.bin/playwright
archives="${PLAYWRIGHT_APT_ARCHIVES:-$HOME/.cache/playwright-apt}"
if [[ ! -x "$playwright" ]]; then
  echo "::error::$playwright is missing: run npm ci in the e2e project first."
  exit 1
fi

"$playwright" install chromium

status=0
report="$("$playwright" install-deps --dry-run chromium 2>&1)" || status=$?
echo "$report"
if [[ "$status" -eq 0 ]]; then
  echo "Chromium's system dependencies are already installed; apt-get not needed."
  exit 0
fi

mapfile -t missing < <(sed -n 's/^  \([a-z0-9][a-z0-9.+-]*\)$/\1/p' <<< "$report")
if [[ "${#missing[@]}" -gt 0 ]]; then
  echo "Installing ${#missing[@]} missing package(s) with the .deb files cached in $archives when present."
  mkdir -p "$archives/partial"
  installed=0
  sudo apt-get install -y --no-install-recommends \
    -o Dir::Cache::Archives="$archives" -o APT::Keep-Downloaded-Packages=true "${missing[@]}" || installed=$?
  # apt leaves partial/ to its sandbox user, mode 0700, which the cache could not read back.
  sudo rm -rf "$archives/partial"
  sudo chmod -R a+rX "$archives"
  if [[ "$installed" -eq 0 ]] && "$playwright" install-deps --dry-run chromium; then
    echo "Chromium's system dependencies are installed."
    exit 0
  fi
fi

echo "Installing Chromium's system dependencies with apt-get update and install."
"$playwright" install-deps chromium
