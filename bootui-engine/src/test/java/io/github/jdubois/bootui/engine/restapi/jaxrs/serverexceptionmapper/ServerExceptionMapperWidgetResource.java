package io.github.jdubois.bootui.engine.restapi.jaxrs.serverexceptionmapper;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * A minimal JAX-RS resource sharing the package with {@link WidgetServerExceptionMapper}, so
 * RAPI-ERR-001 has a non-empty controller set to evaluate against — proving the rule now recognises
 * {@code @ServerExceptionMapper}-only exception handling instead of false-flagging it.
 */
@Path("/server-exception-mapper-widgets")
public class ServerExceptionMapperWidgetResource {

    @GET
    public String get() {
        return "widget";
    }
}
