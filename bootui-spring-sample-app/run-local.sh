#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(dirname "$SCRIPT_DIR")

export MAVEN_OPTS="${MAVEN_OPTS:+$MAVEN_OPTS }-Dmaven.repo.local=$REPOSITORY_ROOT/.m2"

cd "$REPOSITORY_ROOT"

./mvnw -B -ntp -DskipTests -pl bootui-spring-sample-app -am install
exec ./mvnw -B -ntp -Dmaven.test.skip=true -pl bootui-spring-sample-app \
    spring-boot:run -Dspring-boot.run.profiles=dev "$@"
