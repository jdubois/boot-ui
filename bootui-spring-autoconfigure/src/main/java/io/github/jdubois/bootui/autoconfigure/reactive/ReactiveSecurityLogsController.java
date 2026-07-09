package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.core.dto.SecurityLogsReport;
import io.github.jdubois.bootui.engine.security.CapturedSecurityEvent;
import io.github.jdubois.bootui.engine.security.SecurityLogsService;
import io.github.jdubois.bootui.engine.support.BlankStrings;
import io.github.jdubois.bootui.spi.TraceIdProvider;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.boot.actuate.audit.listener.AuditApplicationEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Reactive (WebFlux) sibling of {@code SecurityLogsController}: identical read API over the same
 * framework-neutral Actuator {@link AuditEventRepository}, with the {@code /stream} endpoint
 * rebuilt on {@link ReactiveBootUiChangeStream}.
 *
 * <p>Depends only on Actuator's {@link AuditEventRepository} / {@link AuditApplicationEvent} - not
 * on any Spring Security advisor ruleset - so it fires identically whether the host application
 * wires security via a servlet {@code SecurityFilterChain} or a reactive
 * {@code SecurityWebFilterChain}. The Spring Security *advisor* panel (which analyzes security
 * configuration, not audit events) is a separate, still-unported concern.
 */
@RestController
@ConditionalOnClass(AuditEventRepository.class)
@RequestMapping("/bootui/api/security-logs")
public class ReactiveSecurityLogsController implements ApplicationListener<AuditApplicationEvent> {

    private final ObjectProvider<AuditEventRepository> auditEventRepositoryProvider;

    private final BootUiProperties properties;

    private final BootUiExposure exposure;

    private final SecurityLogsService securityLogsService = new SecurityLogsService();

    private final ReactiveBootUiChangeStream changeStream = new ReactiveBootUiChangeStream("security-logs");

    private TraceIdProvider traceIdProvider;

    private ReactiveSecurityEventTraceRegistry traceRegistry;

    @Autowired
    public ReactiveSecurityLogsController(
            ObjectProvider<AuditEventRepository> auditEventRepositoryProvider,
            BootUiProperties properties,
            BootUiExposure exposure) {
        this.auditEventRepositoryProvider = auditEventRepositoryProvider;
        this.properties = properties;
        this.exposure = exposure;
    }

    ReactiveSecurityLogsController(
            ObjectProvider<AuditEventRepository> auditEventRepositoryProvider, BootUiProperties properties) {
        this(auditEventRepositoryProvider, properties, new BootUiExposure(properties));
    }

    /**
     * Installed only by {@code BootUiReactiveAutoConfiguration} once OpenTelemetry is present, so
     * {@link #onApplicationEvent} can capture the trace id active when Spring Security publishes each
     * audit event; left {@code null} otherwise, in which case {@link #recordTraceId} no-ops.
     */
    public void setTraceIdProvider(TraceIdProvider traceIdProvider) {
        this.traceIdProvider = traceIdProvider;
    }

    /**
     * Installed alongside {@link #setTraceIdProvider}; left {@code null} otherwise, in which case
     * {@link #toCaptured} always renders a {@code null} trace id, exactly like today.
     */
    public void setTraceRegistry(ReactiveSecurityEventTraceRegistry traceRegistry) {
        this.traceRegistry = traceRegistry;
    }

    @GetMapping
    public SecurityLogsReport logs(
            @RequestParam(name = "principal", required = false) String principal,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "after", required = false) String after,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "limit", required = false) Integer limit) {
        int maxLogs = maxLogs();
        AuditEventRepository repository = auditEventRepositoryProvider.getIfAvailable();
        if (repository == null) {
            return SecurityLogsReport.unavailable("No AuditEventRepository bean is available", maxLogs);
        }

        List<CapturedSecurityEvent> events = repository
                .find(
                        BlankStrings.blankToNullTrimmed(principal),
                        BlankStrings.parseInstant(after),
                        BlankStrings.blankToNullTrimmed(type))
                .stream()
                .map(this::toCaptured)
                .toList();
        return securityLogsService.report(
                events,
                maxLogs,
                exposure.maskSecrets(),
                exposure.valueExposure(),
                BlankStrings.blankToNullTrimmed(principal),
                BlankStrings.blankToNullTrimmed(type),
                BlankStrings.parseInstant(after),
                offset,
                limit);
    }

    private CapturedSecurityEvent toCaptured(AuditEvent event) {
        return new CapturedSecurityEvent(
                event.getTimestamp(), event.getPrincipal(), event.getType(), event.getData(), capturedTraceId(event));
    }

    /**
     * Looks up the trace id {@link ReactiveSecurityEventTraceRegistry} captured for this event (type +
     * principal + timestamp window, see {@link ReactiveSecurityEventTraceRegistry#match}); returns
     * {@code null} when no registry is installed, exactly like before this correlation existed.
     */
    private String capturedTraceId(AuditEvent event) {
        if (traceRegistry == null) {
            return null;
        }
        return traceRegistry.match(
                event.getType(), event.getPrincipal(), event.getTimestamp().toEpochMilli());
    }

    /**
     * Streams a coalesced {@code update} notification whenever a new audit event is recorded, so the
     * browser can refresh live without polling. The push is a tiny tick; the browser re-fetches the
     * filtered/paginated report through {@link #logs}, preserving masking and value-exposure rules.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> stream() {
        return changeStream.open();
    }

    @Override
    public void onApplicationEvent(AuditApplicationEvent event) {
        recordTraceId(event.getAuditEvent());
        changeStream.signal();
    }

    /**
     * Captures the trace id active when Spring Security published this audit event, keyed by
     * type/principal/timestamp so {@link #toCaptured} can look it up later - the same
     * {@link TraceIdProvider} signal {@code ReactiveHttpExchangeTraceFilter}/{@code SqlTraceRecorder}
     * capture from. Fully guarded so a missing registry/provider, or a misbehaving tracer, never disrupts
     * Spring Security's own event publication.
     */
    private void recordTraceId(AuditEvent event) {
        if (traceIdProvider == null || traceRegistry == null || event == null || event.getTimestamp() == null) {
            return;
        }
        try {
            String traceId = traceIdProvider.currentTraceId();
            if (traceId == null || traceId.isBlank()) {
                return;
            }
            traceRegistry.record(new ReactiveSecurityEventTraceRegistry.SecurityEventTrace(
                    event.getTimestamp().toEpochMilli(), event.getType(), event.getPrincipal(), traceId));
        } catch (RuntimeException ignored) {
            // Diagnostics capture must never interfere with Spring Security's own event publication.
        }
    }

    /**
     * Completes any open SSE streams when the context starts closing. See
     * {@code SecurityLogsController#shutdown} for why this runs on {@link ContextClosedEvent}
     * rather than a destroy callback.
     */
    @EventListener(ContextClosedEvent.class)
    void shutdown() {
        changeStream.close();
    }

    @ExceptionHandler({DateTimeParseException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage() == null ? "Invalid request" : ex.getMessage()));
    }

    private int maxLogs() {
        return securityLogsService.maxLogs(properties.getSecurityLogs().getMaxLogs());
    }
}
