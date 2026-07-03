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
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.engine.activity.ActivityPage;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.ActivityQuery;
import io.github.jdubois.bootui.engine.activity.ActivityStore;
import io.github.jdubois.bootui.engine.activity.StoredActivityEntry;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class LiveActivityControllerTests {

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
    void activityIsUnaffectedByPersistenceBeansWhenAbsent() {
        // With no ActivityStore/ActivityPersistenceSettings beans (persistence off, the default), the
        // response must carry no page info at all — byte-identical to today's behavior.
        LiveActivityReport result = controller(new BootUiProperties()).activity(null, null, 0, 0, null, null, null, 0);

        assertThat(result.pageInfo()).isNull();
    }

    @Test
    void activityDelegatesEntriesAndPageInfoToStoreWhenPersistenceEnabled() throws Exception {
        ActivityStore store = mock(ActivityStore.class);
        ActivityEntryDto storedEntry = new ActivityEntryDto(
                "sql-1", "SQL", 1_000L, "OK", "select 1", null, null, null, null, null, null, null, false, null, null);
        ActivityPage page =
                new ActivityPage(List.of(new StoredActivityEntry("instance-a", 1L, storedEntry)), "cursor-2", true);
        when(store.query(any())).thenReturn(page);
        ActivityPersistenceSettings settings = persistenceSettings("instance-a", Duration.ofSeconds(2));

        LiveActivityController controller = controllerWith(
                empty(SqlTraceRecorder.class),
                empty(ExceptionStore.class),
                provider(store),
                provider(settings),
                new BootUiProperties());
        try {
            LiveActivityReport expectedLive = referenceLiveReport("SQL", "OK", 0, 0, new BootUiProperties());

            LiveActivityReport result = controller.activity("SQL", "OK", 0, 0, "select", 999L, "cursor-1", 50);

            assertThat(result.entries()).containsExactly(storedEntry);
            assertThat(result.pageInfo()).isEqualTo(new ActivityPageInfo(true, "cursor-2", true));
            // The KPI strip stays a "right now" summary from the live re-merge, not scoped to whichever
            // historical page is being browsed.
            assertThat(result.available()).isEqualTo(expectedLive.available());
            assertThat(result.typeCounts()).isEqualTo(expectedLive.typeCounts());
            assertThat(result.kpis()).isEqualTo(expectedLive.kpis());
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
        ActivityStore store = mock(ActivityStore.class);
        when(store.query(any())).thenReturn(ActivityPage.EMPTY);
        ActivityPersistenceSettings settings = persistenceSettings("instance-b", Duration.ofMillis(50));

        LiveActivityController controller = controllerWith(
                empty(SqlTraceRecorder.class),
                empty(ExceptionStore.class),
                provider(store),
                provider(settings),
                new BootUiProperties());

        Thread captureThread = awaitThreadNamed("bootui-activity-capture");
        assertThat(captureThread)
                .as("capture poller thread should have started")
                .isNotNull();

        controller.shutdown();

        assertThat(awaitNotAlive(captureThread)).isTrue();
    }

    private static ActivityPersistenceSettings persistenceSettings(String instanceId, Duration captureInterval) {
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
                empty(ActivityStore.class),
                empty(ActivityPersistenceSettings.class),
                properties);
    }

    private static LiveActivityController controllerWith(
            ObjectProvider<SqlTraceRecorder> recorder,
            ObjectProvider<ExceptionStore> exceptionStore,
            ObjectProvider<ActivityStore> activityStore,
            ObjectProvider<ActivityPersistenceSettings> persistenceSettings,
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
                activityStore,
                persistenceSettings,
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
