package io.github.jdubois.bootui.autoconfigure.explorer;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.activity.LiveActivityController;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceController;
import io.github.jdubois.bootui.core.dto.*;
import io.github.jdubois.bootui.engine.activity.LiveActivityQueryService;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.github.jdubois.bootui.engine.explorer.ExplorerService;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.aot.AotDetector;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.NativeDetector;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Adapter collection only: the engine owns the projection over canonical, exposure-aware reads. */
@Lazy
public final class SpringExplorerService {
    private final LiveActivityQueryService activity;
    private final ObjectProvider<TelemetryStore> telemetry;
    private final ObjectProvider<SqlTraceController> sql;
    private final ObjectProvider<SqlTraceRecorder> sqlRecorder;
    private final ObjectProvider<CacheActivityRecorder> caches;
    private final BootUiProperties properties;
    private final BeanFactory beanFactory;
    private final ExplorerService service = new ExplorerService();

    public SpringExplorerService(
            LiveActivityController activity,
            ObjectProvider<TelemetryStore> telemetry,
            ObjectProvider<SqlTraceController> sql,
            ObjectProvider<SqlTraceRecorder> sqlRecorder,
            ObjectProvider<CacheActivityRecorder> caches,
            BootUiProperties properties,
            BeanFactory beanFactory) {
        this.activity = activity.queries();
        this.telemetry = telemetry;
        this.sql = sql;
        this.sqlRecorder = sqlRecorder;
        this.caches = caches;
        this.properties = properties;
        this.beanFactory = beanFactory;
    }

    public ExplorerReport report(
            String type, String severity, long since, int limit, String q, Long until, String cursor, int pageSize) {
        requireAccess();
        return service.report(activity.report(type, severity, since, limit, q, until, cursor, pageSize), setup());
    }

    public ExplorerEventDto event(String id) {
        return event(id, null);
    }

    public ExplorerEventDto event(String id, Long timestamp) {
        requireAccess();
        LiveActivityQueryService.Selection selection = activity.select(id, timestamp);
        ExplorerSetupDto setup = setup();
        TelemetryStore store = setup.beanDetailAvailable() ? telemetry.getIfAvailable() : null;
        TelemetryStore.TraceBucket trace =
                store == null || selection.event() == null || selection.event().correlationId() == null
                        ? null
                        : store.findTrace(selection.event().correlationId());
        List<ExplorerService.SqlEvidence> statements = List.of();
        SqlTraceController sqlController = properties.isPanelEnabled("sql-trace") ? sql.getIfAvailable() : null;
        SqlTraceRecorder recorder = sqlController == null ? null : sqlRecorder.getIfAvailable();
        if (recorder != null && selection.event() != null) {
            SqlTraceReport safe = sqlController.trace();
            if (safe.available()) {
                Map<Long, SqlTraceRecorder.CapturedStatement> captured = recorder.recent().stream()
                        .collect(Collectors.toMap(SqlTraceRecorder.CapturedStatement::id, Function.identity()));
                statements = safe.entries().stream()
                        .filter(statement -> {
                            SqlTraceRecorder.CapturedStatement original = captured.get(statement.id());
                            return original != null && original.timestamp() == statement.timestamp();
                        })
                        .map(statement -> {
                            SqlTraceRecorder.CapturedStatement original = captured.get(statement.id());
                            return new ExplorerService.SqlEvidence(
                                    statement, original.invocationId(), original.dataSource());
                        })
                        .toList();
            }
        }
        CacheActivityRecorder cache = properties.isPanelEnabled("cache") ? caches.getIfAvailable() : null;
        List<ExplorerService.CacheEvidence> cacheEvidence =
                cache == null || !cache.isEnabled() || selection.event() == null
                        ? List.of()
                        : cache.recentEvents().stream()
                                .map(event -> new ExplorerService.CacheEvidence(
                                        "cache-" + event.seq(),
                                        event.timestampMillis(),
                                        event.traceId(),
                                        event.invocationId(),
                                        event.managerName(),
                                        event.cacheName(),
                                        event.operation().name()))
                                .toList();
        return service.event(
                selection, setup, trace, statements, cacheEvidence, properties.isPanelEnabled("exceptions"));
    }

    private ExplorerSetupDto setup() {
        boolean enabled = properties.getExplorer().isEnabled();
        String reason;
        if (!properties.isPanelEnabled("beans")) reason = "Bean detail unavailable: the Beans panel is disabled.";
        else if (!properties.isPanelEnabled("traces")
                || !properties.getTelemetry().isEnabled()) {
            reason = "Bean detail unavailable: telemetry and the Traces panel must be enabled.";
        } else if (!enabled) {
            reason = "Bean capture is disabled. Re-enable with bootui.explorer.enabled=true and restart.";
        } else if (!beanFactory.containsBean("bootUiExplorerInvocationContext")) {
            reason =
                    "Bean detail unavailable: local sampled MVC tracing and Spring AOP are required; restart after setup.";
        } else reason = null;
        return new ExplorerSetupDto(
                enabled,
                reason == null,
                reason,
                Math.max(0, properties.getActivity().getRequestSlowThresholdMs()),
                List.of(
                        "Proxy invocations may skip the target body on a cache hit. Self-invocation is not intercepted.",
                        "Bean capture covers synchronous sampled MVC/JVM requests only, not asynchronous handoffs.",
                        "Nested durations overlap. SQL references are not physical-table health or per-table timings.",
                        "SQL/cache exact links require retained capture-time evidence; other activity keeps canonical parentage.",
                        "History can outlive bean/SQL detail. Source pause, sampling and capture budgets leave partial journeys."));
    }

    private void requireAccess() {
        if (NativeDetector.inNativeImage() || AotDetector.useGeneratedArtifacts()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "3D Explorer is unsupported in native/AOT mode.");
        }
        for (String panel : List.of("explorer", "activity")) {
            if (!properties.isPanelEnabled(panel)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, properties.panelDisabledReason(panel));
            }
        }
    }
}
