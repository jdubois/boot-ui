package io.github.jdubois.bootui.engine.restapi.newrules.idempotency;

import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

/**
 * A JAX-RS creation endpoint that accepts a client-supplied Idempotency-Key header via {@code
 * @HeaderParam} — proves RAPI-VALID-005's Idempotency-Key detection works identically on the JAX-RS
 * side, not just Spring's {@code @RequestHeader}. Must PASS RAPI-VALID-005.
 */
@Path("/widgets")
public class CreateWidgetResource {

    @POST
    public String createWidget(@HeaderParam("Idempotency-Key") String idempotencyKey) {
        return "created";
    }
}
