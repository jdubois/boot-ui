#!/usr/bin/env bash
# Installs Playwright's Chromium for the e2e project in the current directory, leaving what
# `playwright install --with-deps chromium` leaves, without running apt-get when nothing is missing.
#
# The browser download is skipped when the workflow restored ~/.cache/ms-playwright for this Playwright version. The
# system packages Chromium needs are usually on the runner image already: Playwright's own dry run simulates their
# install with `apt-get install -s` and exits non-zero when one is missing (or when it cannot tell), and only then does
# this run the real install, so a stalled package mirror can only stall a runner that actually lacks a package.
set -euo pipefail

playwright=./node_modules/.bin/playwright
if [[ ! -x "$playwright" ]]; then
  echo "::error::$playwright is missing: run npm ci in the e2e project first."
  exit 1
fi

"$playwright" install chromium

if "$playwright" install-deps --dry-run chromium; then
  echo "Chromium's system dependencies are already installed; apt-get not needed."
else
  echo "Installing Chromium's missing system dependencies with apt-get."
  "$playwright" install-deps chromium
fi
