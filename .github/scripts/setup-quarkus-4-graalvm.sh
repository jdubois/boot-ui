#!/usr/bin/env bash
# Install the exact compiler that accepts Quarkus 4's native requirements, on the real target host.
set -euo pipefail

target="${1:?Usage: setup-quarkus-4-graalvm.sh <target> <output-directory>}"
output="${2:?An output directory is required}"
case "$target" in
  linux-x86_64)
    host="Linux/x86_64"; platform=linux-x64
    sha256=3f4a89de8eaa96f2ed677f09957c7e872cd8467aad3537f8b5394c1b8c4b942e ;;
  linux-arm64)
    host="Linux/aarch64"; platform=linux-aarch64
    sha256=22286f7ecd21b9aedb3226b9bf797469e1bd3eefc491e12ef3dd49b452d230b7 ;;
  macos-arm64)
    host="Darwin/arm64"; platform=macos-aarch64
    sha256=507330fff8907de51b8621c95a21a491fa9b0d8c240de184594f40f83add3bfa ;;
  *) echo "::error::Unsupported Quarkus 4 native target: $target" >&2; exit 2 ;;
esac
if [[ "$(uname -s)/$(uname -m)" != "$host" ]]; then
  echo "::error::Native target $target requires an actual $host runner; emulation does not count." >&2
  exit 1
fi
mkdir -p "$output"
output="$(cd "$output" && pwd)"
archive="$output/compiler.tar.gz"
asset="graalvm-community-jdk-25i2-25.0.4_${platform}_bin.tar.gz"
curl --fail --location --connect-timeout 30 --max-time 1200 --retry 2 \
  "https://github.com/graalvm/graalvm-ce-builds/releases/download/graal-25.2.4/$asset" -o "$archive"
if command -v sha256sum >/dev/null 2>&1; then
  actual="$(sha256sum "$archive" | cut -d ' ' -f 1)"
else
  actual="$(shasum -a 256 "$archive" | cut -d ' ' -f 1)"
fi
if [[ "$actual" != "$sha256" ]]; then
  echo "::error::Pinned GraalVM archive checksum mismatch for $target." >&2
  exit 1
fi
tar -xzf "$archive" -C "$output" --strip-components=1
rm "$archive"
home="$output"
if [[ "$target" == macos-arm64 ]]; then home="$output/Contents/Home"; fi
test -x "$home/bin/java"
test -x "$home/bin/native-image"
"$home/bin/java" -version
"$home/bin/native-image" --version
if [[ -n "${GITHUB_ENV:-}" ]]; then
  printf 'JAVA_HOME=%s\nGRAALVM_HOME=%s\n' "$home" "$home" >> "$GITHUB_ENV"
  printf '%s/bin\n' "$home" >> "$GITHUB_PATH"
fi
