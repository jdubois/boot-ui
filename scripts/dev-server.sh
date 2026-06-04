#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
MAVEN_REPO="${BOOTUI_MAVEN_REPO:-$ROOT_DIR/.m2/repository}"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Error: '$1' is required to run the BootUI sample app." >&2
    exit 1
  fi
}

validate_port() {
  local name="$1"
  local value="$2"

  if [[ ! "$value" =~ ^[0-9]+$ ]] || ((value < 1 || value > 65535)); then
    echo "Error: $name must be a TCP port between 1 and 65535." >&2
    exit 1
  fi
}

if [[ ! -x "$ROOT_DIR/mvnw" || ! -d "$ROOT_DIR/bootui-sample-app" ]]; then
  echo "Error: this script must live in the scripts directory of a BootUI checkout." >&2
  exit 1
fi

require_command java
require_command docker

HASH="$(printf '%s' "$ROOT_DIR" | cksum | awk '{print $1}')"

APP_PORT="${BOOTUI_PORT:-$((10000 + HASH % 10000))}"
OLLAMA_PORT="${BOOTUI_OLLAMA_PORT:-$((30000 + HASH % 10000))}"
COMPOSE_PROJECT_NAME="${BOOTUI_COMPOSE_PROJECT_NAME:-${COMPOSE_PROJECT_NAME:-bootui-$HASH}}"

validate_port BOOTUI_PORT "$APP_PORT"
validate_port BOOTUI_OLLAMA_PORT "$OLLAMA_PORT"

export BOOTUI_OLLAMA_PORT="$OLLAMA_PORT"
export COMPOSE_PROJECT_NAME

cd "$ROOT_DIR"

echo "Using Maven repository: $MAVEN_REPO"
echo "Using Docker Compose project: $COMPOSE_PROJECT_NAME"
echo "Using Ollama port: $BOOTUI_OLLAMA_PORT"
echo "Building BootUI with tests skipped..."

./mvnw -B -ntp \
  -Dmaven.repo.local="$MAVEN_REPO" \
  -DskipTests \
  install

echo "Starting the BootUI sample app at http://localhost:$APP_PORT/bootui"

./mvnw -B -ntp \
  -Dmaven.repo.local="$MAVEN_REPO" \
  -pl bootui-sample-app \
  spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.arguments="--server.port=$APP_PORT --spring.ai.ollama.base-url=http://localhost:$OLLAMA_PORT"
