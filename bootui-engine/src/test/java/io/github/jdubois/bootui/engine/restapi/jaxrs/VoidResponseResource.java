package io.github.jdubois.bootui.engine.restapi.jaxrs;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

/**
 * A JAX-RS resource with a void GET handler. Per the Jakarta REST spec a void-returning resource
 * method always answers 204 No Content — unlike Spring MVC, which defaults an unannotated void
 * handler to 200 OK — so RAPI-RESP-005 must not flag this as "defaults to 200 OK".
 */
@Path("/probes")
public class VoidResponseResource {

    @GET
    @Path("/{id}")
    public void probe(@PathParam("id") String id) {}
}
