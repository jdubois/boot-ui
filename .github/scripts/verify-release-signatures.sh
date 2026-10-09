#!/usr/bin/env bash
#
# Verifies that every release signature is valid and made by the pinned release key: the primary key whose fingerprint
# is RELEASE_KEY_FINGERPRINT, which release.yml sets once for the whole job.
#
# Usage: verify-release-signatures.sh build
#        verify-release-signatures.sh bundle <bundle-directory>
#
# `build` checks the .asc files `-Prelease clean verify` wrote into ./target and each module's target directory,
# before the release commit and tag exist, so a signing mismatch costs nothing. `bundle` checks every .asc file of the
# unpacked Central bundle, the flattened POMs' included, before the upload, so a bad signature fails here rather than
# in Central's validation of a consumed coordinate. Every failure is one guarded line that check-release-integrity.sh
# pins: the script exits non-zero unless it verified at least one signature and every signature it found.

set -euo pipefail

case "${1:-}" in
  build)
    [[ $# -eq 1 ]] || { echo "Usage: $0 build | bundle <bundle-directory>" >&2; exit 2; }
    readonly SEARCH_ROOT="."
    readonly FIND_FILTER=(-mindepth 2 -maxdepth 3 -path '*/target/*.asc')
    ;;
  bundle)
    [[ $# -eq 2 && -d "$2" ]] || { echo "Usage: $0 build | bundle <bundle-directory>" >&2; exit 2; }
    readonly SEARCH_ROOT="$2"
    readonly FIND_FILTER=(-name '*.asc')
    ;;
  *)
    echo "Usage: $0 build | bundle <bundle-directory>" >&2
    exit 2
    ;;
esac

[[ "${RELEASE_KEY_FINGERPRINT:-}" =~ ^[0-9A-F]{40}$ ]] || { echo "::error::RELEASE_KEY_FINGERPRINT must be the pinned release key's 40-digit fingerprint"; exit 1; }
readonly RELEASE_KEY_FINGERPRINT

signatures=0
while IFS= read -r -d '' signature; do
  verification="$(gpg --batch --status-fd 1 --verify "$signature" "${signature%.asc}")" || { echo "::error::${signature#"$SEARCH_ROOT"/} does not verify"; exit 1; }
  grep -Eq "^\[GNUPG:\] VALIDSIG .* ${RELEASE_KEY_FINGERPRINT}\$" <<<"$verification" || { echo "::error::${signature#"$SEARCH_ROOT"/} is not a valid signature by the release key"; exit 1; }
  signatures=$((signatures + 1))
done < <(find "$SEARCH_ROOT" "${FIND_FILTER[@]}" -type f -print0)
(( signatures > 0 )) || { echo "::error::Found no release signature to verify under $SEARCH_ROOT"; exit 1; }
echo "Verified ${signatures} release signatures by ${RELEASE_KEY_FINGERPRINT} under ${SEARCH_ROOT}."
