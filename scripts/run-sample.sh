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
  git clone "$REPO_URL" "$TARGET_DIR"
  cd "$TARGET_DIR"
fi

require_command java
require_command docker

echo "Building BootUI with tests skipped..."
./mvnw -B -ntp install -DskipTests

echo "Starting the BootUI sample app with PostgreSQL, Redis, and Ollama."
echo "First startup may also pull the qwen2.5:0.5b chat model. Open http://localhost:8080/bootui after startup."
./mvnw -pl bootui-sample-app spring-boot:run -Dspring-boot.run.profiles=dev
