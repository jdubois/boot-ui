package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.core.dto.CaptureRetentionDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.HttpRoutesReport;
import io.github.jdubois.bootui.engine.retention.TieredCaptureBuffer;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.engine.web.CapturedHttpExchange;
import io.github.jdubois.bootui.engine.web.HttpExchangesService;
import io.github.jdubois.bootui.engine.web.HttpRouteSummaryService;
import io.github.jdubois.bootui.spi.MappingProvider;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
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
 * a neutral {@link CapturedHttpExchange} and delegates masking, trace-id extraction, self-exclusion,
 * route resolution and paging to the shared {@link HttpExchangesService}, and route rankings to the shared
 * {@link HttpRouteSummaryService}, so the wire is identical to the Quarkus adapter. Shared by the Spring MVC
 * and Spring WebFlux adapters.
 *
 * <p>When the repository is BootUI's own {@link BootUiHttpExchangeRepository}, the exchange list and the route
 * rankings carry its retention counts, read from the same snapshot as the exchanges: the list's {@code retention}
 * object, and the route window's buffer size and evictions. An application-provided repository, or BootUI's
 * repository fed by an application-provided filter, reports its retention as application-managed and leaves the
 * window's evictions unreported.</p>
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/http-exchanges")
public class HttpExchangesController implements BeanFactoryAware {

    private static final String UNAVAILABLE_REASON = "HTTP exchange repository not available";

    /**
     * Spring MVC always installs the trace registry that carries the matched handler pattern; Spring WebFlux
     * installs it with the OpenTelemetry integration only, which the reactive starter includes.
     */
    private static final String NO_TEMPLATE_NOTE = "No framework route template was recorded, because Spring "
            + "WebFlux records the matched handler pattern only when the OpenTelemetry integration is present. "
            + "Routes fall back to declared mappings when available, then to masked paths.";

    /**
     * The bean names BootUI registers its fallback repository under on Spring MVC and Spring WebFlux. Only
     * that repository is sized from {@code bootui.http-exchanges.max-exchanges}; an application-provided
     * repository does not report its capacity, so the route summary leaves the buffer size unknown for it.
     */
    private static final List<String> BOOTUI_REPOSITORY_BEANS =
            List.of("bootUiHttpExchangeRepository", "bootUiReactiveHttpExchangeRepository");

    private final ObjectProvider<HttpExchangeRepository> repository;

    private final BootUiProperties properties;

    private final BootUiExposure exposure;

    private final BootUiSelfDataFilter selfDataFilter;

    private final HttpExchangesService service = new HttpExchangesService();

    private final HttpRouteSummaryService routeSummary = new HttpRouteSummaryService();

    private HttpExchangeTraceRegistry traceRegistry;

    private Supplier<RouteTemplateResolver> declaredRoutes = RouteTemplateResolver::empty;

    private BeanFactory beanFactory;

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
     * Installed whenever either Spring adapter contributes a registry, so a server-created trace id can
     * be stamped despite Actuator's {@link HttpExchange} model carrying none natively. When no registry
     * exists, {@link #capturedTraceId} returns {@code null} and {@link
     * HttpExchangesService#resolveTraceId} keeps its header-derived fallback unchanged.
     */
    @Autowired(required = false)
    public void setTraceRegistry(HttpExchangeTraceRegistry traceRegistry) {
        this.traceRegistry = traceRegistry;
    }

    /**
     * The application's declared routes, read through the Mappings panel's provider, label exchanges whose
     * handler pattern was not recorded. Absent when Actuator's mappings endpoint is not available.
     */
    @Autowired(required = false)
    public void setMappingProvider(ObjectProvider<MappingProvider> mappingProvider) {
        this.declaredRoutes = DeclaredRouteTemplates.caching(mappingProvider);
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    /** The exchange list without a route filter, for programmatic callers such as MCP and Live Activity. */
    public HttpExchangesReport exchanges(
            String query, String method, String statusClass, Integer offset, Integer limit) {
        return exchanges(query, method, statusClass, null, offset, limit);
    }

    @GetMapping
    public HttpExchangesReport exchanges(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "method", required = false) String method,
            @RequestParam(name = "statusClass", required = false) String statusClass,
            @RequestParam(name = "route", required = false) String route,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "limit", required = false) Integer limit) {
        HttpExchangeRepository exchangeRepository = repository.getIfAvailable();
        if (exchangeRepository == null) {
            return HttpExchangesReport.unavailable(UNAVAILABLE_REASON);
        }
        Window window = window(exchangeRepository);
        return service.report(
                window.captured(),
                window.selfPath(),
                exposure.maskSecrets(),
                exposure.valueExposure(),
                declaredRoutes.get(),
                query,
                method,
                statusClass,
                route,
                offset,
                limit,
                window.retention());
    }

    /** Route rankings without a pinned route, for programmatic callers such as MCP. */
    public HttpRoutesReport routes(Integer limit) {
        return routes(limit, null);
    }

