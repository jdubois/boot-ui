package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.resources.GcPauseRange;
import io.github.jdubois.bootui.engine.resources.ResourceUsage;
import io.github.jdubois.bootui.spi.ThreadKind;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class JournalEntryDiskViewTests {

    private static final String RAW_SQL =
            "select * from accounts where email = 'alice@example.com' and password = 'hunter2'";
    private static final String RAW_LOG = "Login refused for token=abc123 and password=hunter2";
    private static final String RAW_PATH = "/orders/42;token=abc123;region=eu";
    private static final String RAW_ROUTE = "/orders/{id};token=abc123;region=eu";

    @Test
    void fullAndMaskedReadsNeverMakeTheDiskViewLessMaskedThanMasked() {
        ThrowableMarks marks = ThrowableMarks.ofIdentities(101, 202);
        List<JournalEntry> original = List.of(
                entry(JournalSource.HTTP, new HttpPayload("POST", RAW_PATH, RAW_ROUTE, "save token=abc123", 503)),
                entry(JournalSource.SQL, new SqlPayload(RAW_SQL, "Accounts.find(Accounts.java:42)", "main", false)),
                entry(
                        JournalSource.REST_CLIENT,
                        new RestClientPayload(
                                "GET", "user:secret@api.example.com:8443", RAW_PATH, 502, "RestClient", true)),
                entry(JournalSource.LOG, new LogPayload("com.example.Login", "WARN", RAW_LOG, "Failure", marks)),
                entry(
                        JournalSource.AUTHORIZATION,
                        new AuthorizationPayload(
                                AuthorizationPayload.REQUEST,
                                null,
                                "hasPermission(password=hunter2)",
                                AuthorizationPayload.AUTHENTICATED,
                                false,
                                3)),
                entry(
                        JournalSource.MESSAGING,
                        new MessagingPayload("kafka", true, "orders token=abc123", true, "trace-link")),
                entry(
                        JournalSource.WEBSOCKET,
                        new WebSocketPayload(
                                "/socket;token=abc123",
                                WebSocketPayload.MESSAGE,
                                true,
                                "/topic/orders;token=abc123;room=blue",
                                123,
                                "1008 password=hunter2",
                                true)));

        JournalTextExposure full = new JournalTextExposure(ValueExposure.FULL, true);
        assertThat(full.path(RAW_PATH)).contains("abc123");
        assertThat(full.sql(RAW_SQL)).contains("alice@example.com", "hunter2");
        assertThat(full.message(RAW_LOG)).contains("abc123", "hunter2");
        JournalTextExposure unmaskedMaskedMode = new JournalTextExposure(ValueExposure.MASKED, false);
        assertThat(unmaskedMaskedMode.path(RAW_PATH)).contains("abc123");
        assertThat(unmaskedMaskedMode.sql(RAW_SQL)).contains("alice@example.com", "hunter2");
        JournalTextExposure maskedRead = new JournalTextExposure(ValueExposure.MASKED, true);
        assertThat(maskedRead.path(RAW_PATH)).doesNotContain("abc123").contains("region=eu");

        List<JournalEntry> disk =
                original.stream().map(JournalEntryDiskView::of).toList();

        HttpPayload http = payload(disk.get(0));
        assertThat(http.path()).isEqualTo(JournalTextExposure.masked().path(RAW_PATH));
        assertThat(http.routeTemplate()).isEqualTo(JournalTextExposure.masked().path(RAW_ROUTE));
        assertThat(http.operation()).doesNotContain("abc123");
        SqlPayload sql = payload(disk.get(1));
        assertThat(sql.sql()).isEqualTo(JournalTextExposure.masked().sql(RAW_SQL));
        RestClientPayload rest = payload(disk.get(2));
        assertThat(rest.authority()).isEqualTo("******@api.example.com:8443");
        assertThat(rest.path()).isEqualTo(JournalTextExposure.masked().path(RAW_PATH));
        LogPayload log = payload(disk.get(3));
        assertThat(log.template()).isEqualTo(JournalTextExposure.masked().message(RAW_LOG));
        assertThat(log.marks()).isNull();
        AuthorizationPayload authorization = payload(disk.get(4));
        assertThat(authorization.rule())
                .isEqualTo(JournalTextExposure.masked().authorizationRule("hasPermission(password=hunter2)"))
                .doesNotContain("hunter2");
        assertThat(((MessagingPayload) disk.get(5).event().payload()).destination())
                .doesNotContain("abc123");
        WebSocketPayload webSocket = payload(disk.get(6));
        assertThat(webSocket.endpoint()).doesNotContain("abc123");
        assertThat(webSocket.destination()).doesNotContain("abc123").contains("room=blue");
        assertThat(webSocket.closeStatus()).doesNotContain("hunter2");

        assertThat(((HttpPayload) original.get(0).event().payload()).path()).isEqualTo(RAW_PATH);
        assertThat(((SqlPayload) original.get(1).event().payload()).sql()).isEqualTo(RAW_SQL);
        assertThat(((LogPayload) original.get(3).event().payload()).marks()).isSameAs(marks);
        assertThat(disk).allSatisfy(safe -> assertThat(safe).isNotSameAs(original.get(disk.indexOf(safe))));
        assertThat(roundTrip(disk.get(0)).event().payload()).isEqualTo(http);
        assertThat(roundTrip(disk.get(1)).event().payload()).isEqualTo(sql);
        assertThat(roundTrip(disk.get(2)).event().payload()).isEqualTo(rest);
    }

    @Test
    void metadataOnlyIsReappliedToTheAlreadyMaskedDiskValues() {
        JournalEntry http = JournalEntryDiskView.of(
                entry(JournalSource.HTTP, new HttpPayload("GET", RAW_PATH, RAW_ROUTE, null, 200)));
        JournalEntry sql =
                JournalEntryDiskView.of(entry(JournalSource.SQL, new SqlPayload(RAW_SQL, null, "main", false)));
        JournalEntry rest = JournalEntryDiskView.of(entry(
                JournalSource.REST_CLIENT,
                new RestClientPayload("GET", "api.example.com", RAW_PATH, 200, "RestClient", false)));
        JournalEntry log = JournalEntryDiskView.of(
                entry(JournalSource.LOG, new LogPayload("com.example.Login", "WARN", RAW_LOG, null)));
        JournalEntry authorization = JournalEntryDiskView.of(entry(
                JournalSource.AUTHORIZATION,
                new AuthorizationPayload(
                        AuthorizationPayload.REQUEST,
                        null,
                        "hasPermission(password=hunter2)",
                        AuthorizationPayload.AUTHENTICATED,
                        true,
                        1)));

        JournalTextExposure metadata = new JournalTextExposure(ValueExposure.METADATA_ONLY, false);
        assertThat(metadata.path(((HttpPayload) http.event().payload()).path()))
                .isEqualTo("/orders/42;token=******;region=******");
        assertThat(metadata.path(((HttpPayload) http.event().payload()).routeTemplate()))
                .isEqualTo("/orders/{id};token=******;region=******");
        assertThat(metadata.path(((RestClientPayload) rest.event().payload()).path()))
                .isEqualTo("/orders/42;token=******;region=******");
        assertThat(metadata.sql(((SqlPayload) sql.event().payload()).sql()))
                .doesNotContain("alice@example.com", "hunter2");
        assertThat(metadata.message(((LogPayload) log.event().payload()).template()))
                .isNull();
        assertThat(metadata.authorizationRule(
                        ((AuthorizationPayload) authorization.event().payload()).rule()))
                .isNull();
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("fixtures")
    void everyPayloadRoundTripsStructurallyExceptInMemoryMarks(Fixture fixture) {
        JournalEntry original = entry(fixture.source(), fixture.payload());

        JournalEntry disk = JournalEntryDiskView.of(original);
        JournalEntry expected = new JournalEntry(
                original.sequence(), original.event().withPayload(fixture.diskPayload()), original.estimatedBytes());

        assertThat(disk).isEqualTo(expected).isNotSameAs(original);
        assertThat(disk.event()).isNotSameAs(original.event());
        assertThat(disk.event().payload()).isNotSameAs(fixture.payload());
        assertThat(disk.event().payload()).isExactlyInstanceOf(fixture.payload().getClass());
        assertThat(roundTrip(disk)).isEqualTo(expected);
        if (fixture.payload() instanceof ExceptionPayload exception) {
            assertThat(exception.marks()).isNotNull();
        }
        if (fixture.payload() instanceof LogPayload log) {
            assertThat(log.marks()).isNotNull();
        }
        if (fixture.payload() instanceof CaughtExceptionPayload caught) {
            assertThat(caught.identity()).isEqualTo(456);
        }
    }

    @Test
    void reconstructsEntriesWithoutPayloads() {
        JournalEntry original = entry(JournalSource.SECURITY, null);

        JournalEntry disk = JournalEntryDiskView.of(original);

        assertThat(disk).isEqualTo(original).isNotSameAs(original);
        assertThat(disk.event()).isNotSameAs(original.event());
        assertThat(roundTrip(disk)).isEqualTo(original);
    }

    @Test
    void fixturesCoverEveryProductionPayload() {
        Set<String> productionPayloads = new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackagesOf(RuntimeEventPayload.class)
                        .stream()
                        .filter(type -> !type.isInterface() && type.isAssignableTo(RuntimeEventPayload.class))
                        .map(JavaClass::getName)
                        .collect(Collectors.toSet());

        assertThat(fixtures()
                        .map(fixture -> fixture.payload().getClass().getName())
                        .collect(Collectors.toSet()))
                .isEqualTo(productionPayloads);
    }

    @Test
    void failsClosedForUnknownPayloadsAndNullEntries() {
        RuntimeEventPayload unknown = () -> 123;

        assertThatThrownBy(() -> JournalEntryDiskView.of(entry(JournalSource.SQL, unknown)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported journal payload class");
        assertThatThrownBy(() -> JournalEntryDiskView.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JournalEntryDiskView.of(new JournalEntry(1, null, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static JournalEntry entry(JournalSource source, RuntimeEventPayload payload) {
        return new JournalEntry(
                9_876_543_210L,
                new RuntimeEvent(
                        source,
                        1_731_234_567_890L,
                        123_456_789L,
                        "request-1",
                        "execution-2",
                        "trace-3",
                        "worker-é",
                        ThreadKind.WORKER,
                        true,
                        payload),
                12_345);
    }

    private static JournalEntry roundTrip(JournalEntry entry) {
        return JournalEntryCodec.decode(JournalEntryCodec.encode(entry));
    }

    @SuppressWarnings("unchecked")
    private static <P extends RuntimeEventPayload> P payload(JournalEntry entry) {
        return (P) entry.event().payload();
    }

    private static Stream<Fixture> fixtures() {
        ApplicationFrames frames = ApplicationFrames.of(List.of(
                "com.example.Repository.find(Repository.java:12)", "com.example.Service.work(Service.java:34)"));
        ResourceUsage resources = new ResourceUsage(
                101,
                202,
                3,
                1,
                ResourceUsage.Unmeasured.THREAD_ENDED,
                4,
                List.of(new GcPauseRange("young", 10, 12)),
                true);
        RunStart start = new RunStart(
                123L,
                List.of(new StartupStepTiming("ready", "Application", 45)),
                new ComparabilityFacts(
                        List.of("test"), Map.of("main", "jdbc:h2:mem"), "Caffeine", true, List.of("http", "sql")));
        ThrowableMarks marks = ThrowableMarks.ofIdentities(123, 456);

        return Stream.of(
                fixture(
                        JournalSource.HTTP,
                        new HttpPayload(
                                "POST",
                                "/orders/42",
                                "/orders/{id}",
                                "saveOrder",
                                503,
                                resources,
                                new RequestTiming(-123, 234, 345, 456),
                                true)),
                fixture(
                        JournalSource.SQL,
                        new SqlPayload(
                                "select * from orders where id = ?",
                                "Orders.find(Orders.java:7)",
                                "main",
                                false,
                                frames,
                                RequestPhase.RESPONSE,
                                123,
                                234,
                                SqlPayload.Provenance.EXECUTION,
                                3,
                                5L)),
                fixture(
                        JournalSource.REST_CLIENT,
                        new RestClientPayload(
                                "GET", "remote:8443", "/orders", 502, "RestClient", true, frames, 345, 456)),
                fixture(
                        JournalSource.EXCEPTION,
                        new ExceptionPayload("group", "Failure", "signature", List.of("Failure", "Cause"), marks, true),
                        new ExceptionPayload("group", "Failure", "signature", List.of("Failure", "Cause"), null, true)),
                fixture(JournalSource.SECURITY, new SecurityPayload("AUTHENTICATION_FAILURE")),
                fixture(JournalSource.CACHE, new CachePayload("orders", "GET", frames, 567)),
                fixture(JournalSource.SCHEDULED, new ScheduledPayload("Orders.refresh", "Failure")),
                fixture(JournalSource.MESSAGING, new MessagingPayload("kafka", true, "orders", true, "linked-trace")),
                fixture(
                        JournalSource.TRANSACTION,
                        new TransactionPayload(
                                "Orders.save",
                                true,
                                true,
                                true,
                                678,
                                true,
                                "SERIALIZABLE",
                                true,
                                "Failure",
                                12,
                                23,
                                34,
                                true,
                                true)),
                fixture(JournalSource.CONNECTION, new ConnectionPayload("main", 789, 8, 890)),
                fixture(JournalSource.MAIL, new MailPayload(3, 4, true)),
                fixture(
                        JournalSource.FAULT_TOLERANCE,
                        new FaultTolerancePayload(
                                "remote", "retry", "Orders.fetch", "RETRY", 3, "OPEN", "TimeoutException", true, true)),
                fixture(
                        JournalSource.AI,
                        new AiPayload("chat", "openai", "gpt-test", 12L, 23L, "stop", true, "span-4", 901, 1_012)),
                fixture(
                        JournalSource.AUTHORIZATION,
                        new AuthorizationPayload(
                                AuthorizationPayload.METHOD,
                                "Orders#save",
                                "hasRole('ADMIN')",
                                AuthorizationPayload.AUTHENTICATED,
                                true,
                                3)),
                fixture(
                        JournalSource.ORM,
                        new OrmPayload(
                                "main",
                                1,
                                2,
                                3,
                                4,
                                5,
                                6,
                                7,
                                8,
                                9,
                                10,
                                11,
                                12,
                                13,
                                List.of(new OrmPayload.Flush(14, 15, true, 16)))),
                fixture(
                        JournalSource.GC,
                        new GcPayload(
                                "G1 Young Generation",
                                12,
                                "end of minor GC",
                                "G1 Evacuation Pause",
                                true,
                                23,
                                34,
                                45,
                                56)),
                fixture(
                        JournalSource.LOG,
                        new LogPayload("com.example.Orders", "ERROR", "Failed order {}", "Failure", marks),
                        new LogPayload("com.example.Orders", "ERROR", "Failed order {}", "Failure", null)),
                fixture(
                        JournalSource.APP_EVENT,
                        new AppEventPayload(
                                AppEventPayload.LISTENER,
                                "OrderPlaced",
                                "Orders#onPlaced",
                                "AFTER_COMMIT",
                                AppEventPayload.FAILED,
                                "Failure",
                                3,
                                1_123)),
                fixture(JournalSource.LIFECYCLE, LifecyclePayload.runStarted(start)),
                fixture(
                        JournalSource.WEBSOCKET,
                        new WebSocketPayload(
                                "/socket",
                                WebSocketPayload.MESSAGE,
                                true,
                                "/topic/orders/{room}",
                                2_345,
                                "1001",
                                true)),
                fixture(
                        JournalSource.AGENT_EXECUTORS,
                        new AsyncHandoffPayload(
                                "async-1",
                                "async-parent",
                                "OrdersTask",
                                "runWorker",
                                3_456,
                                4_567,
                                5_678L,
                                true,
                                "Failure",
                                true,
                                6_789L,
                                true,
                                false,
                                0L,
                                7_890L,
                                true)),
                fixture(
                        JournalSource.AGENT_CAUGHT_EXCEPTIONS,
                        new CaughtExceptionPayload(
                                CaughtExceptionPayload.CAUGHT,
                                "com.example.Orders",
                                "save",
                                42,
                                List.of("Failure", "OtherFailure"),
                                7,
                                "ConcreteFailure",
                                "io",
                                123,
                                CaughtExceptionPayload.CAUGHT_AGAIN,
                                "Service.log",
                                456),
                        new CaughtExceptionPayload(
                                CaughtExceptionPayload.CAUGHT,
                                "com.example.Orders",
                                "save",
                                42,
                                List.of("Failure", "OtherFailure"),
                                7,
                                "ConcreteFailure",
                                "io",
                                123,
                                CaughtExceptionPayload.CAUGHT_AGAIN,
                                "Service.log",
                                0)));
    }

    private static Fixture fixture(JournalSource source, RuntimeEventPayload payload) {
        return new Fixture(source, payload, payload);
    }

    private static Fixture fixture(JournalSource source, RuntimeEventPayload payload, RuntimeEventPayload diskPayload) {
        return new Fixture(source, payload, diskPayload);
    }

    private record Fixture(JournalSource source, RuntimeEventPayload payload, RuntimeEventPayload diskPayload) {
        @Override
        public String toString() {
            return payload.getClass().getSimpleName();
        }
    }
}
