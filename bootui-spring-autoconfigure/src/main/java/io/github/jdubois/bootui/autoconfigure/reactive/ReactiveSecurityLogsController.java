package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.core.dto.SecurityLogsReport;
import io.github.jdubois.bootui.engine.security.CapturedSecurityEvent;
import io.github.jdubois.bootui.engine.security.SecurityLogsService;
import java.time.Instant;
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

        List<CapturedSecurityEvent> events =
                repository.find(blankToNull(principal), parseAfter(after), blankToNull(type)).stream()
                        .map(ReactiveSecurityLogsController::toCaptured)
                        .toList();
        return securityLogsService.report(
                events,
                maxLogs,
                exposure.maskSecrets(),
                exposure.valueExposure(),
                blankToNull(principal),
                blankToNull(type),
                parseAfter(after),
                offset,
                limit);
    }

    private static CapturedSecurityEvent toCaptured(AuditEvent event) {
        // Spring's Live Activity correlation is thread-based (see LiveActivityService), not trace-id-based,
        // so there is no trace id to stamp here; only the Quarkus adapter populates it.
        return new CapturedSecurityEvent(
                event.getTimestamp(), event.getPrincipal(), event.getType(), event.getData(), null);
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
        changeStream.signal();
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

    private Instant parseAfter(String after) {
        String value = blankToNull(after);
        return value == null ? null : Instant.parse(value);
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
