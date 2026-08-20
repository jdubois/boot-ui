package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.HttpClientRegistryReport;
import io.github.jdubois.bootui.engine.httpclient.HttpClientRegistryService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the HTTP Clients panel ({@code GET /bootui/api/http-clients}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code HttpClientsController}: a thin, read-only transport
 * adapter over the shared engine {@link HttpClientRegistryService}. There is no write path, so the resource
 * carries no {@code LocalhostGuard} write floor, and reading it never contacts a declared client's target.</p>
 *
 * <p>The resource is produced unconditionally and the engine service is always wired (it holds no REST
 * Client types): when {@code quarkus-rest-client} is absent the provider reports unavailable and the engine
 * renders an explicit unavailable report. Availability of the <em>panel</em> in the manifest, by contrast,
 * tracks the build-time {@code bootui.internal.http-clients-present} flag (see
 * {@code QuarkusPanelAvailability}).</p>
 */
@Path("/bootui/api/http-clients")
public class HttpClientsResource {

    private final HttpClientRegistryService httpClientRegistryService;

    @Inject
    public HttpClientsResource(HttpClientRegistryService httpClientRegistryService) {
        this.httpClientRegistryService = httpClientRegistryService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public HttpClientRegistryReport httpClients() {
        return httpClientRegistryService.report();
    }
}
