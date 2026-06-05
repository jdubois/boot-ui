#!/usr/bin/env bash
set -euo pipefail

REPO_URL="${BOOTUI_REPO_URL:-https://github.com/jdubois/boot-ui.git}"
TARGET_DIR="${BOOTUI_DIR:-boot-ui}"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Error: '$1' is required to run the BootUI sample app." >&2
    exit 1
  fi
}

if [[ -x "./mvnw" && -d "bootui-sample-app" ]]; then
  echo "Using existing BootUI checkout at $(pwd)."
else
  require_command git

  if [[ -e "$TARGET_DIR" ]]; then
    echo "Error: target directory '$TARGET_DIR' already exists." >&2
    echo "Run this script from that checkout, remove the directory, or set BOOTUI_DIR to another path." >&2
    exit 1
  fi

  echo "Cloning BootUI into $TARGET_DIR..."
  git clone --depth 1 "$REPO_URL" "$TARGET_DIR"
  cd "$TARGET_DIR"
fi

require_command java
require_command docker

MAVEN_RUN_ARGS=(
  "-ntp"
  "-pl"
  "bootui-sample-app"
  "-Dmaven.test.skip=true"
  "spring-boot:run"
  "-Dspring-boot.run.profiles=dev"
)

echo "Starting the BootUI sample app with PostgreSQL, Redis, and Ollama."
echo "First startup may also pull the qwen2.5:0.5b chat model. Open http://localhost:8080/bootui after startup."
echo "Trying the offline Maven cache first for the fastest startup."
set +e
./mvnw -o "${MAVEN_RUN_ARGS[@]}"
status=$?
set -e

if [[ "$status" -eq 0 ]]; then
  exit 0
fi

if [[ "$status" -eq 130 || "$status" -eq 143 ]]; then
  exit "$status"
fi

echo "Offline launch failed; retrying without -o so Maven can resolve missing artifacts."
./mvnw "${MAVEN_RUN_ARGS[@]}"
