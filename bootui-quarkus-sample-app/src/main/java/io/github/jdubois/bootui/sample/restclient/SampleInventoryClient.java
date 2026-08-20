package io.github.jdubois.bootui.sample.restclient;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * A second MicroProfile REST Client interface that is registered but never injected or invoked, so the BootUI
 * HTTP Clients panel demonstrates the point of the panel: a declarative client is visible from its
 * registration and configuration alone, before any request has ever been made.
 *
 * <p>Unlike {@link SampleApiClient}, this client declares no client-specific timeouts, so the panel shows it
 * inheriting the global {@code quarkus.rest-client.*} defaults, and it has no observed calls.</p>
 */
@RegisterRestClient(configKey = "sample-inventory-client")
public interface SampleInventoryClient {

    @GET
    @Path("/api/sample/inventory")
    @Produces(MediaType.APPLICATION_JSON)
    String listInventory();
}
