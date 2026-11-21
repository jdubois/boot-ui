package io.github.jdubois.bootui.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.client.JsonValue;
import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.insights.InsightsStack;
import io.github.jdubois.bootui.engine.journal.DurableJournalArchiveService;
import io.github.jdubois.bootui.engine.journal.DurableJournalDetail;
import io.github.jdubois.bootui.engine.journal.DurableJournalEvent;
import io.github.jdubois.bootui.engine.journal.DurableJournalRun;
import io.github.jdubois.bootui.engine.journal.DurableJournalRunMetadata;
import io.github.jdubois.bootui.engine.journal.DurableJournalScope;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JdbcDurableJournalStore;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.JournalStatus;
import io.github.jdubois.bootui.engine.journal.RunSummary;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.spi.ThreadKind;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class ArchiveCommandsTests {

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final String DRIVER = "org.h2.Driver";
    private static final long STARTED_AT = 1_700_000_000_000L;

    @Test
    void readsRunsActivityProfilesAndInsightsFromExistingTables() throws Exception {
        Fixture fixture = fixture();
        DurableJournalScope scope = new DurableJournalScope("alpha", "orders", "run-7");
        persist(fixture, scope, "request-42");

        Result runs = run(
                fixture,
                Map.of(),
                "runs",
                "--namespace",
                "alpha",
                "--application",
                "orders",
                "--limit",
                "10",
                "--json");
        assertThat(runs.exitCode()).isZero();
        JsonValue runPayload = JsonValue.parse(runs.out());
        assertThat(runPayload.get("runs")).satisfies(runsValue -> {
            assertThat(runsValue.size()).isOne();
            JsonValue run = runsValue.get(0);
            assertThat(run.get("namespace").asString(null)).isEqualTo("alpha");
            assertThat(run.get("run").asString(null)).isEqualTo("run-7");
            assertThat(run.names()).doesNotContain("metadata", "metadataPayload");
        });

        Result activity = run(
                fixture,
                Map.of(),
                "activity",
                "--namespace",
                "alpha",
                "--application",
                "orders",
                "--run",
                "run-7",
                "--type",
                "REQUEST",
                "--limit",
                "1",
                "--json");
        assertThat(activity.exitCode()).isZero();
        JsonValue activityPayload = JsonValue.parse(activity.out());
        assertThat(activityPayload.get("entries")).satisfies(entries -> {
            assertThat(entries.size()).isOne();
            assertThat(entries.get(0).get("run").asString(null)).isEqualTo("run-7");
            assertThat(entries.get(0).get("activity").get("summary").asString(null))
                    .isEqualTo("GET /orders/42");
        });

        Result profile = run(
                fixture,
                Map.of(),
                "profile",
                "request-42",
                "--namespace",
                "alpha",
                "--application",
                "orders",
                "--run",
                "run-7",
                "--json");
        assertThat(profile.exitCode()).isZero();
        assertThat(JsonValue.parse(profile.out()).get("available").asBoolean(false))
                .isTrue();

        Result insights = run(
                fixture,
                Map.of(),
                "insights",
                "--namespace",
                "alpha",
                "--application",
                "orders",
                "--run",
                "run-7",
                "--json");
        assertThat(insights.exitCode()).isZero();
        assertThat(JsonValue.parse(insights.out()).get("available").asBoolean(false))
                .isTrue();

        Result multi = run(fixture, Map.of(), "insights", "--namespace", "alpha", "--application", "orders", "--json");
        assertThat(multi.exitCode()).isZero();
        JsonValue report = JsonValue.parse(multi.out()).get("runs").get(0);
        assertThat(report.get("origin").get("scope").get("run").asString(null)).isEqualTo("run-7");
        assertThat(report.get("completeness").get("metadataPhase").asString(null))
                .isEqualTo("END");
    }

    @Test
    void crossNamespaceReadsAreExplicitAndActivityCanReadGlobally() {
        Fixture fixture = fixture();
        persist(fixture, new DurableJournalScope("alpha", "orders", "alpha-run"), "alpha-request");
        persist(fixture, new DurableJournalScope("beta", "orders", "beta-run"), "beta-request");

        Result refused = run(fixture, Map.of(), "activity", "--application", "orders", "--json");
        assertThat(refused.exitCode()).isEqualTo(ExitCodes.ERROR);
        assertThat(refused.out()).isEmpty();
        assertThat(refused.err()).contains("--namespace").contains("--allow-cross-namespace");

        Result global =
                run(fixture, Map.of(), "activity", "--application", "orders", "--allow-cross-namespace", "--json");
        assertThat(global.exitCode()).isZero();
        JsonValue entries = JsonValue.parse(global.out()).get("entries");
        assertThat(entries.values())
                .extracting(entry -> entry.get("namespace").asString(null))
                .containsExactlyInAnyOrder("alpha", "beta");
    }

    @Test
    void insightsAcceptsExplicitScopesAcrossApplications() {
        Fixture fixture = fixture();
        persist(fixture, new DurableJournalScope("alpha", "orders", "orders-run"), "orders-request");
        persist(fixture, new DurableJournalScope("alpha", "billing", "billing-run"), "billing-request");

        Result result = run(
                fixture,
                Map.of(),
                "insights",
                "--scope",
                "alpha/orders/orders-run",
                "--scope",
                "alpha/billing/billing-run",
                "--json");

        assertThat(result.exitCode()).isZero();
        JsonValue runs = JsonValue.parse(result.out()).get("runs");
        assertThat(runs.size()).isEqualTo(2);
        assertThat(runs.values())
                .extracting(
                        run -> run.get("origin").get("scope").get("application").asString(null))
                .containsExactly("orders", "billing");
    }

    @Test
    void explicitInsightScopesUseTheSameNamespaceAuthorization() {
        Fixture fixture = fixture();
        persist(fixture, new DurableJournalScope("alpha", "orders", "alpha-run"), "alpha-request");
        persist(fixture, new DurableJournalScope("beta", "billing", "beta-run"), "beta-request");

        Result refused = run(
                fixture,
                Map.of(),
                "insights",
                "--scope",
                "alpha/orders/alpha-run",
                "--scope",
                "beta/billing/beta-run",
                "--json");
        assertThat(refused.exitCode()).isEqualTo(ExitCodes.ERROR);
        assertThat(refused.out()).isEmpty();
        assertThat(refused.err()).contains("outside authorized namespace alpha");

        Result allowed = run(
                fixture,
                Map.of(),
                "insights",
                "--scope",
                "alpha/orders/alpha-run",
                "--scope",
                "beta/billing/beta-run",
                "--allow-cross-namespace",
                "--json");
        assertThat(allowed.exitCode()).isZero();
        assertThat(JsonValue.parse(allowed.out()).get("runs").size()).isEqualTo(2);
    }

    @Test
    void insightScopesRejectMalformedSegmentsAndMoreThanOneHundredValues() {
        Fixture fixture = fixture();
        for (String value :
                List.of("alpha/orders", "alpha/orders/run/extra", "/orders/run", "alpha//run", "alpha/orders/")) {
            Result malformed = run(fixture, Map.of(), "insights", "--scope", value, "--json");
            assertThat(malformed.exitCode()).as(value).isEqualTo(ExitCodes.ERROR);
            assertThat(malformed.err()).as(value).contains("Invalid --scope");
        }

        List<String> command = new ArrayList<>(List.of("insights", "--json"));
        for (int index = 0; index <= DurableJournalArchiveService.MAX_MULTI_RUNS; index++) {
            command.add("--scope");
            command.add("alpha/orders/run-" + index);
        }
        Result tooMany = run(fixture, Map.of(), command.toArray(String[]::new));
        assertThat(tooMany.exitCode()).isEqualTo(ExitCodes.ERROR);
        assertThat(tooMany.err()).contains("At most 100 --scope");
    }

    @Test
    void neverCreatesSchemaAndFailsClearlyForMissingTables() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(freshUrl());

        Result result =
                run(dataSource.getURL(), Map.of(), "runs", "--namespace", "alpha", "--application", "orders", "--json");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.ERROR);
        assertThat(result.err()).contains("tables are missing").contains("schema creation is disabled");
        try (var connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME IN"
                                + " ('BOOTUI_ACTIVITY_RUNS', 'BOOTUI_ACTIVITY_EVENTS', 'BOOTUI_ACTIVITY_DETAILS')")) {
            assertThat(rows.next()).isFalse();
        }
    }

    @Test
    void missingDriverFailsWithoutPrintingDatabaseCredentials() {
        String password = "top-secret-password";
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = BootUiCli.run(
                new String[] {
                    "archive",
                    "runs",
                    "--jdbc-url",
                    "jdbc:does-not-exist:archive",
                    "--username",
                    "archive-user",
                    "--password",
                    password,
                    "--driver",
                    "not.a.RealDriver",
                    "--namespace",
                    "alpha",
                    "--application",
                    "orders",
                    "--verbose"
                },
                Map.of(),
                true,
                new PrintWriter(out, true),
                new PrintWriter(err, true));
        assertThat(exit).isEqualTo(ExitCodes.ERROR);
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString())
                .contains("JDBC driver class was not found")
                .doesNotContain(password)
                .doesNotContain("archive-user");
    }

    @Test
    void archiveHelpDocumentsClientSideReadsEnvironmentAndBundledDocs() {
        Result result = run(new String[] {"archive", "--help"}, Map.of());

        assertThat(result.exitCode()).isZero();
        assertThat(result.out())
                .contains("never creates or changes schema")
                .contains("BOOTUI_ARCHIVE_JDBC_URL")
                .contains("BOOTUI_ARCHIVE_PASSWORD")
                .contains("--allow-cross-namespace")
                .contains("bootui docs cli");

        Result insightsHelp = run(new String[] {"archive", "insights", "--help"}, Map.of());
        assertThat(insightsHelp.exitCode()).isZero();
        assertThat(insightsHelp.out())
                .contains("--scope")
                .contains("namespace/application/run")
                .contains("several runs or")
                .contains("applications (at most 100)");
    }

    @Test
    void archiveOptionsCanComeFromTheDocumentedEnvironment() {
        Fixture fixture = fixture();
        persist(fixture, new DurableJournalScope("alpha", "orders", "run-from-env"), "request-from-env");

        Result result = run(
                new String[] {"archive", "runs", "--json"},
                Map.of(
                        "BOOTUI_ARCHIVE_JDBC_URL",
                        fixture.url(),
                        "BOOTUI_ARCHIVE_DRIVER",
                        DRIVER,
                        "BOOTUI_ARCHIVE_NAMESPACE",
                        "alpha",
                        "BOOTUI_ARCHIVE_APPLICATION",
                        "orders"));

        assertThat(result.exitCode()).isZero();
        assertThat(JsonValue.parse(result.out()).get("runs").get(0).get("run").asString(null))
                .isEqualTo("run-from-env");
    }

    private static void persist(Fixture fixture, DurableJournalScope scope, String requestId) {
        JournalEntry request = request(requestId);
        ActivityEntryDto activity = new ActivityEntryDto(
                requestId,
                "REQUEST",
                request.event().epochMillis(),
                "OK",
                "GET /orders/42",
                "/orders/{id}",
                5L,
                "trace-" + requestId,
                "GET",
                "/orders/42",
                200,
                "http-nio-1",
                true,
                null,
                null,
                false,
                List.of());
        RuntimeJournalSettings settings =
                new RuntimeJournalSettings(true, 1_000, 1_000_000, 100, 10, 10, JournalSource.all());
        JournalStatus status = new JournalStatus(
                true,
                "instance-7",
                scope.run(),
                1,
                1,
                request.estimatedBytes(),
                0,
                0,
                settings.maxEvents(),
                settings.maxBytes(),
                0,
                0,
                0,
                0,
                null,
                STARTED_AT,
                0,
                settings.queueCapacity(),
                Map.of(JournalSource.HTTP, 1L),
                Map.of(),
                0,
                0);
        JournalAggregates aggregates = new JournalAggregates();
        aggregates.onEntries(List.of(request));
        RunSummary summary = RunSummary.of(
                new RunIdentity(scope.run(), 7, STARTED_AT),
                "dev:" + scope.application(),
                aggregates.snapshot(),
                null,
                null,
                STARTED_AT + 5);
        DurableJournalRunMetadata metadata = new DurableJournalRunMetadata(
                DurableJournalRunMetadata.Phase.END,
                STARTED_AT + 5,
                "instance-7",
                "process-7",
                InsightsStack.SPRING_MVC,
                settings,
                status,
                null,
                null,
                List.of(),
                List.of(),
                Map.of(),
                new DurableJournalRunMetadata.SqlCaptureQualification(0, 0, false, null, null),
                Map.of("orders", 10),
                new DurableJournalRunMetadata.DeclaredRoutes(1, false, null),
                summary);
        fixture.store()
                .upsertRun(new DurableJournalRun(
                        scope, STARTED_AT, STARTED_AT + 5, "STOPPED", null, 1, "instance-7", metadata));
        fixture.store().appendEvents(scope, List.of(new DurableJournalEvent(request, activity)));
        fixture.store()
                .upsertDetail(
                        DurableJournalDetail.request(scope, 1, "GET", "/orders/{id}", "OrderController.get", 200));
    }

    private static JournalEntry request(String requestId) {
        RuntimeEvent event = new RuntimeEvent(
                JournalSource.HTTP,
                STARTED_AT,
                5_000_000,
                requestId,
                null,
                "trace-" + requestId,
                "http-nio-1",
                ThreadKind.WORKER,
                false,
                new HttpPayload("GET", "/orders/42", "/orders/{id}", "OrderController.get", 200));
        return new JournalEntry(1, event, event.estimatedBytes());
    }

    private static Fixture fixture() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(freshUrl());
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource, "bootui_activity");
        store.verifySchema();
        return new Fixture(dataSource.getURL(), store);
    }

    private static String freshUrl() {
        return "jdbc:h2:mem:cli-archive-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
    }

    private static Result run(Fixture fixture, Map<String, String> environment, String... command) {
        String[] args = new String[command.length + 3];
        args[0] = "archive";
        System.arraycopy(command, 0, args, 1, command.length);
        args[command.length + 1] = "--jdbc-url";
        args[command.length + 2] = fixture.url();
        if (!contains(command, "--driver")) {
            String[] withDriver = new String[args.length + 2];
            System.arraycopy(args, 0, withDriver, 0, args.length);
            withDriver[args.length] = "--driver";
            withDriver[args.length + 1] = DRIVER;
            args = withDriver;
        }
        return run(args, environment);
    }

    private static Result run(String jdbcUrl, Map<String, String> environment, String... command) {
        String[] args = new String[command.length + 5];
        args[0] = "archive";
        System.arraycopy(command, 0, args, 1, command.length);
        args[command.length + 1] = "--jdbc-url";
        args[command.length + 2] = jdbcUrl;
        args[command.length + 3] = "--driver";
        args[command.length + 4] = DRIVER;
        return run(args, environment);
    }

    private static boolean contains(String[] values, String needle) {
        for (String value : values) {
            if (needle.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static Result run(String[] args, Map<String, String> environment) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = BootUiCli.run(args, environment, true, new PrintWriter(out, true), new PrintWriter(err, true));
        return new Result(exit, out.toString(), err.toString());
    }

    private record Fixture(String url, JdbcDurableJournalStore store) {}

    private record Result(int exitCode, String out, String err) {}
}
