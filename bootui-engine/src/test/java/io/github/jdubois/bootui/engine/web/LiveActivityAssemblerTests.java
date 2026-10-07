package io.github.jdubois.bootui.engine.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.EmailMessageDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.core.dto.PageMetadata;
import io.github.jdubois.bootui.core.dto.RestClientTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.engine.cache.CacheActivityEvent;
import io.github.jdubois.bootui.engine.cache.CacheActivityOperation;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceVocabulary;
import io.github.jdubois.bootui.engine.jms.JmsActivityRecorder;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityEntries;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder.CapturedMessage;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder.Direction;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTaskRunStore;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceGrouping;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies the trace-id correlation both Quarkus and Spring WebFlux rely on for Live Activity: when the
 * captured signals share a distributed-trace id, the assembler nests the SQL/REST-client/exception/security
 * entries under the owning REQUEST entry by setting their {@code parentId} (a uniquely-matched security
 * event additionally stamps {@code securedPrincipal} on that request); when no shared trace id is present
 * the feed stays flat; and an ambiguous trace id shared by more than one request never nests a child under
 * the wrong one nor stamps a principal. Also verifies the {@code SCHEDULED} fallback tier: an exception
 * with no matching request trace id nests under a captured {@code @Scheduled} execution instead, via a
 * serving-thread + time-window join, but only when the request/trace-id tier does not already claim it.
 */
class LiveActivityAssemblerTests {

    private final LiveActivityAssembler assembler = new LiveActivityAssembler();

    @Test
    void nestsSqlAndExceptionUnderRequestSharingTraceId() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        List<SqlTraceEntryDto> sql = List.of(sql(10, "select 1", "trace-a", 1_010L));
        List<ExceptionGroupDto> exceptions = List.of(exception("g-1", "trace-a", 1_020L));

        LiveActivityReport report = assembler.report(
                requests,
                sql,
                true,
                null,
                exceptions,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        ActivityEntryDto request = entry(report, "req-1");
        ActivityEntryDto sqlEntry = entry(report, "sql-10");
        ActivityEntryDto exceptionEntry = entry(report, "exc-g-1");

        assertThat(request.parentId()).isNull();
        assertThat(request.profileable()).isFalse();
        assertThat(sqlEntry.parentId()).isEqualTo("req-1");
        assertThat(sqlEntry.correlationId()).isEqualTo("trace-a");
        assertThat(exceptionEntry.parentId()).isEqualTo("req-1");
        assertThat(exceptionEntry.exceptionGroupId())
                .as("the group id get_exception_detail takes, unlike the entry's own id")
                .isEqualTo("g-1");
        assertThat(request.exceptionGroupId()).isNull();
        assertThat(exceptionEntry.correlationId()).isEqualTo("trace-a");
    }

    @Test
    void nestsSqlUnderTheRequestWhoseRequestIdItCarriesWithoutTracing() {
        HttpExchangesReport requests = requests(stamped("0123456789abcdef", null), stamped("fedcba9876543210", null));
        List<SqlTraceEntryDto> sql =
                List.of(stampedSql(10, "0123456789abcdef", null), stampedSql(11, "fedcba9876543210", null));

        LiveActivityReport report = reportOf(requests, sql);

        assertThat(entry(report, "sql-10").parentId()).isEqualTo("0123456789abcdef");
        assertThat(entry(report, "sql-11").parentId()).isEqualTo("fedcba9876543210");
    }

    @Test
    void aRequestIdNestsExactlyWhereASharedTraceIdCannot() {
        HttpExchangesReport requests =
                requests(stamped("0123456789abcdef", "trace-shared"), stamped("fedcba9876543210", "trace-shared"));
        List<SqlTraceEntryDto> sql = List.of(
                stampedSql(10, "fedcba9876543210", "trace-shared"), sql(11, "select 1", "trace-shared", 1_010L));

        LiveActivityReport report = reportOf(requests, sql);

        assertThat(entry(report, "sql-10").parentId()).isEqualTo("fedcba9876543210");
        assertThat(entry(report, "sql-11").parentId())
                .as("an ambiguous trace id still nests nothing")
                .isNull();
    }

    @Test
    void nestsEveryChildTypeUnderTheRequestWhoseRequestIdItCarriesWithoutTracing() {
        String first = "0123456789abcdef";
        String second = "fedcba9876543210";
        HttpExchangesReport requests = requests(stamped(first, null), stamped(second, null));
        List<SqlTraceEntryDto> sql = new ArrayList<>();
        for (int i = 0; i < SqlTraceGrouping.DEFAULT_N_PLUS_ONE_THRESHOLD; i++) {
            sql.add(stampedSql(20 + i, second, null));
        }
        SecurityLogEventDto login = new SecurityLogEventDto(
                Instant.ofEpochMilli(1_005L).toString(), "alice", "AUTHENTICATION_SUCCESS", List.of(), null, first);
        CacheActivityEvent cacheHit = new CacheActivityEvent(
                7L, 1_006L, "cacheManager", "orders", CacheActivityOperation.HIT, "h", null, "worker-9", second);
        EmailMessageDto mail = new EmailMessageDto(
                "email-3",
                1_007L,
                "noreply@example.com",
                List.of("user@example.com"),
                List.of(),
                List.of(),
                "Welcome",
                "Hello",
                null,
                List.of(),
                true,
                null,
                "worker-9",
                first);
        RestClientTraceEntryDto call = new RestClientTraceEntryDto(
                4L,
                1_008L,
                "GET",
                "https://api.example.com/rates",
                "api.example.com",
                "/rates",
                200,
                3L,
                true,
                null,
                false,
                "RestClient",
                Map.of(),
                null,
                "worker-9",
                null,
                second);
        FaultToleranceEventRecorder.CapturedEvent retry = new FaultToleranceEventRecorder.CapturedEvent(
                5L, 1_009L, "rates", "RETRY", "Resilience4j", "rates", "RETRY", 2, 3L, null, null, null, first);

        LiveActivityReport report = assembler.report(
                requests,
                sql,
                true,
                null,
                List.of(),
                List.of(login),
                true,
                List.of(cacheHit),
                true,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(mail),
                true,
                List.of(call),
                true,
                List.of(retry),
                true);

        assertThat(onlyEntryOfType(report, "SECURITY").parentId()).isEqualTo(first);
        assertThat(entry(report, "cache-7").parentId()).isEqualTo(second);
        assertThat(onlyEntryOfType(report, "MAIL").parentId()).isEqualTo(first);
        assertThat(entry(report, "rest-4").parentId()).isEqualTo(second);
        assertThat(entry(report, "fault-tolerance-5").parentId()).isEqualTo(first);
        assertThat(entry(report, first).securedPrincipal())
                .as("a security event's principal marks the request it carries the id of")
                .isEqualTo("alice");
        assertThat(entry(report, second).sqlNPlusOneSuspected())
                .as("repeated SQL nested by request id flags its request")
                .isTrue();
        assertThat(entry(report, first).sqlNPlusOneSuspected()).isFalse();
    }

