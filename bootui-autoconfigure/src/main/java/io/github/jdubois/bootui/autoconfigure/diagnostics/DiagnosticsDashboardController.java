package io.github.jdubois.bootui.autoconfigure.diagnostics;

import io.github.jdubois.bootui.autoconfigure.exceptions.ExceptionStore;
import io.github.jdubois.bootui.autoconfigure.otlp.TelemetryStore;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.autoconfigure.web.BootUiLogAppender;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangesController;
import io.github.jdubois.bootui.autoconfigure.web.SecurityLogsController;
import io.github.jdubois.bootui.core.dto.DiagnosticsDashboardReport;
import io.github.jdubois.bootui.core.dto.DiagnosticsSourcesDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import io.github.jdubois.bootui.core.dto.SecurityLogsReport;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only Diagnostics dashboard that correlates the diagnostic signal panels (HTTP Exchanges, SQL
 * Trace, Exceptions, Security Logs and Traces) into per-request timelines.
 *
 * <p>It does not capture anything itself: it reads bounded snapshots from the existing panels —
 * reusing their controllers so masking and value-exposure rules are preserved — and joins them with
 * {@link DiagnosticsCorrelator}. Correlation is strongest when distributed tracing is active and a
 * {@code traceId} is present on the signals, and degrades to thread/time heuristics otherwise.
 */
@RestController
@RequestMapping("/bootui/api/diagnostics-dashboard")
public class DiagnosticsDashboardController {

    private static final int MAX_HTTP = 500;
    private static final int MAX_SQL = 1_000;
    private static final int MAX_EXCEPTIONS = 500;
    private static final int MAX_SECURITY = 500;
    private static final int MAX_TRACES = 200;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final ObjectProvider<HttpExchangesController> httpExchanges;
    private final ObjectProvider<SecurityLogsController> securityLogs;
    private final ObjectProvider<SqlTraceRecorder> sqlTraceRecorder;
    private final ObjectProvider<ExceptionStore> exceptionStore;
    private final ObjectProvider<TelemetryStore> telemetryStore;
    private final ObjectProvider<SecurityAuditTraceStore> securityAuditTraceStore;

    public DiagnosticsDashboardController(
            ObjectProvider<HttpExchangesController> httpExchanges,
            ObjectProvider<SecurityLogsController> securityLogs,
            ObjectProvider<SqlTraceRecorder> sqlTraceRecorder,
            ObjectProvider<ExceptionStore> exceptionStore,
            ObjectProvider<TelemetryStore> telemetryStore,
            ObjectProvider<SecurityAuditTraceStore> securityAuditTraceStore) {
        this.httpExchanges = httpExchanges;
        this.securityLogs = securityLogs;
        this.sqlTraceRecorder = sqlTraceRecorder;
        this.exceptionStore = exceptionStore;
        this.telemetryStore = telemetryStore;
        this.securityAuditTraceStore = securityAuditTraceStore;
    }

