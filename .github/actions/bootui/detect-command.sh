#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "::error::$*" >&2
  exit 1
}

kind="${1:-}"
configured="${2:-auto}"
working_directory="${BOOTUI_WORKING_DIRECTORY:-${GITHUB_WORKSPACE:-$PWD}}"
[[ "$working_directory" == /* ]] || working_directory="$(cd "$working_directory" && pwd -P)"
cd "$working_directory"

if [[ "$configured" != auto ]]; then
  printf '%s\n' "$configured"
  exit 0
fi

quarkus=false
spring=false
build_tool=
test_command=
bootui_profile=false
profile=
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"

if [[ -f pom.xml ]]; then
  if grep -Eq '<artifactId>quarkus-maven-plugin</artifactId>' pom.xml; then
    quarkus=true
  fi
  if grep -Eq '<artifactId>(spring-boot-maven-plugin|spring-boot-starter-parent|spring-boot-starter[^<]*)</artifactId>' pom.xml; then
    spring=true
  fi
  profile="$(python3 "$script_dir/maven-context.py" "$working_directory" "$kind")"
  [[ -z "$profile" ]] || bootui_profile=true
  if [[ -x ./mvnw ]]; then
    build_tool='./mvnw'
  elif command -v mvn >/dev/null 2>&1; then
    build_tool=mvn
  else
    fail "A Maven build was detected, but neither ./mvnw nor mvn is available."
  fi
  if [[ "$bootui_profile" == true ]]; then
    test_command="$build_tool -P$profile verify"
  else
    test_command="$build_tool verify"
  fi
elif [[ -f build.gradle || -f build.gradle.kts ]]; then
  for file in build.gradle build.gradle.kts settings.gradle settings.gradle.kts gradle/libs.versions.toml; do
    if [[ -f "$file" ]] && grep -Eq '(^|[^[:alnum:]_])io\.quarkus([^[:alnum:]_]|$)' "$file"; then
      quarkus=true
    fi
    if [[ -f "$file" ]] && grep -Eq '(^|[^[:alnum:]_.])org\.springframework\.boot([^[:alnum:]_]|$)' "$file"; then
      spring=true
    fi
  done
  if [[ -x ./gradlew ]]; then
    build_tool='./gradlew --no-daemon'
  elif command -v gradle >/dev/null 2>&1; then
    build_tool=gradle
  else
    fail "A Gradle build was detected, but neither ./gradlew nor gradle is available."
  fi
  test_command="$build_tool check"
else
  fail "Could not detect Maven or Gradle. Set app-command and test-command explicitly."
fi

case "$kind" in
  test)
    printf '%s\n' "$test_command"
    ;;
  app)
    if [[ "$quarkus" == true && "$spring" == true ]]; then
      fail "Both Spring Boot and Quarkus were detected. Set app-command explicitly."
    elif [[ "$spring" == true ]]; then
      if [[ "$build_tool" == './mvnw' || "$build_tool" == mvn ]]; then
        if [[ "$bootui_profile" == true ]]; then
          printf '%s\n' "$build_tool -P$profile spring-boot:run"
        else
          printf '%s\n' "$build_tool spring-boot:run"
        fi
      else
        printf '%s\n' "$build_tool bootRun"
      fi
    elif [[ "$quarkus" == true ]]; then
      if [[ "$build_tool" == './mvnw' || "$build_tool" == mvn ]]; then
        if [[ "$bootui_profile" == true ]]; then
          printf '%s\n' "$build_tool -P$profile quarkus:dev"
        else
          printf '%s\n' "$build_tool quarkus:dev"
        fi
      else
        printf '%s\n' "$build_tool quarkusDev"
      fi
    else
      fail "The build tool was detected, but neither Spring Boot nor Quarkus was found. Set app-command explicitly or use app-command: none for an application started elsewhere."
    fi
    ;;
  *)
    fail "The command kind must be app or test."
    ;;
esac
