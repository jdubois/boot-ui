package io.github.jdubois.bootui.autoconfigure.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.exceptions.ExceptionsController;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceController;
import io.github.jdubois.bootui.autoconfigure.web.HealthController;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangesController;
import io.github.jdubois.bootui.autoconfigure.web.SecurityLogsController;
import io.github.jdubois.bootui.autoconfigure.web.TracesController;
import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ActivityPageInfo;
import io.github.jdubois.bootui.core.dto.ActivityPersistenceOptionDto;
import io.github.jdubois.bootui.core.dto.ActivitySwitchRequest;
import io.github.jdubois.bootui.core.dto.ActivitySwitchResult;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.engine.activity.ActivityPage;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.ActivityQuery;
import io.github.jdubois.bootui.engine.activity.InMemoryActivityStore;
import io.github.jdubois.bootui.engine.activity.StoredActivityEntry;
import io.github.jdubois.bootui.engine.activity.SwitchableActivityStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

class LiveActivityControllerTests {

    private static final AtomicInteger DB_COUNTER = new AtomicInteger();

    @Test
    void streamOpensAnSseEmitter() {
        assertThat(controller(new BootUiProperties()).stream()).isNotNull();
    }

    @Test
    void shutdownClosesChangeStreamAndTerminatesSchedulerThread() throws Exception {
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, true, false, 100, 100, 2000, 200, 5);
        LiveActivityController controller = controllerWith(provider(recorder), empty(ExceptionStore.class));

        controller.stream(); // open an SSE emitter so a signal schedules a flush
        java.util.Set<Thread> before = streamThreads();
        recorder.clear(); // notifies the subscribed change stream → schedules a flush → starts its thread

        Thread scheduler = awaitNewStreamThread(before);
        assertThat(scheduler).as("scheduler thread should have started").isNotNull();

        controller.shutdown();

