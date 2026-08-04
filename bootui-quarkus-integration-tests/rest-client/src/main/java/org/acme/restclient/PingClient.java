package org.acme.restclient;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** Sample @RegisterRestClient interface; BootUI's QuarkusRestClientTraceListener hooks into its proxy. */
@RegisterRestClient(configKey = "ping-client")
@RegisterProvider(PingClientApplicationFilter.class)
public interface PingClient {

    @GET
    @Path("/api/ping")
    String ping(@QueryParam("n") Integer n);

    @GET
    @Path("/api/ping/http-error")
    Response httpError();

    @GET
    @Path("/api/ping/filtered-status")
    Response filteredStatus();

    @POST
    @Path("/api/ping/echo")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.TEXT_PLAIN)
    String echo(
            @QueryParam("api-token") String token,
            @HeaderParam("Authorization") String authorization,
            @HeaderParam("Cookie") String cookie,
            String body);
}