    /**
     * Route performance rankings over the retained exchanges ({@code GET .../http-exchanges/routes}).
     * {@code limit} is the number of routes each ranking criterion contributes, and {@code route} names a
     * route whose row is included whatever its rank, so a link to it always finds it.
     */
    @GetMapping("/routes")
    public HttpRoutesReport routes(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "route", required = false) String route) {
        HttpExchangeRepository exchangeRepository = repository.getIfAvailable();
        if (exchangeRepository == null) {
            return HttpRoutesReport.unavailable(UNAVAILABLE_REASON);
        }
        Window window = window(exchangeRepository);
        CaptureRetentionDto retention = window.retention();
        return routeSummary.summarize(
                window.captured(),
                window.selfPath(),
                declaredRoutes.get(),
                // Actuator's repository API reports neither its capacity nor its evictions; BootUI's own repository
                // reports both, except when an application filter records into it (application-managed).
                new HttpRouteSummaryService.ExchangeSource(
                        retention.capacity() != null ? retention.capacity() : bufferSize(exchangeRepository),
                        retention.evicted(),
                        traceRegistry == null ? List.of(NO_TEMPLATE_NOTE) : List.of()),
                limit,
                route);
    }

    /**
     * The retained exchanges, the retention counts that describe them, and the read-time self filter, all from one
     * snapshot of the repository. BootUI's own recording filter already kept BootUI's requests out of a BootUI-owned
     * repository, so no read-time check runs there; an application-managed recorder keeps it.
     */
    private Window window(HttpExchangeRepository exchangeRepository) {
        List<HttpExchange> exchanges;
        CaptureRetentionDto retention;
        boolean selfExcludedAtCapture = false;
        if (exchangeRepository instanceof BootUiHttpExchangeRepository bootUiRepository) {
            TieredCaptureBuffer.Snapshot<HttpExchange> snapshot = bootUiRepository.snapshot();
            exchanges = snapshot.newestFirst();
            retention = bootUiRepository.retention(snapshot);
            selfExcludedAtCapture = bootUiRepository.ownsRetention();
        } else {
            exchanges = exchangeRepository.findAll();
            retention = CaptureRetentionDto.applicationManaged(exchanges.size());
        }
        HttpExchangeTraceRegistry.Matcher traces = traceRegistry == null ? null : traceRegistry.matcher();
        BootUiHttpExchangeRepository stamping =
                exchangeRepository instanceof BootUiHttpExchangeRepository bootUiRepository ? bootUiRepository : null;
        List<CapturedHttpExchange> captured = exchanges.stream()
                .map(exchange -> toCaptured(exchange, traces, stamping))
                .toList();
        return new Window(
                captured,
                retention,
                selfExcludedAtCapture ? HttpExchangesService.BootUiSelfPath.EXCLUDED_AT_CAPTURE : selfPath());
    }

    private record Window(
            List<CapturedHttpExchange> captured,
            CaptureRetentionDto retention,
            HttpExchangesService.BootUiSelfPath selfPath) {}

    private HttpExchangesService.BootUiSelfPath selfPath() {
        return uri -> !selfDataFilter.shouldInclude(selfDataFilter.isBootUiPath(uri));
    }

    private Integer bufferSize(HttpExchangeRepository exchangeRepository) {
        if (beanFactory == null || properties == null) {
            return null;
        }
        for (String name : BOOTUI_REPOSITORY_BEANS) {
            try {
                if (beanFactory.containsBean(name) && beanFactory.getBean(name) == exchangeRepository) {
                    return Math.max(1, properties.getHttpExchanges().getMaxExchanges());
                }
            } catch (RuntimeException ex) {
                return null;
            }
        }
        return null;
    }

    /**
     * @param stamping BootUI's own repository, which knows the request id of each exchange it recorded, or {@code null}
     *     for an application-provided repository, whose exchanges carry none
     */
    private CapturedHttpExchange toCaptured(
            HttpExchange exchange, HttpExchangeTraceRegistry.Matcher traces, BootUiHttpExchangeRepository stamping) {
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
                capturedTraceId(traces, exchange, request, durationMs),
                capturedRouteTemplate(traces, exchange, request, durationMs),
                stamping == null ? null : stamping.requestId(exchange));
    }

    /**
     * Looks up the trace id {@link HttpExchangeTraceRegistry} captured for this exchange (method + path +
     * overlapping time window, see {@link HttpExchangeTraceRegistry#match}); returns {@code null} when no
     * registry is installed (or OpenTelemetry is absent on the reactive adapter) so callers fall back to
     * header-derived extraction unchanged.
     */
    private static String capturedTraceId(
            HttpExchangeTraceRegistry.Matcher traces,
            HttpExchange exchange,
            HttpExchange.Request request,
            Long durationMs) {
        if (traces == null || request == null || exchange.getTimestamp() == null) {
            return null;
        }
        long start = exchange.getTimestamp().toEpochMilli();
        long end = durationMs == null ? start : start + durationMs;
        return traces.match(request.getMethod(), request.getUri().getPath(), start, end);
    }

    /**
     * Looks up the handler pattern the framework matched for this exchange, recorded beside its trace id
     * (see {@link HttpExchangeTraceRegistry#matchRouteTemplate}); returns {@code null} when no registry is
     * installed or the candidates disagree, so the engine falls back to declared mappings, then a masked
     * path.
     */
    private static String capturedRouteTemplate(
            HttpExchangeTraceRegistry.Matcher traces,
            HttpExchange exchange,
            HttpExchange.Request request,
            Long durationMs) {
        if (traces == null || request == null || request.getUri() == null || exchange.getTimestamp() == null) {
            return null;
        }
        long start = exchange.getTimestamp().toEpochMilli();
        long end = durationMs == null ? start : start + durationMs;
        return traces.matchRouteTemplate(request.getMethod(), request.getUri().getPath(), start, end);
    }
}
