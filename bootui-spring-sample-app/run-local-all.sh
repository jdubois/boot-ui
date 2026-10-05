#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

# Everything at once: the BootUI Java agent with every sensor, the full docker profile (PostgreSQL with
# pg_stat_statements, Redis, Kafka, and Ollama for Spring AI), and the run-history profile, which keeps Live Activity's
# history in PostgreSQL and the last run's summary in .bootui/run-baseline.bin, so a new run is compared with the
# previous one even after a full restart.

# The baseline file's directory must exist: BootUI never creates it.
mkdir -p "$SCRIPT_DIR/.bootui"

# Every agent sensor, the opt-in ones included. Listing sensors replaces the defaults, so the sensors not shipped yet
# (thread-activity, thread-locals, resources, blocking, security-sinks) are listed too: until they ship, each only logs
# a warning at start-up, and each starts recording as soon as it does. Spring binds BOOTUI_AGENT_SENSORS to
# bootui.agent.sensors; set it before running this script to choose others.
export BOOTUI_AGENT_SENSORS="${BOOTUI_AGENT_SENSORS:-executors,threads,inventory,code-paths,processes,network,files,environment,thread-activity,thread-locals,resources,blocking,security-sinks}"

export BOOTUI_SAMPLE_PROFILES=docker,run-history
exec "$SCRIPT_DIR/run-local-agent.sh" "$@"
