package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.engine.web.CapturedHttpExchange;
import io.github.jdubois.bootui.engine.web.HttpExchangesService;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.web.exchanges.HttpExchange;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only HTTP Exchanges panel ({@code GET /bootui/api/http-exchanges}). Spring keeps Actuator's
 * {@link HttpExchangeRepository} as the capture source; this controller maps each recorded exchange into
 * a neutral {@link CapturedHttpExchange} and delegates masking, trace-id extraction, self-exclusion and
 * paging to the shared {@link HttpExchangesService} so the wire is identical to the Quarkus adapter.
 */
@RestController
@RequestMapping("/bootui/api/http-exchanges")
public class HttpExchangesController {

    private static final String UNAVAILABLE_REASON = "HTTP exchange repository not available";

    private final ObjectProvider<HttpExchangeRepository> repository;

    private final BootUiProperties properties;

    private final BootUiExposure exposure;

    private final BootUiSelfDataFilter selfDataFilter;

    private final HttpExchangesService service = new HttpExchangesService();

    private HttpExchangeTraceRegistry traceRegistry;

    public HttpExchangesController(ObjectProvider<HttpExchangeRepository> repository, BootUiProperties properties) {
        this(repository, properties, BootUiSelfDataFilter.defaults(), new BootUiExposure(properties));
    }

    @Autowired
    public HttpExchangesController(
            ObjectProvider<HttpExchangeRepository> repository,
            BootUiProperties properties,
            BootUiSelfDataFilter selfDataFilter,
            BootUiExposure exposure) {
        this.repository = repository;
        this.properties = properties;
        this.selfDataFilter = selfDataFilter;
        this.exposure = exposure;
    }

    /**
     * Installed only by {@code BootUiReactiveAutoConfiguration} once OpenTelemetry is present, so the
     * reactive adapter can stamp a real trace id despite Actuator's {@link HttpExchange} model carrying
     * none natively; left {@code null} on the servlet adapter, where {@link #capturedTraceId} simply
     * returns {@code null} and {@link HttpExchangesService#resolveTraceId} falls back to its existing
     * header-derived extraction unchanged. See {@link HttpExchangeTraceRegistry}.
     */
    public void setTraceRegistry(HttpExchangeTraceRegistry traceRegistry) {
        this.traceRegistry = traceRegistry;
    }

    @GetMapping
    public HttpExchangesReport exchanges(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "method", required = false) String method,
            @RequestParam(name = "statusClass", required = false) String statusClass,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "limit", required = false) Integer limit) {
        HttpExchangeRepository exchangeRepository = repository.getIfAvailable();
        if (exchangeRepository == null) {
            return HttpExchangesReport.unavailable(UNAVAILABLE_REASON);
        }
        List<CapturedHttpExchange> captured =
                exchangeRepository.findAll().stream().map(this::toCaptured).toList();
        return service.report(
                captured,
                uri -> !selfDataFilter.shouldInclude(selfDataFilter.isBootUiPath(uri)),
                exposure.maskSecrets(),
                exposure.valueExposure(),
                query,
                method,
                statusClass,
                offset,
                limit);
    }

    private CapturedHttpExchange toCaptured(HttpExchange exchange) {
        HttpExchange.Request request = exchange.getRequest();
        HttpExchange.Response response = exchange.getResponse();
        Long durationMs =
                exchange.getTimeTaken() == null ? null : exchange.getTimeTaken().toMillis();
        return new CapturedHttpExchange(
                exchange.getTimestamp(),
                request == null ? null : request.getMethod(),
                request == null ? null : request.getUri(),
                response == null ? 0 : response.getStatus(),
                durationMs,
                request == null ? null : request.getRemoteAddress(),
                exchange.getPrincipal() == null ? null : exchange.getPrincipal().getName(),
                exchange.getSession() == null ? null : exchange.getSession().getId(),
                request == null ? null : request.getHeaders(),
                response == null ? null : response.getHeaders(),
                capturedTraceId(exchange, request, durationMs));
    }

    /**
     * Looks up the trace id {@link HttpExchangeTraceRegistry} captured for this exchange (method + path +
     * overlapping time window, see {@link HttpExchangeTraceRegistry#match}); returns {@code null} when no
     * registry is installed (the servlet adapter, or OpenTelemetry absent on the reactive one) so callers
     * fall back to header-derived extraction unchanged.
     */
    private String capturedTraceId(HttpExchange exchange, HttpExchange.Request request, Long durationMs) {
        if (traceRegistry == null || request == null || exchange.getTimestamp() == null) {
            return null;
        }
        long start = exchange.getTimestamp().toEpochMilli();
        long end = durationMs == null ? start : start + durationMs;
        return traceRegistry.match(request.getMethod(), request.getUri().getPath(), start, end);
    }
}