        // close() shuts the scheduler down so a DevTools restart does not leak one daemon thread
        // (and, through it, the discarded context's class loader) per live reload.
        assertThat(awaitNotAlive(scheduler)).isTrue();
    }

    @Test
    void activityCarriesNoPageInfoAndAnInactivePersistenceOptionWhenNotPersistent() {
        // The store bean always exists (even with persistence disabled, as a bare in-memory store), so
        // the response must carry no page info at all - byte-identical to today's behavior - while still
        // always reporting a persistenceOption so the panel can render its "Use a database" affordance.
        LiveActivityReport result = controller(new BootUiProperties()).activity(null, null, 0, 0, null, null, null, 0);

        assertThat(result.pageInfo()).isNull();
        assertThat(result.persistenceOption())
                .isEqualTo(new ActivityPersistenceOptionDto(false, false, "bootui_activity"));
    }

    @Test
    void activityReportsADataSourceAsAvailableWhenOneIsPresentEvenWithPersistenceOff() {
        LiveActivityController controller = controllerWith(
                empty(SqlTraceRecorder.class),
                empty(ExceptionStore.class),
                defaultActivityStore(),
                disabledSettings(),
                provider(mock(DataSource.class)),
                new BootUiProperties());

        LiveActivityReport result = controller.activity(null, null, 0, 0, null, null, null, 0);

        assertThat(result.persistenceOption())
                .isEqualTo(new ActivityPersistenceOptionDto(false, true, "bootui_activity"));
    }

    @Test
    void activityDelegatesEntriesAndPageInfoToStoreWhenPersistenceEnabled() throws Exception {
        SwitchableActivityStore store = mock(SwitchableActivityStore.class);
        when(store.persistent()).thenReturn(true);
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
        ActivityPage page =
                new ActivityPage(List.of(new StoredActivityEntry("instance-a", 1L, storedEntry)), "cursor-2", true);
        when(store.query(any())).thenReturn(page);
        ActivityPersistenceSettings settings = enabledSettings("instance-a", Duration.ofSeconds(2));

        LiveActivityController controller = controllerWith(
                empty(SqlTraceRecorder.class),
                empty(ExceptionStore.class),
                store,
                settings,
                empty(DataSource.class),
                new BootUiProperties());
        try {
            LiveActivityReport expectedLive = referenceLiveReport("SQL", "OK", 0, 0, new BootUiProperties());

            LiveActivityReport result = controller.activity("SQL", "OK", 0, 0, "select", 999L, "cursor-1", 50);

            assertThat(result.entries()).containsExactly(storedEntry);
            assertThat(result.pageInfo()).isEqualTo(new ActivityPageInfo(true, "cursor-2", true));
            assertThat(result.persistenceOption())
                    .isEqualTo(new ActivityPersistenceOptionDto(true, false, "bootui_activity"));
            // The KPI strip stays a "right now" summary from the live re-merge, not scoped to whichever
            // historical page is being browsed.
            assertThat(result.available()).isEqualTo(expectedLive.available());
            assertThat(result.typeCounts()).isEqualTo(expectedLive.typeCounts());
            // heapUsedBytes is sampled live at call time via ManagementFactory, so it can drift by a few
            // bytes between the two independent report() invocations (this one and referenceLiveReport's)
            // even a few instructions apart - compare everything else exactly and only sanity-check heap.
            assertThat(result.kpis())
                    .usingRecursiveComparison()
                    .ignoringFields("heapUsedBytes", "heapMaxBytes")
                    .isEqualTo(expectedLive.kpis());
            assertThat(result.kpis().heapUsedBytes()).isNotNull().isPositive();
            assertThat(result.kpis().heapMaxBytes()).isNotNull().isPositive();
            assertThat(result.sources()).isEqualTo(expectedLive.sources());
            assertThat(result.warnings()).isEqualTo(expectedLive.warnings());

            ArgumentCaptor<ActivityQuery> captor = ArgumentCaptor.forClass(ActivityQuery.class);
            verify(store).query(captor.capture());
            ActivityQuery query = captor.getValue();
            assertThat(query.instanceId()).isEqualTo("instance-a");
            assertThat(query.type()).isEqualTo("SQL");
            assertThat(query.severity()).isEqualTo("OK");
            assertThat(query.text()).isEqualTo("select");
            // since=0 is the existing "no lower bound" convention; the query must translate that to
            // ActivityQuery's own null-means-unbounded convention rather than filtering on since<=0.
            assertThat(query.since()).isNull();
            assertThat(query.until()).isEqualTo(999L);
            assertThat(query.cursor()).isEqualTo("cursor-1");
            assertThat(query.pageSize()).isEqualTo(50);
        } finally {
            controller.shutdown();
        }
    }

    @Test
    void shutdownStopsCapturePollerThreadWhenPersistenceEnabled() throws Exception {
        SwitchableActivityStore store = mock(SwitchableActivityStore.class);
        when(store.persistent()).thenReturn(true);
        when(store.query(any())).thenReturn(ActivityPage.EMPTY);
        ActivityPersistenceSettings settings = enabledSettings("instance-b", Duration.ofMillis(50));

        LiveActivityController controller = controllerWith(
                empty(SqlTraceRecorder.class),
                empty(ExceptionStore.class),
                store,
                settings,
                empty(DataSource.class),
                new BootUiProperties());

        Thread captureThread = awaitThreadNamed("bootui-activity-capture");
        assertThat(captureThread)
                .as("capture poller thread should have started")
                .isNotNull();

        controller.shutdown();

        assertThat(awaitNotAlive(captureThread)).isTrue();
    }

    @Test
    void useExistingDatasourceReturns404WhenNoDataSourceIsAvailable() {
        LiveActivityController controller = controllerWith(
                empty(SqlTraceRecorder.class),
                empty(ExceptionStore.class),
                defaultActivityStore(),
                disabledSettings(),
                empty(DataSource.class),
                new BootUiProperties());

        ResponseEntity<ActivitySwitchResult> response =
                controller.useExistingDatasource(new ActivitySwitchRequest(true));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().status()).isEqualTo("unavailable");
    }

    @Test
    void useExistingDatasourceReturns400WhenNotConfirmed() {
        LiveActivityController controller = controllerWith(
                empty(SqlTraceRecorder.class),
                empty(ExceptionStore.class),
                defaultActivityStore(),
                disabledSettings(),
                provider(mock(DataSource.class)),
                new BootUiProperties());

        ResponseEntity<ActivitySwitchResult> response = controller.useExistingDatasource(null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().status()).isEqualTo("blocked");
    }

    @Test
    void useExistingDatasourceReturns200AndIsANoOpWhenAlreadyPersistent() {
        SwitchableActivityStore store = mock(SwitchableActivityStore.class);
        when(store.persistent()).thenReturn(true);
        LiveActivityController controller = controllerWith(
                empty(SqlTraceRecorder.class),
                empty(ExceptionStore.class),
                store,
                enabledSettings("instance-c", Duration.ofSeconds(5)),
                provider(mock(DataSource.class)),
                new BootUiProperties());
        try {
            ResponseEntity<ActivitySwitchResult> response =
                    controller.useExistingDatasource(new ActivitySwitchRequest(true));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().status()).isEqualTo("already-active");
        } finally {
            controller.shutdown();
        }
    }

    @Test
    void useExistingDatasourceSwitchesTheStoreAndStartsCapturingOnSuccess() throws Exception {
        DataSource dataSource = newDataSource();
        LiveActivityController controller = controllerWith(
                empty(SqlTraceRecorder.class),
                empty(ExceptionStore.class),
                defaultActivityStore(),
                disabledSettings(),
                provider(dataSource),
                new BootUiProperties());
        try {
            ResponseEntity<ActivitySwitchResult> response =
                    controller.useExistingDatasource(new ActivitySwitchRequest(true));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().status()).isEqualTo("success");
            assertThat(response.getBody().tableName()).isEqualTo("bootui_activity");

            // The switch takes effect immediately: a subsequent GET must now report persistence active
            // and start serving pagination from the (now durable) store, with no restart required.
            LiveActivityReport afterSwitch = controller.activity(null, null, 0, 0, null, null, null, 0);
            assertThat(afterSwitch.persistenceOption())
                    .isEqualTo(new ActivityPersistenceOptionDto(true, true, "bootui_activity"));
            assertThat(afterSwitch.pageInfo()).isNotNull();

            // The capture poller this switch starts must be the controller's own, closeable on shutdown
            // exactly like the constructor-time poller.
            Thread captureThread = awaitThreadNamed("bootui-activity-capture");
            assertThat(captureThread)
                    .as("capture poller thread should have started after the switch")
                    .isNotNull();
        } finally {
            controller.shutdown();
        }
    }

    private static DataSource newDataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:live-activity-controller-" + DB_COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static SwitchableActivityStore defaultActivityStore() {
        return new SwitchableActivityStore(new InMemoryActivityStore(200));
    }

    private static ActivityPersistenceSettings disabledSettings() {
        return persistenceSettings(false, "instance-x", Duration.ofSeconds(5));
    }

    private static ActivityPersistenceSettings enabledSettings(String instanceId, Duration captureInterval) {
        return persistenceSettings(true, instanceId, captureInterval);
    }

    private static ActivityPersistenceSettings persistenceSettings(
            boolean enabled, String instanceId, Duration captureInterval) {
        return new ActivityPersistenceSettings(
                enabled,
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
                captureInterval);
    }

    private static LiveActivityReport referenceLiveReport(
            String type, String severity, long since, int limit, BootUiProperties properties) {
        LiveActivityService service = new LiveActivityService(
                empty(HttpExchangesController.class),
                empty(SqlTraceController.class),
                empty(ExceptionsController.class),
                empty(SecurityLogsController.class),
                empty(HealthController.class),
                empty(RequestCorrelationRegistry.class),
                empty(SecurityEventCorrelationRegistry.class),
                empty(io.github.jdubois.bootui.engine.cache.CacheActivityRecorder.class),
                empty(io.github.jdubois.bootui.engine.scheduled.ScheduledTaskRunStore.class),
                properties);
        return service.report(type, severity, since, limit);
    }

    private static java.util.Set<Thread> streamThreads() {
        java.util.Set<Thread> threads = new java.util.HashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if ("bootui-activity-stream".equals(thread.getName())) {
                threads.add(thread);
            }
        }
        return threads;
    }

    private static Thread awaitNewStreamThread(java.util.Set<Thread> before) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            java.util.Set<Thread> now = streamThreads();
            now.removeAll(before);
            if (!now.isEmpty()) {
                return now.iterator().next();
            }
            Thread.sleep(10);
        }
        return null;
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

    private static boolean awaitNotAlive(Thread thread) throws InterruptedException {
        for (int i = 0; i < 100 && thread.isAlive(); i++) {
            Thread.sleep(10);
        }
        return !thread.isAlive();
    }

    @Test
    void hostRequestExcludesBootUiOwnTrafficToAvoidRefreshLoop() {
        // BootUI's own re-fetches and SSE connection must not re-trigger the feed.
        assertThat(LiveActivityController.isHostRequest("/bootui/api/activity", "/bootui"))
                .isFalse();
        assertThat(LiveActivityController.isHostRequest("/bootui/api/activity/stream", "/bootui"))
                .isFalse();
        // Honors a custom base path.
        assertThat(LiveActivityController.isHostRequest("/app/console/api/activity", "/app/console"))
                .isFalse();
    }

    @Test
    void hostRequestSignalsForApplicationTrafficAndNullUrls() {
        assertThat(LiveActivityController.isHostRequest("/api/sample/products", "/bootui"))
                .isTrue();
        assertThat(LiveActivityController.isHostRequest(null, "/bootui")).isTrue();
    }

    private static LiveActivityController controller(BootUiProperties properties) {
        return controllerWith(empty(SqlTraceRecorder.class), empty(ExceptionStore.class), properties);
    }

    private static LiveActivityController controllerWith(
            ObjectProvider<SqlTraceRecorder> recorder, ObjectProvider<ExceptionStore> exceptionStore) {
        return controllerWith(recorder, exceptionStore, new BootUiProperties());
    }

    private static LiveActivityController controllerWith(
            ObjectProvider<SqlTraceRecorder> recorder,
            ObjectProvider<ExceptionStore> exceptionStore,
            BootUiProperties properties) {
        return controllerWith(
                recorder,
                exceptionStore,
                defaultActivityStore(),
                disabledSettings(),
                empty(DataSource.class),
                properties);
    }

    private static LiveActivityController controllerWith(
            ObjectProvider<SqlTraceRecorder> recorder,
            ObjectProvider<ExceptionStore> exceptionStore,
            SwitchableActivityStore activityStore,
            ActivityPersistenceSettings persistenceSettings,
            ObjectProvider<DataSource> dataSourceProvider,
            BootUiProperties properties) {
        return new LiveActivityController(
                empty(HttpExchangesController.class),
                empty(SqlTraceController.class),
                empty(ExceptionsController.class),
                empty(SecurityLogsController.class),
                empty(TracesController.class),
                empty(HealthController.class),
                recorder,
                exceptionStore,
                empty(RequestCorrelationRegistry.class),
                empty(SecurityEventCorrelationRegistry.class),
                empty(io.github.jdubois.bootui.engine.cache.CacheActivityRecorder.class),
                empty(io.github.jdubois.bootui.engine.scheduled.ScheduledTaskRunStore.class),
                activityStore,
                persistenceSettings,
                dataSourceProvider,
                properties);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> empty(Class<T> type) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
