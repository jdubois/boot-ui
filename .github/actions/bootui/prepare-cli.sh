#!/usr/bin/env bash
set -euo pipefail

readonly RELEASE_KEY_FINGERPRINT="7B7C0BD038603E5A9F1476D0498BA5AC9BABBAF9"

fail() {
  echo "::error::$*" >&2
  exit 1
}

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
[[ -n "${RUNNER_TEMP:-}" && "$RUNNER_TEMP" == /* ]] || fail "RUNNER_TEMP must be an absolute path."
[[ "$RUNNER_TEMP" != *$'\n'* && "$RUNNER_TEMP" != *$'\r'* ]] \
  || fail "RUNNER_TEMP must not contain a newline."
for command in java python3; do
  command -v "$command" >/dev/null 2>&1 || fail "Required command is unavailable: $command."
done
workspace="${GITHUB_WORKSPACE:-$PWD}"
if [[ "${BOOTUI_PHASE:-}" == run && "${BOOTUI_APP_COMMAND:-auto}" == auto && -f "$workspace/pom.xml" ]]; then
  if ! python3 "$script_dir/maven-context.py" "$workspace" app >/dev/null; then
    echo "app-config-error=true" >> "$GITHUB_OUTPUT"
    fail "BootUI could not select the application's Maven profile."
  fi
fi

advertise_cli() {
  local ci_help
  ci_help="$(java -jar "$cli_jar" ci --help 2>&1)" \
    || fail "The selected BootUI CLI cannot run the CI commands."
  grep -Eq '^Usage: bootui ci([[:space:]]|$)' <<<"$ci_help" \
    || fail "The selected BootUI release does not contain the CI command interface; select a CI-capable release."
  echo "BOOTUI_CLI_JAR=$cli_jar" >> "$GITHUB_ENV"
  echo "cli-jar=$cli_jar" >> "$GITHUB_OUTPUT"
}

if [[ -n "${BOOTUI_LOCAL_CLI_JAR:-}" ]]; then
  [[ "${BOOTUI_VERSION:-auto}" == auto ]] || fail "cli-jar cannot be combined with an explicit bootui-version."
  [[ "$BOOTUI_LOCAL_CLI_JAR" != *$'\n'* && "$BOOTUI_LOCAL_CLI_JAR" != *$'\r'* ]] \
    || fail "cli-jar must not contain a newline."
  cli_jar="$BOOTUI_LOCAL_CLI_JAR"
  [[ "$cli_jar" == /* ]] || cli_jar="$workspace/$cli_jar"
  [[ -f "$cli_jar" && -r "$cli_jar" ]] || fail "cli-jar must name a readable local file."
  cli_jar="$(python3 - "$cli_jar" <<'PY'
from pathlib import Path
import sys

path = str(Path(sys.argv[1]).resolve(strict=True))
if "\n" in path or "\r" in path:
    raise SystemExit("::error::The resolved cli-jar must not contain a newline.")
print(path)
PY
  )" || fail "Could not resolve the local CLI jar."
  advertise_cli
  echo "::notice::Using the caller-provided local BootUI CLI; release download and signature verification are skipped."
  exit 0
fi

for command in curl gpg; do
  command -v "$command" >/dev/null 2>&1 || fail "Required command is unavailable: $command."
done
version="$(python3 "$script_dir/resolve-version.py" "${BOOTUI_VERSION:-auto}")"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "bootui-version must resolve to a stable major.minor.patch version."

keyring_alias=""
temporary_directory=""
cleanup() {
  [[ -z "$temporary_directory" ]] || rm -rf -- "$temporary_directory"
  [[ -z "$keyring_alias" ]] || rm -rf -- "$keyring_alias"
}
trap cleanup EXIT

readonly cli_directory="$RUNNER_TEMP/bootui-cli/$version"
readonly keyring_directory="$RUNNER_TEMP/bootui-cli/gnupg"
readonly cli_jar="$cli_directory/bootui-cli-$version-all.jar"
readonly cli_signature="$cli_jar.asc"
readonly release_key="$cli_directory/bootui-release-key.asc"
mkdir -p "$cli_directory" "$keyring_directory"
chmod 700 "$keyring_directory"
keyring_bytes="$(printf '%s' "$keyring_directory" | wc -c)"
if (( keyring_bytes > 70 )); then
  # GPG agent sockets have a short Unix-domain path limit, even on long runner workspaces.
  keyring_alias="$(mktemp -d /tmp/bootui-gnupg.XXXXXXXX)"
  ln -s "$keyring_directory" "$keyring_alias/keyring"
  export GNUPGHOME="$keyring_alias/keyring"
else
  export GNUPGHOME="$keyring_directory"
fi

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

advertise_cli
echo "Verified bootui-cli $version against the pinned BootUI release signing key."
