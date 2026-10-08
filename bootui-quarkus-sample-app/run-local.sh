#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(dirname "$SCRIPT_DIR")

export MAVEN_OPTS="${MAVEN_OPTS:+$MAVEN_OPTS }-Dmaven.repo.local=$REPOSITORY_ROOT/.m2"

cd "$REPOSITORY_ROOT"

# Quarkus dev mode on port 8082. Dev Services starts a throwaway PostgreSQL container, so Docker or Podman must be
# running, and augmentation needs JDK 17 to 27 (see the README).
./mvnw -B -ntp -DskipTests -pl bootui-quarkus-sample-app -am install
exec ./mvnw -ntp -f bootui-quarkus-sample-app/pom.xml quarkus:dev "$@"
