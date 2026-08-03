package org.acme.restclient;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** Sample @RegisterRestClient interface; BootUI's QuarkusRestClientTraceListener hooks into its proxy. */
@RegisterRestClient(configKey = "ping-client")
public interface PingClient {

    @GET
    @Path("/api/ping")
    String ping(@QueryParam("n") Integer n);
}
