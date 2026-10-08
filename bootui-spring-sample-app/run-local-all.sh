#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

# Everything at once: the BootUI Java agent with every sensor, the full docker profile (PostgreSQL with pg_stat_statements, Redis, Kafka,
# and Ollama for Spring AI), and the run-history profile, which keeps Live Activity's history in PostgreSQL and the last
# run's summary in .bootui/run-baseline.bin, so a new run is compared with the previous one even after a full restart.

# The baseline file's directory must exist: BootUI never creates it.
mkdir -p "$SCRIPT_DIR/.bootui"

export BOOTUI_SAMPLE_PROFILES=docker,run-history
# Every sensor the agent ships, the opt-in ones included, through the environment variable Spring binds to
# bootui.agent.sensors. Set BOOTUI_AGENT_SENSORS to choose others. RunLocalAllSensorsTests keeps this list equal to
# AgentSensorSettings.KNOWN_SENSORS.
export BOOTUI_AGENT_SENSORS="${BOOTUI_AGENT_SENSORS:-executors,threads,inventory,code-paths,processes,network,files,environment,blocking,thread-activity,thread-locals,caught-exceptions}"
exec "$SCRIPT_DIR/run-local-agent.sh" "$@"
