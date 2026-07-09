package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ActivityPageInfo;
import io.github.jdubois.bootui.core.dto.ActivityPersistenceOptionDto;
import io.github.jdubois.bootui.core.dto.ActivitySwitchRequest;
import io.github.jdubois.bootui.core.dto.ActivitySwitchResult;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.engine.activity.ActivityPage;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.ActivityQuery;
import io.github.jdubois.bootui.engine.activity.ActivityStore;
import io.github.jdubois.bootui.engine.activity.BufferedActivityStore;
import io.github.jdubois.bootui.engine.activity.InMemoryActivityStore;
import io.github.jdubois.bootui.engine.activity.StoredActivityEntry;
import io.github.jdubois.bootui.engine.activity.SwitchableActivityStore;
import io.github.jdubois.bootui.engine.email.CapturedEmail;
import io.github.jdubois.bootui.engine.email.EmailCaptureService;
import io.github.jdubois.bootui.engine.email.EmailStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionsService;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTaskRunStore;
import io.github.jdubois.bootui.engine.security.SecurityEventBuffer;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.web.CapturedHttpExchange;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.util.TypeLiteral;
import jakarta.ws.rs.core.Response;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link LiveActivityResource}'s persistence wiring: byte-identical behavior when the (always
 * produced, see {@code BootUiEngineProducer}) {@link SwitchableActivityStore} bean reports {@code
 * persistent() == false} (the default), correct entry/pagination delegation plus {@link ActivityQuery}
 * construction when it reports {@code true}, and the "Use the existing datasource" runtime switch action
 * ({@link LiveActivityResource#useExistingDatasource}) — mirroring the Spring adapter's
 * {@code LiveActivityControllerTests}.
 *
 * <p>Unlike the Spring test, which mocks {@code SwitchableActivityStore} directly with Mockito, this
 * module has no Mockito dependency and both {@link SwitchableActivityStore} and
 * {@link BufferedActivityStore} are {@code final} (so neither can be mocked or subclassed). Tests that
 * need {@code persistent() == true} instead wrap a real {@link BufferedActivityStore} (so the {@code
 * instanceof} check in {@link SwitchableActivityStore#persistent()} is genuinely satisfied) around either
 * a hand-rolled {@link RecordingActivityStore} fake durable (for pure delegation/filtering behavior, no
 * I/O) or a real, disposable H2 in-memory database (only where an actual successful JDBC schema
 * verification must be exercised, i.e. the switch-success test) — the same real-H2 approach the Spring
 * test uses for the identical scenario.
 */
class LiveActivityResourceTests {

    private static final AtomicInteger DB_COUNTER = new AtomicInteger();

    @Test
    void activityCarriesNoPageInfoAndAnInactivePersistenceOptionWhenNotPersistent() {
        // The store bean always exists (even with persistence disabled, as a bare in-memory store), so
        // the response must carry no page info at all - byte-identical to today's behavior - while still
        // always reporting a persistenceOption so the panel can render its "Use a database" affordance.
        LiveActivityResource resource =
                resourceWith(new SwitchableActivityStore(new InMemoryActivityStore(10)), disabledSettings());

        LiveActivityReport result = resource.activity(null, null, null, null, null, null, null, null);

        assertThat(result.pageInfo()).isNull();
        assertThat(result.persistenceOption())
                .isEqualTo(new ActivityPersistenceOptionDto(false, false, "bootui_activity"));
    }

    @Test
    void activityReportsADataSourceAsAvailableWhenOneIsPresentEvenWithPersistenceOff() {
        LiveActivityResource resource = resourceWith(
                new SwitchableActivityStore(new InMemoryActivityStore(10)),
                disabledSettings(),
                satisfiedDataSource(newH2DataSource()));

        LiveActivityReport result = resource.activity(null, null, null, null, null, null, null, null);

        assertThat(result.persistenceOption())
                .isEqualTo(new ActivityPersistenceOptionDto(false, true, "bootui_activity"));
    }

    @Test
    void activityDelegatesEntriesAndPageInfoToStoreWhenPersistenceEnabled() {
        ActivityEntryDto storedEntry = new ActivityEntryDto(
                "sql-1",
                "SQL",
                1_000L,
                "OK",
                "select 1",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                false);
        SwitchableActivityStore store = persistentStore(new RecordingActivityStore(ActivityPage.EMPTY), "instance-a");
        LiveActivityResource resource = resourceWith(store, enabledSettings("instance-a"));
        try {
            store.appendBatch(List.of(new StoredActivityEntry("instance-a", 1L, storedEntry)));
            LiveActivityReport expectedLive = resource.mergedReport(0);

            LiveActivityReport result = resource.activity(0, "SQL", "OK", "select", 999L, 1_001L, null, 50);

            assertThat(result.entries()).containsExactly(storedEntry);
            assertThat(result.pageInfo()).isEqualTo(new ActivityPageInfo(true, null, false));
            assertThat(result.persistenceOption())
                    .isEqualTo(new ActivityPersistenceOptionDto(true, false, "bootui_activity"));
            // The KPI strip stays a "right now" summary from the live re-merge, not scoped to whichever
            // historical page is being browsed.
            assertThat(result.available()).isEqualTo(expectedLive.available());
            assertThat(result.typeCounts()).isEqualTo(expectedLive.typeCounts());
            assertThat(result.kpis()).isEqualTo(expectedLive.kpis());
            assertThat(result.sources()).isEqualTo(expectedLive.sources());
            assertThat(result.warnings()).isEqualTo(expectedLive.warnings());
        } finally {
            cleanup(resource, store);
        }
    }

    @Test
    void sinceOfZeroOrLessTranslatesToUnboundedQuery() {
        // A timestamp of exactly 0 is the only value that discriminates the "since<=0 means unbounded"
        // convention from a bug that passed since=0 straight through: entry.timestamp() <= since would
        // wrongly exclude it (0 <= 0) if the conversion to null were missing.
        ActivityEntryDto storedEntry = new ActivityEntryDto(
                "sql-2",
                "SQL",
                0L,
                "OK",
                "boundary",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                false);
        SwitchableActivityStore store = persistentStore(new RecordingActivityStore(ActivityPage.EMPTY), "instance-a");
        LiveActivityResource resource = resourceWith(store, enabledSettings("instance-a"));
        try {
            store.appendBatch(List.of(new StoredActivityEntry("instance-a", 1L, storedEntry)));

            LiveActivityReport result = resource.activity(0, null, null, null, 0L, null, null, null);

            assertThat(result.entries()).containsExactly(storedEntry);
        } finally {
            cleanup(resource, store);
        }
    }

    @Test
    void mergedReportIsUnaffectedByPersistenceFields() {
        LiveActivityResource resource =
                resourceWith(new SwitchableActivityStore(new InMemoryActivityStore(10)), disabledSettings());

        LiveActivityReport report = resource.mergedReport(0);

        assertThat(report).isNotNull();
        assertThat(report.pageInfo()).isNull();
    }

    @Test
    void mergedReportIncludesCapturedEmailsAndCorrelatesThemByTraceId() {
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(50);
        buffer.record(new CapturedHttpExchange(
                Instant.ofEpochMilli(1_000L),
                "GET",
                URI.create("http://localhost:8080/orders"),
                200,
                25L,
                "127.0.0.1",
                null,
                null,
                Map.of(),
                Map.of(),
                "trace-a"));
        EmailCaptureService emailService =
                new EmailCaptureService(new EmailStore(10), new QuarkusExposurePolicy(config(Map.of())), false, false);
        emailService.setTraceIdProvider(() -> "trace-a");
        emailService.capture(CapturedEmail.builder()
                .from("noreply@example.com")
                .to(List.of("user@example.com"))
                .subject("Welcome")
                .textBody("hello")
                .build());

        LiveActivityResource resource = resourceWith(
                new SwitchableActivityStore(new InMemoryActivityStore(10)),
                disabledSettings(),
                unsatisfiedDataSource(),
                buffer,
                satisfiedEmailCaptureService(emailService),
                new KafkaActivityRecorder(true, true, 200, 200),
                config(Map.of(QuarkusPanelAvailability.EMAIL_PRESENT_KEY, "true")));

        LiveActivityReport report = resource.mergedReport(0);

        assertThat(report.sources()).contains("email");
        String requestId = report.entries().stream()
                .filter(entry -> "REQUEST".equals(entry.type()))
                .findFirst()
                .orElseThrow()
                .id();
        assertThat(report.entries()).anySatisfy(entry -> {
            assertThat(entry.type()).isEqualTo("MAIL");
            assertThat(entry.parentId()).isEqualTo(requestId);
            assertThat(entry.correlationId()).isEqualTo("trace-a");
        });
    }

    @Test
    void mergedReportMergesCapturedKafkaMessagesAsMessagingEntries() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 200, 200);
        recorder.recordProduce("orders", 2, "key-1", null, true, null);
        recorder.recordConsume("orders", 3, 42L, "key-2", 5L, true, null, null, "orders-in");
        LiveActivityResource resource = resourceWithKafka(recorder);

        LiveActivityReport report = resource.mergedReport(0);

        List<ActivityEntryDto> messaging = report.entries().stream()
                .filter(entry -> "MESSAGING".equals(entry.type()))
                .toList();
        assertThat(messaging).hasSize(2);
        assertThat(messaging).allSatisfy(entry -> {
            assertThat(entry.id()).startsWith("kafka-");
            assertThat(entry.correlationId()).isNull();
        });
        // Keys are hashed (SHA-256, truncated to 16 hex chars) before capture: these are
        // hashKey("key-1")/hashKey("key-2").
        assertThat(messaging).anySatisfy(entry -> {
            assertThat(entry.summary()).isEqualTo("→ orders [2]");
            assertThat(entry.detail()).isEqualTo("key=be2974546978e373");
            assertThat(entry.severity()).isEqualTo("OK");
        });
        assertThat(messaging).anySatisfy(entry -> {
            assertThat(entry.summary()).isEqualTo("← orders [3]");
            assertThat(entry.detail()).isEqualTo("key=7c36b0a9dedde119 offset=42");
        });
        assertThat(report.typeCounts()).containsEntry("MESSAGING", 2);
        assertThat(report.sources()).contains("kafka");
    }

    @Test
    void mergedReportHasNoMessagingEntriesWhenNoKafkaCaptured() {
        LiveActivityResource resource = resourceWithKafka(new KafkaActivityRecorder(true, true, 200, 200));

        LiveActivityReport report = resource.mergedReport(0);

        assertThat(report.entries()).noneMatch(entry -> "MESSAGING".equals(entry.type()));
        assertThat(report.typeCounts()).doesNotContainKey("MESSAGING");
        assertThat(report.sources()).contains("kafka");
    }

    @Test
    void mergedReportSortsKafkaEntriesNewestFirst() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 200, 200);
        recorder.recordProduce("orders", 2, "key-1", null, true, null);
        recorder.recordConsume("orders", 3, 42L, "key-2", 5L, true, null, null, "orders-in");
        LiveActivityResource resource = resourceWithKafka(recorder);

        LiveActivityReport report = resource.mergedReport(0);

        // The most-recently captured message (the consume) sorts first.
        assertThat(report.entries()).first().satisfies(entry -> {
            assertThat(entry.type()).isEqualTo("MESSAGING");
            assertThat(entry.summary()).startsWith("←");
        });
    }

    @Test
    void mergedReportAppliesLimitAcrossMergedKafkaEntries() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 200, 200);
        recorder.recordProduce("orders", 0, "a", null, true, null);
        recorder.recordProduce("orders", 1, "b", null, true, null);
        recorder.recordProduce("orders", 2, "c", null, true, null);
        LiveActivityResource resource = resourceWithKafka(recorder);

        LiveActivityReport report = resource.mergedReport(2);

        assertThat(report.entries()).hasSize(2);
        assertThat(report.typeCounts()).containsEntry("MESSAGING", 3);
    }

    @Test
    void mergedReportHasNoMessagingEntriesWhenRecorderDisabled() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(false, true, 200, 200);
        // recordProduce is a no-op while disabled, so nothing is ever captured.
        recorder.recordProduce("orders", 0, "a", null, true, null);
        LiveActivityResource resource = resourceWithKafka(recorder);

        LiveActivityReport report = resource.mergedReport(0);

        assertThat(report.entries()).noneMatch(entry -> "MESSAGING".equals(entry.type()));
        assertThat(report.typeCounts()).doesNotContainKey("MESSAGING");
        assertThat(report.sources()).doesNotContain("kafka");
    }

    @Test
    void useExistingDatasourceReturns404WhenNoDataSourceIsAvailable() {
        LiveActivityResource resource =
                resourceWith(new SwitchableActivityStore(new InMemoryActivityStore(10)), disabledSettings());

        Response response = resource.useExistingDatasource(new ActivitySwitchRequest(true));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(((ActivitySwitchResult) response.getEntity()).status()).isEqualTo("unavailable");
    }

    @Test
    void useExistingDatasourceReturns400WhenNotConfirmed() {
        LiveActivityResource resource = resourceWith(
                new SwitchableActivityStore(new InMemoryActivityStore(10)),
                disabledSettings(),
                satisfiedDataSource(newH2DataSource()));

        Response response = resource.useExistingDatasource(null);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(((ActivitySwitchResult) response.getEntity()).status()).isEqualTo("blocked");
    }

    @Test
    void useExistingDatasourceReturns200AndIsANoOpWhenAlreadyPersistent() {
        SwitchableActivityStore store = persistentStore(new RecordingActivityStore(ActivityPage.EMPTY), "instance-c");
        LiveActivityResource resource =
                resourceWith(store, enabledSettings("instance-c"), satisfiedDataSource(newH2DataSource()));
        try {
            Response response = resource.useExistingDatasource(new ActivitySwitchRequest(true));

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(((ActivitySwitchResult) response.getEntity()).status()).isEqualTo("already-active");
        } finally {
            cleanup(resource, store);
        }
    }

    @Test
    void useExistingDatasourceSwitchesTheStoreAndStartsCapturingOnSuccess() throws Exception {
        DataSource dataSource = newH2DataSource();
        SwitchableActivityStore store = new SwitchableActivityStore(new InMemoryActivityStore(200));
        LiveActivityResource resource = resourceWith(store, disabledSettings(), satisfiedDataSource(dataSource));
        try {
            Response response = resource.useExistingDatasource(new ActivitySwitchRequest(true));

            assertThat(response.getStatus()).isEqualTo(200);
            ActivitySwitchResult body = (ActivitySwitchResult) response.getEntity();
            assertThat(body.status()).isEqualTo("success");
            assertThat(body.tableName()).isEqualTo("bootui_activity");

            // The switch takes effect immediately: a subsequent GET must now report persistence active
            // and start serving pagination from the (now durable) store, with no restart required.
            LiveActivityReport afterSwitch = resource.activity(null, null, null, null, null, null, null, null);
            assertThat(afterSwitch.persistenceOption())
                    .isEqualTo(new ActivityPersistenceOptionDto(true, true, "bootui_activity"));
            assertThat(afterSwitch.pageInfo()).isNotNull();

            // The capture poller this switch starts must be this resource's own, closeable on shutdown
            // exactly as QuarkusActivityCapture's own startup poller would have been had persistence been
            // enabled from the start.
            Thread captureThread = awaitThreadNamed("bootui-activity-capture");
            assertThat(captureThread)
                    .as("capture poller thread should have started after the switch")
                    .isNotNull();
        } finally {
            cleanup(resource, store);
        }
    }

    private static ActivityPersistenceSettings disabledSettings() {
        return new ActivityPersistenceSettings(
                false,
                ActivityPersistenceSettings.DataSourceMode.SHARED,
                null,
                null,
                null,
                null,
                "bootui_activity",
                Duration.ofSeconds(5),
                500,
                Duration.ofDays(7),
                "instance-a",
                Duration.ofSeconds(2));
    }

    private static ActivityPersistenceSettings enabledSettings(String instanceId) {
        return new ActivityPersistenceSettings(
                true,
                ActivityPersistenceSettings.DataSourceMode.SHARED,
                null,
                null,
                null,
                null,
                "bootui_activity",
                Duration.ofSeconds(5),
                500,
                Duration.ofDays(7),
                instanceId,
                Duration.ofSeconds(2));
    }

    /**
     * A {@link SwitchableActivityStore} whose delegate is a real {@link BufferedActivityStore} (so
     * {@link SwitchableActivityStore#persistent()} is genuinely {@code true}) wrapping {@code durable} —
     * a fake, in this test module, since neither class can be mocked. The wrapping hot cache starts
     * empty; entries appended through the returned store are immediately visible via the hot cache side
     * of the real merge-for-reads {@code query()} logic, with no JDBC/H2 involved.
     */
    private static SwitchableActivityStore persistentStore(ActivityStore durable, String instanceId) {
        return new SwitchableActivityStore(new BufferedActivityStore(
                new InMemoryActivityStore(200), durable, Duration.ofSeconds(5), 500, instanceId, Duration.ofDays(7)));
    }

    private static DataSource newH2DataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:live-activity-resource-" + DB_COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    /** Stops any capture poller the test started and closes the store, so no test leaks a live thread. */
    private static void cleanup(LiveActivityResource resource, SwitchableActivityStore store) {
        resource.onStop(null);
        store.close();
    }

    private static LiveActivityResource resourceWith(
            SwitchableActivityStore activityStore, ActivityPersistenceSettings settings) {
        return resourceWith(
                activityStore,
                settings,
                unsatisfiedDataSource(),
                new HttpExchangeBuffer(50),
                unsatisfiedEmailCaptureService(),
                new KafkaActivityRecorder(true, true, 200, 200),
                config(Map.of()));
    }

    private static LiveActivityResource resourceWithKafka(KafkaActivityRecorder kafkaRecorder) {
        return resourceWith(
                new SwitchableActivityStore(new InMemoryActivityStore(10)),
                disabledSettings(),
                unsatisfiedDataSource(),
                new HttpExchangeBuffer(50),
                unsatisfiedEmailCaptureService(),
                kafkaRecorder,
                config(Map.of()));
    }

    private static LiveActivityResource resourceWith(
            SwitchableActivityStore activityStore,
            ActivityPersistenceSettings settings,
            Instance<DataSource> dataSources) {
        return resourceWith(
                activityStore,
                settings,
                dataSources,
                new HttpExchangeBuffer(50),
                unsatisfiedEmailCaptureService(),
                new KafkaActivityRecorder(true, true, 200, 200),
                config(Map.of()));
    }

    private static LiveActivityResource resourceWith(
            SwitchableActivityStore activityStore,
            ActivityPersistenceSettings settings,
            Instance<DataSource> dataSources,
            HttpExchangeBuffer buffer,
            Instance<EmailCaptureService> emailCaptureService,
            KafkaActivityRecorder kafkaRecorder,
            SmallRyeConfig config) {
        return new LiveActivityResource(
                buffer,
                new QuarkusExposurePolicy(config),
                unsatisfiedSqlTraceRecorder(),
                new ExceptionStore(10, 10, 10),
                new ExceptionsService(new QuarkusExposurePolicy(config)),
                emailCaptureService,
                new SecurityEventBuffer(10),
                new ScheduledTaskRunStore(10),
                new QuarkusPanelAvailability(config),
                null, // TracesService: unused by activity()/mergedReport(), only by the request() drill-down
                activityStore,
                settings,
                dataSources,
                kafkaRecorder);
    }

    private static SmallRyeConfig config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
    }

    private static Instance<SqlTraceRecorder> unsatisfiedSqlTraceRecorder() {
        return new UnsatisfiedInstance<>();
    }

    private static Instance<DataSource> unsatisfiedDataSource() {
        return new UnsatisfiedInstance<>();
    }

    private static Instance<DataSource> satisfiedDataSource(DataSource dataSource) {
        return new SatisfiedInstance<>(dataSource);
    }

    private static Instance<EmailCaptureService> unsatisfiedEmailCaptureService() {
        return new UnsatisfiedInstance<>();
    }

    private static Instance<EmailCaptureService> satisfiedEmailCaptureService(EmailCaptureService service) {
        return new SatisfiedInstance<>(service);
    }

    private static Thread awaitThreadNamed(String name) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (name.equals(thread.getName())) {
                    return thread;
                }
            }
            Thread.sleep(10);
        }
        return null;
    }

    /** Records the last {@link ActivityQuery} it was asked and answers a fixed page, like a test spy. */
    private static final class RecordingActivityStore implements ActivityStore {

        private final ActivityPage page;
        private ActivityQuery lastQuery;

        RecordingActivityStore(ActivityPage page) {
            this.page = page;
        }

        @Override
        public void appendBatch(List<StoredActivityEntry> entries) {
            // No-op: BufferedActivityStore.close()'s bounded final flush calls this synchronously during
            // test cleanup once entries are pending; this fake durable does not need to actually persist
            // them since these tests only assert on the hot-cache side of the merge.
        }

        @Override
        public ActivityPage query(ActivityQuery query) {
            this.lastQuery = query;
            return page;
        }
    }

    /**
     * A minimal always-unsatisfied {@link Instance}, standing in for an absent {@code SqlTraceRecorder}
     * bean (no datasource configured). No Mockito dependency exists in this module, and CDI {@link
     * Instance} resolution glue is otherwise proven end-to-end by {@code @QuarkusTest} integration
     * coverage rather than mocked here, matching this module's established practice.
     */
    private static final class UnsatisfiedInstance<T> implements Instance<T> {

        @Override
        public Instance<T> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return true;
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public void destroy(T instance) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instance.Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Instance.Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<T> iterator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public T get() {
            throw new UnsatisfiedResolutionException("no SqlTraceRecorder bean produced in this test");
        }
    }

    /**
     * A minimal always-satisfied {@link Instance} wrapping a fixed value, standing in for a present
     * {@code DataSource} bean (a configured datasource). See {@link UnsatisfiedInstance} for why this
     * module hand-rolls {@link Instance} fakes rather than using Mockito.
     */
    private static final class SatisfiedInstance<T> implements Instance<T> {

        private final T value;

        SatisfiedInstance(T value) {
            this.value = value;
        }

        @Override
        public Instance<T> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return false;
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public void destroy(T instance) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instance.Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Instance.Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<T> iterator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public T get() {
            return value;
        }
    }
}
