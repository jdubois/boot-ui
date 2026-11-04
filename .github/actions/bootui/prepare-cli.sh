#!/usr/bin/env bash
set -euo pipefail

readonly RELEASE_KEY_FINGERPRINT="7B7C0BD038603E5A9F1476D0498BA5AC9BABBAF9"

fail() {
  echo "::error::$*" >&2
  exit 1
}

version="${BOOTUI_VERSION:-}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "bootui-version must be a stable major.minor.patch version."
[[ -n "${RUNNER_TEMP:-}" && "$RUNNER_TEMP" == /* ]] || fail "RUNNER_TEMP must be an absolute path."
for command in curl gpg java; do
  command -v "$command" >/dev/null 2>&1 || fail "Required command is unavailable: $command."
done

readonly cli_directory="$RUNNER_TEMP/bootui-cli/$version"
readonly keyring_directory="$RUNNER_TEMP/bootui-cli/gnupg"
readonly cli_jar="$cli_directory/bootui-cli-$version-all.jar"
readonly cli_signature="$cli_jar.asc"
readonly release_key="$cli_directory/bootui-release-key.asc"
mkdir -p "$cli_directory" "$keyring_directory"
chmod 700 "$keyring_directory"
export GNUPGHOME="$keyring_directory"

if ! gpg --batch --list-keys "$RELEASE_KEY_FINGERPRINT" >/dev/null 2>&1; then
  curl --fail --silent --show-error --location --max-redirs 3 --retry 3 --connect-timeout 10 --max-time 60 --retry-max-time 180 \
    --output "$release_key" \
    "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x$RELEASE_KEY_FINGERPRINT" \
    || fail "Could not retrieve the pinned BootUI release signing key."
  gpg --batch --import "$release_key" >/dev/null \
    || fail "Could not import the pinned BootUI release signing key."
fi

verify_signature() {
  local verification
  verification="$(gpg --batch --status-fd 1 --verify "$cli_signature" "$cli_jar")" \
    || fail "The BootUI CLI signature is invalid."
  grep -Eq "^\[GNUPG:\] VALIDSIG .* ${RELEASE_KEY_FINGERPRINT}\$" <<<"$verification" \
    || fail "The BootUI CLI signature is not from the pinned release key."
}

if [[ ! -f "$cli_jar" || ! -f "$cli_signature" ]]; then
  temporary_directory="$(mktemp -d "$RUNNER_TEMP/bootui-cli-download.XXXXXX")"
  trap 'rm -rf -- "$temporary_directory"' EXIT
  artifact_base="https://repo.maven.apache.org/maven2/com/julien-dubois/bootui/bootui-cli/$version/bootui-cli-$version-all.jar"
  curl --fail --silent --show-error --location --max-redirs 3 --retry 3 --connect-timeout 10 --max-time 60 --retry-max-time 180 \
    --output "$temporary_directory/bootui-cli.jar" "$artifact_base"
  curl --fail --silent --show-error --location --max-redirs 3 --retry 3 --connect-timeout 10 --max-time 60 --retry-max-time 180 \
    --output "$temporary_directory/bootui-cli.jar.asc" "$artifact_base.asc"
  gpg --batch --status-fd 1 --verify \
    "$temporary_directory/bootui-cli.jar.asc" "$temporary_directory/bootui-cli.jar" > "$temporary_directory/verify.out" \
    || fail "The downloaded BootUI CLI signature is invalid."
  grep -Eq "^\[GNUPG:\] VALIDSIG .* ${RELEASE_KEY_FINGERPRINT}\$" "$temporary_directory/verify.out" \
    || fail "The downloaded BootUI CLI is not signed by the pinned release key."
  mv "$temporary_directory/bootui-cli.jar" "$cli_jar"
  mv "$temporary_directory/bootui-cli.jar.asc" "$cli_signature"
fi
verify_signature

echo "BOOTUI_CLI_JAR=$cli_jar" >> "$GITHUB_ENV"
echo "Verified bootui-cli $version against the pinned BootUI release signing key."
