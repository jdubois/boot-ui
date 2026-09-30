package io.github.jdubois.bootui.autoconfigure.sqltrace;

import io.github.jdubois.bootui.autoconfigure.activity.RequestCorrelationRegistry;
import io.github.jdubois.bootui.autoconfigure.web.DeclaredRouteTemplates;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry;
import io.github.jdubois.bootui.core.dto.SqlTraceInsightsReport;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.engine.sqltrace.SqlRequestEvidence;
import io.github.jdubois.bootui.engine.sqltrace.SqlRouteAttribution.Correlation;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceInsightsService;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.spi.MappingProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Turns the Spring adapters' existing request-correlation evidence into the framework-neutral form the
 * engine's ranking and attribution services consume.
 *
 * <p>Both bindings answer from buffers BootUI already fills for the per-request profiler and Live Flow:
 * no JDBC wrapper, request interceptor or second statement recorder is introduced here, and no Actuator
 * feature has to be enabled for attribution to work.</p>
 *
 * <p>The two stacks differ in exactly one honest way. Spring MVC serves a request start to finish on one
 * worker thread, so it can offer serving-thread correlation. Spring WebFlux cannot: a request hops event
 * loops and schedulers, so the thread that ran a statement says nothing about which request asked for it.
 * WebFlux therefore relies on trace context, with a uniqueness-guarded time window as the only fallback,
 * and reports that difference through {@code supportedCorrelations} rather than silently degrading.</p>
 */
public final class SqlTraceInsightsSupport {

    private static final String NOT_CONFIGURED = "SQL tracing is not configured";

    /** Spring MVC: one worker thread per request makes thread affinity sound evidence. */
    private static final Set<Correlation> SERVLET_CORRELATIONS =
            Set.of(Correlation.TRACE_ID, Correlation.SERVING_THREAD, Correlation.TIME_WINDOW);

    /** Spring WebFlux: no thread affinity exists, so it is not claimed. */
    private static final Set<Correlation> REACTIVE_CORRELATIONS = Set.of(Correlation.TRACE_ID, Correlation.TIME_WINDOW);

    private SqlTraceInsightsSupport() {}

    /**
     * Reason reported instead of an empty route ranking when WebFlux has no request evidence at all. The
     * reactive exchange registry ships with the OpenTelemetry correlation configuration, because trace
     * context is the only thing that survives Reactor's scheduler hops; without it there is nothing to
     * attribute against, and saying so is honest where an empty ranking would read as a finding about the
     * application.
     */
    private static final String REACTIVE_EVIDENCE_MISSING =
            "Route attribution needs request trace context, which WebFlux only provides through the "
                    + "OpenTelemetry integration. Add an OpenTelemetry starter to attribute retained "
                    + "statements to the routes that issued them.";

    /** Rankings and route attribution for Spring MVC. */
    public static SqlTraceInsightsReport servletInsights(
            ObjectProvider<SqlTraceRecorder> recorderProvider,
            ObjectProvider<RequestCorrelationRegistry> correlationProvider,
            ObjectProvider<MappingProvider> mappingProvider) {
        SqlTraceRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null) {
            return SqlTraceInsightsReport.unavailable(NOT_CONFIGURED);
        }
        return new SqlTraceInsightsService(recorder)
                .insights(
                        servletEvidence(correlationProvider.getIfAvailable()),
                        SERVLET_CORRELATIONS,
                        routeTemplates(mappingProvider));
    }

    /** Rankings and route attribution for Spring WebFlux. */
    public static SqlTraceInsightsReport reactiveInsights(
            ObjectProvider<SqlTraceRecorder> recorderProvider,
            ObjectProvider<HttpExchangeTraceRegistry> traceRegistryProvider,
            ObjectProvider<MappingProvider> mappingProvider) {
        SqlTraceRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null) {
            return SqlTraceInsightsReport.unavailable(NOT_CONFIGURED);
        }
        HttpExchangeTraceRegistry registry = traceRegistryProvider.getIfAvailable();
        return new SqlTraceInsightsService(recorder)
                .insights(
                        reactiveEvidence(registry),
                        REACTIVE_CORRELATIONS,
                        routeTemplates(mappingProvider),
                        registry == null ? REACTIVE_EVIDENCE_MISSING : null);
    }

    /**
     * The application's declared route templates, so a captured request whose handler pattern was not
     * recorded is still grouped by a declared route instead of a masked path. Read from the Mappings
     * panel's existing provider: no new route enumeration is introduced, and an absent or unavailable
     * provider simply means no templates.
     */
    static RouteTemplateResolver routeTemplates(ObjectProvider<MappingProvider> mappingProvider) {
        return DeclaredRouteTemplates.from(mappingProvider);
    }

    static List<SqlRequestEvidence> servletEvidence(RequestCorrelationRegistry registry) {
        if (registry == null) {
            return List.of();
        }
        List<RequestCorrelationRegistry.RequestCorrelation> recent = registry.recent();
        List<SqlRequestEvidence> evidence = new ArrayList<>(recent.size());
        for (int index = 0; index < recent.size(); index++) {
            RequestCorrelationRegistry.RequestCorrelation correlation = recent.get(index);
            evidence.add(SqlRequestEvidence.of(
                    index,
                    correlation.method(),
                    correlation.path(),
                    correlation.routeTemplate(),
                    correlation.traceId(),
                    correlation.startMillis(),
                    correlation.endMillis(),
                    correlation.thread()));
        }
        return List.copyOf(evidence);
    }

    static List<SqlRequestEvidence> reactiveEvidence(HttpExchangeTraceRegistry registry) {
        if (registry == null) {
            return List.of();
        }
        List<HttpExchangeTraceRegistry.HttpExchangeTrace> recent = registry.recent();
        List<SqlRequestEvidence> evidence = new ArrayList<>(recent.size());
        for (int index = 0; index < recent.size(); index++) {
            HttpExchangeTraceRegistry.HttpExchangeTrace trace = recent.get(index);
            evidence.add(SqlRequestEvidence.of(
                    index,
                    trace.method(),
                    trace.path(),
                    trace.routeTemplate(),
                    trace.traceId(),
                    trace.startMillis(),
                    trace.endMillis(),
                    null));
        }
        return List.copyOf(evidence);
    }
}