    @Test
    void aRequestIsProfileableWithoutHeuristicsWhenItCarriesATraceIdOrARequestId() {
        HttpExchangesReport requests = requests(
                stamped("0123456789abcdef", null),
                request("req-traced", "/orders", "trace-a", 1_000L),
                request("req-bare", "/orders", null, 1_000L));
        LiveActivityReport report = LiveActivityAssembler.withExactProfiles(
                reportOf(requests, List.of(stampedSql(10, "0123456789abcdef", null))), requests.exchanges());

        assertThat(entry(report, "0123456789abcdef").profileable()).isTrue();
        assertThat(entry(report, "req-traced").profileable()).isTrue();
        assertThat(entry(report, "req-bare").profileable())
                .as("a request with neither id has nothing exact to profile by")
                .isFalse();
        assertThat(entry(report, "sql-10").profileable()).isFalse();
    }

    @Test
    void nestsAnExceptionGroupUnderTheRequestItsLastOccurrenceCarriesTheIdOf() {
        HttpExchangesReport requests = requests(stamped("0123456789abcdef", null), stamped("fedcba9876543210", null));
        ExceptionGroupDto base = exception("g-1", null, 1_020L);
        ExceptionGroupDto group = new ExceptionGroupDto(
                base.id(),
                base.exceptionClassName(),
                base.message(),
                base.count(),
                base.firstSeen(),
                base.lastSeen(),
                base.location(),
                base.applicationException(),
                base.lastThread(),
                base.lastRequestMethod(),
                base.lastRequestPath(),
                base.lastHandler(),
                base.lastSource(),
                null,
                base.status(),
                base.regressionCount(),
                null,
                "fedcba9876543210");

        LiveActivityReport report = assembler.report(
                requests,
                List.of(),
                true,
                null,
                List.of(group),
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        assertThat(onlyEntryOfType(report, "EXCEPTION").parentId()).isEqualTo("fedcba9876543210");
    }

    @Test
    void nestsSqlRestCallsAndExceptionsUnderTheScheduledRunWhoseExecutionIdTheyCarry() {
        String execution = "00112233aabbccdd";
        ScheduledTaskRunStore store = new ScheduledTaskRunStore(10);
        store.record("com.example.Jobs.sync", 1_000L, 50L, true, null, null, "scheduling-1", execution);
        store.record("com.example.Jobs.sync", 2_000L, 50L, true, null, null, "scheduling-1", "ffeeddccbbaa9988");
        SqlTraceEntryDto base = sql(10, "select 1", null, 1_010L);
        SqlTraceEntryDto statement = new SqlTraceEntryDto(
                base.id(),
                base.timestamp(),
                base.sql(),
                base.statementType(),
                base.category(),
                base.durationMicros(),
                base.durationMillis(),
                base.success(),
                base.errorMessage(),
                base.affectedRows(),
                base.batchSize(),
                base.connectionId(),
                "scheduling-1",
                base.slow(),
                base.parameters(),
                null,
                null,
                null,
                execution);
        RestClientTraceEntryDto call = new RestClientTraceEntryDto(
                4L,
                1_020L,
                "GET",
                "https://api.example.com/rates",
                "api.example.com",
                "/rates",
                200,
                3L,
                true,
                null,
                false,
                "RestClient",
                Map.of(),
                null,
                "scheduling-1",
                null,
                null,
                execution);
        ExceptionGroupDto seed = exception("g-1", null, 1_030L);
        ExceptionGroupDto failure = new ExceptionGroupDto(
                seed.id(),
                seed.exceptionClassName(),
                seed.message(),
                seed.count(),
                seed.firstSeen(),
                seed.lastSeen(),
                seed.location(),
                seed.applicationException(),
                "another-thread",
                null,
                null,
                null,
                "log",
                null,
                seed.status(),
                seed.regressionCount(),
                null,
                null,
                execution);
        String runEntry = "sched-"
                + store.runs().stream()
                        .filter(run -> execution.equals(run.executionId()))
                        .findFirst()
                        .orElseThrow()
                        .sequence();

        LiveActivityReport report = assembler.report(
                requests(),
                List.of(statement),
                true,
                null,
                List.of(failure),
                List.of(),
                false,
                List.of(),
                false,
                store.runs(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(call),
                true);

        assertThat(entry(report, "sql-10").parentId()).isEqualTo(runEntry);
        assertThat(entry(report, "rest-4").parentId()).isEqualTo(runEntry);
        assertThat(onlyEntryOfType(report, "EXCEPTION").parentId())
                .as("an exception thrown on another thread still nests by execution id")
                .isEqualTo(runEntry);
    }

    @Test
    void aConsumedMessageAnchorsItsListenersSignalsAndAMessageNestsUnderItsSender() {
        KafkaActivityRecorder kafka = new KafkaActivityRecorder(true, false, 10, 16);
        try (BootUiCorrelation.Scope ignored =
                BootUiCorrelation.open(CorrelationContext.forRequest("0123456789abcdef"))) {
            kafka.recordProduce("orders", 0, null, null, true, null);
        }
        try (BootUiCorrelation.Scope ignored =
                BootUiCorrelation.open(CorrelationContext.forExecution("00112233aabbccdd"))) {
            kafka.recordConsume("orders", 0, 1L, null, 5L, true, null, "group", "factory");
            kafka.recordProduce("shipments", 0, null, null, true, null);
        }
        List<KafkaActivityRecorder.CapturedMessage> messages = kafka.recent();
        String produce = entryIdOf(messages, KafkaActivityRecorder.Direction.PRODUCE, "orders");
        String consume = entryIdOf(messages, KafkaActivityRecorder.Direction.CONSUME, "orders");
        String forward = entryIdOf(messages, KafkaActivityRecorder.Direction.PRODUCE, "shipments");
        SqlTraceEntryDto base = sql(10, "insert into shipments", null, 1_010L);
        SqlTraceEntryDto listenerSql = new SqlTraceEntryDto(
                base.id(),
                base.timestamp(),
                base.sql(),
                base.statementType(),
                base.category(),
                base.durationMicros(),
                base.durationMillis(),
                base.success(),
                base.errorMessage(),
                base.affectedRows(),
                base.batchSize(),
                base.connectionId(),
                "consumer-1",
                base.slow(),
                base.parameters(),
                null,
                null,
                null,
                "00112233aabbccdd");

        LiveActivityReport report = assembler.report(
                requests(stamped("0123456789abcdef", null)),
                List.of(listenerSql),
                true,
                null,
                List.of(),
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                messages,
                true,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        assertThat(entry(report, produce).parentId()).isEqualTo("0123456789abcdef");
        assertThat(entry(report, consume).parentId())
                .as("a consumed message is an anchor")
                .isNull();
        assertThat(entry(report, "sql-10").parentId()).isEqualTo(consume);
        assertThat(entry(report, forward).parentId()).isEqualTo(consume);
    }

    private static String entryIdOf(
            List<KafkaActivityRecorder.CapturedMessage> messages,
            KafkaActivityRecorder.Direction direction,
            String topic) {
        return KafkaActivityEntries.entryId(messages.stream()
                .filter(message -> message.direction() == direction && topic.equals(message.topic()))
                .findFirst()
                .orElseThrow());
    }

    @Test
    void anUnknownRequestIdFallsBackToTheTraceId() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        List<SqlTraceEntryDto> sql = List.of(stampedSql(10, "0000000000000000", "trace-a"));

        LiveActivityReport report = reportOf(requests, sql);

        assertThat(entry(report, "sql-10").parentId()).isEqualTo("req-1");
    }

    private LiveActivityReport reportOf(HttpExchangesReport requests, List<SqlTraceEntryDto> sql) {
        return assembler.report(
                requests, sql, true, null, List.of(), List.of(), false, List.of(), false, List.of(), "UP", 0, List.of(),
                false, List.of(), false, List.of(), false, List.of(), false);
    }

    private static HttpExchangeDto stamped(String requestId, String traceId) {
        HttpExchangeDto base = request(requestId, "/orders", traceId, 1_000L);
        return new HttpExchangeDto(
                base.id(),
                base.timestamp(),
                base.method(),
                base.path(),
                base.query(),
                base.uri(),
                base.status(),
                base.statusFamily(),
                base.durationMs(),
                base.responseSizeBytes(),
                base.remoteAddress(),
                base.principal(),
                base.sessionId(),
                base.traceId(),
                base.requestHeaders(),
                base.responseHeaders(),
                null,
                null,
                requestId);
    }

    private static SqlTraceEntryDto stampedSql(long id, String requestId, String traceId) {
        SqlTraceEntryDto base = sql(id, "select 1", traceId, 1_010L);
        return new SqlTraceEntryDto(
                base.id(),
                base.timestamp(),
                base.sql(),
                base.statementType(),
                base.category(),
                base.durationMicros(),
                base.durationMillis(),
                base.success(),
                base.errorMessage(),
                base.affectedRows(),
                base.batchSize(),
                base.connectionId(),
                base.thread(),
                base.slow(),
                base.parameters(),
                base.traceId(),
                base.callSite(),
                requestId);
    }

    @Test
    void labelsTheSlowestRequestKpiWithItsResolvedRoute() {
        HttpExchangesReport requests = requests(
                RequestLatencyKpisTests.exchange(
                        "req-1", 1, "GET", "/api/orders/42", 240L, "/api/orders/{id}", "DECLARED_MAPPING"),
                RequestLatencyKpisTests.exchange("req-2", 2, "GET", "/api/health", 4L, "/api/health", "MASKED_PATH"),
                RequestLatencyKpisTests.exchange("req-3", 3, "GET", "/api/health", 6L, "/api/health", "MASKED_PATH"));

        LiveActivityReport report = assembler.report(
                requests, List.of(), false, null, List.of(), List.of(), false, List.of(), false, List.of(), "UP", 0,
                List.of(), false, List.of(), false, List.of(), false, List.of(), false);

        assertThat(report.kpis().latencySampleCount()).isEqualTo(3);
        assertThat(report.kpis().p50LatencyMs()).isEqualTo(6L);
        assertThat(report.kpis().p95LatencyMs()).isEqualTo(240L);
        assertThat(report.kpis().slowestEndpoint()).isEqualTo("/api/orders/42");
        assertThat(report.kpis().slowestEndpointMs()).isEqualTo(240L);
        assertThat(report.kpis().slowestEndpointRoute()).isEqualTo("/api/orders/{id}");
        assertThat(report.kpis().slowestEndpointRouteId()).isEqualTo("GET /api/orders/{id}");
        assertThat(report.kpis().slowestEndpointRouteSource()).isEqualTo("DECLARED_MAPPING");
    }

    @Test
    void leavesSignalsFlatWhenNoTraceIdIsStamped() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", null, 1_000L));
        List<SqlTraceEntryDto> sql = List.of(sql(10, "select 1", null, 1_010L));
        List<ExceptionGroupDto> exceptions = List.of(exception("g-1", null, 1_020L));

        LiveActivityReport report = assembler.report(
                requests,
                sql,
                true,
                null,
                exceptions,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        assertThat(entry(report, "sql-10").parentId()).isNull();
        assertThat(entry(report, "exc-g-1").parentId()).isNull();
    }

    @Test
    void leavesSignalsFlatWhenNoRequestSharesTheirTraceId() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        List<SqlTraceEntryDto> sql = List.of(sql(10, "select 1", "trace-orphan", 1_010L));
        List<ExceptionGroupDto> exceptions = List.of(exception("g-1", "trace-orphan", 1_020L));

        LiveActivityReport report = assembler.report(
                requests,
                sql,
                true,
                null,
                exceptions,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        assertThat(entry(report, "sql-10").parentId()).isNull();
        assertThat(entry(report, "exc-g-1").parentId()).isNull();
    }

    @Test
    void doesNotNestWhenTwoRequestsShareTheSameTraceId() {
        HttpExchangesReport requests = requests(
                request("req-1", "/orders", "trace-a", 1_000L), request("req-2", "/orders", "trace-a", 2_000L));
        List<SqlTraceEntryDto> sql = List.of(sql(10, "select 1", "trace-a", 1_010L));

        LiveActivityReport report = assembler.report(
                requests,
                sql,
                true,
                null,
                exceptions(),
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        assertThat(entry(report, "sql-10").parentId()).isNull();
    }

    @Test
    void nestsOnlyTheChildrenWhoseTraceIdMatchesAUniqueRequest() {
        HttpExchangesReport requests =
                requests(request("req-1", "/orders", "trace-a", 1_000L), request("req-2", "/items", "trace-b", 2_000L));
        List<SqlTraceEntryDto> sql =
                List.of(sql(10, "select 1", "trace-a", 1_010L), sql(11, "select 2", "trace-b", 2_010L));

        LiveActivityReport report = assembler.report(
                requests,
                sql,
                true,
                null,
                exceptions(),
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        assertThat(entry(report, "sql-10").parentId()).isEqualTo("req-1");
        assertThat(entry(report, "sql-11").parentId()).isEqualTo("req-2");
    }

    @Test
    void nestsMailUnderRequestSharingTraceId() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        List<EmailMessageDto> emails = List.of(email("email-1", "trace-a", "mail-thread", 1_010L));

        LiveActivityReport report = assembler.report(
                requests,
                List.of(),
                false,
                null,
                exceptions(),
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                emails,
                true,
                List.of(),
                false);

        ActivityEntryDto mailEntry = entry(report, "email-1");
        assertThat(mailEntry.parentId()).isEqualTo("req-1");
        assertThat(mailEntry.correlationId()).isEqualTo("trace-a");
        assertThat(mailEntry.thread()).isEqualTo("mail-thread");
        assertThat(report.sources()).contains("email");
    }

    @Test
    void leavesMailTopLevelWhenNoRequestSharesItsTraceId() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        List<EmailMessageDto> emails = List.of(email("email-1", "trace-orphan", "mail-thread", 1_010L));

        LiveActivityReport report = assembler.report(
                requests,
                List.of(),
                false,
                null,
                exceptions(),
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                emails,
                true,
                List.of(),
                false);

        assertThat(entry(report, "email-1").parentId()).isNull();
    }

    @Test
    void nestsSecurityEntryUnderRequestSharingTraceIdAndSetsSecuredPrincipal() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        List<SecurityLogEventDto> security = List.of(security("alice", "AUTHENTICATION_SUCCESS", "trace-a", 1_010L));

        LiveActivityReport report = assembler.report(
                requests,
                List.of(),
                false,
                null,
                exceptions(),
                security,
                true,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        ActivityEntryDto securityEntry = securityEntry(report);
        assertThat(securityEntry.type()).isEqualTo("SECURITY");
        assertThat(securityEntry.severity()).isEqualTo("OK");
        assertThat(securityEntry.summary()).isEqualTo("AUTHENTICATION_SUCCESS · alice");
        assertThat(securityEntry.correlationId()).isEqualTo("trace-a");
        assertThat(securityEntry.parentId()).isEqualTo("req-1");
        assertThat(entry(report, "req-1").securedPrincipal()).isEqualTo("alice");
        assertThat(report.sources()).contains("security");
    }

    @Test
    void mapsFailureSecurityEventTypeToWarnSeverity() {
        HttpExchangesReport requests = requests(request("req-1", "/login", "trace-a", 1_000L));
        List<SecurityLogEventDto> security = List.of(security("bob", "AUTHENTICATION_FAILURE", "trace-a", 1_010L));

        LiveActivityReport report = assembler.report(
                requests,
                List.of(),
                false,
                null,
                exceptions(),
                security,
                true,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        assertThat(securityEntry(report).severity()).isEqualTo("WARN");
    }

    @Test
    void doesNotNestAmbiguousSecurityEventAndDoesNotStampSecuredPrincipal() {
        HttpExchangesReport requests =
                requests(request("req-1", "/orders", "trace-a", 1_000L), request("req-2", "/items", "trace-a", 2_000L));
        List<SecurityLogEventDto> security = List.of(security("alice", "AUTHENTICATION_SUCCESS", "trace-a", 1_010L));

        LiveActivityReport report = assembler.report(
                requests,
                List.of(),
                false,
                null,
                exceptions(),
                security,
                true,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        assertThat(securityEntry(report).parentId()).isNull();
        assertThat(entry(report, "req-1").securedPrincipal()).isNull();
        assertThat(entry(report, "req-2").securedPrincipal()).isNull();
    }

    @Test
    void leavesSecurityEntryFlatWhenNoTraceIdIsStamped() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", null, 1_000L));
        List<SecurityLogEventDto> security = List.of(security("alice", "AUTHENTICATION_SUCCESS", null, 1_010L));

        LiveActivityReport report = assembler.report(
                requests,
                List.of(),
                false,
                null,
                exceptions(),
                security,
                true,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        ActivityEntryDto securityEntry = securityEntry(report);
        assertThat(securityEntry.parentId()).isNull();
        assertThat(securityEntry.correlationId()).isNull();
        assertThat(entry(report, "req-1").securedPrincipal()).isNull();
    }

    @Test
    void prefersTheRequestsOwnPrincipalOverACorrelatedSecurityEvent() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", "bob", 1_000L));
        List<SecurityLogEventDto> security = List.of(security("alice", "AUTHENTICATION_SUCCESS", "trace-a", 1_010L));

        LiveActivityReport report = assembler.report(
                requests,
                List.of(),
                false,
                null,
                exceptions(),
                security,
                true,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        assertThat(entry(report, "req-1").securedPrincipal()).isEqualTo("bob");
    }

    @Test
    void omitsSecuritySourceWhenUnavailable() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));

        LiveActivityReport report = assembler.report(
                requests,
                List.of(),
                false,
                null,
                exceptions(),
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        assertThat(report.sources()).doesNotContain("security");
    }

    @Test
    void nestsCacheEntryUnderRequestSharingTraceIdAndComputesHitRatio() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        List<CacheActivityEvent> cache = List.of(
                cache(1, CacheActivityOperation.HIT, "trace-a", 1_010L),
                cache(2, CacheActivityOperation.MISS, "trace-a", 1_020L),
                cache(3, CacheActivityOperation.HIT, "trace-a", 1_030L));

        LiveActivityReport report = assembler.report(
                requests,
                List.of(),
                false,
                null,
                exceptions(),
                List.of(),
                false,
                cache,
                true,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        ActivityEntryDto hit = entry(report, "cache-1");
        assertThat(hit.type()).isEqualTo("CACHE");
        assertThat(hit.severity()).isEqualTo("OK");
        assertThat(hit.parentId()).isEqualTo("req-1");
        assertThat(hit.correlationId()).isEqualTo("trace-a");

        ActivityEntryDto miss = entry(report, "cache-2");
        assertThat(miss.severity()).isEqualTo("WARN");

        assertThat(report.sources()).contains("cache");
        assertThat(report.kpis().cacheHitRatioPercent()).isEqualTo(66.67);
    }

    @Test
    void leavesCacheHitRatioNullWhenCacheUnavailable() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        List<CacheActivityEvent> cache = List.of(cache(1, CacheActivityOperation.HIT, "trace-a", 1_010L));

        LiveActivityReport report = assembler.report(
                requests,
                List.of(),
                false,
                null,
                exceptions(),
                List.of(),
                false,
                cache,
                false,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        assertThat(report.kpis().cacheHitRatioPercent()).isNull();
        assertThat(report.sources()).doesNotContain("cache");
        assertThat(report.entries().stream().noneMatch(e -> "CACHE".equals(e.type())))
                .isTrue();
    }

    @Test
    void typeCountsReflectTheFullFeedEvenWhenTheLimitTruncatesVisibleCacheEntries() {
        HttpExchangesReport requests = requests(
                request("req-1", "/orders", "trace-a", 1_000L),
                request("req-2", "/orders", "trace-b", 1_001L),
                request("req-3", "/orders", "trace-c", 1_002L));
        List<CacheActivityEvent> cache = List.of(cache(1, CacheActivityOperation.HIT, null, 1_003L));

        LiveActivityReport report = assembler.report(
                requests,
                List.of(),
                false,
                null,
                exceptions(),
                List.of(),
                false,
                cache,
                true,
                List.of(),
                "UP",
                2,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        // Only 2 entries are returned (limit=2), but the counts must reflect all 4 captured entries so the
        // dashboard's per-type totals never understate what was actually captured.
        assertThat(report.entries()).hasSize(2);
        assertThat(report.typeCounts().get("REQUEST")).isEqualTo(3);
        assertThat(report.typeCounts().get("CACHE")).isEqualTo(1);
    }

    @Test
    void typeCountsReflectTheFullFeedEvenWhenTheLimitTruncatesVisibleMailEntries() {
        HttpExchangesReport requests = requests(
                request("req-1", "/orders", "trace-a", 1_000L),
                request("req-2", "/orders", "trace-b", 1_001L),
                request("req-3", "/orders", "trace-c", 1_002L));
        List<EmailMessageDto> emails = List.of(email("email-1", null, "mail-thread", 1_003L));

        LiveActivityReport report = assembler.report(
                requests,
                List.of(),
                false,
                null,
                exceptions(),
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                "UP",
                2,
                List.of(),
                false,
                List.of(),
                false,
                emails,
                true,
                List.of(),
                false);

        // Only 2 entries are returned (limit=2), but the counts must reflect all 4 captured entries so the
        // dashboard's per-type totals never understate what was actually captured.
        assertThat(report.entries()).hasSize(2);
        assertThat(report.typeCounts().get("REQUEST")).isEqualTo(3);
        assertThat(report.typeCounts().get("MAIL")).isEqualTo(1);
    }

    @Test
    void flagsRequestAsSqlNPlusOneSuspectedWhenItsCorrelatedSqlHitsTheThreshold() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        List<SqlTraceEntryDto> sql = List.of(
                sql(1, "select * from item where order_id = ?", "trace-a", 1_001L),
                sql(2, "select * from item where order_id = ?", "trace-a", 1_002L),
                sql(3, "select * from item where order_id = ?", "trace-a", 1_003L),
                sql(4, "select * from item where order_id = ?", "trace-a", 1_004L),
                sql(5, "select * from item where order_id = ?", "trace-a", 1_005L));

        LiveActivityReport report = assembler.report(
                requests,
                sql,
                true,
                null,
                exceptions(),
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        assertThat(entry(report, "req-1").sqlNPlusOneSuspected()).isTrue();
    }

    @Test
    void doesNotFlagSqlNPlusOneSuspectedBelowTheThreshold() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        List<SqlTraceEntryDto> sql = List.of(
                sql(1, "select * from item where order_id = ?", "trace-a", 1_001L),
                sql(2, "select * from item where order_id = ?", "trace-a", 1_002L),
                sql(3, "select * from item where order_id = ?", "trace-a", 1_003L),
                sql(4, "select * from item where order_id = ?", "trace-a", 1_004L));

        LiveActivityReport report = assembler.report(
                requests,
                sql,
                true,
                null,
                exceptions(),
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        assertThat(entry(report, "req-1").sqlNPlusOneSuspected()).isFalse();
    }

    @Test
    void nonRequestEntriesAreNeverFlaggedAsSqlNPlusOneSuspected() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        List<SqlTraceEntryDto> sql = List.of(
                sql(1, "select * from item where order_id = ?", "trace-a", 1_001L),
                sql(2, "select * from item where order_id = ?", "trace-a", 1_002L),
                sql(3, "select * from item where order_id = ?", "trace-a", 1_003L),
                sql(4, "select * from item where order_id = ?", "trace-a", 1_004L),
                sql(5, "select * from item where order_id = ?", "trace-a", 1_005L));

        LiveActivityReport report = assembler.report(
                requests,
                sql,
                true,
                null,
                exceptions(),
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        assertThat(entry(report, "sql-1").sqlNPlusOneSuspected()).isFalse();
    }

    @Test
    void mergesKafkaBeforeLimitingAndCountsItPreLimit() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        List<SqlTraceEntryDto> sql = List.of(sql(10, "select 1", "trace-a", 2_000L));
        List<CapturedMessage> kafka = List.of(
                new CapturedMessage(
                        1L,
                        3_000L,
                        Direction.PRODUCE,
                        "orders",
                        0,
                        null,
                        hashedKey("k1"),
                        null,
                        true,
                        null,
                        null,
                        null),
                new CapturedMessage(
                        2L,
                        500L,
                        Direction.CONSUME,
                        "orders",
                        1,
                        42L,
                        hashedKey("k2"),
                        5L,
                        true,
                        null,
                        "group-a",
                        "listener"));

        LiveActivityReport report = assembler.report(
                requests,
                sql,
                true,
                null,
                exceptions(),
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                "UP",
                2,
                kafka,
                true,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        assertThat(report.entries()).extracting(ActivityEntryDto::id).containsExactly("kafka-1", "sql-10");
        assertThat(report.typeCounts())
                .containsEntry("MESSAGING", 2)
                .containsEntry("SQL", 1)
                .containsEntry("REQUEST", 1);
        assertThat(report.sources()).contains("kafka");
    }

    @Test
    void renderedKafkaEntryNeverExposesTheRawKey() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 10, 16);
        recorder.recordProduce("orders", 0, "super-secret-key", null, true, null);

        LiveActivityReport report = assembler.report(
                requests(),
                List.of(),
                false,
                null,
                exceptions(),
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                recorder.recent(),
                true,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        assertThat(entry(report, "kafka-1").detail())
                .contains(hashedKey("super-secret-key"))
                .doesNotContain("super-secret-key");
    }

    @Test
    void mergesJmsMessagesThroughTheSpringCapableOverload() {
        JmsActivityRecorder recorder = new JmsActivityRecorder(true, true, 10, 16);
        recorder.recordConsume("orders", "ID:secret", 4L, true, null, null, "listener");

        LiveActivityReport report = assembler.report(
                requests(),
                List.of(),
                false,
                null,
                exceptions(),
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                recorder.recent(),
                true,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        assertThat(report.sources()).contains("jms");
        assertThat(entry(report, "jms-1")).satisfies(message -> {
            assertThat(message.summary()).isEqualTo("← orders");
            assertThat(message.detail())
                    .contains("messageId=")
                    .doesNotContain("ID:secret")
                    .doesNotContain("key=");
            assertThat(message.durationMs()).isEqualTo(4L);
        });
    }

    @Test
    void nestsExceptionUnderScheduledTaskByThreadAndWindowWhenNoRequestClaimsIt() {
        HttpExchangesReport requests = requests();
        List<ScheduledTaskRunStore.Run> scheduled = List.of(
                new ScheduledTaskRunStore.Run(1L, "com.example.Job#run", 1_000L, 30L, false, null, null, "worker-9"));
        List<ExceptionGroupDto> exceptions = List.of(scheduledException("e1", "worker-9", 1_010L));

        LiveActivityReport report = assembler.report(
                requests,
                List.of(),
                false,
                null,
                exceptions,
                List.of(),
                false,
                List.of(),
                false,
                scheduled,
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        assertThat(entry(report, "exc-e1").parentId()).isEqualTo("sched-1");
    }

    @Test
    void leavesExceptionTopLevelWhenScheduledTaskThreadDoesNotMatch() {
        HttpExchangesReport requests = requests();
        List<ScheduledTaskRunStore.Run> scheduled = List.of(
                new ScheduledTaskRunStore.Run(1L, "com.example.Job#run", 1_000L, 30L, false, null, null, "worker-9"));
        List<ExceptionGroupDto> exceptions = List.of(scheduledException("e1", "other-worker", 1_010L));

        LiveActivityReport report = assembler.report(
                requests,
                List.of(),
                false,
                null,
                exceptions,
                List.of(),
                false,
                List.of(),
                false,
                scheduled,
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        assertThat(entry(report, "exc-e1").parentId()).isNull();
    }

    @Test
    void prefersRequestTraceIdOverScheduledTaskThreadWhenBothCouldMatch() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        List<ScheduledTaskRunStore.Run> scheduled = List.of(
                new ScheduledTaskRunStore.Run(1L, "com.example.Job#run", 1_000L, 30L, false, null, null, "worker-1"));
        // Shares both a matching trace id (via the request) and a matching thread/window (via the
        // scheduled run) — the request/trace-id tier must win, exactly like the Spring adapter's
        // matchExceptionParent-before-matchScheduledTaskParent ordering.
        List<ExceptionGroupDto> exceptions = List.of(exception("e1", "trace-a", 1_010L));

        LiveActivityReport report = assembler.report(
                requests,
                List.of(),
                false,
                null,
                exceptions,
                List.of(),
                false,
                List.of(),
                false,
                scheduled,
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        assertThat(entry(report, "exc-e1").parentId()).isEqualTo("req-1");
    }

    @Test
    void rendersScheduledTaskRunsAsFlatEntriesAndCountsFailuresInKpis() {
        HttpExchangesReport requests = requests();
        List<ScheduledTaskRunStore.Run> scheduled = List.of(
                new ScheduledTaskRunStore.Run(1L, "com.example.Job#run", 1_000L, 5L, true, null, null, "worker-1"),
                new ScheduledTaskRunStore.Run(
                        2L,
                        "com.example.Job#fail",
                        2_000L,
                        5L,
                        false,
                        "java.lang.RuntimeException",
                        "boom",
                        "worker-1"));

        LiveActivityReport report = assembler.report(
                requests,
                List.of(),
                false,
                null,
                exceptions(),
                List.of(),
                false,
                List.of(),
                false,
                scheduled,
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        ActivityEntryDto ok = entry(report, "sched-1");
        assertThat(ok.type()).isEqualTo("SCHEDULED");
        assertThat(ok.severity()).isEqualTo("OK");
        assertThat(ok.summary()).isEqualTo("com.example.Job#run");
        assertThat(ok.parentId()).isNull();

        ActivityEntryDto failed = entry(report, "sched-2");
        assertThat(failed.severity()).isEqualTo("ERROR");
        assertThat(failed.detail()).isEqualTo("java.lang.RuntimeException: boom");

        assertThat(report.kpis().scheduledTaskFailureCount()).isEqualTo(1);
        assertThat(report.sources()).contains("scheduled-tasks");
    }

    @Test
    void omitsScheduledTasksSourceWhenNoRunsAreCaptured() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));

        LiveActivityReport report = assembler.report(
                requests,
                List.of(),
                false,
                null,
                exceptions(),
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        assertThat(report.sources()).doesNotContain("scheduled-tasks");
        assertThat(report.kpis().scheduledTaskFailureCount()).isZero();
    }

    @Test
    void typeCountsReflectTheFullFeedEvenWhenTheLimitTruncatesVisibleScheduledTaskEntries() {
        HttpExchangesReport requests = requests(
                request("req-1", "/orders/1", "trace-a", 4_000L),
                request("req-2", "/orders/2", "trace-b", 3_000L),
                request("req-3", "/orders/3", "trace-c", 2_000L));
        List<ScheduledTaskRunStore.Run> scheduled = List.of(
                new ScheduledTaskRunStore.Run(1L, "com.example.Job#run", 1_000L, 10L, true, null, null, "worker-1"));

        LiveActivityReport report = assembler.report(
                requests,
                List.of(),
                false,
                null,
                exceptions(),
                List.of(),
                false,
                List.of(),
                false,
                scheduled,
                "UP",
                2,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false);

        assertThat(report.entries()).hasSize(2);
        assertThat(report.typeCounts()).containsEntry("REQUEST", 3).containsEntry("SCHEDULED", 1);
    }

    @Test
    void requestAndScheduledSeverityUseTheSharedRequestSlowThreshold() {
        HttpExchangesReport requests = requests(
                timedRequest("req-fast", 200, 999L, 5_000L),
                timedRequest("req-slow", 200, 1_000L, 4_000L),
                timedRequest("req-failed", 503, 1_500L, 3_000L));
        List<ScheduledTaskRunStore.Run> scheduled = List.of(
                new ScheduledTaskRunStore.Run(1L, "com.example.Job#run", 1_000L, 1_200L, true, null, null, "w-1"),
                new ScheduledTaskRunStore.Run(2L, "com.example.Job#run", 2_000L, 600L, true, null, null, "w-1"));

        LiveActivityReport defaults = severityReport(new LiveActivityAssembler(), requests, scheduled);
        assertThat(entry(defaults, "req-fast").severity()).isEqualTo("OK");
        assertThat(entry(defaults, "req-slow").severity()).isEqualTo("SLOW");
        assertThat(entry(defaults, "req-failed").severity()).isEqualTo("ERROR");
        assertThat(entry(defaults, "sched-1").severity()).isEqualTo("SLOW");
        // 600 ms was SLOW under the former fixed 500 ms threshold; the shared default is 1,000 ms.
        assertThat(entry(defaults, "sched-2").severity()).isEqualTo("OK");

        LiveActivityReport custom = severityReport(new LiveActivityAssembler(500), requests, scheduled);
        assertThat(entry(custom, "req-fast").severity()).isEqualTo("SLOW");
        assertThat(entry(custom, "sched-2").severity()).isEqualTo("SLOW");

        LiveActivityReport disabled = severityReport(new LiveActivityAssembler(0), requests, scheduled);
        assertThat(entry(disabled, "req-slow").severity()).isEqualTo("OK");
        assertThat(entry(disabled, "req-failed").severity()).isEqualTo("ERROR");
        assertThat(entry(disabled, "sched-1").severity()).isEqualTo("OK");
    }

    private static LiveActivityReport severityReport(
            LiveActivityAssembler assembler, HttpExchangesReport requests, List<ScheduledTaskRunStore.Run> scheduled) {
        return assembler.report(
                requests, List.of(), false, null, List.of(), List.of(), false, List.of(), false, scheduled, "UP", 0,
                List.of(), false, List.of(), false, List.of(), false, List.of(), false);
    }

    private static HttpExchangeDto timedRequest(String id, int status, long durationMs, long epochMillis) {
        return new HttpExchangeDto(
                id,
                Instant.ofEpochMilli(epochMillis),
                "GET",
                "/orders",
                null,
                "http://localhost:8080/orders",
                status,
                status >= 500 ? "5xx" : "2xx",
                durationMs,
                34L,
                "127.0.0.1",
                null,
                null,
                null,
                List.of(),
                List.of());
    }

    @Test
    void mapsRestClientEntrySeveritySummaryDetailAndParentFromTraceId() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        List<RestClientTraceEntryDto> rest =
                List.of(rest(7, "GET", "api.example.com", "/users", 200, 75L, true, null, false, "trace-a"));

        LiveActivityReport report = assembler.report(
                requests,
                List.of(),
                false,
                null,
                exceptions(),
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                rest,
                true);

        ActivityEntryDto restEntry = entry(report, "rest-7");
        assertThat(restEntry.type()).isEqualTo("REST_CLIENT");
        assertThat(restEntry.severity()).isEqualTo("OK");
        assertThat(restEntry.summary()).isEqualTo("GET api.example.com/users → 200");
        assertThat(restEntry.detail()).isEqualTo("WebClient");
        assertThat(restEntry.method()).isEqualTo("GET");
        assertThat(restEntry.path()).isEqualTo("/users");
        assertThat(restEntry.status()).isEqualTo(200);
        assertThat(restEntry.parentId()).isEqualTo("req-1");
        assertThat(restEntry.correlationId()).isEqualTo("trace-a");
        assertThat(report.sources()).contains("rest-client");
    }

    @Test
    void leavesRestClientEntryFlatWhenNoTraceIdIsStampedOrItIsAmbiguous() {
        HttpExchangesReport missingTraceRequest = requests(request("req-1", "/orders", null, 1_000L));
        LiveActivityReport missingTraceReport = assembler.report(
                missingTraceRequest,
                List.of(),
                false,
                null,
                exceptions(),
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(rest(7, "GET", "api.example.com", "/users", 200, 75L, true, null, false, null)),
                true);
        assertThat(entry(missingTraceReport, "rest-7").parentId()).isNull();

        HttpExchangesReport ambiguousRequests =
                requests(request("req-1", "/orders", "trace-a", 1_000L), request("req-2", "/items", "trace-a", 2_000L));
        LiveActivityReport ambiguousReport = assembler.report(
                ambiguousRequests,
                List.of(),
                false,
                null,
                exceptions(),
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(rest(8, "GET", "api.example.com", "/users", 200, 75L, true, null, false, "trace-a")),
                true);
        assertThat(entry(ambiguousReport, "rest-8").parentId()).isNull();
    }

    @Test
    void computesRestClientKpis() {
        List<RestClientTraceEntryDto> rest = List.of(
                rest(1, "GET", "api", "/ok", 200, 10L, true, null, false, "trace-a"),
                rest(2, "GET", "api", "/slow", 404, 20L, true, null, false, "trace-b"),
                rest(3, "GET", "api", "/down", null, 30L, false, "boom", false, "trace-c"),
                rest(4, "GET", "api", "/slower", 200, 40L, true, null, true, "trace-d"));

        LiveActivityReport report = assembler.report(
                requests(),
                List.of(),
                false,
                null,
                exceptions(),
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                rest,
                true);

        assertThat(report.kpis().restCallErrorRatePercent()).isEqualTo(50.0);
        assertThat(report.kpis().restCallP95LatencyMs()).isEqualTo(40L);
    }

    @Test
    void omitsRestClientSourceAndKpisWhenUnavailable() {
        LiveActivityReport report = assembler.report(
                requests(),
                List.of(),
                false,
                null,
                exceptions(),
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                "UP",
                0,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(rest(1, "GET", "api", "/ok", 200, 10L, true, null, false, "trace-a")),
                false);

        assertThat(report.sources()).doesNotContain("rest-client");
        assertThat(report.kpis().restCallErrorRatePercent()).isNull();
        assertThat(report.kpis().restCallP95LatencyMs()).isNull();
    }

    private static ActivityEntryDto onlyEntryOfType(LiveActivityReport report, String type) {
        List<ActivityEntryDto> ofType = report.entries().stream()
                .filter(candidate -> type.equals(candidate.type()))
                .toList();
        assertThat(ofType).hasSize(1);
        return ofType.get(0);
    }

    private static ActivityEntryDto entry(LiveActivityReport report, String id) {
        return report.entries().stream()
                .filter(e -> e.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entry with id " + id));
    }

    private static String hashedKey(String key) {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 1, 16);
        recorder.recordProduce("orders", 0, key, 0L, true, null);
        return recorder.recent().get(0).key();
    }

    /**
     * Security entry ids are a content hash (see {@link SecurityActivityIds}), not a predictable literal,
     * so tests locate the single security entry by type instead of hardcoding an id.
     */
    private static ActivityEntryDto securityEntry(LiveActivityReport report) {
        return report.entries().stream()
                .filter(e -> "SECURITY".equals(e.type()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no SECURITY entry in report"));
    }

    @Test
    void nestsFaultToleranceEventUnderRequestSharingTraceIdAndCountsIt() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        FaultToleranceEventRecorder recorder = new FaultToleranceEventRecorder(true, 10);
        recorder.setCorrelationContextProvider(() -> BootUiCorrelation.current().withTrace("trace-a", null));
        recorder.record(
                "paymentGateway",
                FaultToleranceVocabulary.TYPE_RETRY,
                FaultToleranceVocabulary.PROVIDER_RESILIENCE4J,
                "PayClient#charge",
                FaultToleranceVocabulary.OUTCOME_RETRY,
                2,
                12L,
                "IOException");

        LiveActivityReport report = faultToleranceReport(requests, recorder, true);

        ActivityEntryDto faultTolerance = entryOfType(report, "FAULT_TOLERANCE");
        assertThat(faultTolerance.parentId()).isEqualTo("req-1");
        assertThat(faultTolerance.severity()).isEqualTo("WARN");
        assertThat(faultTolerance.summary()).contains("paymentGateway");
        assertThat(report.typeCounts().get("FAULT_TOLERANCE")).isEqualTo(1);
        assertThat(report.sources()).contains("fault-tolerance");
    }

    @Test
    void keepsFaultToleranceEventTopLevelWhenNoRequestSharesItsTraceId() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        FaultToleranceEventRecorder recorder = new FaultToleranceEventRecorder(true, 10);
        recorder.setCorrelationContextProvider(BootUiCorrelation::current);
        recorder.recordStateTransition(
                "paymentGateway",
                FaultToleranceVocabulary.PROVIDER_RESILIENCE4J,
                null,
                FaultToleranceVocabulary.STATE_OPEN);

        LiveActivityReport report = faultToleranceReport(requests, recorder, true);

        assertThat(entryOfType(report, "FAULT_TOLERANCE").parentId()).isNull();
    }

    @Test
    void omitsFaultToleranceEntriesWhenTheSourceIsUnavailable() {
        HttpExchangesReport requests = requests(request("req-1", "/orders", "trace-a", 1_000L));
        FaultToleranceEventRecorder recorder = new FaultToleranceEventRecorder(true, 10);
        recorder.record(
                "paymentGateway",
                FaultToleranceVocabulary.TYPE_RETRY,
                FaultToleranceVocabulary.PROVIDER_RESILIENCE4J,
                null,
                FaultToleranceVocabulary.OUTCOME_RETRY,
                1,
                null,
                null);

        LiveActivityReport report = faultToleranceReport(requests, recorder, false);

        assertThat(report.entries()).noneMatch(candidate -> "FAULT_TOLERANCE".equals(candidate.type()));
        assertThat(report.sources()).doesNotContain("fault-tolerance");
    }

    private static ActivityEntryDto entryOfType(LiveActivityReport report, String type) {
        return report.entries().stream()
                .filter(candidate -> type.equals(candidate.type()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + type + " entry in " + report.entries()));
    }

    private LiveActivityReport faultToleranceReport(
            HttpExchangesReport requests, FaultToleranceEventRecorder recorder, boolean faultToleranceAvailable) {
        return assembler.report(
                requests,
                List.of(),
                false,
                null,
                exceptions(),
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                "UP",
                50,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                false,
                recorder.recent(),
                faultToleranceAvailable);
    }

    private static HttpExchangesReport requests(HttpExchangeDto... exchanges) {
        List<HttpExchangeDto> list = List.of(exchanges);
        return new HttpExchangesReport(
                list.size(), list.size(), 0, list, new PageMetadata(0, list.size(), list.size(), 1, 1, false), null);
    }

    private static HttpExchangeDto request(String id, String path, String traceId, long epochMillis) {
        return request(id, path, traceId, null, epochMillis);
    }

    private static HttpExchangeDto request(String id, String path, String traceId, String principal, long epochMillis) {
        return new HttpExchangeDto(
                id,
                Instant.ofEpochMilli(epochMillis),
                "GET",
                path,
                null,
                "http://localhost:8080" + path,
                200,
                "2xx",
                12L,
                34L,
                "127.0.0.1",
                principal,
                null,
                traceId,
                List.of(),
                List.of());
    }

    private static SecurityLogEventDto security(String principal, String type, String traceId, long epochMillis) {
        return new SecurityLogEventDto(
                Instant.ofEpochMilli(epochMillis).toString(), principal, type, List.of(), traceId);
    }

    private static CacheActivityEvent cache(
            long seq, CacheActivityOperation operation, String traceId, long epochMillis) {
        return new CacheActivityEvent(
                seq, epochMillis, "cacheManager", "orders", operation, "hash" + seq, traceId, "worker-1");
    }

    private static SqlTraceEntryDto sql(long id, String sql, String traceId, long epochMillis) {
        return sql(id, sql, traceId, epochMillis, null);
    }

    private static SqlTraceEntryDto sql(long id, String sql, String traceId, long epochMillis, String callSite) {
        return new SqlTraceEntryDto(
                id,
                epochMillis,
                sql,
                "PREPARED",
                "SELECT",
                5_000L,
                5L,
                true,
                null,
                null,
                0,
                "c1",
                "worker-1",
                false,
                List.of(),
                traceId,
                callSite);
    }

    private static RestClientTraceEntryDto rest(
            long id,
            String method,
            String host,
            String path,
            Integer status,
            long durationMillis,
            boolean success,
            String errorMessage,
            boolean slow,
            String traceId) {
        return new RestClientTraceEntryDto(
                id,
                1_000L + id,
                method,
                "https://" + host + path,
                host,
                path,
                status,
                durationMillis,
                success,
                errorMessage,
                slow,
                "WebClient",
                Map.of(),
                traceId,
                "worker-1",
                null);
    }

    private static List<ExceptionGroupDto> exceptions() {
        return List.of();
    }

    private static ExceptionGroupDto exception(String id, String lastTraceId, long lastSeen) {
        return new ExceptionGroupDto(
                id,
                "java.lang.IllegalStateException",
                "boom",
                1,
                lastSeen,
                lastSeen,
                "Foo.java:1",
                true,
                "worker-1",
                "GET",
                "/orders",
                "Handler#x",
                "web",
                lastTraceId,
                "OPEN",
                0,
                null);
    }

    private static EmailMessageDto email(String id, String traceId, String thread, long timestamp) {
        return new EmailMessageDto(
                id,
                timestamp,
                "noreply@example.com",
                List.of("user@example.com"),
                List.of(),
                List.of(),
                "Welcome",
                "Hello",
                null,
                List.of(),
                true,
                traceId,
                thread);
    }

    /**
     * An exception group with no trace id and no correlated HTTP method/path (matching the shape a
     * background {@code @Scheduled} failure's captured exception actually has), so the trace-id tier
     * always yields {@code null} and {@code matchScheduledTaskParent} is the only tier that can attach it
     * to a parent.
     */
    private static ExceptionGroupDto scheduledException(String id, String thread, long lastSeen) {
        return new ExceptionGroupDto(
                id,
                "java.lang.RuntimeException",
                "boom",
                1,
                lastSeen,
                lastSeen,
                "Foo.java:1",
                true,
                thread,
                null,
                null,
                null,
                null,
                null,
                "OPEN",
                0,
                null);
    }
}
