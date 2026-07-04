package io.github.jdubois.bootui.engine.restapi.jaxrs.versioning;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

/**
 * A JAX-RS resource that signals its API version via {@code @HeaderParam}/{@code @QueryParam} —
 * exactly analogous to Spring's {@code @RequestHeader}/{@code @RequestParam} versioning idiom. Before
 * fix #6, {@code toJaxRsHandler} left {@code params}/{@code headers} empty, so RAPI-VER-001/006 could
 * never recognise header/query-param versioning on JAX-RS; this fixture proves it now can.
 */
@Path("/versioned-widgets")
public class VersionedHeaderWidgetResource {

    @GET
    public String getByHeader(@HeaderParam("Api-Version") String apiVersion) {
        return "widget";
    }

    @GET
    @Path("/by-query")
    public String getByQuery(@QueryParam("version") String version) {
        return "widget";
    }
}
