#!/bin/sh
# CRaC (Coordinated Restore at Checkpoint) entrypoint for the BootUI sample app.
#
# On the first start no checkpoint exists yet, so the app is launched with
# `spring.context.checkpoint=onRefresh`: after non-lazy singleton initialization,
# before lifecycle start and the context-refreshed event. This is not a fully
# warmed-up application, nor guaranteed cleanup of resources opened during
# initialization. Hikari's allow-pool-suspension setting alone is not proof of cleanup.
#
# The sample app runs with its default "dev" profile (in-memory H2 + in-memory
# cache), reducing external-service dependencies without guaranteeing a successful
# checkpoint. For the external-services variant
# (PostgreSQL + Redis) see bootui-spring-sample-app/README.md.
#
# This CRIU-based recipe uses Linux and the broad
# CHECKPOINT_RESTORE/SYS_PTRACE/SYS_ADMIN/NET_ADMIN capabilities; see the sample README.
set -eu

CRAC_CHECKPOINT_DIR="${CRAC_CHECKPOINT_DIR-/opt/crac/checkpoint}"
APP_JAR="${APP_JAR:-/app/app.jar}"

# JVM tuning flags (see Dockerfile-crac). Applied only when the checkpoint is created below; a
# restore (-XX:CRaCRestoreFrom) does not receive JAVA_OPTS in this recipe.
# Regenerate the checkpoint when these flags change. Empty by default outside the image.
JAVA_OPTS="${JAVA_OPTS:-}"

# Run with the "dev" profile *active* so BootUI turns on (its activation condition
# inspects the active profiles, not spring.profiles.default) and the app uses the
# in-memory H2 database / cache. CRaC reads this when the checkpoint is taken (the
# first start). Already-created Spring configuration may retain that value on
# restore; regenerate the checkpoint to apply a changed startup profile reliably.
SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}"
export SPRING_PROFILES_ACTIVE

invalid_checkpoint_path() {
  echo "[crac] Invalid CRAC_CHECKPOINT_DIR: use an absolute path with letters, digits, '.', '_' or '-', no dot-only components, repeated/trailing separators or symlinks." >&2
  exit 1
}
# Validate before creating or inspecting checkpoint contents. Reject ambiguous paths
# rather than normalizing them into a different destination.
case "$CRAC_CHECKPOINT_DIR" in
  ""|/|*[!a-zA-Z0-9_./-]*|*/|*//*) invalid_checkpoint_path ;;
  /*) ;;
  *) invalid_checkpoint_path ;;
esac
remaining_path="${CRAC_CHECKPOINT_DIR#/}"
checked_path=""
while [ -n "$remaining_path" ]; do
  component="${remaining_path%%/*}"
  case "$component" in
    *[!.]*) ;;
    *) invalid_checkpoint_path ;;
  esac
  checked_path="$checked_path/$component"
  [ ! -L "$checked_path" ] || invalid_checkpoint_path
  case "$remaining_path" in
    */*) remaining_path="${remaining_path#*/}" ;;
    *) remaining_path="" ;;
  esac
done
mkdir -p "$CRAC_CHECKPOINT_DIR"
if [ ! -r "$CRAC_CHECKPOINT_DIR" ] || [ ! -x "$CRAC_CHECKPOINT_DIR" ]; then
  echo "[crac] Checkpoint directory must be readable and searchable; leaving it unchanged." >&2
  exit 1
fi

# inventory.img is only a candidate marker, not an integrity or compatibility
# certificate. A dump can still fail after writing it; a restore attempt can fail.
checkpoint_candidate_exists() {
  [ -f "$CRAC_CHECKPOINT_DIR/inventory.img" ]
}

# CRIU writes the reason a dump failed to its own log file (dump*.log), not to stdout, so
# surface it: without this the container log only shows the JVM's generic
# "Native checkpoint failed".
print_criu_dump_log() {
  for criu_log in "$CRAC_CHECKPOINT_DIR"/dump*.log; do
    [ -f "$criu_log" ] || continue
    echo "[crac] ----- CRIU $criu_log (last 50 lines) -----" >&2
    tail -n 50 "$criu_log" >&2
  done
}

# Do not hide restore failures or fall back to creating another checkpoint.
if checkpoint_candidate_exists; then
  echo "[crac] Attempting restore from checkpoint candidate in $CRAC_CHECKPOINT_DIR"
  exec java -XX:CRaCRestoreFrom="$CRAC_CHECKPOINT_DIR"
fi

# Preserve partial images, logs and user files, including hidden files and symlinks.
for entry in "$CRAC_CHECKPOINT_DIR"/* "$CRAC_CHECKPOINT_DIR"/.[!.]* "$CRAC_CHECKPOINT_DIR"/..?*; do
  if [ -e "$entry" ] || [ -L "$entry" ]; then
    echo "[crac] Incomplete checkpoint directory (no inventory.img): $CRAC_CHECKPOINT_DIR; leaving all contents unchanged. Inspect it and select a new empty directory." >&2
    exit 1
  fi
done

echo "[crac] No checkpoint found; starting the app to create one (spring.context.checkpoint=onRefresh)"
# CRIU kills the process once the checkpoint is written, so a non-zero exit code
# here can occur even with a usable image. A marker permits only an attempted
# restore; neither its presence nor the exit code certifies a successful dump.
set +e
java $JAVA_OPTS -XX:CRaCCheckpointTo="$CRAC_CHECKPOINT_DIR" \
  -Dspring.context.checkpoint=onRefresh \
  -jar "$APP_JAR"
checkpoint_status=$?
set -e

if ! checkpoint_candidate_exists; then
  echo "[crac] Checkpoint creation failed (exit code $checkpoint_status): no inventory.img candidate marker; preserving all files." >&2
  print_criu_dump_log
  echo "[crac] Verify this CRIU recipe's Linux/JDK/CRIU compatibility and CHECKPOINT_RESTORE/SYS_PTRACE/SYS_ADMIN/NET_ADMIN capabilities; these grant broad privileges." >&2
  echo "[crac] Review early-opened resources and the README's external-services note." >&2
  # Never fall through to a restore: the image is incomplete and restoring it would fail
  # with a far more confusing error than the CRIU log printed above.
  [ "$checkpoint_status" -ne 0 ] || checkpoint_status=1
  exit "$checkpoint_status"
fi

echo "[crac] Checkpoint candidate found in $CRAC_CHECKPOINT_DIR (creation exit code $checkpoint_status); attempting restore"
exec java -XX:CRaCRestoreFrom="$CRAC_CHECKPOINT_DIR"
