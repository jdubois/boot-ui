#!/usr/bin/env bash
# Clones one application at its pinned commit, resets it, and applies its patches.
#
# Usage: validation/bin/prepare-app.sh <app> [--without-bootui] [--change]
#   --without-bootui  apply only align*.patch (the baseline build, and the start of the time to first observation)
#   --change          also apply change.patch, the code change of the agent-attached run
#
# Patches live in validation/apps/<app>/: align*.patch (version alignment or a fix the application needs to build),
# bootui.patch (adds BootUI exactly as docs/SETUP.md says), and change.patch. @BOOTUI_VERSION@ is replaced by the
# version build-v2.sh recorded.
source "$(dirname "$0")/lib.sh"

app="${1:?usage: prepare-app.sh <app> [--without-bootui] [--change]}"
shift
with_bootui=true
with_change=false
for arg in "$@"; do
  case "$arg" in
    --without-bootui) with_bootui=false ;;
    --change) with_change=true ;;
    *) die "unknown option $arg" ;;
  esac
done

require_cmd git
load_pin "$app"
load_build
src="$(app_src "$app")"
home="$(app_home "$app")"

if [ ! -d "$src/.git" ]; then
  log "cloning $APP_REPO into $src"
  mkdir -p "$(dirname "$src")"
  git clone --quiet "$APP_REPO" "$src"
fi
if ! git -C "$src" cat-file -e "$APP_COMMIT^{commit}" 2>/dev/null; then
  git -C "$src" fetch --quiet origin
fi
git -C "$src" checkout --quiet --force --detach "$APP_COMMIT"
git -C "$src" reset --quiet --hard "$APP_COMMIT"
# Keep build output and downloaded tools, drop everything else a previous run changed.
git -C "$src" clean --quiet -fd

apply_patch() {
  local patch="$1"
  log "applying $(basename "$patch")"
  sed "s/@BOOTUI_VERSION@/$BOOTUI_VERSION/g" "$patch" | git -C "$src" apply --whitespace=nowarn -
}

for patch in "$home"/align*.patch; do
  [ -e "$patch" ] && apply_patch "$patch"
done
if $with_bootui && [ -f "$home/bootui.patch" ]; then apply_patch "$home/bootui.patch"; fi
if $with_change; then
  [ -f "$home/change.patch" ] || die "$app has no change.patch"
  apply_patch "$home/change.patch"
fi
log "$app is at $(git -C "$src" rev-parse --short HEAD) with $(git -C "$src" diff --stat | tail -1)"
