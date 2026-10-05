# Spring PetClinic (tuned): Spring MVC, Thymeleaf, Spring Data JPA on the default in-memory H2, no security.
# The dev profile turns BootUI on (bootui.enabled-profiles) without a BootUI property, as in the first run.
# shellcheck shell=bash disable=SC2034

APP_READY_PATH=/

app_build() {
  # Checkstyle scans the project directory; the harness keeps its Maven repository outside it, but the skip keeps the
  # build identical to the first run's.
  app_mvn "$SRC" -DskipTests -Dcheckstyle.skip=true package
}

app_start() {
  local jar
  jar="$(find_jar "$SRC/target" 'spring-petclinic-*.jar')"
  record_jar "$jar"
  start_bg "$RUN_DIR" app java "${JVM_OPTS[@]}" -jar "$jar" --server.port="$PORT" --spring.profiles.active=dev \
    --management.tracing.enabled=false "${APP_ARGS[@]}"
}
