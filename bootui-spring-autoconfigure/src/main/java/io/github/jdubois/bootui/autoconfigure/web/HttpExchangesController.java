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
        return new CapturedHttpExchange(
                exchange.getTimestamp(),
                request == null ? null : request.getMethod(),
                request == null ? null : request.getUri(),
                response == null ? 0 : response.getStatus(),
                exchange.getTimeTaken() == null ? null : exchange.getTimeTaken().toMillis(),
                request == null ? null : request.getRemoteAddress(),
                exchange.getPrincipal() == null ? null : exchange.getPrincipal().getName(),
                exchange.getSession() == null ? null : exchange.getSession().getId(),
                request == null ? null : request.getHeaders(),
                response == null ? null : response.getHeaders(),
                null);
    }
}
