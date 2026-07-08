package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.web.HealthController;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangesController;
import io.github.jdubois.bootui.autoconfigure.web.TracesController;
import io.github.jdubois.bootui.core.dto.ActivityPersistenceOptionDto;
import io.github.jdubois.bootui.core.dto.ActivitySwitchRequest;
import io.github.jdubois.bootui.core.dto.ActivitySwitchResult;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.core.dto.PageMetadata;
import io.github.jdubois.bootui.core.dto.RequestProfileDto;
import io.github.jdubois.bootui.core.dto.SecurityLogsReport;
import io.github.jdubois.bootui.core.dto.TraceDetailDto;
import io.github.jdubois.bootui.engine.activity.ActivityPage;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.ActivityQuery;
import io.github.jdubois.bootui.engine.activity.InMemoryActivityStore;
import io.github.jdubois.bootui.engine.activity.SwitchableActivityStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import reactor.test.StepVerifier;

/**
 * Tests for {@link ReactiveLiveActivityController}. The merge/correlation math itself is already
 * covered by {@code LiveActivityAssemblerTests} and {@code RequestProfileAssemblerTests} (shared with
 * Quarkus); this suite focuses on what is genuinely new here: gathering signals directly from the
 * reactive/shared controllers with each source's own {@code bootui.panels.*} enablement re-checked
 * (since bypassing HTTP also bypasses {@code ReactivePanelAccessFilter}), the persistence
 * switch/pagination contract (ported unchanged from the servlet controller), and the SSE lifecycle.
 */
class ReactiveLiveActivityControllerTests {

    private static final AtomicInteger DB_COUNTER = new AtomicInteger();

    @Test
    void streamOpensAnSseEmitter() {
        assertThat(controller(new BootUiProperties()).stream()).isNotNull();
    }

    @Test
    void signalRequestHandledPushesAnUpdateOnTheOpenStream() {
        ReactiveLiveActivityController controller = controller(new BootUiProperties());

        StepVerifier.create(controller.stream())
                .then(controller::signalRequestHandled)
                .assertNext(sse -> assertThat(sse.event()).isEqualTo("update"))
                .thenCancel()
                .verify(Duration.ofSeconds(3));
    }

