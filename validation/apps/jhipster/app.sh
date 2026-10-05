# JHipster sample app (tuned): Spring MVC, Spring Security with JWT, Spring Data JPA, Ehcache, Liquibase, H2 on disk,
# @Async mail through an SMTP server that does not run, so account emails fail behind 201 responses. Built without
# the Angular client (the API is what is measured) and run with its default dev profile, which turns BootUI on.
# JHipster's enforcer accepts JDK 21 to 25 only; the skip lets a newer JDK build it, as in the first run.
# shellcheck shell=bash disable=SC2034

APP_READY_PATH=/management/health

app_build() {
  app_mvn "$SRC" -DskipTests -Denforcer.skip=true -P-webapp package
}

app_start() {
  local jar
  jar="$(find_jar "$SRC/target" 'jhipster-sample-application-*.jar')"
  record_jar "$jar"
  # The H2 database lives under target/, so every run starts from the same Liquibase data.
  rm -rf "$SRC/target/h2db"
  start_bg "$RUN_DIR" app java "${JVM_OPTS[@]}" -jar "$jar" --server.port="$PORT" "${APP_ARGS[@]}"
}
