package org.acme.restclient;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/** Simple ping resource that the test REST client calls to generate capture entries. */
@Path("/api/ping")
public class PingResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String ping(@QueryParam("n") Integer n) {
        return "pong-" + (n != null ? n : 0);
    }
}