    @Test
    void shutdownStopsCapturePollerThreadWhenPersistenceEnabled() throws Exception {
        SwitchableActivityStore store = mock(SwitchableActivityStore.class);
        when(store.persistent()).thenReturn(true);
        when(store.query(any())).thenReturn(ActivityPage.EMPTY);
        ActivityPersistenceSettings settings = enabledSettings("instance-reactive-b", Duration.ofMillis(50));

        ReactiveLiveActivityController controller = controllerWith(
                empty(SqlTraceRecorder.class),
                empty(RestClientTraceRecorder.class),
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
    void activityCarriesNoPageInfoAndAnInactivePersistenceOptionWhenNotPersistent() {
        LiveActivityReport result = controller(new BootUiProperties()).activity(null, null, 0, 0, null, null, null, 0);

        assertThat(result.pageInfo()).isNull();
        assertThat(result.persistenceOption())
                .isEqualTo(new ActivityPersistenceOptionDto(false, false, "bootui_activity"));
    }

    @Test
    void activityReportsADataSourceAsAvailableWhenOneIsPresentEvenWithPersistenceOff() {
        ReactiveLiveActivityController controller = controllerWith(
                empty(SqlTraceRecorder.class),
                empty(RestClientTraceRecorder.class),
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
        when(store.query(any())).thenReturn(ActivityPage.EMPTY);
        ActivityPersistenceSettings settings = enabledSettings("instance-reactive-a", Duration.ofSeconds(2));

        ReactiveLiveActivityController controller = controllerWith(
                empty(SqlTraceRecorder.class),
                empty(RestClientTraceRecorder.class),
                empty(ExceptionStore.class),
                store,
                settings,
                empty(DataSource.class),
                new BootUiProperties());
        try {
            LiveActivityReport result = controller.activity("SQL", "OK", 0, 0, "select", 999L, "cursor-1", 50);

            assertThat(result.pageInfo())
                    .isEqualTo(new io.github.jdubois.bootui.core.dto.ActivityPageInfo(
                            true, ActivityPage.EMPTY.nextCursor(), ActivityPage.EMPTY.hasMore()));
            assertThat(result.persistenceOption())
                    .isEqualTo(new ActivityPersistenceOptionDto(true, false, "bootui_activity"));

            ArgumentCaptor<ActivityQuery> captor = ArgumentCaptor.forClass(ActivityQuery.class);
            verify(store).query(captor.capture());
            ActivityQuery query = captor.getValue();
            assertThat(query.instanceId()).isEqualTo("instance-reactive-a");
            assertThat(query.type()).isEqualTo("SQL");
            assertThat(query.severity()).isEqualTo("OK");
            assertThat(query.text()).isEqualTo("select");
            assertThat(query.since()).isNull();
            assertThat(query.until()).isEqualTo(999L);
            assertThat(query.cursor()).isEqualTo("cursor-1");
            assertThat(query.pageSize()).isEqualTo(50);
        } finally {
            controller.shutdown();
        }
    }

    @Test
    void useExistingDatasourceReturns404WhenNoDataSourceIsAvailable() {
        ReactiveLiveActivityController controller = controllerWith(
                empty(SqlTraceRecorder.class),
                empty(RestClientTraceRecorder.class),
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
        ReactiveLiveActivityController controller = controllerWith(
                empty(SqlTraceRecorder.class),
                empty(RestClientTraceRecorder.class),
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
        ReactiveLiveActivityController controller = controllerWith(
                empty(SqlTraceRecorder.class),
                empty(RestClientTraceRecorder.class),
                empty(ExceptionStore.class),
                store,
                enabledSettings("instance-reactive-c", Duration.ofSeconds(5)),
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
        ReactiveLiveActivityController controller = controllerWith(
                empty(SqlTraceRecorder.class),
                empty(RestClientTraceRecorder.class),
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

            LiveActivityReport afterSwitch = controller.activity(null, null, 0, 0, null, null, null, 0);
            assertThat(afterSwitch.persistenceOption())
                    .isEqualTo(new ActivityPersistenceOptionDto(true, true, "bootui_activity"));
            assertThat(afterSwitch.pageInfo()).isNotNull();

            Thread captureThread = awaitThreadNamed("bootui-activity-capture");
            assertThat(captureThread)
                    .as("capture poller thread should have started after the switch")
                    .isNotNull();
        } finally {
            controller.shutdown();
        }
    }

    @Test
    void mergedReportCombinesRequestsAndExceptionsAndMarksTracedRequestsProfileable() {
        HttpExchangesController httpExchanges = mock(HttpExchangesController.class);
        HttpExchangeDto tracedExchange = exchange("req-1", "GET", "/api/products", 200, "trace-abc");
        HttpExchangeDto untracedExchange = exchange("req-2", "GET", "/api/orders", 200, null);
        when(httpExchanges.exchanges(null, null, null, null, null))
                .thenReturn(new HttpExchangesReport(
                        2,
                        2,
                        0,
                        List.of(tracedExchange, untracedExchange),
                        new PageMetadata(0, 0, 0, 0, 0, false),
                        null));

        ExceptionStore exceptionStore = new ExceptionStore(50, 20, 30);
        exceptionStore.record(
                new IllegalStateException("boom"), "reactor-http-nio-1", "GET", "/api/products", null, "test");

        BootUiProperties properties = new BootUiProperties();
        ReactiveLiveActivityController controller = new ReactiveLiveActivityController(
                provider(httpExchanges),
                empty(SqlTraceRecorder.class),
                empty(RestClientTraceRecorder.class),
                empty(DataSource.class),
                provider(exceptionStore),
                empty(ReactiveSecurityLogsController.class),
                empty(TracesController.class),
                empty(HealthController.class),
                defaultActivityStore(),
                disabledSettings(),
                properties,
                new BootUiExposure(properties));

        LiveActivityReport report = controller.mergedReport(0);

        assertThat(report.entries()).hasSize(3); // 2 requests + 1 exception
        assertThat(report.entries())
                .filteredOn(entry -> "req-1".equals(entry.id()))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.correlationId()).isEqualTo("trace-abc");
                    assertThat(entry.profileable()).isTrue();
                });
        assertThat(report.entries())
                .filteredOn(entry -> "req-2".equals(entry.id()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.profileable()).isFalse());
        assertThat(report.entries())
                .anySatisfy(entry -> assertThat(entry.type()).isEqualTo("EXCEPTION"));
    }

    @Test
    void mergedReportOmitsRequestsWhenHttpExchangesPanelIsDisabled() {
        HttpExchangesController httpExchanges = mock(HttpExchangesController.class);
        when(httpExchanges.exchanges(null, null, null, null, null))
                .thenReturn(new HttpExchangesReport(
                        1,
                        1,
                        0,
                        List.of(exchange("req-1", "GET", "/api/products", 200, "trace-abc")),
                        new PageMetadata(0, 0, 0, 0, 0, false),
                        null));

        BootUiProperties properties = new BootUiProperties();
        properties.panel(BootUiPanels.HTTP_EXCHANGES).setEnabled(false);
        ReactiveLiveActivityController controller = new ReactiveLiveActivityController(
                provider(httpExchanges),
                empty(SqlTraceRecorder.class),
                empty(RestClientTraceRecorder.class),
                empty(DataSource.class),
                empty(ExceptionStore.class),
                empty(ReactiveSecurityLogsController.class),
                empty(TracesController.class),
                empty(HealthController.class),
                defaultActivityStore(),
                disabledSettings(),
                properties,
                new BootUiExposure(properties));

        assertThat(controller.mergedReport(0).entries()).isEmpty();
    }

    @Test
    void mergedReportOmitsExceptionsWhenExceptionsPanelIsDisabled() {
        ExceptionStore exceptionStore = new ExceptionStore(50, 20, 30);
        exceptionStore.record(
                new IllegalStateException("boom"), "reactor-http-nio-1", "GET", "/api/products", null, "test");

        BootUiProperties properties = new BootUiProperties();
        properties.panel(BootUiPanels.EXCEPTIONS).setEnabled(false);
        ReactiveLiveActivityController controller = new ReactiveLiveActivityController(
                empty(HttpExchangesController.class),
                empty(SqlTraceRecorder.class),
                empty(RestClientTraceRecorder.class),
                empty(DataSource.class),
                provider(exceptionStore),
                empty(ReactiveSecurityLogsController.class),
                empty(TracesController.class),
                empty(HealthController.class),
                defaultActivityStore(),
                disabledSettings(),
                properties,
                new BootUiExposure(properties));

        assertThat(controller.mergedReport(0).entries()).isEmpty();
    }

    @Test
    void mergedReportOmitsSecurityEventsWhenSecurityLogsPanelIsDisabled() {
        ReactiveSecurityLogsController securityLogs = mock(ReactiveSecurityLogsController.class);
        when(securityLogs.logs(null, null, null, null, null))
                .thenReturn(new SecurityLogsReport(true, null, 100, List.of(), List.of(), null));

        BootUiProperties properties = new BootUiProperties();
        properties.panel(BootUiPanels.SECURITY_LOGS).setEnabled(false);
        ReactiveLiveActivityController controller = new ReactiveLiveActivityController(
                empty(HttpExchangesController.class),
                empty(SqlTraceRecorder.class),
                empty(RestClientTraceRecorder.class),
                empty(DataSource.class),
                empty(ExceptionStore.class),
                provider(securityLogs),
                empty(TracesController.class),
                empty(HealthController.class),
                defaultActivityStore(),
                disabledSettings(),
                properties,
                new BootUiExposure(properties));

        // logs() must never even be called once the panel is disabled.
        controller.mergedReport(0);
        org.mockito.Mockito.verifyNoInteractions(securityLogs);
    }

    @Test
    void mergedReportMergesRestClientTraceEntriesWhenAvailable() {
        HttpExchangesController httpExchanges = mock(HttpExchangesController.class);
        HttpExchangeDto tracedExchange = exchange("req-1", "GET", "/api/products", 200, "trace-abc");
        when(httpExchanges.exchanges(null, null, null, null, null))
                .thenReturn(new HttpExchangesReport(
                        1, 1, 0, List.of(tracedExchange), new PageMetadata(0, 0, 0, 0, 0, false), null));
        RestClientTraceRecorder recorder =
                new RestClientTraceRecorder(true, true, false, false, 10, 1_000, 2_000, 200, 5);
        recorder.setTraceIdProvider(() -> "trace-abc");
        recorder.record(
                "GET",
                "https://api.example.com/orders",
                "api.example.com",
                "/orders",
                200,
                12,
                true,
                null,
                "WebClient",
                Map.of(),
                "reactor-http-nio-1");

        BootUiProperties properties = new BootUiProperties();
        ReactiveLiveActivityController controller = new ReactiveLiveActivityController(
                provider(httpExchanges),
                empty(SqlTraceRecorder.class),
                provider(recorder),
                empty(DataSource.class),
                empty(ExceptionStore.class),
                empty(ReactiveSecurityLogsController.class),
                empty(TracesController.class),
                empty(HealthController.class),
                defaultActivityStore(),
                disabledSettings(),
                properties,
                new BootUiExposure(properties));

        LiveActivityReport report = controller.mergedReport(0);

        assertThat(report.sources()).contains("rest-client");
        assertThat(report.entries())
                .filteredOn(entry -> "rest-1".equals(entry.id()))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.type()).isEqualTo("REST_CLIENT");
                    assertThat(entry.parentId()).isEqualTo("req-1");
                    assertThat(entry.summary()).isEqualTo("GET api.example.com/orders → 200");
                });
    }

