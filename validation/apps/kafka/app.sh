# Kafka microservices (tuned): order-service (Spring MVC, Kafka producer, Kafka Streams join and table), and
# payment-service and stock-service (Kafka listeners, Spring Data JPA on H2), against one KRaft broker in a container.
# Each service runs with the dev profile, which turns BootUI on, and keeps its Kafka Streams state under the run.
# shellcheck shell=bash disable=SC2034

APP_MODULE="$SRC/order-service"
APP_READY_PATH=/orders
KAFKA_PORT="${KAFKA_PORT:-19092}"
PAYMENT_PORT="${KAFKA_PAYMENT_PORT:-$((PORT + 1))}"
STOCK_PORT="${KAFKA_STOCK_PORT:-$((PORT + 2))}"
# Collected one by one: name=base URL.
APP_SERVICES="order-service=http://localhost:$PORT payment-service=http://localhost:$PAYMENT_PORT stock-service=http://localhost:$STOCK_PORT"

app_build() {
  app_mvn "$SRC" -DskipTests package
}

app_start() {
  check_port "$PAYMENT_PORT"
  check_port "$STOCK_PORT"
  printf '%s\n%s\n' "$PAYMENT_PORT" "$STOCK_PORT" >>"$RUN_DIR/ports"
  start_container kafka -p "$KAFKA_PORT:9092" \
    -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
    -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093,INTERNAL://:29092 \
    -e KAFKA_ADVERTISED_LISTENERS="PLAINTEXT://localhost:$KAFKA_PORT,INTERNAL://localhost:29092" \
    -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
    -e KAFKA_INTER_BROKER_LISTENER_NAME=INTERNAL \
    -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,INTERNAL:PLAINTEXT \
    -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
    -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
    -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 \
    apache/kafka:4.3.1
  local tries=0
  until docker exec bootui-validation-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --list \
    >/dev/null 2>&1; do
    tries=$((tries + 1))
    [ "$tries" -lt 90 ] || die "Kafka did not start"
    sleep 2
  done
  local service port jar
  for service in order-service:$PORT payment-service:$PAYMENT_PORT stock-service:$STOCK_PORT; do
    port="${service#*:}"
    service="${service%%:*}"
    jar="$(find_jar "$SRC/$service/target" "$service-*.jar")"
    record_jar "$jar"
    start_bg "$RUN_DIR" "$service" java "${JVM_OPTS[@]}" -jar "$jar" --server.port="$port" \
      --spring.profiles.active=dev --spring.kafka.bootstrap-servers="localhost:$KAFKA_PORT" \
      --spring.kafka.streams.state-dir="$RUN_DIR/kafka-streams-$service" "${APP_ARGS[@]}"
  done
  wait_http "http://localhost:$PAYMENT_PORT/bootui/api/runtime-insights" 300
  wait_http "http://localhost:$STOCK_PORT/bootui/api/runtime-insights" 300
}

app_cleanup() {
  stop_container kafka
}
