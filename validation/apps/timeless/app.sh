# Timeless (holdout): a Quarkus personal-finance API. Hibernate ORM with Panache on PostgreSQL (blocking JDBC on
# Quarkus worker threads, Dev Services), SmallRye JWT with @Authenticated and @PermitAll resources, a LangChain4j AI
# service whose getBalance tool reads the database, a transactional outbox relayed every 5 s by the scheduler, and an
# SQS consumer. It runs in dev mode, where BootUI is on, with the application's own dev profile; the chat model is
# validation/stubs/llm-stub.mjs behind the OpenAI provider, and SQS is LocalStack, as the application's own
# docker-compose.yaml sets up. Quinoa's Angular build is skipped: the API is what is measured.
# shellcheck shell=bash disable=SC2034

APP_MODULE="$SRC/timeless-api"
APP_READY_PATH=/q/health/live
APP_READY_TIMEOUT=900
LOCALSTACK_PORT="${TIMELESS_LOCALSTACK_PORT:-14566}"
STUB_PORT="${TIMELESS_LLM_STUB_PORT:-18199}"

app_build() {
  app_mvn "$APP_MODULE" -DskipTests -Dquarkus.quinoa=false package
}

app_start() {
  check_port "$STUB_PORT"
  echo "$STUB_PORT" >>"$RUN_DIR/ports"
  start_bg "$RUN_DIR" llm-stub node "$VALIDATION_HOME/stubs/llm-stub.mjs" --port "$STUB_PORT"
  start_container timeless-localstack -p "$LOCALSTACK_PORT:4566" -e SERVICES=sqs,s3 localstack/localstack:4.2.0
  local tries=0
  until docker exec bootui-validation-timeless-localstack awslocal sqs list-queues >/dev/null 2>&1; do
    tries=$((tries + 1))
    [ "$tries" -lt 150 ] || die "LocalStack did not start"
    sleep 2
  done
  docker exec bootui-validation-timeless-localstack awslocal sqs create-queue --queue-name incoming-message.fifo \
    --attributes FifoQueue=true >/dev/null
  docker exec bootui-validation-timeless-localstack awslocal sqs create-queue --queue-name recognized-message.fifo \
    --attributes FifoQueue=true >/dev/null
  local sqs="http://localhost:$LOCALSTACK_PORT"
  INCOMING_MESSAGE_FIFO_URL="$sqs/000000000000/incoming-message.fifo" \
    RECOGNIZED_MESSAGE_FIFO_URL="$sqs/000000000000/recognized-message.fifo" \
    OPENAI_API_KEY=validation-stub \
    AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-east-1 \
    start_bg "$RUN_DIR" app "$APP_MODULE/mvnw" -f "$APP_MODULE/pom.xml" -B -ntp -Dmaven.repo.local="$M2" quarkus:dev \
    -Ddebug=false \
    -Dquarkus.http.port="$PORT" \
    -Dquarkus.quinoa=false \
    -Dquarkus.analytics.disabled=true \
    -Dquarkus.langchain4j.openai.base-url="http://127.0.0.1:$STUB_PORT/v1/" \
    -Dquarkus.langchain4j.openai.gpt-4-turbo.base-url="http://127.0.0.1:$STUB_PORT/v1/" \
    -Dquarkus.sqs.endpoint-override="$sqs" \
    -Dmp.messaging.incoming.whatsapp-incoming.endpoint-override="$sqs" \
    -Djvm.args="${JVM_OPTS[*]}"
}

app_cleanup() {
  stop_container timeless-localstack
}