    @Test
    void mergedReportSkipsHealthStatusWhenHealthPanelIsDisabled() {
        HealthController health = mock(HealthController.class);
        BootUiProperties properties = new BootUiProperties();
        properties.panel(BootUiPanels.HEALTH).setEnabled(false);
        ReactiveLiveActivityController controller = new ReactiveLiveActivityController(
                empty(HttpExchangesController.class),
                empty(SqlTraceRecorder.class),
                empty(RestClientTraceRecorder.class),
                empty(DataSource.class),
                empty(ExceptionStore.class),
                empty(ReactiveSecurityLogsController.class),
                empty(TracesController.class),
                provider(health),
                defaultActivityStore(),
                disabledSettings(),
                properties,
                new BootUiExposure(properties));

        controller.mergedReport(0);

        org.mockito.Mockito.verifyNoInteractions(health);
    }

    @Test
    void requestReturnsUnavailableProfileWhenExchangeIsNotFound() {
        HttpExchangesController httpExchanges = mock(HttpExchangesController.class);
        when(httpExchanges.exchanges(null, null, null, null, null)).thenReturn(HttpExchangesReport.unavailable("none"));

        BootUiProperties properties = new BootUiProperties();
        ReactiveLiveActivityController controller = new ReactiveLiveActivityController(
                provider(httpExchanges),
                empty(SqlTraceRecorder.class),
                empty(RestClientTraceRecorder.class),
                empty(DataSource.class),
                empty(ExceptionStore.class),
                empty(ReactiveSecurityLogsController.class),
                empty(TracesController.class),
                empty(HealthController.class),
                defaultActivityStore(),
                disabledSettings(),
                properties,
                new BootUiExposure(properties));

        RequestProfileDto profile = controller.request("missing-id");

        assertThat(profile).isEqualTo(RequestProfileDto.unavailable("Request missing-id is no longer in the buffer"));
    }

