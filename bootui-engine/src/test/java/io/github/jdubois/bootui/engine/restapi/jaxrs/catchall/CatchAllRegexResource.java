package io.github.jdubois.bootui.engine.restapi.jaxrs.catchall;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

/**
 * The JAX-RS idiomatic catch-all: a {@code {token:regex}} path-parameter template whose regex is
 * all-matching ({@code .*} or {@code .+}). This causes the same anti-pattern as Spring's {@code /**}
 * or {@code {*path}} — the route silently shadows siblings and swallows 404s — but was previously
 * never flagged by RAPI-MAP-010 (fix #7).
 */
@Path("/catch-all-widgets")
public class CatchAllRegexResource {

    @GET
    @Path("/{path : .*}")
    public String getAny(@PathParam("path") String path) {
        return path;
    }
}
