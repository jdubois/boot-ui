package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.activity.RequestCorrelationRegistry.RequestCorrelation;
import io.github.jdubois.bootui.autoconfigure.exceptions.ExceptionsController;
import io.github.jdubois.bootui.autoconfigure.javaagent.AgentPropagation;
import io.github.jdubois.bootui.autoconfigure.restclienttrace.RestClientTraceController;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceController;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangesController;
import io.github.jdubois.bootui.autoconfigure.web.SecurityLogsController;
import io.github.jdubois.bootui.autoconfigure.web.TracesController;
import io.github.jdubois.bootui.core.dto.ExceptionDetailDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.ExceptionsReport;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.RequestProfileDto;
import io.github.jdubois.bootui.core.dto.RestClientTraceEntryDto;
import io.github.jdubois.bootui.core.dto.RestClientTraceReport;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import io.github.jdubois.bootui.core.dto.SecurityLogsReport;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import io.github.jdubois.bootui.core.dto.TraceDetailDto;
import io.github.jdubois.bootui.engine.cache.CacheActivityEvent;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.web.ExecutionProfileAssembler;
import io.github.jdubois.bootui.engine.web.ProfileCapabilities;
import io.github.jdubois.bootui.engine.web.ProfileEvidence;
import io.github.jdubois.bootui.engine.web.ProfileEvidence.Source;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Spring MVC binding of the per-request profiler at {@code GET /bootui/api/activity/request/{id}}.
 *
 * <p>Correlation policy lives in the shared engine {@link ExecutionProfileAssembler}; this class only
 * gathers what the servlet adapter alone can observe. It reads every source through its own panel's
 * controller, so records arrive masked, self-filtered, and bounded exactly as that panel shows them, and a
 * disabled panel contributes nothing. It also supplies the two hooks the servlet model makes provable: the
 * worker thread that served each request, from {@link RequestCorrelationRegistry}, and whether a security
 * audit event fired on that thread, from {@link SecurityEventCorrelationRegistry}. A servlet request runs
 * start to finish on one thread that serves only one request at a time, so Spring MVC provides the
 * serving-thread and time-window tiers the event-loop adapters report unavailable.</p>
 */
public class LiveActivityCorrelator {

    private final ObjectProvider<HttpExchangesController> httpExchanges;
    private final ObjectProvider<SqlTraceController> sqlTrace;
    private final ObjectProvider<RestClientTraceController> restClientTrace;
    private final ObjectProvider<ExceptionsController> exceptions;
    private final ObjectProvider<SecurityLogsController> securityLogs;
    private final ObjectProvider<TracesController> traces;
    private final ObjectProvider<CacheActivityRecorder> cacheActivity;
    private final ObjectProvider<RequestCorrelationRegistry> requestCorrelations;
    private final ObjectProvider<SecurityEventCorrelationRegistry> securityCorrelations;
    private final BootUiProperties properties;
    private volatile ObjectProvider<JavaAgentService> javaAgent;

    public LiveActivityCorrelator(
            ObjectProvider<HttpExchangesController> httpExchanges,
            ObjectProvider<SqlTraceController> sqlTrace,
            ObjectProvider<RestClientTraceController> restClientTrace,
            ObjectProvider<ExceptionsController> exceptions,
            ObjectProvider<SecurityLogsController> securityLogs,
            ObjectProvider<TracesController> traces,
            ObjectProvider<CacheActivityRecorder> cacheActivity,
            ObjectProvider<RequestCorrelationRegistry> requestCorrelations,
            ObjectProvider<SecurityEventCorrelationRegistry> securityCorrelations,
            BootUiProperties properties) {
        this.httpExchanges = httpExchanges;
        this.sqlTrace = sqlTrace;
        this.restClientTrace = restClientTrace;
        this.exceptions = exceptions;
        this.securityLogs = securityLogs;
        this.traces = traces;
        this.cacheActivity = cacheActivity;
        this.requestCorrelations = requestCorrelations;
        this.securityCorrelations = securityCorrelations;
        this.properties = properties;
    }

    /**
     * Installs the Java Agent service, which says whether the agent propagates executor work, and so whether the
     * {@code PROPAGATED} tier is available.
     */
    public void setJavaAgent(ObjectProvider<JavaAgentService> javaAgent) {
        this.javaAgent = javaAgent;
    }

    /** Build the profile for the request with the given HTTP exchange id or BootUI request id. */
    public RequestProfileDto profile(String requestId) {
        ExecutionProfileAssembler assembler = new ExecutionProfileAssembler(
                properties.getActivity().getNPlusOneThreshold(),
                ExecutionProfileAssembler.DEFAULT_MAX_CHILDREN_PER_SECTION,
                properties.getAgent().getExecutors().getMaxHandoff().toMillis());
        List<HttpExchangeDto> requests = requests();
        boolean found =
                requests.stream().anyMatch(exchange -> ExecutionProfileAssembler.identifies(exchange, requestId));
        ProfileEvidence evidence = found
                ? new ProfileEvidence(
                        requests,
                        sql(),
                        exceptionDetails(),
                        securityEvents(),
                        restCalls(),
                        cacheAccesses(),
                        this::trace)
                : new ProfileEvidence(requests, null, null, null, null, null, null);
        return assembler.requestProfile(requestId, evidence, capabilities());
    }

