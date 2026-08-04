package io.github.jdubois.bootui.sample.restclient;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Sample MicroProfile REST Client interface that makes outbound GET requests to the app's own
 * {@code /api/sample/products} endpoint, so the REST Client panel has calls to display. BootUI's
 * {@code QuarkusRestClientTraceListener} SPI hook automatically registers a
 * {@code QuarkusRestClientTraceFilter} on this client's proxy, capturing method, URI, status, and
 * duration into the shared {@code RestClientTraceRecorder}.
 *
 * <p>The base URI is set to the app's own loopback address via the MicroProfile Config key
 * {@code quarkus.rest-client.sample-api-client.url} in {@code application.properties}. No external
 * network calls are made.</p>
 */
@RegisterRestClient(configKey = "sample-api-client")
public interface SampleApiClient {

    @GET
    @Path("/api/sample/products")
    @Produces(MediaType.APPLICATION_JSON)
    String listProducts(@QueryParam("size") int size);
}
