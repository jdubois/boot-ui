package io.github.jdubois.bootui.engine.restapi.jaxrs.bad;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * A JAX-RS resource declaring a broad {@code throws Exception} on a handler method. The throws clause
 * does not influence exception mapping, so the retired RAPI-ERR-002 no longer reports it.
 */
@Path("/faulty")
public class BadThrowsResource {

    @GET
    public String read() throws Exception {
        return "ok";
    }
}
