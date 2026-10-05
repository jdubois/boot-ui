#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(dirname "$SCRIPT_DIR")

AGENT_JAR=
if [ -n "${BOOTUI_AGENT_JAR:-}" ]; then
    if [ ! -f "$BOOTUI_AGENT_JAR" ]; then
        echo "BOOTUI_AGENT_JAR names no file: $BOOTUI_AGENT_JAR" >&2
        exit 1
    fi
    AGENT_JAR=$(CDPATH= cd -- "$(dirname -- "$BOOTUI_AGENT_JAR")" && pwd)/$(basename -- "$BOOTUI_AGENT_JAR")
fi

export MAVEN_OPTS="${MAVEN_OPTS:+$MAVEN_OPTS }-Dmaven.repo.local=$REPOSITORY_ROOT/.m2"

cd "$REPOSITORY_ROOT"

# The sample depends on bootui-agent (test scope), so -am also builds the agent jar.
./mvnw -B -ntp -DskipTests -pl bootui-spring-sample-app -am install

if [ -z "$AGENT_JAR" ]; then
    # The jar plugin records the version it just built; the agent jar is bootui-agent-<version>.jar beside it.
    AGENT_PROPERTIES=bootui-agent/target/maven-archiver/pom.properties
    AGENT_VERSION=$(sed -n 's/^version=//p' "$AGENT_PROPERTIES" 2>/dev/null || true)
    if [ -n "$AGENT_VERSION" ]; then
        AGENT_JAR=$REPOSITORY_ROOT/bootui-agent/target/bootui-agent-$AGENT_VERSION.jar
    fi
    if [ -z "$AGENT_JAR" ] || [ ! -f "$AGENT_JAR" ]; then
        echo "No BootUI agent jar in $REPOSITORY_ROOT/bootui-agent/target." >&2
        echo "Run ./mvnw install -pl bootui-agent -am, or set BOOTUI_AGENT_JAR to the jar's path." >&2
        exit 1
    fi
fi

echo "Attaching the BootUI agent: $AGENT_JAR"

case $AGENT_JAR in
    *,*)
        echo "The agent jar path contains a comma, which spring-boot.run.agents splits: $AGENT_JAR" >&2
        echo "Copy the jar to a path without commas and set BOOTUI_AGENT_JAR to it." >&2
        exit 1
        ;;
esac

# spring-boot.run.agents adds -javaagent to the forked JVM and leaves spring-boot.run.jvmArguments free for callers.
# DevTools restarts stay enabled: each restart claims the agent again in the same slot, and Code Inventory then lists
# the methods an edit changed. BOOTUI_SAMPLE_PROFILES picks other profiles, as run-local-all.sh does.
exec ./mvnw -B -ntp -Dmaven.test.skip=true -pl bootui-spring-sample-app \
    spring-boot:run "-Dspring-boot.run.profiles=${BOOTUI_SAMPLE_PROFILES:-docker-postgresql}" \
    "-Dspring-boot.run.agents=$AGENT_JAR" "$@"