    private ProfileCapabilities capabilities() {
        RequestCorrelationRegistry requestRegistry =
                requestCorrelations == null ? null : requestCorrelations.getIfAvailable();
        SecurityEventCorrelationRegistry securityRegistry =
                securityCorrelations == null ? null : securityCorrelations.getIfAvailable();
        ProfileCapabilities.ServingThreadResolver servingThreads = requestRegistry == null
                ? (method, path, start, end) -> null
                : (method, path, start, end) -> servingThread(requestRegistry.match(method, path, start, end));
        ProfileCapabilities.SecurityThreadClassifier securityThreads = securityRegistry == null
                ? null
                : (thread, type, timestamp) -> threadMatch(
                        securityRegistry.classify(thread, type, timestamp, ActivitySql.SECURITY_THREAD_SLACK_MS));
        return ProfileCapabilities.threadPerRequest(servingThreads, securityThreads)
                .withPropagation(AgentPropagation.unavailableReason(javaAgent));
    }

    private static ProfileCapabilities.ServingThread servingThread(RequestCorrelation correlation) {
        return correlation == null
                ? null
                : new ProfileCapabilities.ServingThread(
                        correlation.thread(), correlation.startMillis(), correlation.endMillis());
    }

    private static ProfileCapabilities.ThreadMatch threadMatch(SecurityEventCorrelationRegistry.ThreadMatch match) {
        return switch (match) {
            case OURS -> ProfileCapabilities.ThreadMatch.OURS;
            case FOREIGN -> ProfileCapabilities.ThreadMatch.FOREIGN;
            case UNKNOWN -> ProfileCapabilities.ThreadMatch.UNKNOWN;
        };
    }

    private List<HttpExchangeDto> requests() {
        if (!properties.isPanelEnabled(BootUiPanels.HTTP_EXCHANGES)) {
            return List.of();
        }
        HttpExchangesController controller = httpExchanges.getIfAvailable();
        if (controller == null) {
            return List.of();
        }
        HttpExchangesReport report = controller.exchanges(
                null, null, null, 0, properties.getActivity().getMaxEntries());
        return report.unavailableReason() != null ? List.of() : report.exchanges();
    }

    private Source<SqlTraceEntryDto> sql() {
        if (!properties.isPanelEnabled(BootUiPanels.SQL_TRACE)) {
            return Source.panelDisabled("SQL Trace");
        }
        SqlTraceController controller = sqlTrace.getIfAvailable();
        if (controller == null) {
            return Source.notCapturing("SQL Trace");
        }
        SqlTraceReport report = controller.trace();
        return report.available() ? Source.of(report.entries()) : Source.unavailable(report.unavailableReason());
    }

    private Source<RestClientTraceEntryDto> restCalls() {
        if (!properties.isPanelEnabled(BootUiPanels.REST_CLIENT_TRACE)) {
            return Source.panelDisabled("REST Client");
        }
        RestClientTraceController controller = restClientTrace == null ? null : restClientTrace.getIfAvailable();
        if (controller == null) {
            return Source.notCapturing("REST Client");
        }
        RestClientTraceReport report = controller.trace();
        return report.available() ? Source.of(report.entries()) : Source.unavailable(report.unavailableReason());
    }

    /**
     * Every retained occurrence of every exception group, so a profile can match any occurrence — not just
     * each group's most recent one — by trace id, serving thread, or request method, path, and window.
     */
    private Source<ExceptionDetailDto> exceptionDetails() {
        if (!properties.isPanelEnabled(BootUiPanels.EXCEPTIONS)) {
            return Source.panelDisabled("Exceptions");
        }
        ExceptionsController controller = exceptions.getIfAvailable();
        if (controller == null) {
            return Source.notCapturing("Exceptions");
        }
        ExceptionsReport report = controller.list();
        if (!report.available()) {
            return Source.unavailable(report.unavailableReason());
        }
        List<ExceptionDetailDto> details = new ArrayList<>();
        for (ExceptionGroupDto group : report.groups()) {
            ExceptionDetailDto detail = safeDetail(controller, group.id());
            if (detail != null) {
                details.add(detail);
            }
        }
        return Source.of(details);
    }

    private Source<SecurityLogEventDto> securityEvents() {
        if (!properties.isPanelEnabled(BootUiPanels.SECURITY_LOGS)) {
            return Source.panelDisabled("Security Logs");
        }
        SecurityLogsController controller = securityLogs.getIfAvailable();
        if (controller == null) {
            return Source.notCapturing("Security Logs");
        }
        SecurityLogsReport report =
                controller.logs(null, null, null, 0, properties.getActivity().getMaxEntries());
        return report.auditEventsPresent()
                ? Source.of(report.events())
                : Source.unavailable(report.unavailableReason());
    }

    private Source<CacheActivityEvent> cacheAccesses() {
        if (!properties.isPanelEnabled(BootUiPanels.CACHE)) {
            return Source.panelDisabled("Cache");
        }
        return Source.cacheAccesses(cacheActivity == null ? null : cacheActivity.getIfAvailable());
    }

    private TraceDetailDto trace(String traceId) {
        if (traceId == null || traceId.isBlank() || !properties.isPanelEnabled(BootUiPanels.TRACES)) {
            return null;
        }
        TracesController controller = traces.getIfAvailable();
        if (controller == null) {
            return null;
        }
        try {
            TraceDetailDto detail = controller.detail(traceId);
            return detail == null || detail.spans().isEmpty() ? null : detail;
        } catch (RuntimeException ex) {
            // Trace not found or filtered out; correlation simply has no trace tier.
            return null;
        }
    }

    private static ExceptionDetailDto safeDetail(ExceptionsController controller, String id) {
        try {
            return controller.detail(id);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