    @Test
    void requestCorrelatesATraceWhenTheExchangeCarriesATraceIdAndTracesPanelIsEnabled() {
        HttpExchangesController httpExchanges = mock(HttpExchangesController.class);
        HttpExchangeDto tracedExchange = exchange("req-1", "GET", "/api/products", 200, "trace-abc");
        when(httpExchanges.exchanges(null, null, null, null, null))
                .thenReturn(new HttpExchangesReport(
                        1, 1, 0, List.of(tracedExchange), new PageMetadata(0, 0, 0, 0, 0, false), null));
        TracesController traces = mock(TracesController.class);
        TraceDetailDto detail =
                new TraceDetailDto("trace-abc", List.of(mock(io.github.jdubois.bootui.core.dto.SpanDto.class)));
        when(traces.detail("trace-abc")).thenReturn(detail);

        BootUiProperties properties = new BootUiProperties();
        ReactiveLiveActivityController controller = new ReactiveLiveActivityController(
                provider(httpExchanges),
                empty(SqlTraceRecorder.class),
                empty(RestClientTraceRecorder.class),
                empty(DataSource.class),
                empty(ExceptionStore.class),
                empty(ReactiveSecurityLogsController.class),
                provider(traces),
                empty(HealthController.class),
                defaultActivityStore(),
                disabledSettings(),
                properties,
                new BootUiExposure(properties));

        RequestProfileDto profile = controller.request("req-1");

        assertThat(profile.available()).isTrue();
        assertThat(profile.trace()).isEqualTo(detail);
    }

