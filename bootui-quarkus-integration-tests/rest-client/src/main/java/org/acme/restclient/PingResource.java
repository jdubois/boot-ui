package org.acme.restclient;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Simple ping resource that the test REST client calls to generate capture entries. */
@Path("/api/ping")
public class PingResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String ping(@QueryParam("n") Integer n) {
        return "pong-" + (n != null ? n : 0);
    }

    @GET
    @Path("/http-error")
    public Response httpError() {
        return Response.status(503).entity("downstream-error-body-secret").build();
    }

    @GET
    @Path("/filtered-status")
    public Response filteredStatus(@HeaderParam("X-Application-Filter") String applicationFilter) {
        return Response.status("active".equals(applicationFilter) ? 418 : 400).build();
    }

    @POST
    @Path("/echo")
    @Produces(MediaType.TEXT_PLAIN)
    public String echo(String ignoredBody) {
        return "response-body-secret-value";
    }
}
