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

# The sample does not depend on bootui-agent, so the agent is named in -pl.
./mvnw -B -ntp -DskipTests -pl bootui-quarkus-sample-app,bootui-agent -am install

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
    *[[:space:]]*)
        echo "The agent jar path contains whitespace, which quarkus:dev splits jvm.args on: $AGENT_JAR" >&2
        echo "Copy the jar to a path without whitespace and set BOOTUI_AGENT_JAR to it." >&2
        exit 1
        ;;
esac

# run-local.sh with the BootUI agent attached: the same dev mode, and the agent's default sensors, since this script sets
# no bootui.agent.sensors. jvm.args adds -javaagent to the dev-mode JVM, so a -Djvm.args argument passed to this script
# replaces the agent rather than adding to it. Live reload keeps the agent: each restart claims it again in the same
# slot. BOOTUI_AGENT_SENSORS, which Quarkus maps to bootui.agent.sensors, picks other sensors.
exec ./mvnw -ntp -f bootui-quarkus-sample-app/pom.xml quarkus:dev "-Djvm.args=-javaagent:$AGENT_JAR" "$@"
