#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

# run-local-agent.sh with every sensor the agent ships, the opt-in ones included, through the environment variable
# Quarkus maps to bootui.agent.sensors. Set BOOTUI_AGENT_SENSORS to choose others. RunLocalAllSensorsTests keeps this
# list equal to AgentSensorSettings.KNOWN_SENSORS.
export BOOTUI_AGENT_SENSORS="${BOOTUI_AGENT_SENSORS:-executors,threads,inventory,code-paths,processes,network,files,environment,blocking,thread-activity,thread-locals,resources,caught-exceptions,security-sinks}"
exec "$SCRIPT_DIR/run-local-agent.sh" "$@"
