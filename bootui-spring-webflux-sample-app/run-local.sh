#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(dirname "$SCRIPT_DIR")

export MAVEN_OPTS="${MAVEN_OPTS:+$MAVEN_OPTS }-Dmaven.repo.local=$REPOSITORY_ROOT/.m2"

cd "$REPOSITORY_ROOT"

# The Docker-free dev profile: an in-memory H2 database, on port 8081.
./mvnw -B -ntp -DskipTests -pl bootui-spring-webflux-sample-app -am install
exec ./mvnw -B -ntp -Dmaven.test.skip=true -pl bootui-spring-webflux-sample-app \
    spring-boot:run -Dspring-boot.run.profiles=dev "$@"
