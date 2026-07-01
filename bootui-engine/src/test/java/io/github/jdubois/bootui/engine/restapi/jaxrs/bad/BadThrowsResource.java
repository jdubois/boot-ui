package io.github.jdubois.bootui.engine.restapi.jaxrs.bad;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * A JAX-RS resource declaring a broad {@code throws Exception} on a handler method (RAPI-ERR-002,
 * LOW). Declaring a broad throws clause is a plain JVM method-signature fact, not a Spring-only
 * one, so this must be flagged on JAX-RS resources exactly as it is on Spring controllers.
 */
@Path("/faulty")
public class BadThrowsResource {

    @GET
    public String read() throws Exception {
        return "ok";
    }
}
