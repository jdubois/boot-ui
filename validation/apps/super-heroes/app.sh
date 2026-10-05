# Quarkus Super Heroes, rest-villains (tuned): Quarkus REST, Hibernate ORM with Panache on PostgreSQL Dev Services,
# blocking endpoints. align-quarkus-lts.patch aligns it from Quarkus 3.39.5 to the 3.33 LTS BootUI builds on. It runs
# in dev mode, where BootUI is on. The first run also turned OpenTelemetry and JDBC telemetry off to work around a
# build-step cycle BootUI has since fixed (#1204); the rerun does not.
# shellcheck shell=bash disable=SC2034

APP_MODULE="$SRC/rest-villains"
APP_READY_PATH=/q/health/live
APP_READY_TIMEOUT=900

app_build() {
  app_mvn "$APP_MODULE" -DskipTests package
}

app_start() {
  start_bg "$RUN_DIR" app "$APP_MODULE/mvnw" -f "$APP_MODULE/pom.xml" -B -ntp -Dmaven.repo.local="$M2" quarkus:dev \
    -Ddebug=false -Dquarkus.http.port="$PORT" -Dquarkus.analytics.disabled=true -Djvm.args="${JVM_OPTS[*]}"
}

# Quarkus dev mode recompiles and restarts in the same JVM on the next request after a source change.
app_reload() {
  curl -s -o /dev/null --max-time 300 "http://localhost:$PORT$APP_READY_PATH" || true
}