    @GetMapping
    public DiagnosticsDashboardReport dashboard(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "limit", required = false) Integer limit) {

        List<DiagnosticsCorrelator.HttpSignal> http = collectHttp();
        List<DiagnosticsCorrelator.SqlSignal> sql = collectSql();
        List<DiagnosticsCorrelator.ExceptionSignal> exceptions = collectExceptions();
        List<DiagnosticsCorrelator.SecuritySignal> security = collectSecurity();
        List<DiagnosticsCorrelator.SpanSignal> spans = collectSpans();

        DiagnosticsSourcesDto sources = new DiagnosticsSourcesDto(
                httpAvailable(),
                sqlAvailable(),
                exceptionStore.getIfAvailable() != null,
                securityAvailable(),
                telemetryStore.getIfAvailable() != null,
                BootUiLogAppender.find() != null);

        DiagnosticsCorrelator.Inputs inputs =
                new DiagnosticsCorrelator.Inputs(http, sql, exceptions, security, spans, sources);
        return DiagnosticsCorrelator.correlate(inputs, query, offset, limit);
    }

    private List<DiagnosticsCorrelator.HttpSignal> collectHttp() {
        HttpExchangesController controller = httpExchanges.getIfAvailable();
        if (controller == null) {
            return List.of();
        }
        HttpExchangesReport report = controller.exchanges(null, null, null, 0, MAX_HTTP);
        if (report == null || report.unavailableReason() != null) {
            return List.of();
        }
        List<DiagnosticsCorrelator.HttpSignal> signals = new ArrayList<>();
        for (HttpExchangeDto exchange : report.exchanges()) {
            long timestamp =
                    exchange.timestamp() == null ? 0L : exchange.timestamp().toEpochMilli();
            signals.add(new DiagnosticsCorrelator.HttpSignal(
                    timestamp,
                    exchange.traceId(),
                    exchange.method(),
                    exchange.path(),
                    exchange.status() == 0 ? null : exchange.status(),
                    exchange.durationMs(),
                    exchange.principal()));
        }
        return signals;
    }

    private List<DiagnosticsCorrelator.SqlSignal> collectSql() {
        SqlTraceRecorder recorder = sqlTraceRecorder.getIfAvailable();
        if (recorder == null || !recorder.isEnabled()) {
            return List.of();
        }
        List<DiagnosticsCorrelator.SqlSignal> signals = new ArrayList<>();
        List<SqlTraceRecorder.CapturedStatement> recent = recorder.recent();
        int count = Math.min(recent.size(), MAX_SQL);
        for (int i = 0; i < count; i++) {
            SqlTraceRecorder.CapturedStatement statement = recent.get(i);
            signals.add(new DiagnosticsCorrelator.SqlSignal(
                    statement.timestamp(),
                    statement.traceId(),
                    statement.thread(),
                    statement.statementType() == null
                            ? null
                            : statement.statementType().name(),
                    statement.sql(),
                    statement.durationMillis(),
                    statement.success(),
                    recorder.isSlow(statement.durationMillis())));
        }
        return signals;
    }

    private List<DiagnosticsCorrelator.ExceptionSignal> collectExceptions() {
        ExceptionStore store = exceptionStore.getIfAvailable();
        if (store == null) {
            return List.of();
        }
        List<DiagnosticsCorrelator.ExceptionSignal> signals = new ArrayList<>();
        for (ExceptionStore.OccurrenceView view : store.recentOccurrences(MAX_EXCEPTIONS)) {
            ExceptionStore.Occurrence occurrence = view.occurrence();
            signals.add(new DiagnosticsCorrelator.ExceptionSignal(
                    occurrence.timestamp(),
                    occurrence.traceId(),
                    occurrence.thread(),
                    view.exceptionClassName(),
                    view.message(),
                    occurrence.requestMethod(),
                    occurrence.requestPath(),
                    view.applicationException()));
        }
        return signals;
    }

    private List<DiagnosticsCorrelator.SecuritySignal> collectSecurity() {
        SecurityLogsController controller = securityLogs.getIfAvailable();
        if (controller == null) {
            return List.of();
        }
        SecurityLogsReport report = controller.logs(null, null, null, 0, MAX_SECURITY);
        if (report == null || !report.auditEventsPresent()) {
            return List.of();
        }
        SecurityAuditTraceStore traceStore = securityAuditTraceStore.getIfAvailable();
        List<DiagnosticsCorrelator.SecuritySignal> signals = new ArrayList<>();
        for (SecurityLogEventDto event : report.events()) {
            SecurityAuditTraceStore.Captured captured =
                    traceStore == null ? null : traceStore.lookup(event.timestamp(), event.type(), event.principal());
            String traceId = captured == null ? null : captured.traceId();
            String requestMethod = captured == null ? null : captured.requestMethod();
            String requestPath = captured == null ? null : captured.requestPath();
            signals.add(new DiagnosticsCorrelator.SecuritySignal(
                    parseTimestamp(event.timestamp()),
                    traceId,
                    event.principal(),
                    event.type(),
                    requestMethod,
                    requestPath));
        }
        return signals;
    }

    private List<DiagnosticsCorrelator.SpanSignal> collectSpans() {
        TelemetryStore store = telemetryStore.getIfAvailable();
        if (store == null) {
            return List.of();
        }
        List<DiagnosticsCorrelator.SpanSignal> signals = new ArrayList<>();
        for (TelemetryStore.TraceBucket bucket : store.recentTraces(MAX_TRACES)) {
            if (bucket.spans().isEmpty()) {
                continue;
            }
            long minStart = Long.MAX_VALUE;
            long maxEnd = Long.MIN_VALUE;
            boolean error = false;
            String rootName = null;
            long rootStart = Long.MAX_VALUE;
            for (var span : bucket.spans()) {
                minStart = Math.min(minStart, span.startEpochNanos());
                maxEnd = Math.max(maxEnd, span.endEpochNanos());
                error |= span.isError();
                boolean root = span.parentSpanId() == null;
                if ((root || rootName == null) && span.startEpochNanos() < rootStart) {
                    rootName = span.name();
                    rootStart = span.startEpochNanos();
                }
            }
            signals.add(new DiagnosticsCorrelator.SpanSignal(
                    bucket.traceId(),
                    rootName,
                    minStart == Long.MAX_VALUE ? 0L : minStart / NANOS_PER_MILLI,
                    maxEnd == Long.MIN_VALUE ? 0L : maxEnd / NANOS_PER_MILLI,
                    error));
        }
        return signals;
    }

    private boolean httpAvailable() {
        HttpExchangesController controller = httpExchanges.getIfAvailable();
        if (controller == null) {
            return false;
        }
        HttpExchangesReport report = controller.exchanges(null, null, null, 0, 1);
        return report != null && report.unavailableReason() == null;
    }

    private boolean sqlAvailable() {
        SqlTraceRecorder recorder = sqlTraceRecorder.getIfAvailable();
        return recorder != null && recorder.isEnabled();
    }

    private boolean securityAvailable() {
        SecurityLogsController controller = securityLogs.getIfAvailable();
        if (controller == null) {
            return false;
        }
        SecurityLogsReport report = controller.logs(null, null, null, 0, 1);
        return report != null && report.auditEventsPresent();
    }

    private static long parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException ex) {
            return 0L;
        }
    }
}
