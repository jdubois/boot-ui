# Spring Modular Monolith bookstore (holdout): Spring MVC, Thymeleaf and htmx, Spring Security form login with URL
# rules and a role hierarchy, Spring Data JPA with Flyway on PostgreSQL, and Spring Modulith events published through
# a JDBC registry and externalized to RabbitMQ. Its compose.yml binds fixed host ports, so the harness runs its own
# containers instead, on configurable ports.
# shellcheck shell=bash disable=SC2034

APP_READY_PATH=/actuator/health
PG_PORT="${BOOKSTORE_PG_PORT:-15432}"
RABBIT_PORT="${BOOKSTORE_RABBIT_PORT:-15672}"

app_build() {
  app_mvn "$SRC" -DskipTests package
}

app_start() {
  start_container bookstore-postgres -p "$PG_PORT:5432" -e POSTGRES_DB=postgres -e POSTGRES_USER=postgres \
    -e POSTGRES_PASSWORD=postgres postgres:18-alpine
  # A tmpfs gives each run a fresh broker, and avoids an Erlang cookie permission failure on some Docker setups.
  start_container bookstore-rabbitmq -p "$RABBIT_PORT:5672" --tmpfs /var/lib/rabbitmq:uid=999,gid=999 -e RABBITMQ_DEFAULT_USER=guest \
    -e RABBITMQ_DEFAULT_PASS=guest rabbitmq:4.3.4-management
  local tries=0
  until docker exec bootui-validation-bookstore-postgres pg_isready -U postgres >/dev/null 2>&1 &&
    docker exec bootui-validation-bookstore-rabbitmq rabbitmq-diagnostics -q ping >/dev/null 2>&1; do
    tries=$((tries + 1))
    [ "$tries" -lt 150 ] || die "PostgreSQL or RabbitMQ did not start"
    sleep 2
  done
  local jar
  jar="$(find_jar "$SRC/target" 'spring-modular-monolith-*.jar')"
  record_jar "$jar"
  # The dev profile turns BootUI on (bootui.enabled-profiles), the way a developer runs the application locally. The
  # OpenTelemetry exports point at a Grafana stack this harness does not run, so they are turned off: tracing stays
  # off, as §2.2 measures, and no exporter logs a connection failure every few seconds.
  start_bg "$RUN_DIR" app java "${JVM_OPTS[@]}" -jar "$jar" \
    --server.port="$PORT" \
    --spring.profiles.active=dev \
    --spring.datasource.url="jdbc:postgresql://localhost:$PG_PORT/postgres" \
    --spring.rabbitmq.port="$RABBIT_PORT" \
    --management.tracing.export.enabled=false \
    --management.tracing.sampling.probability=0.0 \
    --management.otlp.metrics.export.enabled=false \
    --management.opentelemetry.logging.export.otlp.enabled=false \
    "${APP_ARGS[@]}"
}

app_cleanup() {
  stop_container bookstore-postgres
  stop_container bookstore-rabbitmq
}