    @Test
    void requestSkipsTraceCorrelationWhenTracesPanelIsDisabled() {
        HttpExchangesController httpExchanges = mock(HttpExchangesController.class);
        HttpExchangeDto tracedExchange = exchange("req-1", "GET", "/api/products", 200, "trace-abc");
        when(httpExchanges.exchanges(null, null, null, null, null))
                .thenReturn(new HttpExchangesReport(
                        1, 1, 0, List.of(tracedExchange), new PageMetadata(0, 0, 0, 0, 0, false), null));
        TracesController traces = mock(TracesController.class);

        BootUiProperties properties = new BootUiProperties();
        properties.panel(BootUiPanels.TRACES).setEnabled(false);
        ReactiveLiveActivityController controller = new ReactiveLiveActivityController(
                provider(httpExchanges),
                empty(SqlTraceRecorder.class),
                empty(RestClientTraceRecorder.class),
                empty(DataSource.class),
                empty(ExceptionStore.class),
                empty(ReactiveSecurityLogsController.class),
                provider(traces),
                empty(HealthController.class),
                defaultActivityStore(),
                disabledSettings(),
                properties,
                new BootUiExposure(properties));

        RequestProfileDto profile = controller.request("req-1");

        assertThat(profile.trace()).isNull();
        org.mockito.Mockito.verifyNoInteractions(traces);
    }

    private static HttpExchangeDto exchange(String id, String method, String path, int status, String traceId) {
        return new HttpExchangeDto(
                id,
                Instant.now(),
                method,
                path,
                null,
                path,
                status,
                "2xx",
                5L,
                100L,
                "127.0.0.1",
                null,
                null,
                traceId,
                List.of(),
                List.of());
    }

    private static DataSource newDataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:reactive-live-activity-controller-" + DB_COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static SwitchableActivityStore defaultActivityStore() {
        return new SwitchableActivityStore(new InMemoryActivityStore(200));
    }

    private static ActivityPersistenceSettings disabledSettings() {
        return persistenceSettings(false, "instance-reactive-x", Duration.ofSeconds(5));
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

    private static ReactiveLiveActivityController controller(BootUiProperties properties) {
        return controllerWith(
                empty(SqlTraceRecorder.class),
                empty(RestClientTraceRecorder.class),
                empty(ExceptionStore.class),
                properties);
    }

    private static ReactiveLiveActivityController controllerWith(
            ObjectProvider<SqlTraceRecorder> recorder,
            ObjectProvider<RestClientTraceRecorder> restClientTraceRecorder,
            ObjectProvider<ExceptionStore> exceptionStore,
            BootUiProperties properties) {
        return controllerWith(
                recorder,
                restClientTraceRecorder,
                exceptionStore,
                defaultActivityStore(),
                disabledSettings(),
                empty(DataSource.class),
                properties);
    }

    private static ReactiveLiveActivityController controllerWith(
            ObjectProvider<SqlTraceRecorder> recorder,
            ObjectProvider<RestClientTraceRecorder> restClientTraceRecorder,
            ObjectProvider<ExceptionStore> exceptionStore,
            SwitchableActivityStore activityStore,
            ActivityPersistenceSettings persistenceSettings,
            ObjectProvider<DataSource> dataSourceProvider,
            BootUiProperties properties) {
        return new ReactiveLiveActivityController(
                empty(HttpExchangesController.class),
                recorder,
                restClientTraceRecorder,
                dataSourceProvider,
                exceptionStore,
                empty(ReactiveSecurityLogsController.class),
                empty(TracesController.class),
                empty(HealthController.class),
                activityStore,
                persistenceSettings,
                properties,
                new BootUiExposure(properties));
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
