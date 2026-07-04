package io.github.jdubois.bootui.engine.restapi.jaxrs.catchall;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

/**
 * A legitimately-constrained {@code {token:regex}} path template ({@code [0-9]+}, digits only) —
 * this narrows the match and must NOT be flagged by RAPI-MAP-010, unlike the all-matching regex in
 * {@link CatchAllRegexResource}.
 */
@Path("/constrained-widgets")
public class ConstrainedIdResource {

    @GET
    @Path("/{id:[0-9]+}")
    public String getById(@PathParam("id") String id) {
        return "ok";
    }
}
