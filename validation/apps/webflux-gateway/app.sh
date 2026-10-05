# JHipster WebFlux gateway sample (tuned): Spring WebFlux, Spring Cloud Gateway, Spring Security with JWT, Spring Data
# R2DBC on H2, Liquibase. align-cache-constants.patch adds two cache-name constants the sample references but does not
# declare, so it compiles. A static route sends /services/absent/** to a port nothing listens on, to exercise
# downstream failures, as in the first run. Built without the Angular client, run with its dev profile. Consul, the
# gateway's service registry and configuration server, does not run, so Consul discovery and configuration are off,
# as they were in the first run's log ("No discovery service is set up").
# shellcheck shell=bash disable=SC2034

APP_READY_PATH=/management/health
ABSENT_PORT="${GATEWAY_ABSENT_PORT:-18194}"

app_build() {
  app_mvn "$SRC" -DskipTests -Denforcer.skip=true -P-webapp package
}

app_start() {
  local jar
  jar="$(find_jar "$SRC/target" 'jhipster-sample-gateway-*.jar')"
  record_jar "$jar"
  if port_in_use "$ABSENT_PORT"; then die "port $ABSENT_PORT must stay free: it stands for an absent service"; fi
  rm -rf "$SRC/target/h2db"
  start_bg "$RUN_DIR" app java "${JVM_OPTS[@]}" -jar "$jar" --server.port="$PORT" \
    --spring.cloud.consul.enabled=false --spring.cloud.consul.config.enabled=false \
    --spring.cloud.consul.discovery.enabled=false \
    "--spring.cloud.gateway.server.webflux.routes[0].id=absent" \
    "--spring.cloud.gateway.server.webflux.routes[0].uri=http://127.0.0.1:$ABSENT_PORT" \
    "--spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/services/absent/**" \
    "--spring.cloud.gateway.server.webflux.routes[0].filters[0]=StripPrefix=2" \
    "${APP_ARGS[@]}"
}
