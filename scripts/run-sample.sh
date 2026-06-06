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

# Build the list of selectable refs: "main" followed by up to the 5 most recent tags.
# The default selection is the most recent tag when one exists, otherwise "main".
select_ref() {
  local -a tags=("$@")
  # Use the `${arr[@]+...}` idiom so an empty `tags` array does not trip
  # `set -u` ("unbound variable") on Bash 3.2 (macOS /bin/bash).
  local -a options=("main" ${tags[@]+"${tags[@]}"})
  local default_index=0
  if (( ${#tags[@]} > 0 )); then
    default_index=1
  fi

  # Allow non-interactive overrides and skip the prompt when no terminal is available.
  if [[ -n "${BOOTUI_REF:-}" ]]; then
    SELECTED_REF="$BOOTUI_REF"
    echo "Using BOOTUI_REF=$SELECTED_REF." >&2
    return
  fi
  if [[ ! -r /dev/tty ]]; then
    SELECTED_REF="${options[$default_index]}"
    echo "No interactive terminal detected; defaulting to '$SELECTED_REF'." >&2
    return
  fi

  echo "Select the BootUI version to build and run:" >&2
  local i
  for i in "${!options[@]}"; do
    local suffix=""
    if (( i == default_index )); then
      suffix=" (default)"
    fi
    printf "  %d) %s%s\n" "$((i + 1))" "${options[$i]}" "$suffix" >&2
  done

  local choice
  while true; do
    printf "Enter a number [%d]: " "$((default_index + 1))" >&2
    if ! read -r choice </dev/tty; then
      choice=""
    fi
    if [[ -z "$choice" ]]; then
      SELECTED_REF="${options[$default_index]}"
      break
    fi
    if [[ "$choice" =~ ^[0-9]+$ ]] && (( choice >= 1 && choice <= ${#options[@]} )); then
      SELECTED_REF="${options[$((choice - 1))]}"
      break
    fi
    echo "Invalid selection. Please enter a number between 1 and ${#options[@]}." >&2
  done
}

if [[ -x "./mvnw" && -d "bootui-sample-app" ]]; then
  echo "Using existing BootUI checkout at $(pwd)."
  require_command git

  # Read tags into an array. Avoid `mapfile`/`readarray` (Bash 4+) so the script
  # works under the Bash 3.2 that ships as /bin/bash on macOS.
  TAGS=()
  while IFS= read -r tag; do
    TAGS+=("$tag")
  done < <(git tag --sort=-v:refname 2>/dev/null | head -5)
  select_ref ${TAGS[@]+"${TAGS[@]}"}

  echo "Checking out '$SELECTED_REF'..."
  git checkout "$SELECTED_REF"
else
  require_command git

  if [[ -e "$TARGET_DIR" ]]; then
    echo "Error: target directory '$TARGET_DIR' already exists." >&2
    echo "Run this script from that checkout, remove the directory, or set BOOTUI_DIR to another path." >&2
    exit 1
  fi

  # Read tags into an array. Avoid `mapfile`/`readarray` (Bash 4+) so the script
  # works under the Bash 3.2 that ships as /bin/bash on macOS.
  TAGS=()
  while IFS= read -r tag; do
    TAGS+=("$tag")
  done < <(git ls-remote --tags --refs --sort=-v:refname "$REPO_URL" 2>/dev/null | sed 's#.*refs/tags/##' | head -5)
  select_ref ${TAGS[@]+"${TAGS[@]}"}

  echo "Cloning BootUI ($SELECTED_REF) into $TARGET_DIR..."
  git clone --depth 1 --branch "$SELECTED_REF" "$REPO_URL" "$TARGET_DIR"
  cd "$TARGET_DIR"
fi

require_command java
require_command docker

echo "Building BootUI with tests skipped..."
./mvnw -B -ntp install -DskipTests

echo "Starting the BootUI sample app with PostgreSQL, Redis, and Ollama."
echo "First startup may also pull the qwen2.5:0.5b chat model. Open http://localhost:8080/bootui after startup."
./mvnw -pl bootui-sample-app spring-boot:run -Dspring-boot.run.profiles=dev
