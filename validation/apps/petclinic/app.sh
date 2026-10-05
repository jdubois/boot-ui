# Spring PetClinic (tuned): Spring MVC, Thymeleaf, Spring Data JPA on the default in-memory H2, no security.
# The dev profile turns BootUI on (bootui.enabled-profiles) without a BootUI property, as in the first run. The
# agent-attached code-change run starts it with spring-boot:run instead, where its optional spring-boot-devtools
# dependency restarts it in the same JVM when change.patch is compiled.
# shellcheck shell=bash disable=SC2034

APP_READY_PATH=/

app_build() {
  # Checkstyle scans the project directory; the harness keeps its Maven repository outside it, but the skip keeps the
  # build identical to the first run's.
  app_mvn "$SRC" -DskipTests -Dcheckstyle.skip=true package
}

app_start() {
  if $DEV_MODE; then
    start_bg "$RUN_DIR" app "$SRC/mvnw" -f "$SRC/pom.xml" -B -ntp -Dmaven.repo.local="$M2" -Dcheckstyle.skip=true \
      spring-boot:run -Dspring-boot.run.jvmArguments="${JVM_OPTS[*]}" \
      -Dspring-boot.run.arguments="--server.port=$PORT --spring.profiles.active=dev --management.tracing.enabled=false ${APP_ARGS[*]}"
    return
  fi
  local jar
  jar="$(find_jar "$SRC/target" 'spring-petclinic-*.jar')"
  record_jar "$jar"
  start_bg "$RUN_DIR" app java "${JVM_OPTS[@]}" -jar "$jar" --server.port="$PORT" --spring.profiles.active=dev \
    --management.tracing.enabled=false "${APP_ARGS[@]}"
}

# DevTools restarts the application when its compiled classes change.
app_reload() {
  app_mvn "$SRC" -q -Dcheckstyle.skip=true compile
}
