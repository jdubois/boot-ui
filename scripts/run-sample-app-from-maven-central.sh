#!/usr/bin/env bash
set -euo pipefail

artifact_id="bootui-sample-app"
group_path="com/julien-dubois/bootui"
central_base_url="${MAVEN_CENTRAL_BASE_URL:-https://repo1.maven.org/maven2}"
artifact_base_url="${central_base_url%/}/${group_path}/${artifact_id}"
download_dir="${BOOTUI_DOWNLOAD_DIR:-${TMPDIR:-/tmp}/bootui-sample-app}"
version="${BOOTUI_VERSION:-latest}"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 127
  fi
}

resolve_latest_version() {
  local metadata
  local resolved_version
  metadata="$(curl -fsSL "${artifact_base_url}/maven-metadata.xml")"
  resolved_version="$(printf '%s\n' "${metadata}" | sed -n 's:.*<release>\(.*\)</release>.*:\1:p' | tail -n 1)"
  if [[ -z "${resolved_version}" ]]; then
    resolved_version="$(printf '%s\n' "${metadata}" | sed -n 's:.*<latest>\(.*\)</latest>.*:\1:p' | tail -n 1)"
  fi
  printf '%s\n' "${resolved_version}"
}

has_active_profile_arg() {
  local arg
  for arg in "$@"; do
    case "${arg}" in
      --spring.profiles.active | --spring.profiles.active=*)
        return 0
        ;;
    esac
  done
  return 1
}

require_command curl
require_command java
require_command jar

if [[ "${version}" == "latest" ]]; then
  version="$(resolve_latest_version)"
  if [[ -z "${version}" ]]; then
    echo "Unable to resolve the latest ${artifact_id} version from Maven Central." >&2
    exit 1
  fi
fi

mkdir -p "${download_dir}"
download_dir="$(cd "${download_dir}" && pwd -P)"
jar_file="${download_dir}/${artifact_id}-${version}.jar"
jar_url="${artifact_base_url}/${version}/${artifact_id}-${version}.jar"

echo "Downloading ${jar_url}"
curl -fsSL --retry 3 --output "${jar_file}.tmp" "${jar_url}"
mv "${jar_file}.tmp" "${jar_file}"

manifest_dir="$(mktemp -d)"
trap 'rm -rf "${manifest_dir}"' EXIT
(cd "${manifest_dir}" && jar xf "${jar_file}" META-INF/MANIFEST.MF)

if ! grep -q '^Main-Class: org.springframework.boot.loader.launch.JarLauncher' \
  "${manifest_dir}/META-INF/MANIFEST.MF"; then
  echo "The downloaded ${artifact_id} ${version} JAR is not executable with java -jar." >&2
  echo "Use a BootUI version published after the sample app executable JAR change." >&2
  exit 1
fi
if ! grep -q '^Start-Class: io.github.jdubois.bootui.sample.BootUiSampleApplication' \
  "${manifest_dir}/META-INF/MANIFEST.MF"; then
  echo "The downloaded ${artifact_id} ${version} JAR does not start the BootUI sample app." >&2
  exit 1
fi

profile_args=()
if ! has_active_profile_arg "$@"; then
  profile_args=(--spring.profiles.active="${BOOTUI_PROFILE:-dev}")
fi

echo "Starting BootUI sample app ${version} at http://localhost:8080/bootui"
cd "${download_dir}"
exec java ${JAVA_OPTS:-} -jar "${jar_file}" "${profile_args[@]}" "$@"
