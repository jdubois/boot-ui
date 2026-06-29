package io.github.jdubois.bootui.autoconfigure.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.exceptions.ExceptionsController;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceController;
import io.github.jdubois.bootui.autoconfigure.web.HealthController;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangesController;
import io.github.jdubois.bootui.autoconfigure.web.SecurityLogsController;
import io.github.jdubois.bootui.autoconfigure.web.TracesController;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class LiveActivityControllerTests {

    @Test
    void streamOpensAnSseEmitter() {
        assertThat(controller(new BootUiProperties()).stream()).isNotNull();
    }

    @Test
    void shutdownClosesChangeStreamAndTerminatesSchedulerThread() throws Exception {
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, true, 100, 100, 2000, 200, 5);
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
