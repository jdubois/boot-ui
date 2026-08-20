package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.HttpClientRegistryReport;
import io.github.jdubois.bootui.engine.httpclient.HttpClientRegistryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Framework-neutral HTTP Clients controller shared by the servlet and reactive stacks. It serves
 * {@code GET /bootui/api/http-clients} by delegating to the engine {@link HttpClientRegistryService}, which
 * sanitizes, orders and cross-links the registrations discovered by the (optional) provider.
 *
 * <p>The endpoint is always registered so the panel can answer honestly with an explicit unavailable state
 * rather than a 404 when no supported HTTP client technology is present. It is read-only: no request is
 * ever made to a declared client's target.</p>
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/http-clients")
public class HttpClientsController {

    private final HttpClientRegistryService httpClientRegistryService;

    public HttpClientsController(HttpClientRegistryService httpClientRegistryService) {
        this.httpClientRegistryService = httpClientRegistryService;
    }

    @GetMapping
    public HttpClientRegistryReport httpClients() {
        return httpClientRegistryService.report();
    }
}
